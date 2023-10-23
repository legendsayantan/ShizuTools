package com.legendsayantan.adbtools

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar

class ThemePatcherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme_patcher)
        initialiseStatusBar()

    }
}