package com.legendsayantan.adbtools.lib

import android.media.AudioTrack
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.legendsayantan.adbtools.lib.PlayBackThread.Companion.LOG_TAG
import com.legendsayantan.adbtools.services.SoundMasterService

/**
 * @author legendsayantan
 */
@Suppress("DEPRECATION")
class AudioPlayer(
    streamType : Int,
    sampleRateInHz : Int,
    channelConfig : Int,
    audioFormat : Int,
    bufferSizeInBytes : Int,
    mode : Int
) : AudioTrack(streamType,sampleRateInHz,channelConfig,audioFormat, bufferSizeInBytes, mode) {
    var volume: Float = 1f
    private var stereoGainFactor = arrayOf(1f, 1f)
    private var bandCompensations = arrayOf(0, 0, 0)
    var savedBands = arrayOf(50f, 50f, 50f)
    private var equalizer: Equalizer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var suppress: NoiseSuppressor? = null
    private var echoCancel: AcousticEchoCanceler? = null
    fun setCurrentVolume(it: Float) {
        volume = (it / 100f).coerceAtMost(1f)
        setStereoVolume(volume * stereoGainFactor[0], volume * stereoGainFactor[1])
        try {
            if (enhancer == null) enhancer = LoudnessEnhancer(audioSessionId)
            enhancer?.enabled = it > 100
            if (it > 100) enhancer?.setTargetGain(((it.toInt() - 100) * 150))
        } catch (e: Exception) {
            Log.i(LOG_TAG, "ENHANCER NOT SUPPORTED")
        }
        try {
            if (suppress == null) suppress = NoiseSuppressor.create(audioSessionId)
            suppress?.enabled = it > 100
        } catch (e: Exception) {
            Log.i(LOG_TAG, "NOISE SUPPRESSION NOT SUPPORTED")
        }
        try {
            if (echoCancel == null) echoCancel = AcousticEchoCanceler.create(audioSessionId)
            echoCancel?.enabled = it > 100
        } catch (e: Exception) {
            Log.i(LOG_TAG, "ECHO CANCELLATION NOT SUPPORTED")
        }
    }

    /**
     * changes levels of certain band groups in equalizer. follows SoundMasterService.bandDivision.
     */
    private fun updateBandLevel(bandRange: Int, percentage: Float = -1f) {
        try {
            if (equalizer == null) equalizer = Equalizer(0, audioSessionId)
            val eq = equalizer ?: return
            // Iterate through the frequency bands
            val modifiedLevel =
                eq.bandLevelRange[0] +
                        ((eq.bandLevelRange[1] - eq.bandLevelRange[0]) * percentage / 100f) +
                        bandCompensations[bandRange]
            for (i in 0 until eq.numberOfBands) {
                val centerFreq = eq.getCenterFreq(i.toShort()) / 1000
                if (centerFreq in bandDivision[bandRange]..bandDivision[bandRange + 1]) {
                    eq.setBandLevel(
                        i.toShort(),
                        if (percentage >= 0) modifiedLevel.toInt().toShort()
                        else (eq.getBandLevel(i.toShort()) + bandCompensations[bandRange]).toShort()
                            .coerceIn(eq.bandLevelRange[0], eq.bandLevelRange[1])
                    )
                }
            }
        } catch (e: Exception) {
            Log.i(LOG_TAG, "EQ NOT SUPPORTED")
        }
    }

    fun getBalance(): Float {
        return (100 - (stereoGainFactor[0] * 100).toInt()) - (100 - (stereoGainFactor[1] * 100))
    }

    fun setBalance(value: Float) {
        stereoGainFactor = arrayOf(
            if (value <= 0) 1f else 1f - (value / 100f),
            if (value >= 0) 1f else 1f + (value / 100f)
        )
        setStereoVolume(volume * stereoGainFactor[0], volume * stereoGainFactor[1])
    }
    fun setBand(band: Int, value: Float) {
        savedBands[band] = value
        try {
            if (equalizer == null) equalizer = Equalizer(0, audioSessionId)
            equalizer?.enabled = savedBands.any { it!=50f }
            updateBandLevel(band, value)
        } catch (_: Exception) { }
    }

    override fun release() {
        try { super.release() } catch (e: Exception) {}
        try { equalizer?.release() } catch (e: Exception) {}
        try { enhancer?.release() } catch (e: Exception) {}
        try { suppress?.release() } catch (e: Exception) {}
        try { echoCancel?.release() } catch (e: Exception) {}
        equalizer = null
        enhancer = null
        suppress = null
        echoCancel = null
    }

    companion object{
        val bandDivision = arrayOf(0, 250, 2000, 20000)
    }
}