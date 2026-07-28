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
        val uid = context.packageManager.getPackageInfo(pkg, 0).applicationInfo?.uid ?: -1

        // Remember the app's current PLAY_AUDIO/TAKE_AUDIO_FOCUS modes (via the privileged
        // service's reflection-based getAppOpMode - the raw AppOpsManager.MODE_* int, not a
        // hand-parsed CLI string) so interrupt() can restore them, then mute the app's own output
        // BEFORE capture starts - starting capture unconditionally here (outside this callback)
        // raced the mute call, so the original app could keep playing audibly for however long
        // Shizuku took to bind/respond.
        com.legendsayantan.adbtools.lib.ShizuToolsController.execute { controller ->
            try {
                val playAudioMode = controller.getAppOpMode(pkg, uid, AppOps.PLAY_AUDIO)
                val focusMode = controller.getAppOpMode(pkg, uid, AppOps.TAKE_AUDIO_FOCUS)
                val prefs = context.getSharedPreferences("sm_recovery", Context.MODE_PRIVATE)
                prefs.edit().putString(pkg, "$playAudioMode,$focusMode").apply()
                val muted = controller.setAppOpMode(pkg, uid, AppOps.PLAY_AUDIO, AppOps.MODE_ERRORED)
                if (!muted) {
                    // The write reported success but the mode never actually changed - a known
                    // shell-privilege restriction on some Android versions/OEM builds that
                    // silently no-ops appops writes for other packages. No client-side retry can
                    // fix this; it needs root-level Shizuku instead of ADB/shell pairing.
                    Handler(context.mainLooper).post {
                        Toast.makeText(
                            context,
                            "Couldn't mute $pkg's original audio - your Shizuku permission level may not allow this (try enabling Shizuku via root).",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    context.log("PLAY_AUDIO mute for $pkg did not take effect (silently rejected).", true)
                }
            } catch (e: Exception) {
                Handler(context.mainLooper).post {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                context.log(e.stackTraceToString())
            } finally {
                super@PlayBackThread.start()
            }
        }
    }

    fun isDisconnectedFromSystem(callback: (Boolean) -> Unit) {
        val uid = context.packageManager.getPackageInfo(pkg, 0).applicationInfo?.uid ?: -1
        com.legendsayantan.adbtools.lib.ShizuToolsController.execute { controller ->
            val mode = try { controller.getAppOpMode(pkg, uid, AppOps.PLAY_AUDIO) } catch (e: Exception) { 0 }
            callback(mode == 2)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
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
            context.log(e.stackTraceToString(), true)
            // mCapture never got initialized - bail out now instead of falling through to
            // startRecording(), which would throw on the uninitialized lateinit property and
            // leave this thread's app silently "controlled" but never actually captured.
            interrupt()
            return
        }
        try {
            mCapture.startRecording()
            Log.i(LOG_TAG, "Audio Recording started")
            while (playback) {
                val read = synchronized(rmsLock) { mCapture.read(dataBuffer, 0, BUF_SIZE) }
                if (read > 0) {
                    val players = mPlayers.values.toList()
                    players.forEach {
                        it.write(dataBuffer, 0, read)
                    }
                    loadedCycles++
                }
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
        plyr?.release()
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
        val uid = context.packageManager.getPackageInfo(pkg, 0).applicationInfo?.uid ?: -1
        
        val prefs = context.getSharedPreferences("sm_recovery", Context.MODE_PRIVATE)
        val savedState = prefs.getString(pkg, "0,0")
        val modes = savedState?.split(",")?.mapNotNull { it.toIntOrNull() } ?: listOf(0, 0)
        val playAudioMode = modes.getOrElse(0) { 0 }
        val focusMode = modes.getOrElse(1) { 0 }
        
        com.legendsayantan.adbtools.lib.ShizuToolsController.execute { controller ->
            try {
                controller.setAppOpMode(pkg, uid, AppOps.PLAY_AUDIO, playAudioMode)
                controller.setAppOpMode(pkg, uid, AppOps.TAKE_AUDIO_FOCUS, focusMode)
            } catch (e: Exception) {
                Handler(context.mainLooper).post {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                context.log(e.stackTraceToString())
            }
        }
        prefs.edit().remove(pkg).apply()

        try {
            mCapture.stop()
            mCapture.release()
        } catch (_: Exception) {
        }
        mPlayers.values.forEach { 
            it.stop()
            it.release()
        }
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

    // Guards dataBuffer between the capture thread (writing via mCapture.read) and calculateRMS()
    // (read from the main thread's rms-meter timer) - without it the two race on the same array,
    // which can hand the meter a torn mix of old/new samples.
    private val rmsLock = Any()
    private val rmsShortBuffer = ShortArray(BUF_SIZE / 2)
    private val rmsByteBuffer = ByteBuffer.wrap(dataBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

    /** Returns RMS amplitude of the most recently captured buffer, normalized to 0f..1f. */
    fun calculateRMS(): Float {
        synchronized(rmsLock) {
            rmsByteBuffer.position(0)
            rmsByteBuffer.get(rmsShortBuffer)
        }
        var sum = 0.0
        for (sample in rmsShortBuffer) {
            sum += (sample * sample).toFloat()
        }
        return (sqrt(sum / rmsShortBuffer.size).toFloat() / Short.MAX_VALUE).coerceIn(0f, 1f)
    }


    companion object {
        const val LOG_TAG = "SoundMaster"

    }
}