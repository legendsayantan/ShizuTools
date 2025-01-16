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
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.setPadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.SoundMasterActivity
import com.legendsayantan.adbtools.services.SoundMasterService

/**
 * @author legendsayantan
 */
class SoundMasterDialog(context:Context) : Dialog(context) {
    init {
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setGravity(Gravity.CENTER)
        window?.setLayout(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val prefs = context.getSharedPreferences("soundmaster", Context.MODE_PRIVATE)
        val content = MaterialCardView(context).apply {
            radius = 50f
            elevation = 20f
        }
        content.addView(LinearLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(50,50,50,50)
            }
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                setPadding(0,0,0,25)
                text = context.getString(R.string.soundmaster)
                textSize = 24f
            })
            addView(MaterialSwitch(context).apply {
                isChecked = prefs.getBoolean("show_notification", false)
                text = context.getString(R.string.show_soundmaster_notification)
                updateControlNotiState(context,isChecked)
                setOnCheckedChangeListener { _, isChecked ->
                    prefs.edit().putBoolean("show_notification", isChecked).apply()
                    updateControlNotiState(context,isChecked)
                }
            })
            addView(MaterialSwitch(context).apply {
                isChecked = prefs.getBoolean("show_on_volume_change", false)
                text = context.getString(R.string.show_on_volume_change)
                setOnCheckedChangeListener { _, isChecked ->
                    prefs.edit().putBoolean("show_on_volume_change", isChecked).apply()
                }
            })
            addView(MaterialSwitch(context).apply {
                isChecked = prefs.getBoolean("auto_hide", true)
                text = context.getString(R.string.auto_hide_soundmaster)
                setOnCheckedChangeListener { _, isChecked ->
                    prefs.edit().putBoolean("auto_hide", isChecked).apply()
                }
            })
            addView(MaterialButton(context).apply {
                text = context.getString(R.string.open_controls)
                setOnClickListener {
                    val intent = Intent(context, SoundMasterActivity::class.java)
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            })

        })
        setContentView(content)
        setCancelable(true)
    }
    private fun updateControlNotiState(context: Context, show:Boolean){
        if(show){
            val intent = Intent(context, SoundMasterActivity::class.java)
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            SoundMasterService.uiIntent = intent
            val channelId = "notifications"
            val notificationBuilder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.outline_info_24)
                .setContentTitle("Tap to control "+context.getString(R.string.soundmaster))
                .setOngoing(true)
                .setSound(null)
                .setSilent(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_LOW)

            // Show the notification.
            with(NotificationManagerCompat.from(context)) {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    notify(notificationID, notificationBuilder.build())
                }
            }
        }else{
            with(NotificationManagerCompat.from(context)) {
                cancel(notificationID)
            }
        }
    }
    companion object{
        const val notificationID = 3
    }
}