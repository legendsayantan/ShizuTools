package com.legendsayantan.adbtools.dialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.lib.Logger.Companion.log
import com.legendsayantan.adbtools.lib.ShizukuRunner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalShellBottomSheet(context: Context) : BottomSheetDialog(context, R.style.SheetDialogTheme) {
    private val prefs = context.getSharedPreferences("local_shell_prefs", Context.MODE_PRIVATE)
    private var commandHistory = mutableListOf<String>()
    private var historyIndex = -1
    private var historyBuffer = StringBuilder()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_local_shell, null)
        setContentView(view)
        
        val editText = view.findViewById<EditText>(R.id.edit_command)
        val btnRun = view.findViewById<MaterialButton>(R.id.btn_run)
        val commandOut = view.findViewById<TextView>(R.id.command_out)
        val btnCopy = view.findViewById<ImageButton>(R.id.btn_copy)
        val btnClear = view.findViewById<ImageButton>(R.id.btn_clear)
        val chipGroup = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chip_group)
        
        // UX 9.1: Load histories
        val savedHistory = prefs.getString("command_history", "")
        if (!savedHistory.isNullOrEmpty()) {
            commandHistory.addAll(savedHistory.split("|||"))
            historyIndex = commandHistory.size
        }
        
        val savedOutput = prefs.getString("terminal_output", "")
        if (!savedOutput.isNullOrEmpty()) {
            historyBuffer.append(savedOutput)
            updateOutputView(commandOut, "")
        }
        
        // UX 9.6: Command templates
        val templates = mapOf(
            "List Packages" to "pm list packages",
            "List 3rd Party Apps" to "pm list packages -3",
            "Clear App Data" to "pm clear ",
            "Force Stop" to "am force-stop ",
            "Uninstall App" to "pm uninstall ",
            "Disable App" to "pm disable-user --user 0 ",
            "Enable App" to "pm enable ",
            "Install APK" to "pm install ",
            "Grant Permission" to "pm grant ",
            "Revoke Permission" to "pm revoke ",
            "Get Prop" to "getprop ",
            "Dumpsys Battery" to "dumpsys battery",
            "Screen Size" to "wm size",
            "Screen Density" to "wm density"
        )
        templates.forEach { (label, cmd) ->
            val chip = Chip(context).apply {
                text = label
                setOnClickListener {
                    editText.setText(cmd)
                    editText.setSelection(editText.text.length)
                }
            }
            chipGroup.addView(chip)
        }
        
        // UX 9.5: Up-arrow navigation
        editText.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (historyIndex > 0) {
                        historyIndex--
                        editText.setText(commandHistory[historyIndex])
                        editText.setSelection(editText.text.length)
                    }
                    return@setOnKeyListener true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (historyIndex < commandHistory.size - 1) {
                        historyIndex++
                        editText.setText(commandHistory[historyIndex])
                        editText.setSelection(editText.text.length)
                    } else {
                        historyIndex = commandHistory.size
                        editText.setText("")
                    }
                    return@setOnKeyListener true
                }
            }
            false
        }
        
        // UX 9.4: Copy button
        btnCopy.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Terminal Output", commandOut.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        
        btnClear.setOnClickListener {
            historyBuffer.setLength(0)
            prefs.edit().putString("terminal_output", "").apply()
            updateOutputView(commandOut, "", true)
        }
        
        btnRun.setOnClickListener {
            val cmd = editText.text.toString().trim()
            if (cmd.isEmpty()) return@setOnClickListener
            
            // Save command to history
            if (commandHistory.isEmpty() || commandHistory.last() != cmd) {
                commandHistory.add(cmd)
                prefs.edit().putString("command_history", commandHistory.joinToString("|||")).apply()
            }
            historyIndex = commandHistory.size
            
            // UX 9.2: Timestamp + Status
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val currentHeader = "\n[$time] $ $cmd\n"
            
            updateOutputView(commandOut, currentHeader, true)
            btnRun.isEnabled = false
            
            com.legendsayantan.adbtools.lib.ShizuToolsController.execute { service ->
                service.runCommand(cmd, object : com.legendsayantan.adbtools.services.ICommandCallback.Stub() {
                    override fun onCommandResult(output: String, done: Boolean) {
                        commandOut.post {
                            if (done) {
                                historyBuffer.append(currentHeader)
                                historyBuffer.append(output)
                                if (output.isNotEmpty() && !output.endsWith("\n")) historyBuffer.append("\n")
                                historyBuffer.append("[$time] ✓\n")
                                saveAndRefreshOutput(commandOut)
                                btnRun.isEnabled = true
                                editText.requestFocus() // UX 9.7
                            } else {
                                updateOutputView(commandOut, currentHeader + output, true)
                            }
                        }
                    }
                    
                    override fun onCommandError(error: String) {
                        commandOut.post {
                            historyBuffer.append(currentHeader)
                            historyBuffer.append("Error: $error\n")
                            historyBuffer.append("[$time] ✗\n")
                            saveAndRefreshOutput(commandOut)
                            btnRun.isEnabled = true
                            editText.requestFocus() // UX 9.7
                        }
                        context.applicationContext.log(error)
                    }
                }, 50)
            }
            editText.selectAll()
            editText.requestFocus()
        }
    }
    
    private fun saveAndRefreshOutput(textView: TextView) {
        if (historyBuffer.length > 10000) {
            historyBuffer = StringBuilder(historyBuffer.substring(historyBuffer.length - 10000))
        }
        prefs.edit().putString("terminal_output", historyBuffer.toString()).apply()
        updateOutputView(textView, "")
    }
    
    // UX 9.3: Syntax Highlighting
    private fun updateOutputView(textView: TextView, currentExecution: String, isLive: Boolean = false) {
        val text = historyBuffer.toString() + currentExecution
        
        if (isLive) {
            textView.text = text
            val parentScrollView = textView.parent as? androidx.core.widget.NestedScrollView
            parentScrollView?.post {
                parentScrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
            return
        }

        Thread {
            val spannable = SpannableString(text)
            
            // Red for Error / Exception / ✗
            val errPattern = "(?i)(error|exception|✗).*".toRegex()
            errPattern.findAll(text).forEach { match ->
                spannable.setSpan(
                    ForegroundColorSpan(Color.parseColor("#EF5350")),
                    match.range.first,
                    match.range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            
            // Green for Success / ✓
            val succPattern = "(?i)(success|✓)".toRegex()
            succPattern.findAll(text).forEach { match ->
                spannable.setSpan(
                    ForegroundColorSpan(Color.parseColor("#66BB6A")),
                    match.range.first,
                    match.range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            
            // Underline packages
            val pkgPattern = "([a-zA-Z_][a-zA-Z0-9_]*\\.)+[a-zA-Z_][a-zA-Z0-9_]*".toRegex()
            pkgPattern.findAll(text).forEach { match ->
                spannable.setSpan(
                    UnderlineSpan(),
                    match.range.first,
                    match.range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            
            textView.post {
                textView.text = spannable
                val parentScrollView = textView.parent as? androidx.core.widget.NestedScrollView
                parentScrollView?.post {
                    parentScrollView.fullScroll(android.view.View.FOCUS_DOWN)
                }
            }
        }.start()
    }
}
