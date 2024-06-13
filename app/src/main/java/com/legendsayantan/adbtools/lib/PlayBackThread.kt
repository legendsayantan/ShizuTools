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
import com.legendsayantan.adbtools.services.SoundMasterService.Companion.notiUpdateTime

/**
 * @author legendsayantan
 */
class PlayBackThread(
    val context: Context,
    val pkg: String, val initialOutput : AudioDeviceInfo?,
    private val mediaProjection: MediaProjection
) : Thread("$LOG_TAG : $pkg") {
    var playback = true
    var targetVolume: Float = 100f
    val dataBuffer = ByteArray(BUF_SIZE)
    var loadedCycles = 0
    var latencyUpdate: (Int) -> Unit = {}

    lateinit var mCapture: AudioRecord
    var mPlayers =  (hashMapOf <Int, AudioPlayer>())
    override fun start() {
        ShizukuRunner.runAdbCommand("appops set $pkg PLAY_AUDIO deny",
            object : ShizukuRunner.CommandResultListener {
                override fun onCommandResult(output: String, done: Boolean) {}
                override fun onCommandError(error: String) {
                    Handler(context.mainLooper).post {
                        Toast.makeText(context, "Shizuku Error", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        super.start()
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
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_ALARM)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
//                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION) //causes failure
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
                .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION)
                .addMatchingUid(Utils.getAppUidFromPackage(context, pkg))
                .build()
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL)
                .build()

            mCapture = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(
                    AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        CHANNEL,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                )
                .setAudioPlaybackCaptureConfig(config)
                .build()

            createOutput(initialOutput)
            mPlayers[initialOutput?.id]?.setCurrentVolume(targetVolume)
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
                mPlayers.values.forEach { it.write(dataBuffer, 0, dataBuffer.size) }
                loadedCycles++
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error in VolumeThread: ${e.message}")
        }
    }

    fun createOutput(device:AudioDeviceInfo?=null){
        mPlayers[device?.id?:-1] = AudioPlayer(
            AudioManager.STREAM_MUSIC,
            SAMPLE_RATE, CHANNEL,
            AudioFormat.ENCODING_PCM_16BIT, BUF_SIZE,
            AudioTrack.MODE_STREAM
        ).also {
            it.setCurrentVolume(targetVolume)
            it.playbackRate = SAMPLE_RATE
            if(device!=null) it.preferredDevice = device
            it.play()
            try {
                it.equalizer.enabled = true
            }catch (_:Exception){}
        }
    }

    fun deleteOutput(outputId:Int){
        mPlayers[outputId]?.stop()
        mPlayers.remove(outputId)
        if(mPlayers.size==0){
            interrupt()
        }
    }

    fun switchOutputDevice(key:AudioOutputKey, newDevice:AudioDeviceInfo){
        val playerKey = if(mPlayers.contains(key.outputDevice)) key.outputDevice else -1
        mPlayers[playerKey]?.preferredDevice = newDevice
        mPlayers[newDevice.id] = mPlayers[playerKey]!!
        mPlayers.remove(playerKey)
    }
    fun getLatency(): Float {
        return notiUpdateTime.toFloat() / loadedCycles.coerceAtLeast(1).also { loadedCycles = 0 }
    }

    override fun interrupt() {
        playback = false
        ShizukuRunner.runAdbCommand("appops set $pkg PLAY_AUDIO allow",
            object : ShizukuRunner.CommandResultListener {
                override fun onCommandResult(output: String, done: Boolean) {}
                override fun onCommandError(error: String) {
                    Handler(context.mainLooper).post {
                        Toast.makeText(context, "Shizuku Error", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        mCapture.stop()
        mCapture.release()
        mPlayers.values.forEach { it.stop() }
        super.interrupt()
    }

    fun getBalance(device:Int): Float? {
        return mPlayers[device]?.getBalance()
    }

    fun setBalance(device:Int,value: Float) {
        mPlayers[device]?.setBalance(value)
    }

    fun getBand(deviceId:Int,band:Int): Float?{
        return mPlayers[deviceId]?.savedBands?.get(band)
    }

    fun setBand(device:Int,band: Int, value: Float) {
        mPlayers[device]?.setBand(band,value)
    }

    fun setVolume(outputDevice: Int, vol: Float) {
        mPlayers[outputDevice]?.setCurrentVolume(vol)
    }

    fun getVolume(it: AudioOutputKey): Float? {
        return mPlayers[it.outputDevice]?.volume?.times(100f)
    }

    companion object{
        const val SAMPLE_RATE = 44100
        const val LOG_TAG = "SoundMaster"
        const val CHANNEL = AudioFormat.CHANNEL_IN_STEREO
        val BUF_SIZE =
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, AudioFormat.ENCODING_PCM_16BIT)
    }
}