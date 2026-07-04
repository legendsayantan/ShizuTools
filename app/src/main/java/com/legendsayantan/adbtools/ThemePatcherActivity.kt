package com.legendsayantan.adbtools

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.legendsayantan.adbtools.lib.ShizukuRunner
import com.legendsayantan.adbtools.lib.Utils.Companion.getAllInstalledApps
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import com.legendsayantan.adbtools.services.ThemePatcherService

class ThemePatcherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_theme_patcher)
        initialiseStatusBar()
        
        val root = findViewById<View>(R.id.root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Only apply side + bottom padding to root; let AppBarLayout handle the top
            view.setPadding(insets.left, 0, insets.right, 0)
            // Push the header content below the status bar
            val header = findViewById<android.widget.LinearLayout>(R.id.header_content)
            header?.setPadding(header.paddingLeft, insets.top + resources.getDimensionPixelSize(R.dimen.header_padding_top_extra), header.paddingRight, header.paddingBottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.animate(root).alpha(1f).scaleX(1f).scaleY(1f).setDuration(280).start()

        val themeStoreBtn = findViewById<MaterialButton>(R.id.launchThemeStore)

        ShizukuRunner.command("pm grant $packageName android.permission.WRITE_SETTINGS",
            object : ShizukuRunner.CommandResultListener { })
        ShizukuRunner.command("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS",
            object : ShizukuRunner.CommandResultListener { })

        val logContainer = findViewById<View>(R.id.log_container)
        val logTextView = findViewById<android.widget.TextView>(R.id.patcher_log)
        val sessionSummary = findViewById<android.widget.TextView>(R.id.session_summary)
        val stopPatcherBtn = findViewById<MaterialButton>(R.id.stopPatcherBtn)

        stopPatcherBtn.setOnClickListener {
            stopService(Intent(this, ThemePatcherService::class.java))
            themeStoreBtn.visibility = View.VISIBLE
            themeStoreBtn.isEnabled = true
            stopPatcherBtn.visibility = View.GONE
            logContainer.visibility = View.GONE
            sessionSummary.visibility = View.GONE
        }

        themeStoreBtn.setOnClickListener {
            themeStoreBtn.visibility = View.GONE
            stopPatcherBtn.visibility = View.VISIBLE
            logContainer.visibility = View.VISIBLE
            sessionSummary.visibility = View.GONE
            logTextView.text = ""
            startForegroundService(Intent(this, ThemePatcherService::class.java))

            Thread {
                var themeStores = packageManager.getAllInstalledApps().filter { 
                    it.packageName.contains("theme") || it.loadLabel(packageManager).contains("theme") 
                }
                if (themeStores.any { it.packageName.contains("store") }) {
                    themeStores = themeStores.filter { it.packageName.contains("store") }
                }
                if (themeStores.isNotEmpty()) {
                    runOnUiThread {
                        val intent = packageManager.getLaunchIntentForPackage(themeStores[0].packageName)
                        intent?.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        if (intent != null) {
                            startActivity(intent)
                        }
                    }
                }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        val themeStoreBtn = findViewById<MaterialButton>(R.id.launchThemeStore)
        val stopPatcherBtn = findViewById<MaterialButton>(R.id.stopPatcherBtn)
        val logContainer = findViewById<View>(R.id.log_container)
        val logTextView = findViewById<android.widget.TextView>(R.id.patcher_log)
        val sessionSummary = findViewById<android.widget.TextView>(R.id.session_summary)

        val isRunning = ThemePatcherService.isRunning
        if (isRunning) {
            themeStoreBtn.visibility = View.GONE
            stopPatcherBtn.visibility = View.VISIBLE
            logContainer.visibility = View.VISIBLE
        } else {
            themeStoreBtn.visibility = View.VISIBLE
            themeStoreBtn.isEnabled = true
            stopPatcherBtn.visibility = View.GONE
        }

        ThemePatcherService.logListener = { logMsg ->
            runOnUiThread {
                logContainer.visibility = View.VISIBLE
                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                logTextView.append("[$time] $logMsg\n")
                findViewById<android.widget.ScrollView>(R.id.log_scrollview).fullScroll(View.FOCUS_DOWN)
            }
        }

        ThemePatcherService.patchCompleteListener = { items ->
            runOnUiThread {
                themeStoreBtn.visibility = View.VISIBLE
                themeStoreBtn.isEnabled = true
                stopPatcherBtn.visibility = View.GONE
                sessionSummary.visibility = View.VISIBLE
                val count = items.count { it.isNotEmpty() }
                sessionSummary.text = "Patched $count items this session"
            }
        }
    }

    override fun onPause() {
        super.onPause()
        ThemePatcherService.logListener = null
        ThemePatcherService.patchCompleteListener = null
    }
}