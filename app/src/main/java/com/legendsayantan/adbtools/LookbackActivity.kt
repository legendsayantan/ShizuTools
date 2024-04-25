package com.legendsayantan.adbtools

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.legendsayantan.adbtools.lib.ShizukuRunner
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

class LookbackActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lookback)
        initialiseStatusBar()
        findViewById<MaterialButton>(R.id.startBtn).setOnClickListener {
            selectFile()
        }
    }
    private fun selectFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "application/vnd.android.package-archive" // You can set specific MIME types here if needed
        startActivityForResult(intent, Companion.PICK_FILE_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                // Handle the selected file URI here
                Thread{
                    val inputStream = contentResolver.openInputStream(uri)
                    val cacheFile = File(Environment.getExternalStorageDirectory(), "/Android/data/${packageName}/installcache.apk")
                    copyFile(inputStream, cacheFile){
                        if (it) {
                            val packageToInstall = packageManager.getPackageArchiveInfo(cacheFile.absolutePath, 0)?.packageName ?:""
                            val command = "cat ${cacheFile.absolutePath} | pm install -S ${cacheFile.length()} -r -d"
                            Handler(mainLooper).post {
                                Toast.makeText(this, "Installing $packageToInstall.", Toast.LENGTH_SHORT).show()
                            }
                            ShizukuRunner.runAdbCommand(command,object : ShizukuRunner.CommandResultListener{
                                override fun onCommandResult(output: String, done: Boolean) {
                                    if(done){
                                        Handler(mainLooper).post {
                                            if (output.contains("Success", true)) {
                                                Toast.makeText(
                                                    applicationContext,
                                                    "Installed Successfully.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                Toast.makeText(
                                                    applicationContext,
                                                    "Failure: ${output}",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            cacheFile.delete()
                                        }
                                    }
                                }
                            })

                        }
                    }
                }.start()
            }
        }
    }
    private fun copyFile(inputStream: InputStream?, outputFile: File,callback:(Boolean)->Unit) {
        if (inputStream == null) {
            callback(false)
            return
        }

        try {
            val outputStream = FileOutputStream(outputFile)
            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            callback(true)
        } catch (e: IOException) {
            callback(false)
        }
    }

    companion object {
        private const val PICK_FILE_REQUEST_CODE: Int = 1
    }
}