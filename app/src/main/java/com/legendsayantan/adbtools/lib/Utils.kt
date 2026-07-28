package com.legendsayantan.adbtools.lib

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.lib.Logger.Companion.log

/**
 * @author legendsayantan
 */
class Utils {
    companion object{
        fun String.extractUrls(): List<String> {
            val urlRegex = Regex("(https?|ftp|file)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]*[-A-Za-z0-9+&@#/%=~_|]")
            val matches = urlRegex.findAll(this)
            val urls = mutableListOf<String>()

            for (match in matches) {
                urls.add(match.value)
            }

            return urls
        }
        fun String.removeUrls(): String {
            val urlRegex = Regex("(https?|ftp|file)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]*[-A-Za-z0-9+&@#/%=~_|]")
            var counter = 1
            val replacedText = urlRegex.replace(this) {
                val replacement = "[link $counter]"
                counter++
                replacement
            }
            return replacedText
        }

        fun Activity.initialiseStatusBar(){
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        }

        fun Activity.setupEdgeToEdgeInsets(rootId: Int, headerId: Int) {
            val root = findViewById<android.view.View>(rootId) ?: return
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
                val insets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                view.setPadding(insets.left, 0, insets.right, 0)
                val header = findViewById<android.view.View>(headerId)
                header?.setPadding(header.paddingLeft, insets.top + resources.getDimensionPixelSize(R.dimen.header_padding_top_extra), header.paddingRight, header.paddingBottom)
                androidx.core.view.WindowInsetsCompat.CONSUMED
            }
        }

        fun Activity.showSnackbar(msg: String, length: Int = com.google.android.material.snackbar.Snackbar.LENGTH_SHORT) {
            com.google.android.material.snackbar.Snackbar.make(findViewById(android.R.id.content), msg, length).show()
        }

        fun android.view.View.hapticConfirm() {
            performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        }

        fun Activity.hapticConfirm() {
            findViewById<android.view.View>(android.R.id.content)?.hapticConfirm()
        }

        fun PackageManager.getAllInstalledApps(): List<ApplicationInfo> {
            return getInstalledApplications(PackageManager.GET_META_DATA)
        }

        fun Context.postNotification(title: String, message: String, success: Boolean = true,id:Int=0) {
            val channelId = "notifications"

            // Create the notification using NotificationCompat.
            val notificationBuilder = NotificationCompat.Builder(applicationContext, channelId)
                .setSmallIcon(if(success)R.drawable.baseline_verified_24 else R.drawable.outline_info_24) // Replace with your notification icon.
                .setContentTitle(title) // Replace with your notification title.
                .setContentText(message)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_HIGH)

            // Show the notification.
            with(NotificationManagerCompat.from(applicationContext)) {
                if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    notify(id, notificationBuilder.build())
                }
            }
        }

        fun Context.initialiseNotiChannel(){
            val channelId = "notifications"

            // Create a notification channel (for Android 8.0 and higher).
            val channel = NotificationChannel(
                channelId,
                "Notifications",
                NotificationManager.IMPORTANCE_HIGH
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        fun Context.getNotiPerms(){
            try {
                // grantPermission() already goes through PackageManager.grantRuntimePermission()
                // via reflection in the privileged process - no need to spawn a `pm grant` shell.
                ShizuToolsController.execute { service ->
                    try {
                        service.grantPermission(packageName, Manifest.permission.POST_NOTIFICATIONS)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        fun loadApps(specifyUser:Int=-1,callback: (List<String>) -> Unit,errorCallback:(String)->Unit={}) {
            Thread {
                ShizuToolsController.execute { service ->
                    try {
                        val packages = service.getInstalledPackages(specifyUser)
                        callback(packages)
                    } catch (e: Exception) {
                        if (specifyUser >= 0) {
                            errorCallback(e.message ?: "Error getting packages")
                        } else {
                            loadApps(0, callback, errorCallback)
                        }
                    }
                }
            }.start()
        }

        fun getAppUidFromPackage(context: Context, packageName: String): Int {
            return context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).uid
        }

        fun getAppNameFromPackage(context: Context, packageName: String): String {
            return try {
                context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).loadLabel(context.packageManager).toString()
            }catch (e:Exception){
                context.log(e.stackTraceToString(),true)
                packageName
            }
        }

        fun Float.toFixed(digits: Int) = "%.${digits}f".format(this)

        /** Human-readable Android version for a given SDK_INT, for "requires Android X+" messaging. */
        fun androidVersionName(sdkInt: Int): String = when (sdkInt) {
            android.os.Build.VERSION_CODES.O -> "8.0"
            android.os.Build.VERSION_CODES.O_MR1 -> "8.1"
            android.os.Build.VERSION_CODES.P -> "9"
            android.os.Build.VERSION_CODES.Q -> "10"
            android.os.Build.VERSION_CODES.R -> "11"
            android.os.Build.VERSION_CODES.S -> "12"
            android.os.Build.VERSION_CODES.S_V2 -> "12L"
            android.os.Build.VERSION_CODES.TIRAMISU -> "13"
            android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> "14"
            else -> "API $sdkInt"
        }
    }
}