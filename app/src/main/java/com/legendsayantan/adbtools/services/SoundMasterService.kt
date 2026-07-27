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
        //foreground service
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
                val mThread = PlayBackThread(
                    applicationContext,
                    key.pkg,
                    mediaProjection!!
                )
                packageThreads[key.pkg] = mThread
                mThread.start()
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
        if (intent?.action == "ACTION_WAKE_BUBBLE") {
            wakeBubble()
            return START_STICKY
        }
        if (intent?.action == ACTION_ENABLE_DSP) {
            onModeChanged(true)
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

                // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION/MEDIA_PLAYBACK have existed since
                // Android 10 (Q) - gating this at R (Android 11) meant Android 10 devices got
                // type=0 (none) despite the manifest declaring these types.
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
                
                val activeConfigs = configs?.filter { config ->
                    try {
                        config.javaClass.getMethod("isActive").invoke(config) as Boolean
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
                            val uids = service.activeAudioUids?.toList() ?: return@execute
                            
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
        val activePkgs = uids.mapNotNull { uid ->
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

    fun onModeChanged(isDsp: Boolean) {
        if (isDsp) {
            if (mediaProjection == null) {
                mediaProjection = acquireMediaProjection()
            }
            if (mediaProjection != null) {
                attachActiveAppsToDsp()
            } else {
                // No live projection token yet (e.g. engine was started in Smart mode, which
                // never requests one), or the cached token was already spent (single-use as of
                // Android 14+). Ask for fresh consent instead of just failing - the switch is
                // left optimistically on and gets reverted by revertDspSwitch() if the user
                // denies or grants fail.
                requestDspConsent()
            }
        } else {
            packageThreads.forEach { it.value.interrupt() }
            packageThreads.clear()
            apps.clear()
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
        com.legendsayantan.adbtools.lib.ShizuToolsController.execute { controller ->
            val pm = packageManager
            controller.activeAudioUids?.forEach { uid ->
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
                controller.setAppOpMode(packageName, uid, 27, android.app.AppOpsManager.MODE_ALLOWED) // RECORD_AUDIO
                controller.setAppOpMode(packageName, uid, 46, android.app.AppOpsManager.MODE_ALLOWED) // MEDIA_PROJECTION
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
        contentResolver.unregisterContentObserver(mVolumeObserver)
        audioManager.unregisterAudioPlaybackCallback(mPlaybackCallback)
        if (::bubble.isInitialized) bubble.hide()
        latencyUpdateTimer.cancel()
        packageThreads.forEach { it.value.interrupt() }
        mediaProjection?.stop()
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
        const val ACTION_DSP_CONSENT_DENIED = "ACTION_DSP_CONSENT_DENIED"
        const val EXTRA_LIVE_UPGRADE = "live_upgrade"

        lateinit var uiIntent: Intent

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