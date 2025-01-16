package com.legendsayantan.adbtools.data

/**
 * @author legendsayantan
 */
open class AudioOutputKey(
    val pkg:String,
    val output:Int=-1
){
    //defining the equals method
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val key = other as AudioOutputKey
        return pkg == key.pkg && output == key.output
    }
}
