package com.legendsayantan.adbtools

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.legendsayantan.adbtools.lib.ShizukuRunner
import com.legendsayantan.adbtools.lib.Utils.Companion.getAllInstalledApps
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import com.legendsayantan.adbtools.lib.Utils.Companion.postNotification
import java.util.Timer
import kotlin.concurrent.schedule
/**
 * @author legendsayantan
 */
class ThemePatcherActivity : AppCompatActivity() {
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
        ShizukuRunner.runAdbCommand("pm grant $packageName android.permission.WRITE_SETTINGS",object : ShizukuRunner.CommandResultListener{
            override fun onCommandResult(output: String, done: Boolean) {}
        })
        ShizukuRunner.runAdbCommand("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS",object : ShizukuRunner.CommandResultListener{
            override fun onCommandResult(output: String, done: Boolean) {}
        })
        ShizukuRunner.runAdbCommand("pm grant $packageName android.permission.POST_NOTIFICATIONS",object : ShizukuRunner.CommandResultListener{
            override fun onCommandResult(output: String, done: Boolean) {}
        })
        var themeStores =
            packageManager.getAllInstalledApps().filter { it.packageName.contains("theme") }
        if (themeStores.any { it.loadLabel(packageManager).contains("theme") }) themeStores =
            themeStores.filter { it.loadLabel(packageManager).contains("theme") }
        else if (themeStores.any { it.packageName.contains("store") }) themeStores =
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
                    getString(R.string.themepatcher),
                    getString(R.string.trial_item_detected_generating_patch), success = false
                )
            }
            Timer().schedule(15000) {
                patchAll(storepackage){
                    if(it.isEmpty()){
                        patched()
                    }else{
                        runOnUiThread {
                            postNotification(
                                getString(R.string.themepatcher),
                                "Error: $it", success = false
                            )
                        }
                    }
                }

            }
        }.start()
    }

    private fun patchAll(storepackage: String, done: (String) -> Unit) {
        if (isOnTrial()) {
            //patch
            if(storepackage.isNotEmpty()){
                ShizukuRunner.runAdbCommand("am force-stop $storepackage",object : ShizukuRunner.CommandResultListener{
                    override fun onCommandResult(output: String, done: Boolean) {}
                })
            }

            zeroByDefault.forEach {
                ShizukuRunner.runAdbCommand("settings put system $it 0",object : ShizukuRunner.CommandResultListener{
                    override fun onCommandResult(output: String, done: Boolean) {}
                })
                ShizukuRunner.runAdbCommand("settings put secure $it 0",object : ShizukuRunner.CommandResultListener{
                    override fun onCommandResult(output: String, done: Boolean) {}
                })
            }
            negativeOneByDefault.forEach {
                ShizukuRunner.runAdbCommand("settings put system $it -1",object : ShizukuRunner.CommandResultListener{
                    override fun onCommandResult(output: String, done: Boolean) {}
                })
                ShizukuRunner.runAdbCommand("settings put secure $it -1",object : ShizukuRunner.CommandResultListener{
                    override fun onCommandResult(output: String, done: Boolean) {}
                })
            }
            otherDefaults.forEach {
                ShizukuRunner.runAdbCommand("settings put system ${it.key} ${it.value}",object : ShizukuRunner.CommandResultListener{
                    override fun onCommandResult(output: String, done: Boolean) {}
                })
                ShizukuRunner.runAdbCommand("settings put secure ${it.key} ${it.value}",object : ShizukuRunner.CommandResultListener{
                    override fun onCommandResult(output: String, done: Boolean) {
                        runOnUiThread { done(output) }
                    }
                })
            }
        }
    }

    private fun isOnTrial(): Boolean {
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
            getString(R.string.themepatcher),
            getString(R.string.selected_item_was_patched_as_permanent),
            success = true
        )
    }




}