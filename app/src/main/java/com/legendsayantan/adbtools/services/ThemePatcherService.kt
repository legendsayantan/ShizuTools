package com.legendsayantan.adbtools.services

import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.lib.Logger.Companion.log
import com.legendsayantan.adbtools.lib.Utils.Companion.getAllInstalledApps
import com.legendsayantan.adbtools.lib.Utils.Companion.postNotification

class ThemePatcherService : Service() {
    private val zeroByDefault = listOf(
        "persist.sys.trial.theme",
        "persist.sys.trial_theme",
        "persist.sys.trial.font",
        "persist.sys.trial.live_wp",
    )
    private val negativeOneByDefault = listOf(
        "persist.sys.oplus.theme_uuid",
        "persist.sys.oppo.theme.uuid",
        "persist.sys.oppo.theme.uuid",
        "persist.sys.oppo.theme_uuid"
    )
    private val otherDefaults = hashMapOf(
        Pair("persist.sys.oplus.live_wp_uuid", "default_live_wp_package_name"),
        Pair("persist.sys.oppo.live_wp_uuid", "default_live_wp_package_name")
    )
    private var intent: Intent? = null
    override fun onBind(intent: Intent): IBinder? {
        this.intent = intent
        return null
    }

    private val handler by lazy { Handler(mainLooper) }

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        val builder = NotificationCompat.Builder(this, "notifications")
            .setContentTitle(getString(R.string.themepatcher))
            .setContentText(
                getString(
                    R.string.themepatcher_initial_noti
                )
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(1000 * 60 * 5)
            .setWhen(System.currentTimeMillis() + 1000 * 60 * 5)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTI_ID,
                builder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            )
        } else {
            startForeground(
                NOTI_ID,
                builder.build()
            )
        }

        var themeStores =
            packageManager.getAllInstalledApps().filter {
                it.packageName.contains("theme") || it.loadLabel(packageManager).contains("theme")
            }
        if (themeStores.any { it.packageName.contains("store") }) themeStores =
            themeStores.filter { it.packageName.contains("store") }

        startPatcher(themeStores) {
            postPatchNotification(it)
        }
    }

    override fun onTimeout(startId: Int) {
        isRunning = false
        try {
            stopService(Intent(this, ThemePatcherService::class.java))
        } catch (_: Exception) {
            stopForeground(STOP_FOREGROUND_DETACH)
        }
        super.onTimeout(startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }

    private fun startPatcher(
        storepackages: List<ApplicationInfo>,
        patched: (Array<String>) -> Unit
    ) {
        logListener?.invoke("Starting ThemePatcher...")
        logListener?.invoke("Resetting trial items...")
        patchAll() {}
        Thread {
            logListener?.invoke("Scanning for applied trial items (waiting for user)...")
            var trialStates = trialItems()
            while (trialStates[3].isEmpty()) {
                trialStates = trialItems()
                Thread.sleep(3500)
            }
            trialStates = trialItems()
            var notiMessage = "Applying on " + trialStates.take(3).filter { it.isNotEmpty() }
                .joinToString(separator = ", ")
            logListener?.invoke("Detected: $notiMessage")
            handler.post {
                postNotification(
                    getString(R.string.themepatcher_patching),
                    notiMessage, success = false, NOTI_ID * 10
                )
            }
            logListener?.invoke("Waiting for system to stabilize (12s)...")
            Thread.sleep(12000)
            logListener?.invoke("Killing Theme Store apps...")
            killStores(storepackages)
            Thread.sleep(3000)
            logListener?.invoke("Ensuring Theme Store apps are dead...")
            killStores(storepackages)
            logListener?.invoke("Patching trial configurations...")
            patchAll() {
                if (it.isEmpty()) {
                    logListener?.invoke("Patching complete!")
                    handler.post { 
                        patched(trialStates)
                        patchCompleteListener?.invoke(trialStates)
                    }
                } else {
                    logListener?.invoke("Patching Error: $it")
                    handler.post {
                        postNotification(
                            getString(R.string.themepatcher),
                            "Error: $it", success = false
                        )
                    }
                }
                logListener?.invoke("Shutting down service in 10s...")
                Thread.sleep(10000)
                try {
                    stopService(Intent(this, ThemePatcherService::class.java))
                } catch (_: Exception) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                }
            }
        }.start()
    }

    private fun killStores(storepackages: List<ApplicationInfo>) {
        if (storepackages.isNotEmpty()) {
            com.legendsayantan.adbtools.lib.ShizuToolsController.execute { service ->
                storepackages.forEach { sPackage ->
                    try { service.forceStopPackage(sPackage.packageName) } catch (e: Exception) {}
                }
            }
        }
    }

    private fun patchAll(done: (String) -> Unit) {
        if (trialItems()[3].isNotEmpty()) {
            // All writes go through one privileged call instead of spawning a `settings put`
            // shell process per key/table - also replaces the old completion signal (which only
            // fired off the *last* table's otherDefaults callback, a timing coincidence rather
            // than an actual "everything finished" check) with a real one.
            com.legendsayantan.adbtools.lib.ShizuToolsController.execute { service ->
                val errors = mutableListOf<String>()
                val tables = listOf("system", "secure")
                tables.forEach { table ->
                    zeroByDefault.forEach { key ->
                        try { service.putSetting(table, key, "0") } catch (e: Exception) { errors.add("$table/$key: ${e.message}") }
                    }
                    negativeOneByDefault.forEach { key ->
                        try { service.putSetting(table, key, "-1") } catch (e: Exception) { errors.add("$table/$key: ${e.message}") }
                    }
                    otherDefaults.forEach { (key, value) ->
                        try { service.putSetting(table, key, value) } catch (e: Exception) { errors.add("$table/$key: ${e.message}") }
                    }
                }
                handler.post { done(errors.joinToString("; ")) }
            }
        }
    }

    /*
    * Retuns an array of strings with the following values:
    * 0 - Theme name if Trial
    * 1 - Font name if Trial
    * 2 - Trial status for live wallpaper
    * 3 - Trial status for any of them
    * */
    private fun trialItems(): Array<String> {
        val items = Array<String>(4) { "" }
        zeroByDefault.forEach {
            val system = Settings.System.getInt(contentResolver, it, 0)
            val secure = Settings.Secure.getInt(contentResolver, it, 0)
            if (system != 0 || secure != 0) {
                items[3] = "Trial"
                if (it.contains("theme")) {
                    items[0] = "Theme : " + extractNameFor(false)
                } else if (it.contains("font")) {
                    items[1] = "Font : " + extractNameFor(true)
                } else if (it.contains("live_wp")) {
                    items[2] = "Live Wallpaper : On Trial"
                }
            }
        }
        negativeOneByDefault.forEach {
            val system = Settings.System.getInt(contentResolver, it, -1)
            val secure = Settings.Secure.getInt(contentResolver, it, -1)
            if (system != -1 || secure != -1) {
                items[3] = "Trial"
            }
        }
        otherDefaults.forEach {
            val system = Settings.System.getString(contentResolver, it.key)
            val secure = Settings.Secure.getString(contentResolver, it.key)
            if (system != it.value || secure != it.value) {
                items[3] = "Trial"
            }
        }
        return items
    }

    private fun extractNameFor(isFont: Boolean): String {
        return if (isFont) {
            Settings.System.getString(contentResolver, "current_typeface_name")
        } else {
            Settings.System.getString(contentResolver, "current_wallpaper_name").split(";")[0]
                .replace("InnerTheme:", "")
        }.trim()
    }

    private fun postPatchNotification(items: Array<String>) {
        postNotification(
            getString(R.string.themepatcher),
            getString(R.string.patched) + " " + items.take(3).filter { it.isNotEmpty() }
                .joinToString(separator = ", "),
            success = true, NOTI_ID * 10
        )
    }

    companion object {
        const val NOTI_ID = 2
        var isRunning: Boolean = false
        var logListener: ((String) -> Unit)? = null
        var patchCompleteListener: ((Array<String>) -> Unit)? = null
    }
}