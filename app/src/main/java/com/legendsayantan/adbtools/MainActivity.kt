package com.legendsayantan.adbtools

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.google.android.material.card.MaterialCardView
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initialiseStatusBar()
        val cardDebloat = findViewById<MaterialCardView>(R.id.cardDebloat)
        val cardThemePatcher = findViewById<MaterialCardView>(R.id.cardThemePatcher)
        val cardLookBack = findViewById<MaterialCardView>(R.id.cardLookBack)
        val cardMixedAudio = findViewById<MaterialCardView>(R.id.cardMixedAudio)
        cardDebloat.setOnClickListener { startActivity(Intent(applicationContext,DebloatActivity::class.java)) }
        cardThemePatcher.setOnClickListener { startActivity(Intent(applicationContext,ThemePatcherActivity::class.java)) }
        cardLookBack.setOnClickListener { startActivity(Intent(applicationContext,LookbackActivity::class.java)) }
        cardMixedAudio.setOnClickListener { startActivity(Intent(applicationContext,MixedAudioActivity::class.java)) }
    }
}