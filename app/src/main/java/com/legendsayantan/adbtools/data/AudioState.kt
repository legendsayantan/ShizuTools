package com.legendsayantan.adbtools.data

/**
 * @author legendsayantan
 */
data class AudioState(var name:String,var muted:Boolean,var focus:Focus){
    enum class Focus{
        ALLOWED,
        IGNORED,
        DENIED,
    }
}
