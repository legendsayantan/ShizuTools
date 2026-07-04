package com.legendsayantan.adbtools.dialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.ChipGroup
import com.google.android.material.tabs.TabLayout
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.lib.Logger.Companion.clearLogs
import com.legendsayantan.adbtools.lib.Logger.Companion.readLog
import com.legendsayantan.adbtools.lib.Utils.Companion.hapticConfirm
import java.io.File
import java.util.Locale

class LogBottomSheetDialog(context: Context) : BottomSheetDialog(context, R.style.SheetDialogTheme) {

    private lateinit var recyclerView: RecyclerView
    private lateinit var logAdapter: LogAdapter
    private var currentLogs: List<String> = emptyList()

    private var currentFilter = "All"
    private var currentSearch = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_log)

        recyclerView = findViewById<RecyclerView>(R.id.log_text)!!
        logAdapter = LogAdapter()
        recyclerView.adapter = logAdapter
        
        val checkAutoScroll = findViewById<CheckBox>(R.id.check_autoscroll)
        logAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (checkAutoScroll?.isChecked == true) {
                    recyclerView.scrollToPosition(logAdapter.itemCount - 1)
                }
            }
        })

        val tablayout = findViewById<TabLayout>(R.id.tab_layout)
        tablayout?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                loadLogs(tab?.position == 0)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        val chipGroup = findViewById<ChipGroup>(R.id.chip_group_severity)
        chipGroup?.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val selectedChipId = checkedIds.first()
            currentFilter = when (selectedChipId) {
                R.id.chip_error -> "Error"
                R.id.chip_warning -> "Warning"
                R.id.chip_info -> "Info"
                else -> "All"
            }
            applyFilters()
        }

        val searchEdit = findViewById<EditText>(R.id.edit_search)
        searchEdit?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearch = s?.toString()?.lowercase(Locale.getDefault()) ?: ""
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        findViewById<View>(R.id.copy_logs)?.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Logs", currentLogs.joinToString("\n\n"))
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Logs copied to clipboard.", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.share_logs)?.setOnClickListener {
            val text = currentLogs.joinToString("\n\n")
            if (text.isEmpty()) {
                Toast.makeText(context, "No logs to share", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val file = File(context.cacheDir, "shizutools_log_${System.currentTimeMillis()}.txt")
            file.writeText(text)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Log"))
        }

        findViewById<View>(R.id.clear_logs)?.setOnClickListener {
            findViewById<View>(R.id.clear_logs)?.hapticConfirm()
            context.clearLogs(tablayout?.selectedTabPosition == 0)
            loadLogs(tablayout?.selectedTabPosition == 0)
        }

        loadLogs(true)
    }

    private fun loadLogs(app: Boolean) {
        context.readLog(app) { content ->
            val logs = content.split("\n\n").filter { it.isNotBlank() }
            Handler(Looper.getMainLooper()).post {
                currentLogs = logs
                applyFilters()
            }
        }
    }

    private fun applyFilters() {
        var filtered = currentLogs

        if (currentFilter != "All") {
            filtered = filtered.filter { log ->
                val lowerLog = log.lowercase(Locale.getDefault())
                when (currentFilter) {
                    "Error" -> lowerLog.contains("error") || lowerLog.contains("exception")
                    "Warning" -> lowerLog.contains("warning") || lowerLog.contains("shizukurunner")
                    "Info" -> !lowerLog.contains("error") && !lowerLog.contains("exception")
                    else -> true
                }
            }
        }

        if (currentSearch.isNotBlank()) {
            filtered = filtered.filter { it.lowercase(Locale.getDefault()).contains(currentSearch) }
        }

        logAdapter.submitList(filtered)
    }

    inner class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {
        private var items: List<String> = emptyList()

        fun submitList(newItems: List<String>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_simple_list, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val logText = items[position]
            val spannable = SpannableString(logText)
            val lower = logText.lowercase(Locale.getDefault())

            if (lower.contains("exception") || lower.contains("error")) {
                spannable.setSpan(ForegroundColorSpan(Color.parseColor("#E57373")), 0, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (lower.contains("warning") || lower.contains("shizukurunner")) {
                spannable.setSpan(ForegroundColorSpan(Color.parseColor("#FFB74D")), 0, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (lower.contains("success")) {
                spannable.setSpan(ForegroundColorSpan(Color.parseColor("#81C784")), 0, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            
            holder.textView.text = spannable
            holder.textView.typeface = android.graphics.Typeface.MONOSPACE
            holder.textView.textSize = 12f
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(android.R.id.text1)
        }
    }
}