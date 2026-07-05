package com.legendsayantan.adbtools

import android.graphics.drawable.Drawable

data class AppItem(
    val packageName: String,
    val name: String,
    val icon: Drawable,
    var bucket: Int,
    var isLocked: Boolean
)
