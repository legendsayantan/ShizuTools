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
    val isServiceActive: () -> Boolean = { false }
)
