package com.legendsayantan.adbtools.lib

/**
 * @author legendsayantan
 */
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

class ShizukuRunner {
    interface CommandResultListener {
        fun onCommandResult(output: String, done:Boolean)
    }

    companion object {
        fun runAdbCommand(command: String, listener: CommandResultListener) {
            Thread {
                val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, "/")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = StringBuilder()

                var line: String?
                var linecount = 0
                while (reader.readLine().also { line = it } != null) {
                    linecount++
                    output.append(line).append("\n")
                    if (linecount == 50) {
                        linecount = 0
                        listener.onCommandResult(output.toString(),false)
                    }
                }
                listener.onCommandResult(output.toString(),true)
                process.waitFor()
            }.start()
        }
    }
}

