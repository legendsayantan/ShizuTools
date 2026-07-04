package com.legendsayantan.adbtools.services

import android.annotation.SuppressLint
import android.os.Process
import android.util.Log

class ShizuToolsService : IShizuToolsService.Stub() {

    @SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi")
    override fun setAppOpMode(pkgName: String, uid: Int, opCode: Int, mode: Int) {
        try {
            val aoClz = Class.forName("android.app.AppOpsManager")
            val getAos = aoClz.getDeclaredMethod("getService").apply { isAccessible = true }
            val aos = getAos.invoke(null) ?: return
            
            val setMode = aos.javaClass.getDeclaredMethod(
                "setMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
            
            setMode.invoke(aos, opCode, uid, pkgName, mode)
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("ShizuToolsService", "Error setting AppOp: ${e.stackTraceToString()}")
        }
    }
}
