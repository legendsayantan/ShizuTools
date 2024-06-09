package com.legendsayantan.adbtools.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.legendsayantan.adbtools.lib.ShizukuRunner
/**
 * @author legendsayantan
 */
class IntentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("execution", Context.MODE_PRIVATE)
        val key = intent.getStringExtra("key") ?: return
        if (!prefs.getBoolean("enabled", false) || prefs.getLong(
                "lockuntil",
                0
            ) > System.currentTimeMillis()
        ) return
        if (prefs.getString("key", null) != key) {
            prefs.edit().putLong("lockuntil", System.currentTimeMillis() + 300000).apply()
            return
        }
        val command = intent.getStringExtra("command") ?: intent.data ?: return
        val responseAction = intent.getStringExtra("response")
        val listener = object : ShizukuRunner.CommandResultListener {
            override fun onCommandResult(output: String, done: Boolean) {
                if (done && responseAction!=null) {
                    context.sendBroadcast(Intent(responseAction).apply {
                        putExtra("response", output)
                        putExtra("command", command.toString())
                    })
                }
            }
        }
        ShizukuRunner.runAdbCommand(command.toString(), listener)
    }
}