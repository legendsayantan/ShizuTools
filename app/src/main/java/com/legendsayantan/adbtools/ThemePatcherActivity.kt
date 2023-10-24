package com.legendsayantan.adbtools

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.legendsayantan.adbtools.lib.ShizukuShell
import com.legendsayantan.adbtools.lib.Utils.Companion.getAllInstalledApps
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import java.util.Timer
import kotlin.concurrent.schedule

class ThemePatcherActivity : AppCompatActivity() {
    lateinit var shell: ShizukuShell
    var output = listOf<String>()
    val zeroByDefault = listOf(
        "persist.sys.trial.theme",
        "persist.sys.trial_theme",
        "persist.sys.trial.font",
        "persist.sys.trial.live_wp",
    )
    val negativeOneByDefault = listOf(
        "persist.sys.oplus.theme_uuid",
        "persist.sys.oppo.theme.uuid",
        "persist.sys.oppo.theme.uuid",
        "persist.sys.oppo.theme_uuid"
    )
    val otherDefaults = hashMapOf(
        Pair("persist.sys.oplus.live_wp_uuid", "default_live_wp_package_name"),
        Pair("persist.sys.oppo.live_wp_uuid", "default_live_wp_package_name")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme_patcher)
        initialiseStatusBar()
        shell = ShizukuShell(output, "pm grant $packageName android.permission.WRITE_SETTINGS")
        shell.exec()
        shell =
            ShizukuShell(output, "pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
        shell.exec()
        findViewById<LinearLayout>(R.id.noTrial).visibility = LinearLayout.VISIBLE
        findViewById<LinearLayout>(R.id.patchedState).visibility = LinearLayout.GONE
        var themeStores = packageManager.getAllInstalledApps().filter { it.packageName.contains("theme") }
        if(themeStores.any { it.loadLabel(packageManager).contains("theme") }) themeStores = themeStores.filter { it.loadLabel(packageManager).contains("theme") }
        if(themeStores.any { it.packageName.contains("store") }) themeStores = themeStores.filter { it.packageName.contains("store") }
        if(isOnTrial()){
            patchAll {
                findViewById<LinearLayout>(R.id.noTrial).visibility = LinearLayout.GONE
                findViewById<LinearLayout>(R.id.patchedState).visibility = LinearLayout.VISIBLE
            }
        }else{
            val themeStoreBtn = findViewById<MaterialButton>(R.id.launchThemeStore)
            themeStoreBtn.setOnClickListener {
                //start via intent
                val intent = packageManager.getLaunchIntentForPackage(themeStores[0].packageName)
                intent?.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                startPatcher {
                    findViewById<LinearLayout>(R.id.noTrial).visibility = LinearLayout.GONE
                    findViewById<LinearLayout>(R.id.patchedState).visibility = LinearLayout.VISIBLE
                    output.forEach {
                        Toast.makeText(applicationContext, it, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun startPatcher(patched: () -> Unit) {
        Toast.makeText(this, "Waiting for trial item...", Toast.LENGTH_SHORT).show()
        Thread {
            while (!isOnTrial()) {
                Thread.sleep(1000)
            }
            patchAll(patched)
        }.start()
    }

    private fun patchAll(done: () -> Unit) {
        if (isOnTrial()) {
            //patch
            shell = ShizukuShell(output, "am force-stop com.heytap.themestore")
            shell.exec()
            shell = ShizukuShell(output, "am force-stop com.nearme.themestore")
            shell.exec()
            zeroByDefault.forEach {
                shell = ShizukuShell(output, "settings put system $it 0")
                shell.exec()
                shell = ShizukuShell(output, "settings put secure $it 0")
                shell.exec()
            }
            negativeOneByDefault.forEach {
                shell = ShizukuShell(output, "settings put system $it -1")
                shell.exec()
                shell = ShizukuShell(output, "settings put secure $it -1")
                shell.exec()
            }
            otherDefaults.forEach {
                shell = ShizukuShell(output, "settings put system ${it.key} ${it.value}")
                shell.exec()
                shell = ShizukuShell(output, "settings put secure ${it.key} ${it.value}")
                shell.exec()
            }
            runOnUiThread { done() }
        }
    }

    fun isOnTrial(): Boolean {
        zeroByDefault.forEach {
            val system = Settings.System.getInt(contentResolver, it, 0)
            val secure = Settings.Secure.getInt(contentResolver, it, 0)
            if (system != 0 || secure != 0) {
                return true
            }
        }
        negativeOneByDefault.forEach {
            val system = Settings.System.getInt(contentResolver, it, -1)
            val secure = Settings.Secure.getInt(contentResolver, it, -1)
            if (system != -1 || secure != -1) {
                return true
            }
        }
        otherDefaults.forEach {
            val system = Settings.System.getString(contentResolver, it.key)
            val secure = Settings.Secure.getString(contentResolver, it.key)
            if (system != it.value || secure != it.value) {
                return true
            }
        }
        return false
    }
}