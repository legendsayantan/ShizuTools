package com.legendsayantan.adbtools

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.widget.ImageView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.legendsayantan.adbtools.adapters.VolumeBarAdapter
import com.legendsayantan.adbtools.data.AudioOutputBase
import com.legendsayantan.adbtools.data.AudioOutputKey
import com.legendsayantan.adbtools.dialog.AppSelectionDialog
import com.legendsayantan.adbtools.dialog.OutputSelectionDialog
import com.legendsayantan.adbtools.lib.ShizukuRunner
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import com.legendsayantan.adbtools.services.SoundMasterService
import com.legendsayantan.adbtools.services.SoundMasterService.Companion.prepareGetAudioDevices
import java.io.File
import java.io.FileNotFoundException
import java.util.Timer
import kotlin.concurrent.timerTask

/**
 * @author legendsayantan
 */

class SoundMasterActivity : AppCompatActivity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var packageSliders: MutableList<AudioOutputBase>
        get() = try {
            File(applicationContext.filesDir, FILENAME_SOUNDMASTER).let { file ->
                file.readText().split("\n").let { text ->
                    if (!text.any { it.isBlank() }) text.map { line ->
                        val splits = line.split("/")
                        AudioOutputBase(splits[0], splits[1].toInt(), splits[2].toFloatOrNull()?:100f)
                    }.toMutableList()
                    else {
                        file.delete()
                        mutableListOf()
                    }
                }
            }
        } catch (e: Exception) {
            File(applicationContext.filesDir, FILENAME_SOUNDMASTER).delete()
            mutableListOf()
        }
        set(value) {
            val file = File(applicationContext.filesDir, FILENAME_SOUNDMASTER)
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
            file.writeText(value.joinToString("\n") { it.pkg + "/" + it.output + "/" + it.volume})
        }

    val volumeBarView by lazy { findViewById<RecyclerView>(R.id.volumeBars) }

    @SuppressLint("ApplySharedPref")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showing = true
        setContentView(R.layout.activity_sound_master)
        initialiseStatusBar()
        prepareGetAudioDevices()
        interacted()
        //new slider
        findViewById<MaterialCardView>(R.id.newSlider).setOnClickListener {
            lastInteractionAt = -1
            AppSelectionDialog(this@SoundMasterActivity) { pkg ->
                OutputSelectionDialog(this@SoundMasterActivity,SoundMasterService.getAudioDevices()) { device ->
                    val key = AudioOutputBase(pkg, device?.id ?: -1, 100f)
                    if (
                        if (SoundMasterService.running) SoundMasterService.isAttachable(key)
                        else (packageSliders.find { it.pkg == key.pkg && it.output == key.output }==null)
                    ) {
                        val newPackages = packageSliders
                        newPackages.add(key)
                        packageSliders = newPackages
                        if (SoundMasterService.running) SoundMasterService.onDynamicAttach(key, device)
                        updateSliders()
                    }else combinationExists()
                    interacted()
                }.show()
            }.show()
        }

        //adjustment
        ViewCompat.getRootWindowInsets(findViewById(R.id.main))?.let {
            val systemBars = it.getInsets(WindowInsetsCompat.Type.systemBars())
            findViewById<Space>(R.id.insetSpace).minimumHeight = systemBars.bottom
        }

        //outside touch
        findViewById<ConstraintLayout>(R.id.main).setOnClickListener {
            finish()
        }

        setupAutoHide()
    }

    private fun setupAutoHide() {
        Timer().schedule(timerTask {
            if (lastInteractionAt >= 0
                && lastInteractionAt + hideTimerInterval < System.currentTimeMillis()
            ) {
                finish()
                cancel()
            }
        }, hideTimerInterval, hideTimerInterval)
    }

    override fun onResume() {
        updateBtnState()
        updateSliders()
        super.onResume()
    }

    fun updateBtnState() {
        val btnImage = findViewById<ImageView>(R.id.playPauseButton)
        btnImage.setImageResource(if (SoundMasterService.running) R.drawable.baseline_stop_24 else R.drawable.baseline_play_arrow_24)
        btnImage.setOnClickListener {
            val state = SoundMasterService.running
            if (state) {
                stopService(Intent(this, SoundMasterService::class.java))
            } else if (packageSliders.size > 0) {
                if (packageSliders.isEmpty()) {
                    Toast.makeText(
                        applicationContext,
                        "No apps selected to control",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    ShizukuRunner.runAdbCommand("pm grant ${baseContext.packageName} android.permission.RECORD_AUDIO",
                        object : ShizukuRunner.CommandResultListener {
                            override fun onCommandResult(output: String, done: Boolean) {
                                if (done) {
                                    ShizukuRunner.runAdbCommand("appops set ${baseContext.packageName} PROJECT_MEDIA allow",
                                        object : ShizukuRunner.CommandResultListener {
                                            override fun onCommandResult(
                                                output: String,
                                                done: Boolean
                                            ) {
                                                if (done) {
                                                    mediaProjectionManager =
                                                        applicationContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                                    startActivityForResult(
                                                        mediaProjectionManager.createScreenCaptureIntent(),
                                                        MEDIA_PROJECTION_REQUEST_CODE
                                                    )
                                                }
                                            }
                                        })
                                }
                            }

                            override fun onCommandError(error: String) {
                                Handler(mainLooper).post {
                                    Toast.makeText(
                                        applicationContext,
                                        "Shizuku Error",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        })
                }
            }
            var count = 0
            Timer().schedule(timerTask {
                if (SoundMasterService.running != state) {
                    updateBtnState()
                    updateSliders()
                    cancel()
                } else count++
                if (count > 50) cancel()
                interacted()
            }, 500, 500)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                Toast.makeText(
                    applicationContext,
                    "Controlling audio from selected apps",
                    Toast.LENGTH_SHORT
                ).show()
                SoundMasterService.projectionData = data
                startService(Intent(this, SoundMasterService::class.java).apply {
                    putExtra("packages", packageSliders.map { it.pkg }.toTypedArray())
                    putExtra("devices", packageSliders.map { it.output }.toIntArray())
                    putExtra("volumes",packageSliders.map { it.volume }.toFloatArray())
                })
                interacted()
            } else {
                Toast.makeText(
                    this, "Request to obtain MediaProjection failed.",
                    Toast.LENGTH_SHORT
                ).show()
                interacted()
            }
        }
    }

    override fun finish() {
        showing = false
        super.finish()
    }

    private fun updateSliders() {
        interacted()
        findViewById<TextView>(R.id.none).visibility =
            if (packageSliders.size > 0) View.GONE else View.VISIBLE
        Thread {
            val adapter =
                VolumeBarAdapter(this@SoundMasterActivity, packageSliders, { app, vol ->
                    interacted()
                    val newPackages = packageSliders
                    newPackages[app] = AudioOutputBase(packageSliders[app].pkg, packageSliders[app].output, vol)
                    packageSliders = newPackages
                    SoundMasterService.setVolumeOf(packageSliders[app], vol)
                }, {
                    interacted()
                    val newPackages = packageSliders
                    newPackages.removeAt(it)
                    packageSliders = newPackages
                    updateSliders()
                    SoundMasterService.onDynamicDetach(packageSliders[it])
                }, { app, sliderIndex ->
                    interacted()
                    if (sliderIndex == 0) SoundMasterService.getBalanceOf(packageSliders[app])
                    else SoundMasterService.getBandValueOf(packageSliders[app], sliderIndex - 1)
                }, { app, slider, value ->
                    interacted()
                    if (slider == 0) SoundMasterService.setBalanceOf(packageSliders[app], value)
                    else SoundMasterService.setBandValueOf(packageSliders[app], slider - 1, value)
                }, {
                    interacted()
                    SoundMasterService.getAudioDevices()
                }, { pkg, device ->
                    interacted()
                    if(SoundMasterService.switchDeviceFor(packageSliders[pkg], device)){
                        val newPackages = packageSliders
                        newPackages[pkg] = AudioOutputBase(packageSliders[pkg].pkg, device?.id?:-1, packageSliders[pkg].volume)
                        packageSliders = newPackages
                        updateSliders()
                        true
                    }else {
                        combinationExists()
                        false
                    }
                })
            runOnUiThread {
                volumeBarView.adapter = adapter
                volumeBarView.invalidate()
            }
        }.start()
    }

    private fun combinationExists(){
        Toast.makeText(applicationContext,"Combination already exists.",Toast.LENGTH_SHORT).show()
    }

    companion object {
        var showing = false
        private const val hideTimerInterval = 3000L
        var lastInteractionAt = 0L
        var interacted = {
            lastInteractionAt = System.currentTimeMillis()
        }
        private const val FILENAME_SOUNDMASTER = "soundmaster.txt"
        private const val MEDIA_PROJECTION_REQUEST_CODE = 13
    }
}