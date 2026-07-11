package com.legendsayantan.adbtools

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.legendsayantan.adbtools.services.SoundMasterService

class SoundMasterWakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService(Intent(this, SoundMasterService::class.java).apply {
            action = "ACTION_WAKE_BUBBLE"
        })
        finish()
    }
}
