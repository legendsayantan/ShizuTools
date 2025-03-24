package com.legendsayantan.adbtools.dialog

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabItem
import com.google.android.material.tabs.TabLayout
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.adapters.SimpleAdapter
import com.legendsayantan.adbtools.lib.Logger.Companion.clearLogs
import com.legendsayantan.adbtools.lib.Logger.Companion.readLog

/**
 * @author legendsayantan
 */
class LogBottomSheetDialog(context: Context) : BottomSheetDialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_log)
        val tablayout = findViewById<TabLayout>(R.id.tab_layout)
        tablayout?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when(tab?.position){
                    0 -> setupLog(true)
                    1 -> setupLog(false)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}

            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        findViewById<MaterialCardView>(R.id.copy_logs)?.setOnClickListener {
            context.readLog(tablayout?.selectedTabPosition==0){
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Logs", it)
                clipboard.setPrimaryClip(clip)
                Handler(context.mainLooper).post {
                    Toast.makeText(context, "Logs copied to clipboard.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        findViewById<MaterialCardView>(R.id.clear_logs)?.setOnClickListener {
            context.clearLogs(tablayout?.selectedTabPosition==0)
        }
        setupLog(true)
    }
    fun setupLog(app:Boolean){
        val txt = findViewById<RecyclerView>(R.id.log_text)!!
        //adapter for simple string data
        txt.adapter = SimpleAdapter(listOf("Loading...")) { }
        context.readLog(app){
            txt.post{ txt.adapter = SimpleAdapter(it.split("\n\n")) { } }
        }
    }
}