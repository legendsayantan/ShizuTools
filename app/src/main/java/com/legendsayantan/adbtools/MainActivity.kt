package com.legendsayantan.adbtools

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.legendsayantan.adbtools.lib.ShizukuShell
import com.legendsayantan.adbtools.lib.Utils.Companion.clearCommandOutputs
import com.legendsayantan.adbtools.lib.Utils.Companion.commandOutputPath
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import com.legendsayantan.adbtools.lib.Utils.Companion.lastCommandOutput

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initialiseStatusBar()
        findViewById<ImageView>(R.id.github).setOnClickListener {
            val shizukuUrl = "https://github.com/legendsayantan/shizutools"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(shizukuUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val cardDebloat = findViewById<MaterialCardView>(R.id.cardDebloat)
        val cardThemePatcher = findViewById<MaterialCardView>(R.id.cardThemePatcher)
        val cardLookBack = findViewById<MaterialCardView>(R.id.cardLookBack)
        val cardMixedAudio = findViewById<MaterialCardView>(R.id.cardMixedAudio)
        val cardShell = findViewById<MaterialCardView>(R.id.cardShell)
        cardDebloat.setOnClickListener { startActivity(Intent(applicationContext,DebloatActivity::class.java)) }
        cardThemePatcher.setOnClickListener { startActivity(Intent(applicationContext,ThemePatcherActivity::class.java)) }
        cardLookBack.setOnClickListener { startActivity(Intent(applicationContext,LookbackActivity::class.java)) }
        cardMixedAudio.setOnClickListener { startActivity(Intent(applicationContext,MixedAudioActivity::class.java)) }
        cardShell.setOnClickListener { openShell() }
    }
    private fun openShell(){
        val dialog = MaterialAlertDialogBuilder(this)
        dialog.setTitle("ADB Shell")
        val scrollContainer = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(30,25,30,15)
        val output = TextView(this)
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
            if(editText.text.isEmpty()) return@setOnClickListener
            clearCommandOutputs()
            ShizukuShell(listOf(),"${editText.text} | tee -a ${commandOutputPath()}").exec()
            output.text = lastCommandOutput()
            editText.selectAll()
        }
        layout.addView(output)
        layout.addView(commandBar.apply {
            addView(editText)
            addView(btn)
        })
        dialog.setView(scrollContainer.apply {
            addView(layout)
        })
        dialog.create().show()
    }
}