package com.legendsayantan.adbtools.data

/**
 * @author legendsayantan
 */
data class AppData(var name:String="",val id:String,val list:String="",val description:String="",val dependencies:ArrayList<String>,val neededBy:ArrayList<String>,val labels:ArrayList<String>,val removal:String="")
