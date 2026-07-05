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
            // Only apply side + bottom padding to root; let AppBarLayout handle the top
            v.setPadding(systemBars.left, 0, systemBars.right, 0)
            // Push the header content below the status bar
            val header = findViewById<android.widget.LinearLayout>(R.id.header_content)
            header?.setPadding(header.paddingLeft, systemBars.top + resources.getDimensionPixelSize(R.dimen.header_padding_top_extra), header.paddingRight, header.paddingBottom)
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

        findViewById<Button>(R.id.btn_reset_defaults).setOnClickListener {
            prefs.edit().clear().apply()
            Toast.makeText(this, "Debug settings reset to defaults", Toast.LENGTH_SHORT).show()
            recreate()
        }
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
            val disconnectedApps = mutableListOf<String>()
            var loaded = false
            Thread {
                try {
                    SoundMasterService.getDisconnectedAppsFromSystem({
                        disconnectedApps += it
                    })
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
            var loadedData = "Error loading apps"
            Thread {
                loadApps(callback = {
                    loadedData = "${it.size} apps found"
                    loaded = true
                })
            }.start()
            var count = 0
            while (count < 30) {
                if (loaded && count > 10) break
                Thread.sleep(1000)
                count++
            }
            val data =
                """
                Soundmaster active : ${SoundMasterService.running}
                MediaProjection active : ${com.legendsayantan.adbtools.dialog.SoundMasterBottomSheet.isMediaProjectionActive}
                Loaded apps : $loadedData
                
                Audio output devices : 
                ${
                    SoundMasterService.getAudioDevices()
                        .joinToString("\n") {
                            it?.productName.toString() + " " + AudioOutputMap.getName(
                                it?.type ?: 0
                            )
                        }
                }
                
                Apps not under system control : 
                ${disconnectedApps.joinToString("\n")}
                
                Audio input RMS : 
                ${SoundMasterService.getAudioRmsData().joinToString("\n")}
                
                Saved Sliders info : 
                ${
                    File(
                        applicationContext.filesDir,
                        "soundmaster.txt"
                    ).let { if (it.exists()) it.readText() else "No data" }
                }
                
                Saved Balance info : 
                ${
                    File(
                        applicationContext.filesDir,
                        "soundmaster_balance.txt"
                    ).let { if (it.exists()) it.readText() else "No data" }
                }
                
                Saved Band info : 
                ${
                    File(
                        applicationContext.filesDir,
                        "soundmaster_band.txt"
                    ).let { if (it.exists()) it.readText() else "No data" }
                }
                    """.trimIndent().replace(Regex("(?m)^\\s+"), "")
            tView.post {
                tView.text = data
            }
        }.start()
    }
}