package com.legendsayantan.adbtools.lib

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import com.legendsayantan.adbtools.data.AudioOutputKey
import com.legendsayantan.adbtools.lib.Logger.Companion.log
import com.legendsayantan.adbtools.services.SoundMasterService.Companion.updateInterval
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * @author legendsayantan
 */
class PlayBackThread(
    val context: Context,
    val pkg: String,
    private val mediaProjection: MediaProjection
) : Thread("$LOG_TAG : $pkg") {
    @Volatile
    var playback = true
    val constants by lazy { AppParameters(context) }
    val ENCODING = constants.getSoundMasterEncoding()
    val CHANNEL = constants.getSoundMasterChannel()
    val SAMPLE_RATE = constants.getSoundMasterSampleRate()
    val BUF_SIZE = constants.getSoundMasterBufferSize()
    val dataBuffer = ByteArray(BUF_SIZE)
    var loadedCycles = 0

    lateinit var mCapture: AudioRecord
    var mPlayers = (hashMapOf<Int, AudioPlayer>())
    override fun start() {
        ShizukuRunner.command(
            "appops set $pkg PLAY_AUDIO deny",
            object : ShizukuRunner.CommandResultListener {
                override fun onCommandError(error: String) {
                    Handler(context.mainLooper).post {
                        Toast.makeText(context, "Error: $error", Toast.LENGTH_SHORT).show()
                    }
                    context.log(error)
                }
            })
        super.start()
    }

    fun isDisconnectedFromSystem(callback:(Boolean)->Unit){
        ShizukuRunner.command("appops get $pkg PLAY_AUDIO", object : ShizukuRunner.CommandResultListener {
            override fun onCommandResult(output: String, done: Boolean) {
                if (done) {
                    if (output.contains("deny")) {
                        callback(true)
                    }else{
                        callback(false)
                    }
                }
            }
        })
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun run() {
        if (ActivityCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            interrupt()
            return
        }
        try {
            val allUsages = listOf(
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_ALARM,
                AudioAttributes.USAGE_NOTIFICATION,
                AudioAttributes.USAGE_ASSISTANT,
                AudioAttributes.USAGE_UNKNOWN,
                AudioAttributes.USAGE_VOICE_COMMUNICATION
            )
            val configBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            for (i in 0..constants.getSoundMasterControlScope().coerceIn(0,allUsages.size-1)) {
                configBuilder.addMatchingUsage(allUsages[i])
            }
            val config = configBuilder.addMatchingUid(Utils.getAppUidFromPackage(context, pkg))
                .build()
            val audioFormat = AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL)
                .build()

            mCapture = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(BUF_SIZE)
                .setAudioPlaybackCaptureConfig(config)
                .build()


        } catch (e: Exception) {
            Log.e(
                "Error",
                "Initializing Audio Record and Play objects Failed ${e.message} for $pkg"
            )
        }
        try {
            mCapture.startRecording()
            Log.i(LOG_TAG, "Audio Recording started")
            while (playback) {
                mCapture.read(dataBuffer, 0, BUF_SIZE)
                val players = mPlayers.values.toList()
                players.forEach {
                    it.write(dataBuffer, 0, dataBuffer.size)
                }
                loadedCycles++
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error in PlayBackThread")
            context.log(e.stackTraceToString(), true)
            e.printStackTrace()
        }
    }

    fun hasOutput(deviceId: Int): Boolean {
        return mPlayers.contains(deviceId)
    }

    fun createOutput(
        device: AudioDeviceInfo? = null,
        outputKey: Int = device?.id ?: -1,
        startVolume: Float, bal: Float? = null,
        bands: Array<Float> = arrayOf()
    ) {
        val plyr = AudioPlayer(
            AudioManager.STREAM_MUSIC,
            SAMPLE_RATE, CHANNEL,
            ENCODING, BUF_SIZE,
            AudioTrack.MODE_STREAM
        )
        plyr.setCurrentVolume(startVolume)
        plyr.playbackRate = SAMPLE_RATE
        plyr.preferredDevice = device
        plyr.play()
        bal?.let { plyr.setBalance(bal) }
        bands.forEachIndexed { index, fl -> plyr.setBand(index, fl) }
        mPlayers[outputKey] = plyr
    }

    fun deleteOutput(outputKey: Int, interruption: Boolean = true): AudioPlayer? {
        val plyr = mPlayers.remove(outputKey)
        plyr?.stop()
        if (mPlayers.size == 0 && interruption) {
            interrupt()
        }
        return plyr
    }

    fun switchOutputDevice(key: AudioOutputKey, newDevice: AudioDeviceInfo?): Boolean {
        if (mPlayers.contains(newDevice?.id ?: -1)) return false
        deleteOutput(key.output, false)?.let {
            createOutput(
                newDevice,
                startVolume = it.volume * 100f,
                bal = it.getBalance(),
                bands = it.savedBands
            )
        }
        return true
    }

    fun getLatency(): Float {
        return updateInterval.toFloat() / loadedCycles.coerceAtLeast(1).also { loadedCycles = 0 }
    }

    override fun interrupt() {
        playback = false
        ShizukuRunner.command(
            "appops set $pkg PLAY_AUDIO allow",
            object : ShizukuRunner.CommandResultListener {
                override fun onCommandError(error: String) {
                    Handler(context.mainLooper).post {
                        Toast.makeText(context, "Error: $error", Toast.LENGTH_SHORT).show()
                    }
                    context.log(error)
                }
            })
        try {
            mCapture.stop()
            mCapture.release()
        } catch (_: Exception) {
        }
        mPlayers.values.forEach { it.stop() }
        super.interrupt()
    }

    fun getBalance(device: Int): Float? {
        return mPlayers[device]?.getBalance()
    }

    fun setBalance(device: Int, value: Float) {
        mPlayers[device]?.setBalance(value)
    }

    fun getBand(deviceId: Int, band: Int): Float? {
        return mPlayers[deviceId]?.savedBands?.get(band)
    }

    fun setBand(device: Int, band: Int, value: Float) {
        mPlayers[device]?.setBand(band, value)
    }

    fun setVolume(outputDevice: Int, vol: Float) {
        mPlayers[outputDevice]?.setCurrentVolume(vol)
    }

    fun getVolume(it: AudioOutputKey): Float? {
        return mPlayers[it.output]?.volume?.times(100f)
    }

    fun calculateRMS(): Float {
        val shortBuffer = ShortArray(dataBuffer.size / 2)
        ByteBuffer.wrap(dataBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuffer)
        var sum = 0.0
        for (sample in shortBuffer) {
            sum += (sample * sample).toFloat()
        }
        return sqrt(sum / shortBuffer.size).toFloat()
    }


    companion object {
        const val LOG_TAG = "SoundMaster"

    }
}