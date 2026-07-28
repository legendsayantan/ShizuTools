package com.legendsayantan.adbtools.services

import android.Manifest
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioAttributes.ALLOW_CAPTURE_BY_NONE
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.legendsayantan.adbtools.R

import com.legendsayantan.adbtools.data.AudioOutputBase
import com.legendsayantan.adbtools.data.AudioOutputKey
import com.legendsayantan.adbtools.lib.AppOps
import com.legendsayantan.adbtools.lib.Logger.Companion.log
import com.legendsayantan.adbtools.lib.PlayBackThread
import com.legendsayantan.adbtools.lib.ShizukuRunner
import com.legendsayantan.adbtools.lib.Utils.Companion.toFixed
import java.util.Timer
import kotlin.Boolean
import kotlin.Float
import kotlin.Int
import kotlin.String
import kotlin.Unit
import kotlin.arrayOf
import kotlin.concurrent.timerTask
import kotlin.getValue
import kotlin.lazy


class SoundMasterService : Service() {
    private lateinit var mVolumeObserver: ContentObserver
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    var packageThreads = hashMapOf<String, PlayBackThread>()
    
    var latency = mutableListOf(0)
    var latencyUpdateTimer = Timer()
    private lateinit var notiBuilder: NotificationCompat.Builder

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        // SoundMaster's audio routing is built entirely on per-app playback capture
        // (AudioPlaybackCallback/AudioPlaybackCapture + MediaProjection), none of which exist
        // before Android 10 (Q). Skip all setup below that version - onStartCommand() checks
        // the same condition and stops the service immediately instead of touching any of the
        // members that are only initialized here, so nothing downstream can crash on a `lateinit`
        // that was never assigned.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        
        Handler(mainLooper).post {
            bubble = com.legendsayantan.adbtools.views.SoundMasterBubble(this)
        }
        setupSmartVisibility()
        mainHandler.post(rmsUpdateRunnable)

        val wakeIntent = Intent(this, com.legendsayantan.adbtools.SoundMasterWakeActivity::class.java)
        val pendingWakeIntent = android.app.PendingIntent.getActivity(this, 0, wakeIntent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)

        notiBuilder = NotificationCompat.Builder(this, "notifications")
            .setContentText(
                getString(
                    R.string.soundmaster_initial_noti,
                    applicationContext.getString(R.string.soundmaster)
                )
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingWakeIntent)
            .setOnlyAlertOnce(true)
            
