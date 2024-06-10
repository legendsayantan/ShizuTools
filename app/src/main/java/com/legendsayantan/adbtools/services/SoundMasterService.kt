package com.legendsayantan.adbtools.services

import android.Manifest
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.PackageManagerCompat
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.SoundMasterActivity
import com.legendsayantan.adbtools.lib.ShizukuRunner
import java.lang.Byte
import java.util.Timer
import kotlin.Boolean
import kotlin.Float
import kotlin.Int
import kotlin.String
import kotlin.Unit
import kotlin.arrayOf
import kotlin.concurrent.timerTask
import kotlin.let


class SoundMasterService : Service() {
    private lateinit var mVolumeObserver: ContentObserver
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    var threadMap = hashMapOf<String, AudioThread>()
    var apps = mutableListOf<String>()
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
            val avg = threadMap.values.map { it.getLatency() }.average().toInt()
            threadMap.values.forEach { it.loadedCycles = 0 }
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

        getVolumeOf = {
            (threadMap[it]?.volume?.times(100f)) ?: volumeTemp[it] ?: 100f
        }

        setVolumeOf = { pkg, vol ->
            threadMap[pkg].let {
                if (it != null) it.setCurrentVolume(vol) else volumeTemp[pkg] = vol
            }
        }

        getBalanceOf = {
            threadMap[it]?.getBalance() ?: 0f
        }

        setBalanceOf = { it, value ->
            threadMap[it]?.setBalance(value)
        }

        getBandValueOf = { it, band ->
            threadMap[it]?.savedBands?.get(band) ?: 50f
        }

        setBandValueOf = { it, band, value ->
            threadMap[it]?.setBand(band, value)
        }

        onDynamicAttach = { it ->
            if (!threadMap.contains(it)) {
                if (!apps.contains(it)) apps.add(it)
                val mThread = AudioThread(applicationContext, it, mediaProjection!!)
                mThread.targetVolume = volumeTemp[it] ?: 100f
                mThread.latencyUpdate = { value ->
                    latency.add(value)
                }
                threadMap[it] = mThread
                mThread.start()
            }
        }

        onDynamicDetach = { pkg ->
            if (apps.contains(pkg)) {
                apps.remove(pkg)
                threadMap[pkg]?.interrupt()
                threadMap.remove(pkg)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            apps = intent.getStringArrayExtra("packages")?.toMutableList() ?: mutableListOf()
            if (apps.isNotEmpty()) {
                running = true
                mediaProjection = mediaProjectionManager?.getMediaProjection(
                    Activity.RESULT_OK,
                    projectionData!!
                ) as MediaProjection
                apps.forEach {
                    onDynamicAttach(it)
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
                if(newVolume != prevVolume){
                    prevVolume = newVolume
                    Handler(mainLooper).post {
                        if(SoundMasterActivity.showing) SoundMasterActivity.interacted()
                        else ShizukuRunner.runAdbCommand("am start -n $packageName/${SoundMasterActivity::class.java.canonicalName}",object : ShizukuRunner.CommandResultListener{
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
        threadMap.forEach { it.value.interrupt() }
        mediaProjection?.stop()
        super.onDestroy()
    }

    companion object {
        var running = false
        var projectionData: Intent? = null
        var onDynamicAttach: (String) -> Unit = {}
        var onDynamicDetach: (String) -> Unit = {}
        var volumeTemp = HashMap<String, Float>()
        var setVolumeOf: (String, Float) -> Unit = { a, b -> volumeTemp[a] = b }
        var getVolumeOf: (String) -> Float = { p -> volumeTemp[p] ?: 100f }
        var setBalanceOf: (String, Float) -> Unit = { a, b -> }
        var getBalanceOf: (String) -> Float = { _ -> 0f }
        var setBandValueOf: (String, Int, Float) -> Unit = { _, _, _ -> }
        var getBandValueOf: (String, Int) -> Float = { _, _ -> 50f }

        const val NOTI_ID = 1
        const val notiUpdateTime = 30000L
        const val SAMPLE_RATE = 44100
        const val LOG_TAG = "SoundMaster"
        const val CHANNEL = AudioFormat.CHANNEL_IN_STEREO
        val BUF_SIZE =
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, AudioFormat.ENCODING_PCM_16BIT)

        val zeroByte = Byte.valueOf(0)
        val bandDivision = arrayOf(0, 250, 2000, 20000)
        lateinit var uiIntent: Intent
    }

}