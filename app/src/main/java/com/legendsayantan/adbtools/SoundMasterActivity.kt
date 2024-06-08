package com.legendsayantan.adbtools

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
import com.legendsayantan.adbtools.dialog.NewSliderDialog
import com.legendsayantan.adbtools.lib.ShizukuRunner
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import com.legendsayantan.adbtools.services.SoundMasterService
import java.io.File
import java.io.FileNotFoundException
import java.util.Timer
import kotlin.concurrent.timerTask

class SoundMasterActivity : AppCompatActivity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    val preferences by lazy {
        applicationContext.getSharedPreferences(
            "volumeplus",
            Context.MODE_PRIVATE
        )
    }
    var packages: MutableList<String>
        get() = try {
            File(applicationContext.filesDir, "soundmaster.txt").readText().split("\n")
                .toMutableList()
        } catch (f: FileNotFoundException) {
            mutableListOf()
        }
        set(value) {
            val file = File(applicationContext.filesDir, "soundmaster.txt")
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
            file.writeText(value.joinToString("\n"))
        }

    val volumeBarView by lazy { findViewById<RecyclerView>(R.id.volumeBars) }

    @SuppressLint("ApplySharedPref")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sound_master)
        initialiseStatusBar()
        //new slider
        findViewById<MaterialCardView>(R.id.newSlider).setOnClickListener {
            NewSliderDialog(this@SoundMasterActivity) { pkg ->
                val newPackages = packages
                newPackages.add(pkg)
                packages = newPackages
                if (SoundMasterService.running) SoundMasterService.onDynamicAttach(pkg)
                updateSliders()
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
            } else if (packages.size > 0) {
                if (packages.isEmpty()) {
                    Toast.makeText(
                        applicationContext,
                        "No apps selected to control",
                        Toast.LENGTH_SHORT
                    )
                        .show()
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
                    putExtra("packages", packages.toTypedArray())
                })
            } else {
                Toast.makeText(
                    this, "Request to obtain MediaProjection denied.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updateSliders() {
        findViewById<TextView>(R.id.none).visibility =
            if (packages.size > 0) View.GONE else View.VISIBLE
        Thread {
            val sliderMap = HashMap<String, Float>()
            for (pkg in packages) {
                val volume = SoundMasterService.getVolumeOf(pkg)
                sliderMap[pkg] = volume
            }
            val adapter =
                VolumeBarAdapter(this@SoundMasterActivity, sliderMap, { app, vol ->
                    SoundMasterService.setVolumeOf(app, vol)
                }, {
                    val newPackages = packages
                    newPackages.remove(it)
                    packages = newPackages
                    updateSliders()
                    SoundMasterService.onDynamicDetach(it)
                }, { app, sliderIndex ->
                    if (sliderIndex == 0) SoundMasterService.getBalanceOf(app)
                    else SoundMasterService.getBandValueOf(app, sliderIndex - 1)
                }, { app, slider, value ->
                    if (slider == 0) SoundMasterService.setBalanceOf(app, value)
                    else SoundMasterService.setBandValueOf(app, slider - 1, value)
                })
            runOnUiThread {
                volumeBarView.adapter = adapter
                volumeBarView.invalidate()
            }
        }.start()
    }

    companion object {
        private const val MEDIA_PROJECTION_REQUEST_CODE = 13
    }
}