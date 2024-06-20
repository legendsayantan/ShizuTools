package com.legendsayantan.adbtools.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.legendsayantan.adbtools.PipStarterActivity.Companion.handlePip

class PipReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        context.handlePip()
    }
}