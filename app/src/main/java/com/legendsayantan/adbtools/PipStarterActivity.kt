package com.legendsayantan.adbtools

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.legendsayantan.adbtools.lib.ShizukuRunner
import java.util.Timer
import kotlin.concurrent.timerTask

class PipStarterActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent.getStringExtra("package")
            ?.let {
                startActivity(packageManager.getLaunchIntentForPackage(it))
                playVideo()
            }
        finish()
    }

    companion object {
        fun Context.handlePip() {
            ShizukuRunner.runAdbCommand("settings get global overlay_display_devices",object : ShizukuRunner.CommandResultListener{
                override fun onCommandResult(output: String, done: Boolean) {
                    if(done){
                        if(output.trim().contains("null",true)){
                            enablePip()
                        }else{
                            disablePip()
                        }
                    }
                }
            })
        }

        fun Context.enablePip(){
            Timer().schedule(timerTask {
                ShizukuRunner.runAdbCommand(
                    "dumpsys window displays | grep -E 'mCurrentFocus'",
                    object : ShizukuRunner.CommandResultListener {
                        override fun onCommandResult(output: String, done: Boolean) {
                            if (done) {
                                val pipPackage = output.split(" ")[4].split("/")[0]
                                val metrics = resources.displayMetrics
                                val height = (metrics.widthPixels / (metrics.heightPixels.toFloat() / metrics.widthPixels))+100
                                ShizukuRunner.runAdbCommand("settings put global overlay_display_devices ${metrics.widthPixels}x${height.toInt()}/240",
                                    object : ShizukuRunner.CommandResultListener {
                                        override fun onCommandResult(
                                            output: String,
                                            done: Boolean
                                        ) {
                                            if (done) {
                                                Timer().schedule(timerTask {
                                                    ShizukuRunner.runAdbCommand("dumpsys display | grep 'Display [0-9][0-9]*'",
                                                        object : ShizukuRunner.CommandResultListener {
                                                            override fun onCommandResult(
                                                                output: String,
                                                                done: Boolean
                                                            ) {
                                                                if (done) {
                                                                    val newDisplayId =
                                                                        "\\d+".toRegex()
                                                                            .findAll(output)
                                                                            .filter { it.value != "0" }
                                                                            .map { it.value.toInt() }
                                                                            .maxOrNull() ?: 0
                                                                    println(output)
                                                                    println(newDisplayId)
                                                                    val command =
                                                                        "am start -n $packageName/${PipStarterActivity::class.java.canonicalName} --es package $pipPackage --display $newDisplayId"
                                                                    ShizukuRunner.runAdbCommand(
                                                                        command,
                                                                        object :
                                                                            ShizukuRunner.CommandResultListener {
                                                                            override fun onCommandResult(
                                                                                output: String,
                                                                                done: Boolean
                                                                            ) {
                                                                                if (done) {
                                                                                    println("PIP started")
                                                                                }
                                                                            }

                                                                            override fun onCommandError(
                                                                                error: String
                                                                            ) {
                                                                                disablePip()
                                                                                println(error)
                                                                            }
                                                                        })
                                                                }
                                                            }

                                                            override fun onCommandError(error: String) {
                                                                disablePip()
                                                                println(error)
                                                            }
                                                        })
                                                }, 500)
                                            }
                                        }
                                    })
                            }
                        }
                    })
            }, 1500)
        }

        fun disablePip() {
            ShizukuRunner.runAdbCommand("settings put global overlay_display_devices null",
                object : ShizukuRunner.CommandResultListener {
                    override fun onCommandResult(output: String, done: Boolean) {
                        playVideo()
                    }
                })
        }

        fun playVideo() {
            Timer().schedule(timerTask {
                ShizukuRunner.runAdbCommand("input keyevent ${KeyEvent.KEYCODE_MEDIA_PLAY}",
                    object : ShizukuRunner.CommandResultListener {})
            }, 1500)
        }
    }
}