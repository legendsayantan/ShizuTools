package com.legendsayantan.adbtools.lib

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL

/**
 * @author legendsayantan
 */
class GradleUpdate(val context: Context,val gradleFileUrl: String,val checkInterval:Long) {
    val prefs = context.getSharedPreferences("update",Context.MODE_PRIVATE)
    fun check(updateAvailable: (String) -> Unit, noUpdateAvailable: () -> Unit = {}) {
        if((System.currentTimeMillis() - prefs.getLong("lastchecked",0)) < checkInterval){
            return
        }
        GlobalScope.launch {
            try {
                val url = URL(gradleFileUrl)
                val connection = withContext(Dispatchers.IO) {
                    url.openConnection()
                }
                val bufferedReader =
                    BufferedReader(InputStreamReader(withContext(Dispatchers.IO) {
                        connection.getInputStream()
                    }))
                val contents = bufferedReader.use { it.readText() }
                contents.let {
                    val match = Regex("versionName \".*?\"").find(it)
                    if (match != null) {
                        val version = match.value.trim().substring(13, match.value.length - 1)
                        //get package versionName
                        if(compareVersionNames(context.packageManager.getPackageInfo(context.packageName,0).versionName,version)>0){
                            updateAvailable(version)
                        } else {
                            noUpdateAvailable()
                        }
                        prefs.edit().putLong("lastchecked",System.currentTimeMillis()).apply()
                    }
                }
            } catch (e: Exception) {
                print("Error retrieving data: ${e.message}")
            }
        }
    }

    fun checkAndNotify(apkUrl: String,smallIcon:Int) {
        check({ version ->
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val intent = Intent(Intent.ACTION_VIEW,android.net.Uri.parse(apkUrl))
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        "${context.packageName}.update",
                        "Updates",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
                Notification.Builder(context, "${context.packageName}.update")
                    .setContentTitle("Update available")
                    .setContentText("New version $version available, click to download.")
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setSmallIcon(smallIcon)
                    .build()
            } else {
                Notification.Builder(context)
                    .setContentTitle("Update available")
                    .setContentText("New version $version available")
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setSmallIcon(smallIcon)
                    .build()
            }
            notificationManager.notify(1, notification)
        }, {})
    }
    companion object{
        fun compareVersionNames(oldVersionName: String, newVersionName: String): Int {
            var res = 0
            val oldVersion = oldVersionName.replace("[^\\dd.]".toRegex(), "")
            val newVersion = newVersionName.replace("[^\\dd.]".toRegex(), "")

            val oldNumbers = oldVersion.split("\\.".toRegex()).toTypedArray()
            val newNumbers = newVersion.split("\\.".toRegex()).toTypedArray()

            // To avoid IndexOutOfBounds
            val maxIndex = oldNumbers.size.coerceAtMost(newNumbers.size)

            for (i in 0 until maxIndex) {
                val oldVersionPart = oldNumbers[i].toInt()
                val newVersionPart = newNumbers[i].toInt()

                if (oldVersionPart < newVersionPart) {
                    res = 1
                    break
                } else if (oldVersionPart > newVersionPart) {
                    res = -1
                    break
                }
            }
            return res
        }
    }
}