package com.legendsayantan.adbtools.data

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

data class ToolCard(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val descRes: Int,
    @DrawableRes val iconRes: Int,
    @ColorRes val accentColorRes: Int,
    val activityClass: Class<*>?,
    val onClickOverride: (() -> Unit)? = null,
    val isServiceActive: () -> Boolean = { false },
    /** Minimum SDK_INT this tool works on at all. 0 = supported on every version this app installs on. */
    val minSdk: Int = 0
)
