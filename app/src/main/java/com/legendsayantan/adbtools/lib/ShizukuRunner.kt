package com.legendsayantan.adbtools.lib

/**
 * @author legendsayantan
 */
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

class ShizukuRunner {
    interface CommandResultListener {
        /*
        * Runs after the command executes, at least partially. Does not run with 'done' if the command throws an error.
        * output: The output of the command
        * done: If the command has finished executing
         */
        fun onCommandResult(output: String, done: Boolean){}

        /*
        * Runs if the command throws an error.
        * error: The error message
         */
        fun onCommandError(error: String) {}
    }

    companion object {
        fun runAdbCommand(command: String, listener: CommandResultListener, lineBundle: Int = 50) {
            Thread {
                try {
                    val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, "/")
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val err = BufferedReader(InputStreamReader(process.errorStream))
                    val output = StringBuilder()
                    val errordata = StringBuilder()
                    var line: String?
                    var linecount = 0
                    while (reader.readLine().also { line = it } != null) {
                        linecount++
                        output.append(line).append("\n")
                        if (linecount == lineBundle) {
                            linecount = 0
                            listener.onCommandResult(output.toString(), false)
                        }
                    }
                    while (err.readLine().also { line = it } != null) {
                        errordata.append(line).append("\n")
                    }
                    if(errordata.isNotBlank()) listener.onCommandError(errordata.toString())
                    else listener.onCommandResult(output.toString(), true)
                    process.waitFor()
                } catch (e: Exception) {
                    listener.onCommandError(e.message ?: "No Shizuku")
                }

            }.start()
        }
    }
}

