package com.legendsayantan.adbtools.dialog

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.lib.AppOps
import com.legendsayantan.adbtools.lib.SoundMasterPreferences
import com.legendsayantan.adbtools.lib.Utils.Companion.showSnackbar
import com.legendsayantan.adbtools.services.SoundMasterService
import com.legendsayantan.adbtools.services.SoundMasterService.Companion.prepareGetAudioDevices
import com.legendsayantan.adbtools.lib.Logger.Companion.log

class SoundMasterBottomSheet : BottomSheetDialogFragment() {

    @SuppressLint("ApplySharedPref")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        showing = true
        return inflater.inflate(R.layout.bottom_sheet_sound_master, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireContext().prepareGetAudioDevices()
        setupSettings(view)
    }

    override fun onResume() {
        super.onResume()
        view?.let { updateBtnState(it) }
    }

    private fun updateBtnState(view: View) {
        val btnImage = view.findViewById<ImageView>(R.id.playPauseButton)
        val btnText = view.findViewById<TextView>(R.id.toggleText)
        val btnCard = view.findViewById<MaterialCardView>(R.id.newSlider)
        val modeToggleGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.modeToggleGroup)
        val textModeDescription = view.findViewById<TextView>(R.id.textModeDescription)
        val btnRepairAudio = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRepairAudio)

        // Per-app audio routing needs playback capture APIs that only exist from Android 10 (Q)
        // onward. Everything else in this sheet (auto-hide timeout, notification toggle, etc.)
        // is just local preference and stays fully usable - only the engine start/mode controls
        // are disabled here.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            btnImage.setImageResource(R.drawable.outline_info_24)
            btnText.text = "Unavailable"
            btnCard.isEnabled = false
            btnCard.alpha = 0.5f
            btnCard.setOnClickListener {
                requireActivity().showSnackbar(
                    getString(
                        R.string.tool_unsupported_version,
                        getString(R.string.soundmaster),
                        com.legendsayantan.adbtools.lib.Utils.androidVersionName(android.os.Build.VERSION_CODES.Q),
                        com.legendsayantan.adbtools.lib.Utils.androidVersionName(android.os.Build.VERSION.SDK_INT)
                    ),
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                )
            }
            modeToggleGroup.isEnabled = false
            for (i in 0 until modeToggleGroup.childCount) modeToggleGroup.getChildAt(i).isEnabled = false
            textModeDescription.text = getString(
                R.string.tool_unsupported_version,
                getString(R.string.soundmaster),
                com.legendsayantan.adbtools.lib.Utils.androidVersionName(android.os.Build.VERSION_CODES.Q),
                com.legendsayantan.adbtools.lib.Utils.androidVersionName(android.os.Build.VERSION.SDK_INT)
            )
            btnRepairAudio.visibility = View.GONE
            return
        }

        val isRunning = SoundMasterService.running
        btnImage.setImageResource(if (isRunning) R.drawable.baseline_stop_24 else R.drawable.baseline_play_arrow_24)
        btnText.text = if (isRunning) "Stop Engine" else "Start Engine"
        btnCard.isEnabled = true
        btnCard.alpha = 1f

        modeToggleGroup.isEnabled = !isRunning
        for (i in 0 until modeToggleGroup.childCount) {
            modeToggleGroup.getChildAt(i).isEnabled = !isRunning
        }

        val prefs = requireContext().getSharedPreferences("sm_recovery", Context.MODE_PRIVATE)
        val strandedApps = prefs.all.keys
        
        if (!isRunning && strandedApps.isNotEmpty()) {
            btnRepairAudio.visibility = View.VISIBLE
            btnRepairAudio.setOnClickListener {
                btnRepairAudio.isEnabled = false
                btnRepairAudio.text = "Repairing..."
                com.legendsayantan.adbtools.lib.ShizuToolsController.execute { controller ->
                    strandedApps.forEach { pkg ->
                        try {
                            val uid = requireContext().packageManager.getPackageInfo(pkg, 0).applicationInfo?.uid ?: -1
                            val savedState = prefs.getString(pkg, "0,0")
                            val modes = savedState?.split(",")?.mapNotNull { it.toIntOrNull() } ?: listOf(0, 0)
                            val playAudioMode = modes.getOrElse(0) { 0 }
                            val focusMode = modes.getOrElse(1) { 0 }
                            
                            controller.setAppOpMode(pkg, uid, AppOps.PLAY_AUDIO, playAudioMode)
                            controller.setAppOpMode(pkg, uid, AppOps.TAKE_AUDIO_FOCUS, focusMode)
                        } catch (e: Exception) {}
                    }
                    prefs.edit().clear().apply()
                    Handler(Looper.getMainLooper()).post {
                        requireActivity().showSnackbar("Audio routing repaired.", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                        updateBtnState(view)
                    }
                }
            }
        } else {
            btnRepairAudio.visibility = View.GONE
        }

        btnCard.setOnClickListener {
            if (isRunning) {
                requireContext().stopService(Intent(requireContext(), SoundMasterService::class.java))
                Handler(Looper.getMainLooper()).postDelayed({ view.let { updateBtnState(it) } }, 500)
            } else {
                val isDspMode = SoundMasterPreferences.isAdvancedDspMode(requireContext())
                SoundMasterService.startEngine(requireContext().applicationContext, isDspMode) { success, message ->
                    Handler(Looper.getMainLooper()).post {
                        if (!isAdded) return@post
                        requireActivity().showSnackbar(message, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                        if (success && isDspMode) {
                            dismiss()
                        } else if (success) {
                            view.let { updateBtnState(it) }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        showing = false
        super.onDestroyView()
    }

    private fun setupSettings(view: View) {
        val prefs = requireContext().getSharedPreferences("soundmaster", Context.MODE_PRIVATE)
        val switchNoti = view.findViewById<MaterialSwitch>(R.id.switch_notification)
        val switchVol = view.findViewById<MaterialSwitch>(R.id.switch_volume_change)
        val switchAutoWakeup = view.findViewById<MaterialSwitch>(R.id.switch_auto_wakeup)
        val dropdownHide = view.findViewById<android.widget.AutoCompleteTextView>(R.id.dropdown_auto_hide)

        val modeToggleGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.modeToggleGroup)
        val textModeDescription = view.findViewById<TextView>(R.id.textModeDescription)

        val isDsp = SoundMasterPreferences.isAdvancedDspMode(requireContext())
        if (isDsp) {
            modeToggleGroup.check(R.id.btnModeDsp)
            textModeDescription.text = "Advanced audio engine with equalizer and custom effects. Uses slightly more battery."
        } else {
            modeToggleGroup.check(R.id.btnModeSmart)
            textModeDescription.text = "Simple volume control with zero latency and no extra battery drain."
        }

        modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newIsDsp = checkedId == R.id.btnModeDsp
                SoundMasterPreferences.setAdvancedDspMode(requireContext(), newIsDsp)
                if (newIsDsp) {
                    textModeDescription.text = "Advanced audio engine with equalizer and custom effects. Uses slightly more battery."
                } else {
                    textModeDescription.text = "Simple volume control with zero latency and no extra battery drain."
                }
            }
        }

        switchNoti.isChecked = prefs.getBoolean("show_notification", false)
        switchVol.isChecked = prefs.getBoolean("show_on_volume_change", true)
        switchAutoWakeup.isChecked = prefs.getBoolean("auto_wakeup", true)

        val hideOptions = arrayOf("Disabled", "2 Seconds", "5 Seconds", "10 Seconds", "30 Seconds")
        val hideValues = arrayOf(0L, 2000L, 5000L, 10000L, 30000L)
        val adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, hideOptions)
        dropdownHide.setAdapter(adapter)

        val currentTimeout = prefs.getLong("auto_hide_timeout", 5000L)
        val currentIndex = hideValues.indexOf(currentTimeout).takeIf { it >= 0 } ?: 2
        dropdownHide.setText(hideOptions[currentIndex], false)

        dropdownHide.setOnItemClickListener { _, _, position, _ ->
            prefs.edit().putLong("auto_hide_timeout", hideValues[position]).apply()
        }
        
        updateControlNotiState(requireContext(), switchNoti.isChecked)

        switchNoti.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_notification", isChecked).apply()
            updateControlNotiState(requireContext(), isChecked)
        }
        switchVol.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_on_volume_change", isChecked).apply()
        }
        switchAutoWakeup.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_wakeup", isChecked).apply()
        }
    }

    private fun updateControlNotiState(context: Context, show: Boolean) {
        val notificationID = 3
        if (show) {
            val intent = Intent(context, SoundMasterService::class.java).apply {
                action = "bubble"
            }
            val pendingIntent = PendingIntent.getService(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val channelId = "notifications"
            val notificationBuilder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.outline_info_24)
                .setContentTitle("Tap to control " + context.getString(R.string.soundmaster))
                .setOngoing(true)
                .setSound(null)
                .setSilent(true)
                .setContentIntent(pendingIntent)
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
        var showing = false
        var isMediaProjectionActive = false
    }
}