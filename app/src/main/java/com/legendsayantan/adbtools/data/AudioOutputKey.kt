package com.legendsayantan.adbtools.data

/**
 * @author legendsayantan
 */
open class AudioOutputKey(
    val pkg:String,
    val output:Int=-1
){
    // Identity is (pkg, output) regardless of subclass - `is AudioOutputKey` (not a javaClass
    // check) so an AudioOutputBase and a bare AudioOutputKey for the same pair compare equal,
    // which apps.contains()/remove() rely on when called with either type.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioOutputKey) return false
        return pkg == other.pkg && output == other.output
    }

    override fun hashCode(): Int {
        return 31 * pkg.hashCode() + output
    }
}
