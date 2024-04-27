package com.legendsayantan.adbtools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.legendsayantan.adbtools.lib.ShizukuRunner
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import java.util.UUID

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initialiseStatusBar()
        findViewById<ImageView>(R.id.github).setOnClickListener {
            val shizukuUrl = "https://github.com/legendsayantan/shizutools"
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(shizukuUrl)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        val cardDebloat = findViewById<MaterialCardView>(R.id.cardDebloat)
        val cardThemePatcher = findViewById<MaterialCardView>(R.id.cardThemePatcher)
        val cardLookBack = findViewById<MaterialCardView>(R.id.cardLookBack)
        val cardMixedAudio = findViewById<MaterialCardView>(R.id.cardMixedAudio)
        val cardShell = findViewById<MaterialCardView>(R.id.cardShell)
        val cardIntentShell = findViewById<MaterialCardView>(R.id.cardIntentShell)
        cardDebloat.setOnClickListener {
            startActivity(
                Intent(
                    applicationContext,
                    DebloatActivity::class.java
                )
            )
        }
        cardThemePatcher.setOnClickListener {
            startActivity(
                Intent(
                    applicationContext,
                    ThemePatcherActivity::class.java
                )
            )
        }
        cardLookBack.setOnClickListener {
            startActivity(
                Intent(
                    applicationContext,
                    LookbackActivity::class.java
                )
            )
        }
        cardMixedAudio.setOnClickListener {
            startActivity(
                Intent(
                    applicationContext,
                    MixedAudioActivity::class.java
                )
            )
        }
        cardShell.setOnClickListener { openShell() }
        cardIntentShell.setOnClickListener { intentShell() }
    }

    private fun openShell() {
        val dialog = MaterialAlertDialogBuilder(this)
        dialog.setTitle("ADB Shell")
        val scrollContainer = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(30, 25, 30, 15)
        val commandOut = TextView(this)
        val commandBar = LinearLayout(this)
        commandBar.orientation = LinearLayout.HORIZONTAL
        commandBar.gravity = Gravity.CENTER
        val editText = EditText(this)
        editText.hint = "Enter Command..."
        editText.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            weight = 1f
        }
        val btn = MaterialButton(this)
        btn.text = "Run"

        btn.setOnClickListener {
            if (editText.text.isEmpty()) return@setOnClickListener
            ShizukuRunner.runAdbCommand(editText.text.toString(), object :
                ShizukuRunner.CommandResultListener {
                override fun onCommandResult(output: String, done: Boolean) {
                    runOnUiThread {
                        commandOut.text = output
                        btn.isEnabled = done
                    }
                }
            })
            editText.selectAll()
        }
        layout.addView(commandOut)
        layout.addView(commandBar.apply {
            addView(editText)
            addView(btn)
        })
        dialog.setView(scrollContainer.apply {
            addView(layout)
        })
        dialog.create().show()
    }
    private fun intentShell(){
        val prefs = getSharedPreferences("execution", MODE_PRIVATE)
        if(prefs.getString("key",null)==null){
            prefs.edit().putString("key",UUID.randomUUID().toString()).apply()
        }
        val key = prefs.getString("key",null)
        val layout = LinearLayout(this).apply {
            setPadding(60,0,60,0)
        }
        layout.orientation = LinearLayout.VERTICAL
        val toggle = SwitchMaterial(this)
        toggle.isChecked = prefs.getBoolean("enabled",false)
        toggle.text = "Accept adb intents"
        toggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enabled",isChecked).apply()
        }
        val editLayout = TextInputLayout(this)
        val editText = TextInputEditText(this)
        editText.setText(key)
        editText.focusable = View.NOT_FOCUSABLE
        editText.hint = "Access key"
        editLayout.addView(editText)
        editText.setOnClickListener {
            //copy to clipboar
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("key", key)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this,"Key copied to clipboard",Toast.LENGTH_SHORT).show()
        }
        layout.addView(toggle)
        layout.addView(editLayout)
        MaterialAlertDialogBuilder(this)
            .setTitle("Intent Shell")
            .setMessage("Intent shell locks for 5 minutes after usage of a wrong access key, to prevent brute force attempts.")
            .setView(layout)
            .setPositiveButton("OK") { _, _ -> }
            .create().show()
    }
}