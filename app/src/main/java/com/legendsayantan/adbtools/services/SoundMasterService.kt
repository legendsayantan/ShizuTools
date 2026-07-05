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
    var apps = mutableListOf<AudioOutputBase>()
    var latency = mutableListOf(0)
    var latencyUpdateTimer = Timer()
    private lateinit var notiBuilder: NotificationCompat.Builder

    override fun onBind(intent: Intent): IBinder {
        return null!!
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

        notiBuilder = NotificationCompat.Builder(this, "notifications")
            .setContentText(
                getString(
                    R.string.soundmaster_initial_noti,
                    applicationContext.getString(R.string.soundmaster)
                )
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            
        latencyUpdateTimer.schedule(timerTask {
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
                
                if (isDsp) {
                    mediaProjection = mediaProjectionManager?.getMediaProjection(
                        Activity.RESULT_OK,
                        projectionData!!
                    ) as MediaProjection
                }
                
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
                com.legendsayantan.adbtools.lib.ShizuToolsController.execute { service ->
                    val uids = service.activeAudioUids
                    val isPlaying = uids != null && uids.isNotEmpty()
                    
                    if (isPlaying) {
                        // Persist Smart Volume
                        if (!com.legendsayantan.adbtools.lib.SoundMasterPreferences.isAdvancedDspMode(applicationContext)) {
                            val prefs = getSharedPreferences("soundmaster_vols", Context.MODE_PRIVATE)
                            val pm = packageManager
                            uids?.forEach { uid ->
                                try {
                                    val pkgs = pm.getPackagesForUid(uid)
                                    val pkg = pkgs?.firstOrNull()
                                    if (pkg != null) {
                                        val vol = prefs.getFloat(pkg, 1.0f)
                                        service.setPlayerVolume(uid, vol)
                                    }
                                } catch (e: Exception) {}
                            }
                        }
                    
                        if (!wasPlaying) {
                            val prefs = getSharedPreferences("soundmaster", MODE_PRIVATE)
                            if (prefs.getBoolean("auto_wakeup", true)) {
                                mainHandler.post { wakeBubble() }
                            }
                        }
                    } else if (wasPlaying) {
                        mainHandler.post {
                            if (::bubble.isInitialized) bubble.hide()
                        }
                    }
                    
                    mainHandler.post {
                        if (::bubble.isInitialized) {
                            bubble.updateBubbleAppIcon(uids)
                        }
                    }
                    
                    wasPlaying = isPlaying
                }
            }
        }
        audioManager.registerAudioPlaybackCallback(mPlaybackCallback, mainHandler)
    }

    private fun wakeBubble() {
        if (::bubble.isInitialized) {
            bubble.show(com.legendsayantan.adbtools.views.SoundMasterBubble.State.BUBBLE)
            mainHandler.removeCallbacks(fadeOutRunnable)
            val prefs = getSharedPreferences("soundmaster", MODE_PRIVATE)
            val timeout = prefs.getLong("auto_hide_timeout", 5000L)
            if (timeout > 0) {
                mainHandler.postDelayed(fadeOutRunnable, timeout)
            }
        }
    }

    fun onModeChanged(isDsp: Boolean) {
        if (isDsp) {
            // Start DSP logic
        } else {
            // Stop DSP threads
            packageThreads.forEach { it.value.interrupt() }
            packageThreads.clear()
        }
    }

    override fun onDestroy() {
        running = false
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