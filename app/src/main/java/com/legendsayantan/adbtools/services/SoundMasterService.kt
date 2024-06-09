package com.legendsayantan.adbtools.services

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.legendsayantan.adbtools.R

class SoundMasterService : Service() {
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    var threadMap = hashMapOf<String, AudioThread>()
    var apps = mutableListOf<String>()
    override fun onBind(intent: Intent): IBinder {
        return null!!
    }

    override fun onCreate() {
        super.onCreate()

        //foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val notification = NotificationCompat.Builder(this, "notifications")
                .setContentTitle(applicationContext.getString(R.string.soundmaster)+" is controlling apps.")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        }
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

        setBalanceOf = {it,value->
            threadMap[it]?.setBalance(value)
        }

        getBandValueOf = {it,band->
            threadMap[it]?.savedBands?.get(band) ?: 50f
        }

        setBandValueOf = { it,band,value->
            threadMap[it]?.setBand(band,value)
        }

        onDynamicAttach = {
            if (!apps.contains(it)) {
                apps += it
                val mThread = AudioThread(applicationContext, it, mediaProjection!!)
                threadMap[it] = mThread
                mThread.start()
            }
        }

        onDynamicDetach = { pkg->
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
                    val mThread = AudioThread(applicationContext, it, mediaProjection!!).apply {
                        targetVolume = volumeTemp[it]?:100f
                    }
                    threadMap[it] = mThread
                    mThread.start()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
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
        var getVolumeOf: (String) -> Float = { p -> volumeTemp[p]?:100f }
        var setBalanceOf:  (String, Float) -> Unit = { a, b ->  }
        var getBalanceOf: (String) -> Float = {_->0f}
        var setBandValueOf: (String,Int,Float) ->Unit = {_,_,_->}
        var getBandValueOf:(String,Int)->Float = {_,_-> 50f}

        const val SAMPLE_RATE = 44100
        const val LOG_TAG = "SoundMaster"
        const val CHANNEL = AudioFormat.CHANNEL_IN_STEREO
        val BUF_SIZE =
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, AudioFormat.ENCODING_PCM_16BIT)

        val bandDivision = arrayOf(0, 250, 2000, 20000)
    }


}