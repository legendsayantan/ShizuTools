package com.legendsayantan.adbtools.lib

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Environment
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.legendsayantan.adbtools.R
import java.io.File

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
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }

        fun PackageManager.getAllInstalledApps(): List<ApplicationInfo> {
            return getInstalledApplications(PackageManager.GET_META_DATA)
        }

        fun Context.postNotification(title: String, message: String, success: Boolean = true) {
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
                    notify(0, notificationBuilder.build())
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

        fun loadApps(callback: (List<String>) -> Unit) {
            ShizukuRunner.runAdbCommand(
                "pm list packages",
                object : ShizukuRunner.CommandResultListener {
                    override fun onCommandResult(output: String, done: Boolean) {
                        val packages = output.replace("package:", "").split("\n")
                        callback(packages)
                    }
                })
        }

        fun getAppUidFromPackage(context: Context, packageName: String): Int {
            return context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).uid
        }

        fun getAppNameFromPackage(context: Context, packageName: String): String {
            return context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).loadLabel(context.packageManager).toString()
        }


    }
}