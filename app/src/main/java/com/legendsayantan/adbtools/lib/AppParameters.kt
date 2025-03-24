package com.legendsayantan.adbtools.lib

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.AudioRecord
import java.util.prefs.Preferences

/**
 * @author legendsayantan
 */
class AppParameters(val debugPrefs:SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences("debug", Context.MODE_PRIVATE)){}

    fun getValue(key: String): Int {
        return debugPrefs.getInt(key, defaultSettings[key] ?: -1)
    }
    fun getSoundMasterControlScope(): Int {
        return getValue("soundmaster_control_scope")
    }
    fun getSoundMasterSampleRate(): Int {
        return getValue("soundmaster_sample_rate").coerceIn(8000,192000)
    }
    fun getSoundMasterChannel(): Int {
        return getValue("soundmaster_channel").coerceAtLeast(AudioFormat.CHANNEL_INVALID)
    }
    fun getSoundMasterEncoding(): Int {
        return getValue("soundmaster_encoding").coerceAtLeast(AudioFormat.ENCODING_INVALID)
    }

    fun getSoundMasterBufferSize(): Int {
        return debugPrefs.getInt("soundmaster_buffer_size", AudioRecord.getMinBufferSize(getSoundMasterSampleRate(), getSoundMasterChannel(), getSoundMasterEncoding())).coerceAtLeast(1)
    }

    companion object{
        val defaultSettings = mapOf(
            "soundmaster_control_scope" to 5,
            "soundmaster_sample_rate" to 48000,
            "soundmaster_channel" to (AudioFormat.CHANNEL_IN_STEREO or AudioFormat.CHANNEL_OUT_STEREO),
            "soundmaster_encoding" to AudioFormat.ENCODING_PCM_16BIT
        )
    }
}