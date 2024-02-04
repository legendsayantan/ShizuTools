package com.legendsayantan.adbtools

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.legendsayantan.adbtools.lib.ShizukuShell
import com.legendsayantan.adbtools.lib.Utils.Companion.clearCommandOutputs
import com.legendsayantan.adbtools.lib.Utils.Companion.commandOutputPath
import com.legendsayantan.adbtools.lib.Utils.Companion.getAllInstalledApps
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import com.legendsayantan.adbtools.lib.Utils.Companion.lastCommandOutput
import com.legendsayantan.adbtools.lib.Utils.Companion.postNotification
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
        shell = ShizukuShell(output, "pm grant $packageName android.permission.POST_NOTIFICATIONS")
        shell.exec()
        var themeStores =
            packageManager.getAllInstalledApps().filter { it.packageName.contains("theme") }
        if (themeStores.any { it.loadLabel(packageManager).contains("theme") }) themeStores =
            themeStores.filter { it.loadLabel(packageManager).contains("theme") }
        if (themeStores.any { it.packageName.contains("store") }) themeStores =
            themeStores.filter { it.packageName.contains("store") }
        if (isOnTrial()) {
            startPatcher(themeStores[0].packageName) {
                postPatchNotification()
            }
        }
        val themeStoreBtn = findViewById<MaterialButton>(R.id.launchThemeStore)
        themeStoreBtn.setOnClickListener {
            //start via intent
            val intent = packageManager.getLaunchIntentForPackage(themeStores[0].packageName)
            intent?.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            startPatcher(themeStores[0].packageName) {
                postPatchNotification()
                output.forEach {
                    Toast.makeText(applicationContext, it, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startPatcher(storepackage: String, patched: () -> Unit) {
        patchAll("") {}
        Toast.makeText(this, "Waiting for trial item...", Toast.LENGTH_SHORT).show()
        Thread {
            while (!isOnTrial()) {
                Thread.sleep(1000)
            }
            runOnUiThread {
                postNotification(
                    "ThemePatcher",
                    "Trial item detected, generating patch..", success = false
                )
            }
            Timer().schedule(7000) {
                patchAll(storepackage){
                    if(lastCommandOutput().isEmpty()){
                        patched()
                    }else{
                        runOnUiThread {
                            postNotification(
                                "ThemePatcher",
                                "Error: ${lastCommandOutput()}", success = false
                            )
                        }
                    }
                }

            }
        }.start()
    }

    private fun patchAll(storepackage: String, done: () -> Unit) {
        clearCommandOutputs()
        if (isOnTrial()) {
            //patch
            if(storepackage.isNotEmpty()){
                shell = ShizukuShell(output, "am force-stop $storepackage")
                shell.exec()
            }

            zeroByDefault.forEach {
                shell = ShizukuShell(output, "settings put system $it 0 | tee -a ${commandOutputPath()}")
                shell.exec()
                shell = ShizukuShell(output, "settings put secure $it 0 | tee -a ${commandOutputPath()}")
                shell.exec()
            }
            negativeOneByDefault.forEach {
                shell = ShizukuShell(output, "settings put system $it -1 | tee -a ${commandOutputPath()}")
                shell.exec()
                shell = ShizukuShell(output, "settings put secure $it -1 | tee -a ${commandOutputPath()}")
                shell.exec()
            }
            otherDefaults.forEach {
                shell = ShizukuShell(output, "settings put system ${it.key} ${it.value} | tee -a ${commandOutputPath()}")
                shell.exec()
                shell = ShizukuShell(output, "settings put secure ${it.key} ${it.value} | tee -a ${commandOutputPath()}")
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

    private fun postPatchNotification() {
        postNotification(
            "ThemePatcher",
            "Selected item was patched as permanent.",
            success = true
        )
    }




}