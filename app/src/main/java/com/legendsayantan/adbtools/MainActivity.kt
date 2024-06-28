package com.legendsayantan.adbtools

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.legendsayantan.adbtools.lib.ShizukuRunner
import com.legendsayantan.adbtools.lib.Utils.Companion.getNotiPerms
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import com.legendsayantan.adbtools.receivers.PipReceiver
import com.legendsayantan.adbtools.services.SoundMasterService
import java.util.UUID
/**
 * @author legendsayantan
 */
class MainActivity : AppCompatActivity() {
    @SuppressLint("LaunchActivityFromNotification")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initialiseStatusBar()
        getNotiPerms()
        findViewById<ImageView>(R.id.github).setOnClickListener {
            val shizukuUrl = "https://github.com/legendsayantan/shizutools"
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(shizukuUrl)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        val cardDebloat = findViewById<MaterialCardView>(R.id.cardDebloat)
        val cardThemePatcher = findViewById<MaterialCardView>(R.id.cardThemePatcher)
        val cardLookBack = findViewById<MaterialCardView>(R.id.cardLookBack)
        val cardMixedAudio = findViewById<MaterialCardView>(R.id.cardMixedAudio)
        val cardSoundMaster = findViewById<MaterialCardView>(R.id.cardSoundMaster)
        val cardForcePip = findViewById<MaterialCardView>(R.id.cardForcePip)
        val cardShell = findViewById<MaterialCardView>(R.id.cardShell)
        val cardIntentShell = findViewById<MaterialCardView>(R.id.cardIntentShell)
        cardDebloat.setOnClickListener {
            startActivity(
                Intent(
                    applicationContext,
                    DebloatActivity::class.java
                )
            )
        }
        cardThemePatcher.setOnClickListener {
            startActivity(
                Intent(
                    applicationContext,
                    ThemePatcherActivity::class.java
                )
            )
        }
        cardLookBack.setOnClickListener {
            startActivity(
                Intent(
                    applicationContext,
                    LookbackActivity::class.java
                )
            )
        }
        cardMixedAudio.setOnClickListener {
            startActivity(
                Intent(
                    applicationContext,
                    MixedAudioActivity::class.java
                )
            )
        }
        cardSoundMaster.setOnClickListener {
            if(Build.VERSION.SDK_INT<Build.VERSION_CODES.Q) return@setOnClickListener
            //create notification
            val intent = Intent(this, SoundMasterActivity::class.java)
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            SoundMasterService.uiIntent = intent
            val channelId = "notifications"
            val notificationBuilder = NotificationCompat.Builder(applicationContext, channelId)
                .setSmallIcon(R.drawable.outline_info_24)
                .setContentTitle("Tap to configure "+applicationContext.getString(R.string.soundmaster))
                .setOngoing(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_LOW)

            // Show the notification.
            with(NotificationManagerCompat.from(applicationContext)) {
                if (ActivityCompat.checkSelfPermission(
                        applicationContext,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    notify(3, notificationBuilder.build())
                }
            }
        }
        cardForcePip.setOnClickListener {
            val intent = Intent(this, PipReceiver::class.java)
            SoundMasterService.uiIntent = intent
            val channelId = "notifications"
            val notificationBuilder = NotificationCompat.Builder(applicationContext, channelId)
                .setSmallIcon(R.drawable.outline_info_24)
                .setContentTitle("Tap to toggle "+getString(R.string.universalpip))
                .setOngoing(true)
                .setContentIntent(
                    PendingIntent.getBroadcast(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_LOW)

            // Show the notification.
            with(NotificationManagerCompat.from(applicationContext)) {
                if (ActivityCompat.checkSelfPermission(
                        applicationContext,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    notify(4, notificationBuilder.build())
                }
            }
        }
        cardShell.setOnClickListener { localShell() }
        cardIntentShell.setOnClickListener { intentShell() }
    }

    private fun localShell() {
        val dialog = MaterialAlertDialogBuilder(this)
        dialog.setTitle(getString(R.string.localshell))
        val scrollContainer = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(30, 25, 30, 15)
        val commandOut = TextView(this)
        val commandBar = LinearLayout(this)
        commandOut.typeface = resources.getFont(R.font.consolas)
        commandBar.orientation = LinearLayout.HORIZONTAL
        commandBar.gravity = Gravity.CENTER
        val editText = EditText(this)
        editText.hint = getString(R.string.enter_command)
        editText.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            weight = 1f
        }
        val btn = MaterialButton(this)
        btn.text = getString(R.string.run)

        btn.setOnClickListener {
            if (editText.text.isEmpty()) return@setOnClickListener
            ShizukuRunner.runAdbCommand(editText.text.toString(), object :
                ShizukuRunner.CommandResultListener {
                override fun onCommandResult(output: String, done: Boolean) {
                    runOnUiThread {
                        commandOut.text = output
                        btn.isEnabled = done
                    }
                }

                override fun onCommandError(error: String) {
                    runOnUiThread {
                        commandOut.text = "ERROR:\n$error"
                    }
                }
            })
            editText.selectAll()
        }
        layout.addView(commandOut)
        layout.addView(commandBar.apply {
            addView(editText)
            addView(btn)
        })
        dialog.setView(scrollContainer.apply {
            addView(layout)
        })
        dialog.create().show()
    }
    private fun intentShell(){
        val prefs = getSharedPreferences("execution", MODE_PRIVATE)
        if(prefs.getString("key",null)==null){
            prefs.edit().putString("key",UUID.randomUUID().toString().replace("-","")).apply()
        }
        val key = prefs.getString("key",null)?.replace("-","")
        val layout = LinearLayout(this).apply {
            setPadding(60,0,60,0)
        }
        layout.orientation = LinearLayout.VERTICAL
        val toggle = SwitchMaterial(this)
        toggle.isChecked = prefs.getBoolean("enabled",false)
        toggle.text = getString(R.string.accept_adb_intents)
        toggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enabled",isChecked).apply()
        }
        val editLayout = TextInputLayout(this)
        val editText = TextInputEditText(this)
        editText.setText(key)
        editText.focusable = View.NOT_FOCUSABLE
        editText.hint = getString(R.string.access_key)
        editLayout.addView(editText)
        editText.setOnClickListener {
            //copy to clipboard
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("key", key)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.key_copied_to_clipboard),Toast.LENGTH_SHORT).show()
        }
        layout.addView(toggle)
        layout.addView(editLayout)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.intent_shell))
            .setMessage(getString(R.string.intent_shell_security))
            .setView(layout)
            .setPositiveButton(getString(R.string.ok)) { _, _ -> }
            .create().show()
    }
}