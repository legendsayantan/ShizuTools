package com.legendsayantan.adbtools.dialog

import android.app.Dialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.setPadding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.receivers.PipReceiver

class UniversalPipDialog(context: Context) : Dialog(context) {
    init {
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setGravity(Gravity.CENTER)
        window?.setLayout(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val prefs = context.getSharedPreferences("universal_pip", Context.MODE_PRIVATE)
        val content = MaterialCardView(context).apply {
            radius = 50f
            elevation = 0f
            strokeWidth = context.resources.getDimensionPixelSize(R.dimen.stroke_gold)
            val typedArray = context.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.colorOutline))
            strokeColor = typedArray.getColor(0, 0)
            typedArray.recycle()
            setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        content.addView(LinearLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(50,50,50,50)
            background = context.getDrawable(R.drawable.bg_paper)
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                setPadding(0,0,0,25)
                text = context.getString(R.string.universalpip)
                textSize = 24f
            })
            addView(MaterialSwitch(context).apply {
                isChecked = prefs.getBoolean("show_notification", false)
                text = context.getString(R.string.show_soundmaster_notification)
                updateControlNotiState(context, isChecked)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean("show_notification", checked).apply()
                    updateControlNotiState(context, checked)
                }
            })
        })
        setContentView(content)
        setCancelable(true)
    }

    private fun updateControlNotiState(context: Context, show: Boolean) {
        if (show) {
            val intent = Intent(context, PipReceiver::class.java)
            val channelId = "notifications"
            val notificationBuilder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.outline_info_24)
                .setContentTitle("Tap to control " + context.getString(R.string.universalpip))
                .setOngoing(true)
                .setContentIntent(
                    PendingIntent.getBroadcast(
                        context,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_LOW)

            with(NotificationManagerCompat.from(context)) {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    notify(notificationID, notificationBuilder.build())
                }
            }
        } else {
            with(NotificationManagerCompat.from(context)) {
                cancel(notificationID)
            }
        }
    }

    companion object {
        const val notificationID = 4
    }
}
