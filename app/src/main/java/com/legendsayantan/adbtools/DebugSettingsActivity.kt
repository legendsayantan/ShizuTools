package com.legendsayantan.adbtools

import android.media.AudioRecord
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.edit
import androidx.core.widget.doOnTextChanged
import com.legendsayantan.adbtools.SoundMasterActivity.Companion.FILENAME_SOUNDMASTER_BALANCE_SLIDERS
import com.legendsayantan.adbtools.SoundMasterActivity.Companion.FILENAME_SOUNDMASTER_BAND_SLIDERS
import com.legendsayantan.adbtools.SoundMasterActivity.Companion.FILENAME_SOUNDMASTER_PACKAGE_SLIDERS
import com.legendsayantan.adbtools.lib.AudioOutputMap
import com.legendsayantan.adbtools.lib.AppParameters
import com.legendsayantan.adbtools.lib.AppParameters.Companion.defaultSettings
import com.legendsayantan.adbtools.lib.Utils.Companion.loadApps
import com.legendsayantan.adbtools.services.SoundMasterService
import java.io.File

class DebugSettingsActivity : AppCompatActivity() {
    val prefs by lazy { getSharedPreferences("debug", MODE_PRIVATE) }
    val appParameters by lazy { AppParameters(prefs) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_debug_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        //soundmaster
        bind("soundmaster_control_scope", findViewById<EditText>(R.id.soundmaster_control_scope))
        bind("soundmaster_sample_rate", findViewById<EditText>(R.id.soundmaster_sample_rate))
        bind("soundmaster_channel", findViewById<EditText>(R.id.soundmaster_channel))
        bind("soundmaster_encoding", findViewById<EditText>(R.id.soundmaster_encoding))
        bind(
            "soundmaster_buffer_size",
            findViewById(R.id.soundmaster_buffer_size),
            appParameters.getSoundMasterBufferSize()
        )
        doOnClick(R.id.soundmaster_run_diagnosis) { soundmasterDiagnosis() }
    }

    private fun bind(id: String, seekBar: SeekBar) {
        seekBar.progress = prefs.getInt(id, defaultSettings[id]!!)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.edit() { putInt(id, progress) }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun bind(id: String, editText: EditText, defaultValue: Int = -1) {
        editText.setText(
            (prefs.getInt(id, defaultSettings[id] ?: defaultValue)).toString()
        )
        editText.setHint((defaultSettings[id] ?: defaultValue).toString())
        editText.doOnTextChanged { text, start, before, count ->
            try {
                prefs.edit() {
                    if (editText.text.isEmpty()) remove(id)
                    else putInt(id, editText.text.toString().toInt())
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Invalid input", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun doOnClick(id: Int, action: () -> Unit) {
        findViewById<Button>(id).setOnClickListener {
            action()
        }
    }

    private fun soundmasterDiagnosis() {
        val tView = findViewById<TextView>(R.id.soundmaster_diagnosis)
        tView.text = "Running diagnosis, please wait."
        Thread {
            var loadedData = "Error loading apps"
            Thread {
                loadApps(callback = {
                    loadedData = "${it.size} apps found"
                })
            }.start()
            val disconnectedApps = mutableListOf<String>()
            Thread {
                try {
                    SoundMasterService.getDisconnectedAppsFromSystem({
                        disconnectedApps += it
                    })
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
            Thread.sleep(30000)
            val data =
                """
                    Soundmaster active : ${SoundMasterService.running}
                    MediaProjection active : ${SoundMasterActivity.isMediaProjectionActive}
                    Loaded apps : $loadedData
                    Audio output devices : ${
                    SoundMasterService.getAudioDevices()
                        .joinToString { it?.productName.toString() + " " + AudioOutputMap.getName(it?.type ?: 0) }
                }
                    Apps not under system control : ${disconnectedApps.joinToString()}
                    Audio input RMS : ${SoundMasterService.getAudioRmsData().joinToString()}
                    Saved Sliders info : ${
                    File(
                        applicationContext.filesDir,
                        FILENAME_SOUNDMASTER_PACKAGE_SLIDERS
                    ).let { if (it.exists()) it.readText() else "No data" }
                }
                    Saved Balance info : ${
                    File(
                        applicationContext.filesDir,
                        FILENAME_SOUNDMASTER_BALANCE_SLIDERS
                    ).let { if (it.exists()) it.readText() else "No data" }
                }
                    Saved Band info : ${
                    File(
                        applicationContext.filesDir,
                        FILENAME_SOUNDMASTER_BAND_SLIDERS
                    ).let { if (it.exists()) it.readText() else "No data" }
                }
                    """.trimIndent()
            tView.post {
                tView.text = data
            }
        }.start()
    }
}