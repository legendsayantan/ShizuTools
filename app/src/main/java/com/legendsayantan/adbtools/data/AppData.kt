package com.legendsayantan.adbtools.data

/**
 * @author legendsayantan
 */
data class AppData(
    var name: String = "",
    val list: String = "",
    val description: String = "",
    val dependencies: ArrayList<String> = arrayListOf(),
    val neededBy: ArrayList<String> = arrayListOf(),
    val labels: ArrayList<String> = arrayListOf(),
    val removal: String = "",
    var isDisabled: Boolean = false,
    var isHidden: Boolean = false
)
