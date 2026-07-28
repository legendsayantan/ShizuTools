package com.legendsayantan.adbtools.dialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.lib.AppCommands
import java.util.UUID

class IntentShellBottomSheet(context: Context) : BottomSheetDialog(context, R.style.SheetDialogTheme) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_intent_shell, null)
        setContentView(view)

        val prefs = context.getSharedPreferences("execution", AppCompatActivity.MODE_PRIVATE)
        if (prefs.getString("key", null) == null) {
            prefs.edit().putString("key", UUID.randomUUID().toString().replace("-", "")).apply()
        }
        val key = prefs.getString("key", null)?.replace("-", "")

        val toggle = view.findViewById<SwitchMaterial>(R.id.switch_intent_shell)
        toggle.isChecked = prefs.getBoolean("enabled", false)
        toggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enabled", isChecked).apply()
        }

        val editKey = view.findViewById<TextInputEditText>(R.id.edit_key)
        editKey.setText(key)
        editKey.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("key", prefs.getString("key", null)?.replace("-", ""))
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, context.getString(R.string.key_copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }

        val btnRegenerate = view.findViewById<MaterialButton>(R.id.btn_regenerate_key)
        btnRegenerate.setOnClickListener {
            val dialog = AlertDialog.Builder(context)
                .setTitle("Regenerate Key?")
                .setMessage("This will break all existing automations. Regenerate?")
                .setPositiveButton("Regenerate") { _, _ ->
                    val newKey = UUID.randomUUID().toString().replace("-", "")
                    prefs.edit().putString("key", newKey).apply()
                    editKey.setText(newKey)
                    Toast.makeText(context, "Key Regenerated", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .create()
            dialog.show()
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(context.getColor(R.color.colorSecondary))
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.setTextColor(context.getColor(R.color.colorSecondary))
        }

        val toggleCommandsRef = view.findViewById<TextView>(R.id.toggle_commands_ref)
        val commandsRefText = view.findViewById<TextView>(R.id.commands_ref_text)
        commandsRefText.text = AppCommands.fullHelp()
        toggleCommandsRef.setOnClickListener {
            val expanded = commandsRefText.visibility == android.view.View.VISIBLE
            commandsRefText.visibility = if (expanded) android.view.View.GONE else android.view.View.VISIBLE
            toggleCommandsRef.text = if (expanded) "▸ App Commands Reference" else "▾ App Commands Reference"
        }

        val textIntentLog = view.findViewById<TextView>(R.id.text_intent_log)
        val history = prefs.getString("intent_history", "") ?: ""
        val historyList = history.split("|||").filter { it.isNotEmpty() }
        if (historyList.isEmpty()) {
            textIntentLog.text = "No intents received yet."
        } else {
            textIntentLog.text = historyList.joinToString("\n")
        }
    }
}
