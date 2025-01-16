package com.legendsayantan.adbtools.lib

import android.content.Context
import java.io.File
import java.io.FileWriter

/**
 * @author legendsayantan
 */class Logger {
    companion object {
        private const val APP_LOG_FILE = "app.log"
        private const val SHIZUKU_LOG_FILE = "shizuku.log"

        fun Context.log(message: String, appError:Boolean=false) {
            val logFile = if(appError) APP_LOG_FILE else SHIZUKU_LOG_FILE
            Thread{
                try {
                    val file = File(dataDir,logFile)
                    if (!file.exists()) {
                        file.createNewFile()
                    }
                    val writer = FileWriter(file, true)
                    writer.append(System.currentTimeMillis().toString()+"\n")
                    writer.append(message)
                    writer.append("\n\n")
                    writer.flush()
                    writer.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        }

        fun Context.readLog(appLogs:Boolean=true,onResult:(String)->Unit) {
            Thread{
                val logFile = if(appLogs) APP_LOG_FILE else SHIZUKU_LOG_FILE
                try {
                    val file = File(dataDir,logFile)
                    if (!file.exists()) {
                        file.createNewFile()
                    }
                    val reader = file.bufferedReader()
                    val text = reader.readText()
                    reader.close()
                    onResult(text)
                } catch (e: Exception) {
                    e.printStackTrace()
                    onResult("Error reading log: ${e.message}")
                }
            }.start()
        }

        fun Context.clearLogs(appLogs: Boolean){
            Thread{
                val logFile = if(appLogs) APP_LOG_FILE else SHIZUKU_LOG_FILE
                try {
                    val file = File(dataDir,logFile)
                    if (file.exists()) {
                        file.delete()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        }
    }
}