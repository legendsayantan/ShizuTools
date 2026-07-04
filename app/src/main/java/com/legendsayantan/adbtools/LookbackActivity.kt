package com.legendsayantan.adbtools

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.legendsayantan.adbtools.lib.Logger.Companion.log
import com.legendsayantan.adbtools.lib.ShizukuRunner
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import com.legendsayantan.adbtools.lib.Utils.Companion.setupEdgeToEdgeInsets
import com.legendsayantan.adbtools.lib.Utils.Companion.showSnackbar
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LookbackActivity : AppCompatActivity() {

    private lateinit var cardSelect: MaterialCardView
    private lateinit var cardProgress: MaterialCardView
    private lateinit var cardPreview: MaterialCardView
    private lateinit var dropZone: LinearLayout
    private lateinit var btnStart: MaterialButton
    private lateinit var btnInstall: MaterialButton
    private lateinit var btnCancel: MaterialButton
    
    private lateinit var progressTitle: TextView
    private lateinit var progressSubtitle: TextView
    private lateinit var progressBar: LinearProgressIndicator
    
    private lateinit var previewIcon: ImageView
    private lateinit var previewAppName: TextView
    private lateinit var previewPackage: TextView
    private lateinit var previewVersionInfo: TextView
    private lateinit var previewWarning: TextView
    
    private lateinit var recyclerHistory: RecyclerView
    private lateinit var historyEmpty: TextView
    
    private var cacheFile: File? = null
    private var parsedPackageName: String = ""
    private var parsedVersionCode: Long = 0
    private var parsedVersionName: String = ""
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_lookback)
        initialiseStatusBar()
        
        val root = findViewById<View>(R.id.root_layout)
        setupEdgeToEdgeInsets(R.id.root_layout, R.id.header_content)
        ViewCompat.animate(root).alpha(1f).scaleX(1f).scaleY(1f).setDuration(280).start()

        initViews()
        setupListeners()
        loadHistory()
    }

    private fun initViews() {
        cardSelect = findViewById(R.id.card_select)
        cardProgress = findViewById(R.id.card_progress)
        cardPreview = findViewById(R.id.card_preview)
        dropZone = findViewById(R.id.drop_zone)
        
        btnStart = findViewById(R.id.startBtn)
        btnInstall = findViewById(R.id.btn_install)
        btnCancel = findViewById(R.id.btn_cancel)
        
        progressTitle = findViewById(R.id.progress_title)
        progressSubtitle = findViewById(R.id.progress_subtitle)
        progressBar = findViewById(R.id.progress_bar)
        
        previewIcon = findViewById(R.id.preview_icon)
        previewAppName = findViewById(R.id.preview_app_name)
        previewPackage = findViewById(R.id.preview_package)
        previewVersionInfo = findViewById(R.id.preview_version_info)
        previewWarning = findViewById(R.id.preview_warning)
        
        recyclerHistory = findViewById(R.id.recycler_history)
        historyEmpty = findViewById(R.id.history_empty)
    }

    private fun setupListeners() {
        btnStart.setOnClickListener { selectFile() }
        
        btnCancel.setOnClickListener {
            resetUI()
        }
        
        btnInstall.setOnClickListener {
            installApk()
        }
        
        dropZone.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    event.clipDescription.hasMimeType("application/vnd.android.package-archive") ||
                    event.clipDescription.hasMimeType("application/octet-stream")
                }
                DragEvent.ACTION_DRAG_ENTERED -> {
                    dropZone.alpha = 0.5f
                    true
                }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                    dropZone.alpha = 1f
                    true
                }
                DragEvent.ACTION_DROP -> {
                    dropZone.alpha = 1f
                    val item = event.clipData.getItemAt(0)
                    item.uri?.let { processUri(it) }
                    true
                }
                else -> false
            }
        }
    }

    private fun resetUI() {
        cacheFile?.delete()
        cacheFile = null
        cardSelect.visibility = View.VISIBLE
        cardProgress.visibility = View.GONE
        cardPreview.visibility = View.GONE
    }

    private fun selectFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*" 
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
        }
        startActivityForResult(intent, PICK_FILE_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri -> processUri(uri) }
        }
    }

    private fun processUri(uri: Uri) {
        cardSelect.visibility = View.GONE
        cardProgress.visibility = View.VISIBLE
        progressTitle.text = "Copying APK to cache..."
        progressBar.isIndeterminate = true
        progressSubtitle.text = "Preparing..."

        Thread {
            val file = File(Environment.getExternalStorageDirectory(), "/Android/data/${packageName}/installcache.apk")
            file.parentFile?.mkdirs()
            
            // Get file size for progress
            var fileSize = 0L
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                    }
                }
            } catch (e: Exception) {}

            val inputStream = contentResolver.openInputStream(uri)
            val success = copyFileWithProgress(inputStream, file, fileSize)
            
            Handler(mainLooper).post {
                if (success) {
                    cacheFile = file
                    parseAndShowMetadata(file)
                } else {
                    showSnackbar("Failed to copy APK", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    resetUI()
                }
            }
        }.start()
    }

    private fun copyFileWithProgress(inputStream: InputStream?, outputFile: File, totalSize: Long): Boolean {
        if (inputStream == null) return false
        try {
            if (!outputFile.exists()) outputFile.createNewFile()
            val outputStream = FileOutputStream(outputFile)
            val buffer = ByteArray(8192)
            var copied = 0L
            var read: Int
            
            Handler(mainLooper).post {
                if (totalSize > 0) progressBar.isIndeterminate = false
            }

            inputStream.use { input ->
                outputStream.use { output ->
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        copied += read
                        if (totalSize > 0) {
                            val percent = (copied * 100 / totalSize).toInt()
                            val mbCopied = copied / (1024 * 1024)
                            val mbTotal = totalSize / (1024 * 1024)
                            Handler(mainLooper).post {
                                progressBar.progress = percent
                                progressSubtitle.text = "Copied ${mbCopied}MB / ${mbTotal}MB ($percent%)"
                            }
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            applicationContext.log(e.stackTraceToString(), true)
            return false
        }
    }

    private fun parseAndShowMetadata(file: File) {
        progressTitle.text = "Parsing APK metadata..."
        progressBar.isIndeterminate = true
        progressSubtitle.text = "Please wait"
        
        Thread {
            try {
                val pm = packageManager
                val packageInfo = pm.getPackageArchiveInfo(file.absolutePath, 0)
                
                if (packageInfo == null) {
                    Handler(mainLooper).post {
                        showSnackbar("Invalid APK file", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                        resetUI()
                    }
                    return@Thread
                }
                
                packageInfo.applicationInfo.sourceDir = file.absolutePath
                packageInfo.applicationInfo.publicSourceDir = file.absolutePath
                
                val appIcon = packageInfo.applicationInfo.loadIcon(pm)
                val appName = packageInfo.applicationInfo.loadLabel(pm).toString()
                
                parsedPackageName = packageInfo.packageName
                parsedVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
                parsedVersionName = packageInfo.versionName ?: "Unknown"
                
                // Check currently installed version
                var installedVersionCode = -1L
                var installedVersionName = "None"
                try {
                    val installedInfo = pm.getPackageInfo(parsedPackageName, 0)
                    installedVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installedInfo.longVersionCode else installedInfo.versionCode.toLong()
                    installedVersionName = installedInfo.versionName ?: "Unknown"
                } catch (e: PackageManager.NameNotFoundException) {
                    // Not installed
                }
                
                val isDowngrade = installedVersionCode != -1L && parsedVersionCode < installedVersionCode
                val isUpgrade = installedVersionCode != -1L && parsedVersionCode > installedVersionCode
                val isSame = installedVersionCode != -1L && parsedVersionCode == installedVersionCode
                
                Handler(mainLooper).post {
                    cardProgress.visibility = View.GONE
                    cardPreview.visibility = View.VISIBLE
                    
                    previewIcon.setImageDrawable(appIcon)
                    previewAppName.text = appName
                    previewPackage.text = parsedPackageName
                    
                    if (installedVersionCode == -1L) {
                        previewVersionInfo.text = "Installing new app v$parsedVersionName"
                        previewWarning.visibility = View.GONE
                    } else {
                        previewVersionInfo.text = "Current: v$installedVersionName  ➔  New: v$parsedVersionName"
                        previewWarning.visibility = View.VISIBLE
                        when {
                            isDowngrade -> {
                                previewWarning.text = "Valid Downgrade"
                                previewWarning.setTextColor(getColor(R.color.tool_lookback))
                            }
                            isSame -> {
                                previewWarning.text = "Warning: This version is already installed."
                                previewWarning.setTextColor(getColor(R.color.red))
                            }
                            isUpgrade -> {
                                previewWarning.text = "Notice: This is an upgrade, not a downgrade."
                                previewWarning.setTextColor(getColor(R.color.colorSecondary))
                            }
                        }
                    }
                }
                
            } catch (e: Exception) {
                applicationContext.log(e.stackTraceToString(), true)
                Handler(mainLooper).post {
                    showSnackbar("Failed to parse APK", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    resetUI()
                }
            }
        }.start()
    }

    private fun installApk() {
        val file = cacheFile ?: return
        cardPreview.visibility = View.GONE
        cardProgress.visibility = View.VISIBLE
        progressTitle.text = "Installing $parsedPackageName..."
        progressBar.isIndeterminate = true
        progressSubtitle.text = "Executing ADB command..."
        
        Thread {
            val command = "cat ${file.absolutePath} | pm install -S ${file.length()} -r -d"
            ShizukuRunner.execute(command, onResult = { output, done ->
                if (done) {
                    Handler(mainLooper).post {
                        if (output.contains("Success", true)) {
                            showSnackbar("Installed Successfully.", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                            saveHistory(parsedPackageName, parsedVersionName)
                            resetUI()
                            loadHistory()
                        } else {
                            showSnackbar("Error installing.\n$output", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                            resetUI()
                        }
                    }
                }
            }, onError = { error ->
                Handler(mainLooper).post {
                    showSnackbar("Failed to start install process", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    resetUI()
                }
                applicationContext.log(error)
            })
        }.start()
    }

    private fun saveHistory(pkg: String, version: String) {
        try {
            val file = File(filesDir, "lookback_history.txt")
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            val entry = "$pkg|$version|$date\n"
            file.appendText(entry)
        } catch (e: Exception) {
            applicationContext.log(e.stackTraceToString(), true)
        }
    }

    private fun loadHistory() {
        Thread {
            try {
                val file = File(filesDir, "lookback_history.txt")
                if (!file.exists()) {
                    Handler(mainLooper).post {
                        recyclerHistory.visibility = View.GONE
                        historyEmpty.visibility = View.VISIBLE
                    }
                    return@Thread
                }
                
                val lines = file.readLines().reversed() // Newest first
                if (lines.isEmpty()) {
                    Handler(mainLooper).post {
                        recyclerHistory.visibility = View.GONE
                        historyEmpty.visibility = View.VISIBLE
                    }
                    return@Thread
                }
                
                val adapter = HistoryAdapter(lines)
                Handler(mainLooper).post {
                    recyclerHistory.visibility = View.VISIBLE
                    historyEmpty.visibility = View.GONE
                    recyclerHistory.adapter = adapter
                }
            } catch (e: Exception) {
                applicationContext.log(e.stackTraceToString(), true)
            }
        }.start()
    }

    inner class HistoryAdapter(private val items: List<String>) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.history_text)
            val date: TextView = view.findViewById(R.id.history_date)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lookback_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val parts = items[position].split("|")
            if (parts.size >= 3) {
                val pkg = parts[0]
                val ver = parts[1]
                val d = parts[2]
                holder.text.text = "Installed $pkg v$ver"
                holder.date.text = d
            }
        }

        override fun getItemCount() = items.size
    }

    companion object {
        private const val PICK_FILE_REQUEST_CODE: Int = 1
    }
}