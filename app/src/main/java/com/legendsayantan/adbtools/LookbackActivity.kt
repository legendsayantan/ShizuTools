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
import com.google.android.material.checkbox.MaterialCheckBox
import com.legendsayantan.adbtools.lib.Logger.Companion.log
import com.legendsayantan.adbtools.lib.ShizuToolsController
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
import java.util.zip.ZipInputStream
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.app.PendingIntent
import android.widget.ImageButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class LookbackActivity : AppCompatActivity() {

    private lateinit var cardSelect: MaterialCardView
    private lateinit var cardProgress: MaterialCardView
    private lateinit var dropZone: LinearLayout
    private lateinit var btnStart: MaterialButton
    
    private lateinit var progressTitle: TextView
    private lateinit var progressSubtitle: TextView
    private lateinit var progressBar: LinearProgressIndicator
    
    private lateinit var recyclerHistory: RecyclerView
    private lateinit var historyEmpty: TextView
    
    private lateinit var appsContainer: LinearLayout
    private lateinit var globalActions: LinearLayout
    private lateinit var btnInstallAll: MaterialButton
    private lateinit var btnCancel: MaterialButton
    
    enum class InstallStatus { PENDING, INSTALLING, SUCCESS, FAILED }

    data class AppGroup(
        val packageName: String,
        val files: List<File>,
        val basePackageInfo: PackageInfo,
        val baseAppInfo: ApplicationInfo,
        var status: InstallStatus = InstallStatus.PENDING,
        var isDowngrade: Boolean = false,
        var isUpgrade: Boolean = false,
        var isSame: Boolean = false,
        var installedVersionName: String? = null,
        var installedVersionCode: Long = -1L
    )
    
    private var cacheFiles = mutableListOf<File>()
    private val appGroups = mutableListOf<AppGroup>()
    private val installQueue: java.util.Queue<AppGroup> = java.util.LinkedList()
    
    // Global flags
    private var flagDowngrade = true
    private var flagReplace = true
    private var flagTest = false
    private var flagPermissions = false
    private var flagAllUsers = false
    private var flagDontKill = false
    
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

    override fun onDestroy() {
        super.onDestroy()
        // If the activity is torn down (rotation, backgrounding, low memory) while an install is
        // still in flight, installReceiver would otherwise stay registered against this dead
        // context until the process dies.
        if (installReceiverRegistered) {
            try { unregisterReceiver(installReceiver) } catch (e: Exception) {}
            installReceiverRegistered = false
        }
    }

    private fun initViews() {
        cardSelect = findViewById(R.id.card_select)
        cardProgress = findViewById(R.id.card_progress)
        dropZone = findViewById(R.id.drop_zone)
        
        btnStart = findViewById(R.id.startBtn)
        btnCancel = findViewById(R.id.btn_cancel)
        btnInstallAll = findViewById(R.id.btn_install_all)
        
        progressTitle = findViewById(R.id.progress_title)
        progressSubtitle = findViewById(R.id.progress_subtitle)
        progressBar = findViewById(R.id.progress_bar)
        
        appsContainer = findViewById(R.id.apps_container)
        globalActions = findViewById(R.id.global_actions)
        
        recyclerHistory = findViewById(R.id.recycler_history)
        historyEmpty = findViewById(R.id.history_empty)
    }

    private fun setupListeners() {
        btnStart.setOnClickListener { selectFile() }
        
        btnCancel.setOnClickListener {
            resetUI()
        }
        
        btnInstallAll.setOnClickListener {
            installQueue.clear()
            installQueue.addAll(appGroups.filter { it.status == InstallStatus.PENDING || it.status == InstallStatus.FAILED })
            processInstallQueue()
        }
        
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            showSettingsDialog()
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
                    val dropPermissions = requestDragAndDropPermissions(event)
                    val uris = mutableListOf<Uri>()
                    for (i in 0 until event.clipData.itemCount) {
                        event.clipData.getItemAt(i).uri?.let { uris.add(it) }
                    }
                    if (uris.isNotEmpty()) processUris(uris, dropPermissions)
                    else dropPermissions?.release()
                    true
                }
                else -> false
            }
        }
    }

    private fun resetUI() {
        cacheFiles.forEach { it.delete() }
        cacheFiles.clear()
        appGroups.clear()
        installQueue.clear()
        appsContainer.removeAllViews()
        appsContainer.visibility = View.GONE
        globalActions.visibility = View.GONE
        cardSelect.visibility = View.VISIBLE
        cardProgress.visibility = View.GONE
    }

    private fun selectFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*" 
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/octet-stream", "application/zip"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, PICK_FILE_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val uris = mutableListOf<Uri>()
            if (data?.clipData != null) {
                for (i in 0 until data.clipData!!.itemCount) {
                    data.clipData!!.getItemAt(i).uri?.let { uris.add(it) }
                }
            } else {
                data?.data?.let { uris.add(it) }
            }
            if (uris.isNotEmpty()) processUris(uris)
        }
    }

    private fun processUris(uris: List<Uri>, dropPermissions: android.view.DragAndDropPermissions? = null) {
        cardSelect.visibility = View.GONE
        cardProgress.visibility = View.VISIBLE
        progressTitle.text = "Processing files..."
        progressBar.isIndeterminate = true
        progressSubtitle.text = "Preparing cache..."

        Thread {
            val cacheDir = File(Environment.getExternalStorageDirectory(), "/Android/data/${packageName}/installcache")
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
            
            var successCount = 0
            
            for (uri in uris) {
                // Get filename
                var filename = "temp_${System.currentTimeMillis()}.apk"
                try {
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1) filename = cursor.getString(nameIndex)
                        }
                    }
                } catch (e: Exception) {}

                val lowerName = filename.lowercase(Locale.ROOT)
                val isArchive = lowerName.endsWith(".apks") || lowerName.endsWith(".apkm") || lowerName.endsWith(".xapk") || lowerName.endsWith(".zip")

                if (isArchive) {
                    try {
                        val inputStream = contentResolver.openInputStream(uri) ?: continue
                        ZipInputStream(inputStream).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory && entry.name.lowercase(Locale.ROOT).endsWith(".apk")) {
                                    val outFile = File(cacheDir, entry.name.substringAfterLast('/'))
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { out ->
                                        zis.copyTo(out)
                                    }
                                    cacheFiles.add(outFile)
                                    successCount++
                                }
                                entry = zis.nextEntry
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    val outFile = File(cacheDir, filename)
                    try {
                        val inputStream = contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            FileOutputStream(outFile).use { out ->
                                inputStream.copyTo(out)
                            }
                            cacheFiles.add(outFile)
                            successCount++
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            
            Handler(mainLooper).post {
                dropPermissions?.release()
                if (cacheFiles.isNotEmpty()) {
                    parseAndGroupMetadata()
                } else {
                    showSnackbar("Failed to find valid APKs", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    resetUI()
                }
            }
        }.start()
    }

    private fun parseAndGroupMetadata() {
        progressTitle.text = "Parsing APK metadata..."
        progressBar.isIndeterminate = true
        progressSubtitle.text = "Grouping applications..."
        
        Thread {
            try {
                val pm = packageManager
                val groupMap = mutableMapOf<String, MutableList<File>>()
                
                for (file in cacheFiles) {
                    val packageInfo = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: continue
                    val pName = packageInfo.packageName ?: continue
                    if (!groupMap.containsKey(pName)) {
                        groupMap[pName] = mutableListOf()
                    }
                    groupMap[pName]?.add(file)
                }
                
                appGroups.clear()
                
                for ((pName, files) in groupMap) {
                    var bestPackageInfo: PackageInfo? = null
                    var bestAppInfo: ApplicationInfo? = null
                    
                    for (file in files) {
                        val pInfo = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: continue
                        pInfo.applicationInfo.sourceDir = file.absolutePath
                        pInfo.applicationInfo.publicSourceDir = file.absolutePath
                        
                        if (bestPackageInfo == null || file.name.contains("base", true) || pInfo.applicationInfo.className != null) {
                            bestPackageInfo = pInfo
                            bestAppInfo = pInfo.applicationInfo
                        }
                    }
                    
                    if (bestPackageInfo == null || bestAppInfo == null) continue
                    
                    val group = AppGroup(pName, files, bestPackageInfo, bestAppInfo)
                    val parsedVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) bestPackageInfo.longVersionCode else bestPackageInfo.versionCode.toLong()
                    
                    try {
                        val installedInfo = pm.getPackageInfo(pName, 0)
                        group.installedVersionName = installedInfo.versionName
                        group.installedVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installedInfo.longVersionCode else installedInfo.versionCode.toLong()
                        
                        group.isSame = group.installedVersionCode == parsedVersionCode
                        group.isUpgrade = parsedVersionCode > group.installedVersionCode
                        group.isDowngrade = parsedVersionCode < group.installedVersionCode
                    } catch (e: PackageManager.NameNotFoundException) {
                        // Not installed
                    }
                    appGroups.add(group)
                }
                
                Handler(mainLooper).post {
                    if (appGroups.isEmpty()) {
                        showSnackbar("Failed to parse valid APKs", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                        resetUI()
                    } else {
                        buildAppsUI()
                    }
                }
                
            } catch (e: Exception) {
                applicationContext.log(e.stackTraceToString(), true)
                Handler(mainLooper).post {
                    showSnackbar("Failed to group APKs", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    resetUI()
                }
            }
        }.start()
    }

    private fun buildAppsUI() {
        cardProgress.visibility = View.GONE
        appsContainer.visibility = View.VISIBLE
        globalActions.visibility = View.VISIBLE
        appsContainer.removeAllViews()
        
        val pm = packageManager
        for (group in appGroups) {
            val view = LayoutInflater.from(this).inflate(R.layout.item_lookback_app, appsContainer, false)
            
            val icon = view.findViewById<ImageView>(R.id.app_icon)
            val name = view.findViewById<TextView>(R.id.app_name)
            val pkg = view.findViewById<TextView>(R.id.app_package)
            val versionInfo = view.findViewById<TextView>(R.id.app_version_info)
            val warning = view.findViewById<TextView>(R.id.app_warning)
            val btnInstallSingle = view.findViewById<MaterialButton>(R.id.btn_install_single)
            
            icon.setImageDrawable(group.baseAppInfo.loadIcon(pm))
            name.text = group.baseAppInfo.loadLabel(pm).toString()
            pkg.text = group.packageName
            
            val parsedVersionName = group.basePackageInfo.versionName ?: "Unknown"
            
            if (group.installedVersionCode == -1L) {
                versionInfo.text = "Installing new app v$parsedVersionName"
                warning.visibility = View.GONE
            } else {
                versionInfo.text = "Current: v${group.installedVersionName}  ➔  New: v$parsedVersionName"
                warning.visibility = View.VISIBLE
                when {
                    group.isDowngrade -> {
                        warning.text = "Valid Downgrade (Check Allow Downgrade in settings)"
                        warning.setTextColor(getColor(R.color.tool_lookback))
                    }
                    group.isSame -> {
                        warning.text = "Warning: This version is already installed."
                        warning.setTextColor(getColor(R.color.red))
                    }
                    group.isUpgrade -> {
                        warning.text = "Notice: This is an upgrade."
                        warning.setTextColor(getColor(R.color.colorSecondary))
                    }
                }
            }
            
            btnInstallSingle.setOnClickListener {
                if (group.status != InstallStatus.INSTALLING) {
                    installQueue.clear()
                    installQueue.add(group)
                    processInstallQueue()
                }
            }
            
            view.tag = group.packageName
            appsContainer.addView(view)
        }
    }
    
    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_lookback_settings, null)
        val cbDowngrade = view.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.cb_downgrade)
        val cbReplace = view.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.cb_replace)
        val cbTest = view.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.cb_test)
        val cbPermissions = view.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.cb_permissions)
        val cbAllUsers = view.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.cb_all_users)
        val cbDontKill = view.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.cb_dont_kill)
        
        cbDowngrade.isChecked = flagDowngrade
        cbReplace.isChecked = flagReplace
        cbTest.isChecked = flagTest
        cbPermissions.isChecked = flagPermissions
        cbAllUsers.isChecked = flagAllUsers
        cbDontKill.isChecked = flagDontKill
        
        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                flagDowngrade = cbDowngrade.isChecked
                flagReplace = cbReplace.isChecked
                flagTest = cbTest.isChecked
                flagPermissions = cbPermissions.isChecked
                flagAllUsers = cbAllUsers.isChecked
                flagDontKill = cbDontKill.isChecked
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun updateAppStatus(packageName: String, statusText: String, color: Int) {
        for (i in 0 until appsContainer.childCount) {
            val view = appsContainer.getChildAt(i)
            if (view.tag == packageName) {
                val tvStatus = view.findViewById<TextView>(R.id.app_status)
                val btnInstallSingle = view.findViewById<MaterialButton>(R.id.btn_install_single)
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = statusText
                tvStatus.setTextColor(getColor(color))
                if (statusText == "Installing...") {
                    btnInstallSingle.isEnabled = false
                } else {
                    btnInstallSingle.isEnabled = true
                }
                break
            }
        }
    }

    private var currentInstallingGroup: AppGroup? = null
    private var installReceiverRegistered = false

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_FAILURE)
            val msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
            
            currentInstallingGroup?.let { group ->
                if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                    group.status = InstallStatus.SUCCESS
                    updateAppStatus(group.packageName, "Success", R.color.green)
                    val appName = group.baseAppInfo.loadLabel(packageManager).toString()
                    saveHistory(group.packageName, group.basePackageInfo.versionName ?: "", appName)
                } else {
                    group.status = InstallStatus.FAILED
                    updateAppStatus(group.packageName, "Failed: $msg", R.color.red)
                }
            }
            
            try {
                unregisterReceiver(this)
            } catch(e:Exception){}
            installReceiverRegistered = false

            currentInstallingGroup = null
            loadHistory()
            
            // Process next in queue
            Handler(mainLooper).postDelayed({
                processInstallQueue()
            }, 500)
        }
    }

    private fun processInstallQueue() {
        if (installQueue.isEmpty()) {
            return
        }
        
        val group = installQueue.poll() ?: return
        currentInstallingGroup = group
        group.status = InstallStatus.INSTALLING
        updateAppStatus(group.packageName, "Installing...", R.color.tool_lookback)
        
        var flags = 0
        if (flagDowngrade) flags = flags or 0x00000080
        if (flagReplace) flags = flags or 0x00000002
        if (flagTest) flags = flags or 0x00000004
        if (flagPermissions) flags = flags or 0x00000100
        if (flagAllUsers) flags = flags or 0x00000040
        if (flagDontKill) flags = flags or 0x40000000 
        
        val paths = group.files.map { it.absolutePath }
        
        val action = "com.legendsayantan.adbtools.INSTALL_RESULT_${System.currentTimeMillis()}"
        // Only ever triggered by this app's own PendingIntent (passed to PackageInstaller as the
        // status receiver), which the system delivers using this app's identity regardless of
        // export state - no other app has a legitimate reason to send this broadcast.
        installReceiverRegistered = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(installReceiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(installReceiver, IntentFilter(action))
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(action).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        
        Thread {
            ShizuToolsController.execute { service ->
                try {
                    service.installApks(paths, flags, pendingIntent.intentSender)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Handler(mainLooper).post {
                        group.status = InstallStatus.FAILED
                        updateAppStatus(group.packageName, "Failed: ${e.message}", R.color.red)
                        try { unregisterReceiver(installReceiver) } catch(e:Exception){}
                        installReceiverRegistered = false
                        processInstallQueue()
                    }
                }
            }
        }.start()
    }

    /** appName comes from loadLabel() (app/OEM controlled) and can contain "|" or newlines, which
     *  would otherwise shift every column of this pipe-delimited format. */
    private fun encodeHistoryField(s: String) = s.replace("|", "%7C").replace("\n", "%0A")
    private fun decodeHistoryField(s: String) = s.replace("%7C", "|").replace("%0A", "\n")

    private fun saveHistory(pkg: String, version: String, appName: String) {
        try {
            val file = File(filesDir, "lookback_history.txt")
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            val entry = "${encodeHistoryField(pkg)}|${encodeHistoryField(version)}|${encodeHistoryField(date)}|${encodeHistoryField(appName)}\n"
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
            val parts = items[position].split("|").map { decodeHistoryField(it) }
            if (parts.size >= 4) {
                val pkg = parts[0]
                val ver = parts[1]
                val d = parts[2]
                val appName = parts[3]
                holder.text.text = "$appName v$ver"
                holder.date.text = "$pkg • $d"
            } else if (parts.size >= 3) {
                val pkg = parts[0]
                val ver = parts[1]
                val d = parts[2]
                holder.text.text = "$pkg v$ver"
                holder.date.text = d
            }
        }

        override fun getItemCount() = items.size
    }

    companion object {
        private const val PICK_FILE_REQUEST_CODE: Int = 1
    }
}