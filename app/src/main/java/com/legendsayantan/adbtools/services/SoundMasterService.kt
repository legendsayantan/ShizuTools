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
    var apps = mutableListOf<AudioOutputKey>()
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
            builder.setContentTitle(applicationContext.getString(R.string.soundmaster) + " is controlling ${apps.size} apps.")
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
        }, notiUpdateTime, notiUpdateTime)

        initVolumeBtnControl()

        mediaProjectionManager =
            applicationContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        getAudioDevices = {
            var dev = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
                it.type !in arrayOf(7, 18, 25)
            }
            if(dev.find { it.type in 3..4 }!=null) dev = dev.filter { it.type !in 1..2 }
            dev
        }

        switchDeviceFor = { key, device ->
            packageThreads[key.pkg]?.switchOutputDevice(key, device)
        }

        getVolumeOf = {
            (packageThreads[it.pkg]?.getVolume(it) ?: volumeTemp[it] ?: 100f)
        }

        setVolumeOf = { key, vol ->
            packageThreads[key.pkg].let {
                if (it != null) it.setVolume(key.outputDevice, vol) else volumeTemp[key] = vol
            }
        }

        getBalanceOf = {
            packageThreads[it.pkg]?.getBalance(it.outputDevice) ?: 0f
        }

        setBalanceOf = { it, value ->
            packageThreads[it.pkg]?.setBalance(it.outputDevice, value)
        }

        getBandValueOf = { it, band ->
            packageThreads[it.pkg]?.getBand(it.outputDevice, band) ?: 50f
        }

        setBandValueOf = { it, band, value ->
            packageThreads[it.pkg]?.setBand(it.outputDevice, band, value)
        }

        onDynamicAttach = { key, device ->
            if (!apps.contains(key)) apps.add(key)
            if (packageThreads.contains(key.pkg)) {
                packageThreads[key.pkg]?.createOutput(device)
            } else {
                val mThread = PlayBackThread(
                    applicationContext,
                    key.pkg,
                    getAudioDevices().find { it.id == key.outputDevice },
                    mediaProjection!!
                )
                mThread.targetVolume = volumeTemp[key] ?: 100f
                packageThreads[key.pkg] = mThread
                mThread.start()
            }
        }

        onDynamicDetach = { key ->
            val thread = packageThreads[key.pkg]
            thread?.deleteOutput(key.outputDevice)
            if(thread?.mPlayers?.size==0){
                packageThreads.remove(key.pkg)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val pkgs = intent.getStringArrayExtra("packages")?.toMutableList() ?: mutableListOf()
            val devices = intent.getIntArrayExtra("devices")?.toMutableList() ?: mutableListOf()
            pkgs.forEachIndexed { index, s ->
                apps.add(AudioOutputKey(s, devices[index]))
            }
            if (apps.isNotEmpty()) {
                running = true
                mediaProjection = mediaProjectionManager?.getMediaProjection(
                    Activity.RESULT_OK,
                    projectionData!!
                ) as MediaProjection
                apps.forEach { key ->
                    onDynamicAttach(key,
                        getAudioDevices().find { it.id == key.outputDevice }
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
        var onDynamicAttach: (AudioOutputKey, AudioDeviceInfo?) -> Unit = { _, _ -> }
        var onDynamicDetach: (AudioOutputKey) -> Unit = { _ -> }
        var getAudioDevices: () -> List<AudioDeviceInfo> = { listOf() }
        var switchDeviceFor: (AudioOutputKey, AudioDeviceInfo) -> Unit = { _, _ -> }
        var volumeTemp = HashMap<AudioOutputKey, Float>()
        var setVolumeOf: (AudioOutputKey, Float) -> Unit = { a, b -> volumeTemp[a] = b }
        var getVolumeOf: (AudioOutputKey) -> Float = { p -> volumeTemp[p] ?: 100f }
        var setBalanceOf: (AudioOutputKey, Float) -> Unit = { a, b -> }
        var getBalanceOf: (AudioOutputKey) -> Float = { _ -> 0f }
        var setBandValueOf: (AudioOutputKey, Int, Float) -> Unit = { _, _, _ -> }
        var getBandValueOf: (AudioOutputKey, Int) -> Float = { _, _ -> 50f }

        const val NOTI_ID = 1
        const val notiUpdateTime = 30000L

        lateinit var uiIntent: Intent
    }

}