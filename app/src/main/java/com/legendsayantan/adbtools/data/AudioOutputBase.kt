package com.legendsayantan.adbtools.data

/**
 * @author legendsayantan
 */
data class AudioOutputBase(
    val p : String, var d : Int,
    val volume:Float
):AudioOutputKey(p,d)
