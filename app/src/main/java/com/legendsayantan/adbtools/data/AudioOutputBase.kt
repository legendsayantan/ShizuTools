package com.legendsayantan.adbtools.data

/**
 * @author legendsayantan
 */
// Deliberately not a data class: a data class here would auto-generate its own equals()/hashCode()
// over (pkg, output, volume) and an `is AudioOutputBase` type check, shadowing AudioOutputKey's
// (pkg, output)-only identity - breaking apps.contains()/remove() whenever called with a bare
// AudioOutputKey, or whenever volume differs for what should still count as the same output.
class AudioOutputBase(
    pkg: String, output: Int,
    val volume: Float
) : AudioOutputKey(pkg, output)
