package com.legendsayantan.adbtools.services

import android.Manifest
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
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
import com.legendsayantan.adbtools.SoundMasterActivity
import com.legendsayantan.adbtools.data.AudioOutputBase
import com.legendsayantan.adbtools.data.AudioOutputKey
import com.legendsayantan.adbtools.lib.PlayBackThread
import com.legendsayantan.adbtools.lib.ShizukuRunner
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
import kotlin.let


class SoundMasterService : Service() {
    private lateinit var mVolumeObserver: ContentObserver
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    var packageThreads = hashMapOf<String, PlayBackThread>()
    var apps = mutableListOf<AudioOutputBase>()
    var latency = mutableListOf(0)
    var latencyUpdateTimer = Timer()
    override fun onBind(intent: Intent): IBinder {
        return null!!
    }

    override fun onCreate() {
        super.onCreate()

        //foreground service
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }

        val builder = NotificationCompat.Builder(this, "notifications")
            .setContentText("You can change volume to configure ${applicationContext.getString(R.string.soundmaster)} as well.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
        startForeground(
            NOTI_ID,
            builder.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        latencyUpdateTimer.schedule(timerTask {
            val avg = packageThreads.values.map { it.getLatency() }.average().toInt()
            packageThreads.values.forEach { it.loadedCycles = 0 }
            builder.setContentTitle(applicationContext.getString(R.string.soundmaster) + " is controlling ${packageThreads.size} apps.")
            builder.setContentText("Average Latency: $avg ms")
            latency.clear()
            if (ActivityCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(applicationContext)
                    .notify(NOTI_ID, builder.build())
            }
        }, updateInterval, updateInterval)

        initVolumeBtnControl()

        mediaProjectionManager =
            applicationContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        prepareGetAudioDevices()

        switchDeviceFor = { key, device ->
            packageThreads[key.pkg]?.switchOutputDevice(key, device) == true
        }

        getVolumeOf = { key ->
            (packageThreads[key.pkg]?.getVolume(key) ?: apps.find { it.pkg==key.pkg && it.output==key.output }?.volume ?: 100f)
        }

        setVolumeOf = { key, vol ->
            packageThreads[key.pkg]?.setVolume(key.output, vol)
        }

        getBalanceOf = {
            packageThreads[it.pkg]?.getBalance(it.output) ?: 0f
        }

        setBalanceOf = { it, value ->
            packageThreads[it.pkg]?.setBalance(it.output, value)
        }

        getBandValueOf = { it, band ->
            packageThreads[it.pkg]?.getBand(it.output, band) ?: 50f
        }

        setBandValueOf = { it, band, value ->
            packageThreads[it.pkg]?.setBand(it.output, band, value)
        }

        isAttachable = { key ->
            !(packageThreads.contains(key.pkg) && packageThreads[key.pkg]?.hasOutput(key.output) == true)
        }

        onDynamicAttach = { key, device ->
            if (!apps.contains(key)) apps.add(AudioOutputBase(key.pkg,key.output,key.volume))
            if (!packageThreads.contains(key.pkg)) {
                val mThread = PlayBackThread(
                    applicationContext,
                    key.pkg,
                    mediaProjection!!
                )
                packageThreads[key.pkg] = mThread
                mThread.start()
            }
            packageThreads[key.pkg]?.createOutput(device, outputKey = key.output, startVolume = key.volume)
        }

        onDynamicDetach = { key ->
            val thread = packageThreads[key.pkg]
            thread?.deleteOutput(key.output)
            if (thread?.mPlayers?.size == 0) {
                packageThreads.remove(key.pkg)
                apps.remove(key)
            }
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
            if (apps.isNotEmpty()) {
                running = true
                mediaProjection = mediaProjectionManager?.getMediaProjection(
                    Activity.RESULT_OK,
                    projectionData!!
                ) as MediaProjection
                apps.forEach { base ->
                    onDynamicAttach(base,
                        getAudioDevices().find { it?.id == base.output }
                    )
                }
            }
        }
        return START_STICKY
    }

    private fun initVolumeBtnControl() {
        mVolumeObserver = object : ContentObserver(Handler(mainLooper)) {
            var prevVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                val newVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (newVolume != prevVolume) {
                    prevVolume = newVolume

                    Handler(mainLooper).post {
                        if (SoundMasterActivity.showing) SoundMasterActivity.interacted()
                        else ShizukuRunner.runAdbCommand("am start -n $packageName/${SoundMasterActivity::class.java.canonicalName}",
                            object : ShizukuRunner.CommandResultListener {
                                override fun onCommandResult(output: String, done: Boolean) {}
                            })
                    }
                }
            }
        }
        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI, true,
            mVolumeObserver
        )
    }

    override fun onDestroy() {
        running = false
        contentResolver.unregisterContentObserver(mVolumeObserver)
        latencyUpdateTimer.cancel()
        packageThreads.forEach { it.value.interrupt() }
        mediaProjection?.stop()
        super.onDestroy()
    }

    companion object {
        var running = false
        var projectionData: Intent? = null
        var isAttachable: (AudioOutputKey) -> Boolean = { false }
        var onDynamicAttach: (AudioOutputBase, AudioDeviceInfo?) -> Unit = { _, _ -> }
        var onDynamicDetach: (AudioOutputKey) -> Unit = { _ -> }
        var getAudioDevices: () -> List<AudioDeviceInfo?> = { listOf() }
        var switchDeviceFor: (AudioOutputKey, AudioDeviceInfo?) -> Boolean = { _, _ -> false }
        var setVolumeOf: (AudioOutputKey, Float) -> Unit = { a, b ->  }
        var getVolumeOf: (AudioOutputKey) -> Float = { p -> 100f }
        var setBalanceOf: (AudioOutputKey, Float) -> Unit = { a, b -> }
        var getBalanceOf: (AudioOutputKey) -> Float = { _ -> 0f }
        var setBandValueOf: (AudioOutputKey, Int, Float) -> Unit = { _, _, _ -> }
        var getBandValueOf: (AudioOutputKey, Int) -> Float = { _, _ -> 50f }

        const val NOTI_ID = 1
        const val updateInterval = 30000L

        lateinit var uiIntent: Intent

        fun Context.prepareGetAudioDevices() {
            if (getAudioDevices().isEmpty())
                getAudioDevices = {
                    var dev = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(
                        AudioManager.GET_DEVICES_OUTPUTS
                    ).filter {
                        it.type !in arrayOf(7, 18, 25)
                    }
                    //block builtin outputs
                    if (dev.any { it?.type in 3..4 }) dev = dev.filter { it?.type !in 1..2 }
                    //block earpiece only
                    if (dev.all { it?.type in 1..2 } || dev.any { it?.type == 8 }) dev =
                        dev.filter { it?.type != 1 }
                    listOf(null) + dev
                }
        }
    }

}