        latencyUpdateTimer.schedule(timerTask {
            if (com.legendsayantan.adbtools.lib.SoundMasterPreferences.isAdvancedDspMode(applicationContext)) {
                val avg = packageThreads.values.map { it.getLatency() }.average().toInt()
                packageThreads.values.forEach { it.loadedCycles = 0 }
                notiBuilder.setContentTitle(applicationContext.getString(R.string.soundmaster) + " is controlling ${packageThreads.size} apps.")
                notiBuilder.setContentText("Average Latency: $avg ms")
                latency.clear()
                if (ActivityCompat.checkSelfPermission(
                        applicationContext,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    NotificationManagerCompat.from(applicationContext)
                        .notify(NOTI_ID, notiBuilder.build())
                }
            }
        }, updateInterval, updateInterval)


        mediaProjectionManager =
            applicationContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        audioManager.setAllowedCapturePolicy(ALLOW_CAPTURE_BY_NONE)
        prepareGetAudioDevices()

        switchDeviceFor = { key, device ->
            packageThreads[key.pkg]?.switchOutputDevice(key, device) == true
        }

        getVolumeOf = { key ->
            (packageThreads[key.pkg]?.getVolume(key)
                ?: apps.find { it.pkg == key.pkg && it.output == key.output }?.volume ?: 100f)
        }

        setVolumeOf = { key, vol ->
            packageThreads[key.pkg]?.setVolume(key.output, vol)
        }

        getBalanceOf = {
            packageThreads[it.pkg]?.getBalance(it.output)
        }

        setBalanceOf = { it, value ->
            packageThreads[it.pkg]?.setBalance(it.output, value)
        }

        getBandValueOf = { it, band ->
            packageThreads[it.pkg]?.getBand(it.output, band)
        }

        setBandValueOf = { it, band, value ->
            packageThreads[it.pkg]?.setBand(it.output, band, value)
        }

        isAttachable = { key ->
            !(packageThreads.contains(key.pkg) && packageThreads[key.pkg]?.hasOutput(key.output) == true)
        }

        onDynamicAttach = { key, device ->
            if (!apps.contains(key)) apps.add(AudioOutputBase(key.pkg, key.output, key.volume))
            if (!packageThreads.contains(key.pkg)) {
                // mediaProjection is only acquired in DSP mode. If leftover `apps` entries from a
                // previous DSP session get reattached after switching to Smart mode (no capture
                // session), there's nothing to build a PlayBackThread from - skip instead of
                // crashing on a null projection.
                val projection = mediaProjection
                if (projection == null) {
                    log("Skipping audio attach for ${key.pkg}: no active capture session.")
                } else {
                    val mThread = PlayBackThread(
                        applicationContext,
                        key.pkg,
                        projection
                    )
                    packageThreads[key.pkg] = mThread
                    mThread.start()
                }
            }
            packageThreads[key.pkg]?.createOutput(
                device,
                outputKey = key.output,
                startVolume = key.volume
            )
        }

        onDynamicDetach = { key ->
            val thread = packageThreads[key.pkg]
            thread?.deleteOutput(key.output)
            if (thread?.mPlayers?.size == 0) {
                packageThreads.remove(key.pkg)
                apps.remove(key)
            }
        }
        getDisconnectedAppsFromSystem = { callback ->
            packageThreads.forEach { app ->
                app.value.isDisconnectedFromSystem {
                    callback(app.key)
                }
            }
        }
        getAudioRmsData = {
            packageThreads.map { it.key+" -> "+it.value.calculateRMS().toFixed(2) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            log("SoundMaster requires Android 10 (API 29) or higher; refusing to start.", true)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == "ACTION_WAKE_BUBBLE") {
            wakeBubble()
            return START_STICKY
        }
        if (intent?.action == ACTION_ENABLE_DSP) {
            onModeChanged(true)
            return START_STICKY
        }
        if (intent?.action == ACTION_ENABLE_SMART) {
            onModeChanged(false)
            return START_STICKY
        }
        if (intent?.action == ACTION_DSP_CONSENT_DENIED) {
            revertDspSwitch("Screen capture permission denied.")
            return START_STICKY
        }
        if (intent != null) {
            val pkgs = intent.getStringArrayExtra("packages")?.toMutableList() ?: mutableListOf()
            val devices = intent.getIntArrayExtra("devices")?.toMutableList() ?: mutableListOf()
            val volumes = intent.getFloatArrayExtra("volumes")?.toMutableList() ?: mutableListOf()
            pkgs.forEachIndexed { index, s ->
                apps.add(AudioOutputBase(s, devices[index], volumes[index]))
            }
            if (!running) {
                running = true
                startingIntent = intent

                val isDsp = com.legendsayantan.adbtools.lib.SoundMasterPreferences.isAdvancedDspMode(this)
                val type = computeForegroundServiceType(isDsp)

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTI_ID, notiBuilder.build(), type)
                    }else{
                        startForeground(NOTI_ID, notiBuilder.build())
                    }
                } catch (e: Exception) {
                    startForeground(NOTI_ID, notiBuilder.build())
                }

                // The FGS must already be active (with the matching type) before the
                // MediaProjection is put to use - enforced starting Android 14. startForeground()
                // above now always runs first.
                if (isDsp) {
                    mediaProjection = acquireMediaProjection()
                }

                if (apps.isNotEmpty()) {
                    apps.forEach { base ->
                        onDynamicAttach(base,
                            getAudioDevices().find { it?.id == base.output }
                        )
                    }
                }
            }
        }
        return START_STICKY
    }

    val mainHandler by lazy { Handler(applicationContext.mainLooper) }
    private val fadeOutRunnable = Runnable {
        if (::bubble.isInitialized) bubble.hide()
    }
    private val rmsUpdateRunnable = object : Runnable {
        override fun run() {
            if (::bubble.isInitialized &&
                bubble.currentState == com.legendsayantan.adbtools.views.SoundMasterBubble.State.BUBBLE &&
                packageThreads.isNotEmpty()
            ) {
                val avgRms = packageThreads.values.map { it.calculateRMS() }.average().toFloat()
                bubble.updateRms(avgRms)
            }
            mainHandler.postDelayed(this, 120L)
        }
    }
    private lateinit var bubble: com.legendsayantan.adbtools.views.SoundMasterBubble
    private lateinit var mPlaybackCallback: AudioManager.AudioPlaybackCallback

    private fun setupSmartVisibility() {
        val prefs by lazy { getSharedPreferences("soundmaster", MODE_PRIVATE) }
        mVolumeObserver = object : ContentObserver(mainHandler) {
            var prevVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                if(prefs.getBoolean("show_on_volume_change", true)) {
                    val newVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (newVolume != prevVolume) {
                        prevVolume = newVolume
                        wakeBubble()
                    }
                }
            }
        }
        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI, true, mVolumeObserver
        )

        mPlaybackCallback = object : AudioManager.AudioPlaybackCallback() {
            var wasPlaying = false
            override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
                super.onPlaybackConfigChanged(configs)
                
                // Exclude our own uid from every stage below - when DSP mode is re-playing
                // captured audio through our own AudioTrack instances, that playback is itself
                // reported here (it belongs to this app's process), which would otherwise make
                // SoundMaster attach to and show a slider for itself, and would mask the real
                // source app actually having stopped (isPlaying would stay true on our own
                // residual activity alone).
                val myUid = android.os.Process.myUid()
                val activeConfigs = configs?.filter { config ->
                    try {
                        val isActive = config.javaClass.getMethod("isActive").invoke(config) as Boolean
                        val uid = try { config.javaClass.getMethod("getClientUid").invoke(config) as? Int } catch (e: Exception) { null }
                        isActive && uid != myUid
                    } catch (e: Exception) { true }
                } ?: emptyList()

                val immediateUids = activeConfigs.mapNotNull { config ->
                    try {
                        val uid = config.javaClass.getMethod("getClientUid").invoke(config) as? Int
                        if (uid != null && uid > 0) uid else null
                    } catch (e: Exception) { null }
                }.distinct()

                val isPlaying = activeConfigs.isNotEmpty()
                
                if (isPlaying) {
                    if (!wasPlaying) {
                        val prefs = getSharedPreferences("soundmaster", MODE_PRIVATE)
                        if (prefs.getBoolean("auto_wakeup", true)) {
                            mainHandler.post { wakeBubble() }
                        }
                    }
                    
                    if (immediateUids.isNotEmpty()) {
                        updateActivePackages(immediateUids)
                    }
                    
                    val isDspMode = com.legendsayantan.adbtools.lib.SoundMasterPreferences.isAdvancedDspMode(applicationContext)
                    if (!isDspMode || immediateUids.isEmpty()) {
                        com.legendsayantan.adbtools.lib.ShizuToolsController.execute { service ->
                            val uids = service.activeAudioUids?.toList()?.filter { it != myUid } ?: return@execute

                            if (immediateUids.isEmpty()) {
                                mainHandler.post { updateActivePackages(uids) }
                            }

                            if (!isDspMode) {
                                val pm = packageManager
                                val prefs = getSharedPreferences("soundmaster_vols", Context.MODE_PRIVATE)
                                val uidsToUse = if (immediateUids.isNotEmpty()) immediateUids else uids
                                uidsToUse.forEach { uid ->
                                    try {
                                        val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return@forEach
                                        service.setPlayerVolume(uid, prefs.getFloat(pkg, 1.0f))
                                    } catch (e: Exception) {}
                                }
                            }
                        }
                    }
                } else if (wasPlaying) {
                    updateActivePackages(emptyList())
                }
                
                wasPlaying = isPlaying
            }
        }
        audioManager.registerAudioPlaybackCallback(mPlaybackCallback, mainHandler)
    }

    private fun updateActivePackages(uids: List<Int>) {
        val pm = packageManager
        val myUid = android.os.Process.myUid()
        val activePkgs = uids.filter { it != myUid }.mapNotNull { uid ->
            try { pm.getPackagesForUid(uid)?.firstOrNull() } catch (e: Exception) { null }
        }.distinct()
        
        val isDspMode = com.legendsayantan.adbtools.lib.SoundMasterPreferences.isAdvancedDspMode(applicationContext)
        val prefs = getSharedPreferences("soundmaster_vols", Context.MODE_PRIVATE)
        val currentTime = System.currentTimeMillis()
        
        activePkgs.forEach { appStopTimes.remove(it) }
        val stoppedApps = activePackages.filter { it !in activePkgs && it !in appStopTimes.keys }
        stoppedApps.forEach { pkg ->
            appStopTimes[pkg] = currentTime
            mainHandler.postDelayed({
                if (appStopTimes.containsKey(pkg) && System.currentTimeMillis() - appStopTimes[pkg]!! >= 45000L) {
                    appStopTimes.remove(pkg)
                    activePackages = activePackages.filter { it != pkg }
                    if (::bubble.isInitialized) bubble.populateSliders()
                }
            }, 45000L)
        }
        
        val expired = appStopTimes.filter { currentTime - it.value >= 45000L }.keys
        expired.forEach { appStopTimes.remove(it) }
        
        activePackages = (activePkgs + appStopTimes.keys).distinct()
        
        if (isDspMode && mediaProjection != null) {
            activePackages.forEach { pkg ->
                if (!apps.any { it.pkg == pkg }) {
                    val base = com.legendsayantan.adbtools.data.AudioOutputBase(pkg, -1, 100f)
                    apps.add(base)
                    mainHandler.post { onDynamicAttach(base, getAudioDevices().find { it?.id == -1 }) }
                } else {
                    // Already attached - this playback-config change likely means the app just
                    // (re)started a new audio session. AppOpsManager's PLAY_AUDIO=ERRORED can fail
                    // to retroactively silence a session that was already open when the op was
                    // first set, so re-assert it every time the app becomes active again rather
                    // than trusting the one-time mute from attach time.
                    reassertMute(pkg)
                }
            }
            val toRemove = apps.filter { !activePackages.contains(it.pkg) }
            toRemove.forEach { base ->
                mainHandler.post { onDynamicDetach(com.legendsayantan.adbtools.data.AudioOutputKey(base.pkg, base.output)) }
                apps.remove(base)
            }
        }
        
        mainHandler.post {
            if (::bubble.isInitialized) {
                bubble.updateBubbleAppIcon(uids.toIntArray())
                bubble.populateSliders()
            }
        }
    }

    private fun reassertMute(pkg: String) {
        com.legendsayantan.adbtools.lib.ShizuToolsController.execute { controller ->
            try {
                val uid = packageManager.getApplicationInfo(pkg, 0).uid
                controller.setAppOpMode(pkg, uid, com.legendsayantan.adbtools.lib.AppOps.PLAY_AUDIO, com.legendsayantan.adbtools.lib.AppOps.MODE_ERRORED)
            } catch (e: Exception) {}
        }
    }

    private fun wakeBubble() {
        mainHandler.post {
            if (::bubble.isInitialized) {
                if (bubble.currentState != com.legendsayantan.adbtools.views.SoundMasterBubble.State.MINI && 
                    bubble.currentState != com.legendsayantan.adbtools.views.SoundMasterBubble.State.EXPANDED) {
                    bubble.show(com.legendsayantan.adbtools.views.SoundMasterBubble.State.BUBBLE)
                }
                extendTimeout()
            }
        }
    }

    fun extendTimeout() {
        if (::bubble.isInitialized) {
            mainHandler.removeCallbacks(fadeOutRunnable)
            val prefs = getSharedPreferences("soundmaster", MODE_PRIVATE)
            val timeout = prefs.getLong("auto_hide_timeout", 5000L)
            if (timeout > 0) {
                mainHandler.postDelayed(fadeOutRunnable, timeout)
            }
        }
    }

    fun pauseTimeout() {
        mainHandler.removeCallbacks(fadeOutRunnable)
    }

    fun extendAppTimeout(pkg: String) {
        if (appStopTimes.containsKey(pkg)) {
            appStopTimes[pkg] = System.currentTimeMillis()
            mainHandler.postDelayed({
                if (appStopTimes.containsKey(pkg) && System.currentTimeMillis() - appStopTimes[pkg]!! >= 45000L) {
                    appStopTimes.remove(pkg)
                    activePackages = activePackages.filter { it != pkg }
                    if (::bubble.isInitialized) bubble.populateSliders()
                }
            }, 45000L)
        }
    }

    // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION/MEDIA_PLAYBACK have existed since Android 10 (Q) -
    // gating this at R (Android 11) meant Android 10 devices got type=0 (none) despite the
    // manifest declaring these types.
    private fun computeForegroundServiceType(isDsp: Boolean): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isDsp) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
            }
        } else 0
    }

    // Android 14+ enforces that the hosting foreground service already declares the
    // mediaProjection type *before* MediaProjectionManager#getMediaProjection() is called - an
    // engine started in Smart mode only declares specialUse, so live-switching to DSP without
    // re-declaring here makes getMediaProjection() throw every time, acquireMediaProjection()
    // swallow it, and onModeChanged() re-request consent forever (visible as the capture-consent
    // screen reopening in a loop, stacking a new task each time and starving back navigation).
    private fun promoteForegroundServiceType(isDsp: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !::notiBuilder.isInitialized) return
        try {
            startForeground(NOTI_ID, notiBuilder.build(), computeForegroundServiceType(isDsp))
        } catch (e: Exception) {
            log(e.stackTraceToString(), true)
        }
    }

    private var dspConsentAttempted = false

    fun onModeChanged(isDsp: Boolean) {
        if (isDsp) {
            if (mediaProjection == null) {
                promoteForegroundServiceType(true)
                mediaProjection = acquireMediaProjection()
            }
            if (mediaProjection != null) {
                dspConsentAttempted = false
                attachActiveAppsToDsp()
            } else if (!dspConsentAttempted) {
                // No live projection token yet (e.g. engine was started in Smart mode, which
                // never requests one), or the cached token was already spent (single-use as of
                // Android 14+). Ask for fresh consent instead of just failing - the switch is
                // left optimistically on and gets reverted by revertDspSwitch() if the user
                // denies or grants fail. Bounded to a single retry so a persistent acquisition
                // failure reverts cleanly instead of re-requesting consent forever.
                dspConsentAttempted = true
                requestDspConsent()
            } else {
                dspConsentAttempted = false
                revertDspSwitch("Couldn't start Advanced DSP capture on this device.")
            }
        } else {
            dspConsentAttempted = false
            packageThreads.forEach { it.value.interrupt() }
            packageThreads.clear()
            apps.clear()
            promoteForegroundServiceType(false)
        }
    }

    /**
     * Wraps MediaProjectionManager#getMediaProjection so every acquisition point (initial
     * start, live mode upgrade) shares the same failure handling and gets a Callback
     * registered - the system's own screen-share/record "Stop" control can end a projection
     * at any point across every supported version, and without a callback the app would keep
     * capturing against a dead session instead of noticing and reverting cleanly.
     */
    private fun acquireMediaProjection(): MediaProjection? {
        val data = projectionData ?: return null
        return try {
            val proj = mediaProjectionManager?.getMediaProjection(Activity.RESULT_OK, data)
            proj?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    mainHandler.post { handleProjectionStopped() }
                }
            }, mainHandler)
            proj
        } catch (e: Exception) {
            null
        }
    }

    private fun handleProjectionStopped() {
        packageThreads.forEach { it.value.interrupt() }
        packageThreads.clear()
        apps.clear()
        mediaProjection = null
        com.legendsayantan.adbtools.lib.SoundMasterPreferences.setAdvancedDspMode(this, false)
        if (::bubble.isInitialized) bubble.syncSwitchState()
    }

    private fun attachActiveAppsToDsp() {
        val myUid = android.os.Process.myUid()
        com.legendsayantan.adbtools.lib.ShizuToolsController.execute { controller ->
            val pm = packageManager
            controller.activeAudioUids?.filter { it != myUid }?.forEach { uid ->
                try {
                    pm.getPackagesForUid(uid)?.firstOrNull()?.let { pkg ->
                        if (!apps.any { it.pkg == pkg }) {
                            val base = AudioOutputBase(pkg, -1, 100f)
                            apps.add(base)
                            mainHandler.post {
                                onDynamicAttach(base, getAudioDevices().find { it?.id == -1 })
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private fun requestDspConsent() {
        com.legendsayantan.adbtools.lib.ShizuToolsController.execute { controller ->
            try {
                val uid = android.os.Process.myUid()
                controller.setAppOpMode(packageName, uid, AppOps.RECORD_AUDIO, android.app.AppOpsManager.MODE_ALLOWED)
                controller.setAppOpMode(packageName, uid, AppOps.PROJECT_MEDIA, android.app.AppOpsManager.MODE_ALLOWED)
                mainHandler.post {
                    startActivity(Intent(this, com.legendsayantan.adbtools.SoundMasterProjectionActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(EXTRA_LIVE_UPGRADE, true)
                    })
                }
            } catch (e: Exception) {
                revertDspSwitch("Permission error enabling Advanced DSP.")
            }
        }
    }

    fun revertDspSwitch(message: String) {
        mainHandler.post {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            com.legendsayantan.adbtools.lib.SoundMasterPreferences.setAdvancedDspMode(this, false)
            if (::bubble.isInitialized) bubble.syncSwitchState()
        }
    }

    override fun onDestroy() {
        running = false
        mainHandler.removeCallbacks(rmsUpdateRunnable)
        // mVolumeObserver/mPlaybackCallback are only assigned past the Q version-gate in onCreate,
        // so a service that starts and immediately stops on an unsupported version (or dies before
        // reaching setupSmartVisibility) must not blindly touch them here.
        if (::mVolumeObserver.isInitialized) contentResolver.unregisterContentObserver(mVolumeObserver)
        if (::mPlaybackCallback.isInitialized) audioManager.unregisterAudioPlaybackCallback(mPlaybackCallback)
        if (::bubble.isInitialized) bubble.hide()
        latencyUpdateTimer.cancel()
        packageThreads.forEach { it.value.interrupt() }
        // Clear all static/companion state so a fresh start (possibly in a different mode) never
        // reattaches stale entries left over from this session - see onDynamicAttach's null-projection
        // guard above for what happens if this ever gets missed.
        packageThreads.clear()
        apps.clear()
        activePackages = listOf()
        appStopTimes.clear()
        mediaProjection?.stop()
        mediaProjection = null
        super.onDestroy()
    }

    companion object {
        var running = false
        var apps = mutableListOf<com.legendsayantan.adbtools.data.AudioOutputBase>()
        /** Packages currently producing audio, updated by mPlaybackCallback */
        var activePackages = listOf<String>()
        val appStopTimes = mutableMapOf<String, Long>()
        var startingIntent : Intent? = null
        var projectionData: Intent? = null
        var isAttachable: (AudioOutputKey) -> Boolean = { false }
        var onDynamicAttach: (AudioOutputBase, AudioDeviceInfo?) -> Unit = { _, _ -> }
        var onDynamicDetach: (AudioOutputKey) -> Unit = { _ -> }
        var getAudioDevices: () -> List<AudioDeviceInfo?> = { listOf() }
        var switchDeviceFor: (AudioOutputKey, AudioDeviceInfo?) -> Boolean = { _, _ -> false }
        var setVolumeOf: (AudioOutputKey, Float) -> Unit = { _, _ -> }
        var getVolumeOf: (AudioOutputKey) -> Float = { 100f }
        var setBalanceOf: (AudioOutputKey, Float) -> Unit = { _, _ -> }
        var getBalanceOf: (AudioOutputKey) -> Float? = { null }
        var setBandValueOf: (AudioOutputKey, Int, Float) -> Unit = { _, _, _ -> }
        var getBandValueOf: (AudioOutputKey, Int) -> Float? = { _, _ -> null }

        const val NOTI_ID = 1
        const val updateInterval = 30000L
        const val ACTION_ENABLE_DSP = "ACTION_ENABLE_DSP"
        const val ACTION_ENABLE_SMART = "ACTION_ENABLE_SMART"
        const val ACTION_DSP_CONSENT_DENIED = "ACTION_DSP_CONSENT_DENIED"
        const val EXTRA_LIVE_UPGRADE = "live_upgrade"

        lateinit var uiIntent: Intent

        /**
         * Single reusable "start the engine" flow - overlay-permission silent grant, then either
         * the Smart (headless) or Advanced DSP (needs one-time on-screen MediaProjection consent,
         * an unavoidable OS constraint) path. Shared by SoundMasterBottomSheet's button and the
         * app-command dispatcher so there's exactly one implementation of this flow.
         */
        fun startEngine(context: Context, isDsp: Boolean, onResult: (success: Boolean, message: String) -> Unit) {
            fun startEngineLogic() {
                if (isDsp) {
                    com.legendsayantan.adbtools.lib.ShizuToolsController.execute { service ->
                        try {
                            val uid = android.os.Process.myUid()
                            val pkg = context.packageName
                            service.setAppOpMode(pkg, uid, com.legendsayantan.adbtools.lib.AppOps.RECORD_AUDIO, android.app.AppOpsManager.MODE_ALLOWED)
                            service.setAppOpMode(pkg, uid, com.legendsayantan.adbtools.lib.AppOps.PROJECT_MEDIA, android.app.AppOpsManager.MODE_ALLOWED)
                            Handler(context.mainLooper).post {
                                context.startActivity(Intent(context, com.legendsayantan.adbtools.SoundMasterProjectionActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                                onResult(true, "Advanced DSP requires one-time on-screen consent - permission prompt shown.")
                            }
                        } catch (e: Exception) {
                            Handler(context.mainLooper).post {
                                context.log(e.stackTraceToString(), true)
                                onResult(false, "Permission error enabling Advanced DSP: ${e.message}")
                            }
                        }
                    }
                } else {
                    projectionData = null
                    context.startService(Intent(context, SoundMasterService::class.java))
                    Handler(context.mainLooper).post {
                        onResult(true, "Smart Volume Engine started.")
                    }
                }
            }

            if (!android.provider.Settings.canDrawOverlays(context)) {
                com.legendsayantan.adbtools.lib.ShizuToolsController.execute { service ->
                    try {
                        service.setAppOpMode(context.packageName, android.os.Process.myUid(), com.legendsayantan.adbtools.lib.AppOps.SYSTEM_ALERT_WINDOW, android.app.AppOpsManager.MODE_ALLOWED)
                        Handler(context.mainLooper).post { startEngineLogic() }
                    } catch (e: Exception) {
                        Handler(context.mainLooper).post {
                            onResult(false, "Overlay permission required for SoundMaster.")
                            context.startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            } else {
                startEngineLogic()
            }
        }

        fun Context.prepareGetAudioDevices() {
            if (getAudioDevices().isEmpty())
                getAudioDevices = {
                    var dev = (getSystemService(AUDIO_SERVICE) as AudioManager).getDevices(
                        AudioManager.GET_DEVICES_OUTPUTS
                    ).filter {
                        it.type !in arrayOf(1, 7, 18, 25)
                    }
                    //block builtin outputs
                    if (dev.any { it?.type in 3..4 }) dev = dev.filter { it?.type != 2 }
                    listOf(null) + dev
                }
        }

        //debug
        var getDisconnectedAppsFromSystem = { _ : (String)->Unit ->  }
        var getAudioRmsData = { listOf<String>() }
    }
}