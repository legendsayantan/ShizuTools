package com.legendsayantan.adbtools

import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.legendsayantan.adbtools.adapters.DebloatAdapter
import com.legendsayantan.adbtools.adapters.SimpleAdapter
import com.legendsayantan.adbtools.data.AppData
import com.legendsayantan.adbtools.data.HistoryManager
import com.legendsayantan.adbtools.lib.Logger.Companion.log
import com.legendsayantan.adbtools.lib.ShizukuRunner
import com.legendsayantan.adbtools.lib.Utils.Companion.extractUrls
import com.legendsayantan.adbtools.lib.Utils.Companion.getAllInstalledApps
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import com.legendsayantan.adbtools.lib.Utils.Companion.loadApps
import com.legendsayantan.adbtools.lib.Utils.Companion.showSnackbar
import com.legendsayantan.adbtools.lib.Utils.Companion.hapticConfirm
import com.legendsayantan.adbtools.lib.Utils.Companion.setupEdgeToEdgeInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStreamReader
import java.lang.reflect.Type
import java.net.URL
import java.text.DateFormat
import java.util.Date

class DebloatActivity : AppCompatActivity() {
    val output = listOf<String>()
    var database: String?
        get() = try {
            File(applicationContext.filesDir, FILENAME_DATABASE).readText()
        } catch (f: FileNotFoundException) {
            null
        }
        set(value) {
            val file = File(applicationContext.filesDir, FILENAME_DATABASE)
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
            if (value != null) {
                file.writeText(value)
            }
        }
    lateinit var apps: HashMap<String, AppData>
    lateinit var list: RecyclerView
    lateinit var cachedApps: HashMap<String, AppData>

    private var isBatchMode = false
    private val selectedPackages = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_debloat)
        initialiseStatusBar()
        
        val root = findViewById<View>(R.id.root_layout)
        setupEdgeToEdgeInsets(R.id.root_layout, R.id.header_content)
        ViewCompat.animate(root).alpha(1f).scaleX(1f).scaleY(1f).setDuration(280).start()

        list = findViewById(R.id.apps_list)
        list.layoutManager = LinearLayoutManager(this)
        
        ShizukuRunner.command("pm grant $packageName android.permission.QUERY_ALL_PACKAGES",
            object : ShizukuRunner.CommandResultListener {
                override fun onCommandResult(output: String, done: Boolean) {}
                override fun onCommandError(error: String) {
                    applicationContext.log(error)
                }
            })

        if (output.isEmpty()) {
            info("Loading online database")
            loadDatabase({ databaseApps ->
                runOnUiThread {
                    info("Scanning local apps")
                    // Fetch disabled packages first
                    ShizukuRunner.command("pm list packages -d", object : ShizukuRunner.CommandResultListener {
                        override fun onCommandResult(disabledOutput: String, done: Boolean) {
                            if (done) {
                                val disabledSet = disabledOutput.lines().map { it.trim().removePrefix("package:") }.toSet()
                                
                                val localApps = packageManager.getAllInstalledApps()
                                val finalApp = hashMapOf<String, AppData>()
                                Thread {
                                    localApps.forEach { app ->
                                        val searchResult = databaseApps[app.packageName]
                                        val appName = app.loadLabel(packageManager).toString()

                                        if (searchResult != null) {
                                            if (searchResult.removal != "Unsafe") finalApp[app.packageName] =
                                                searchResult.apply {
                                                    name = appName
                                                    isDisabled = disabledSet.contains(app.packageName)
                                                }
                                        } else {
                                            finalApp[app.packageName] =
                                                AppData(
                                                    appName, "",
                                                    "", arrayListOf(), arrayListOf(),
                                                    arrayListOf(), "", disabledSet.contains(app.packageName)
                                                )
                                        }
                                    }
                                    runOnUiThread {
                                        apps = finalApp.entries.sortedWith(compareBy { it.value.name })
                                            .associate { it.key to it.value } as HashMap<String, AppData>
                                        cachedApps = apps
                                        
                                        findViewById<LinearLayout>(R.id.loader).visibility = LinearLayout.GONE
                                        list.visibility = View.VISIBLE
                                        setupAdapter(apps)
                                        list.layoutAnimation = AnimationUtils.loadLayoutAnimation(this@DebloatActivity, R.anim.layout_animation_stagger)

                                        findViewById<TextView>(R.id.infoView).visibility = View.GONE
                                        val searchBar = findViewById<EditText>(R.id.search_bar)
                                        searchBar.visibility = View.VISIBLE
                                        
                                        searchBar.addTextChangedListener(object : TextWatcher {
                                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                                            override fun afterTextChanged(s: Editable?) {
                                                val t = s.toString().lowercase()
                                                apps = cachedApps.filterValues { appData ->
                                                    appData.name.lowercase().contains(t) || appData.removal.lowercase().contains(t) ||
                                                    appData.description.lowercase().contains(t) || appData.list.lowercase().contains(t)
                                                } as java.util.HashMap<String, AppData>
                                                setupAdapter(apps)
                                            }
                                        })
                                    }
                                }.start()
                            }
                        }
                        override fun onCommandError(error: String) {
                            applicationContext.log(error)
                        }
                    })
                }
            }, {
                info("No local or online database found.\nPlease check your internet connection and try again.")
            })
        }

        findViewById<ImageView>(R.id.imageRestore).setOnClickListener { restoreMode() }
        setupBatchActionBar()
    }

    private fun setupAdapter(data: HashMap<String, AppData>) {
        list.adapter = DebloatAdapter(
            this@DebloatActivity,
            data,
            { pkg, app -> onAppClick(pkg, app) },
            { pkg, app -> onAppLongClick(pkg, app) },
            { isBatchMode },
            { pkg -> selectedPackages.contains(pkg) }
        )
    }

    private fun onAppClick(id: String, app: AppData) {
        if (isBatchMode) {
            if (selectedPackages.contains(id)) {
                selectedPackages.remove(id)
                if (selectedPackages.isEmpty()) {
                    isBatchMode = false
                }
            } else {
                selectedPackages.add(id)
            }
            updateBatchUI()
            list.adapter?.notifyDataSetChanged()
            return
        }

        var dialog: AlertDialog? = null
        val links = MaterialTextView(this)
        links.text = getString(R.string.links)
        val listOfLinks = app.description.extractUrls()
        val linkView = ListView(this)
        linkView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listOfLinks)
        linkView.setOnItemClickListener { _, _, linkPosition, _ ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(listOfLinks[linkPosition])))
        }
        val disableBtn = MaterialButton(this)
        
        disableBtn.post {
            val isDisabled = app.isDisabled
            disableBtn.text = if (isDisabled) getString(R.string.confirm_to_enable) else getString(R.string.confirm_to_disable)
            disableBtn.setOnClickListener {
                ShizukuRunner.execute("cmd package ${if (isDisabled) "enable" else "disable-user"} --user 0 $id",
                    onResult = { out, done ->
                        if (done) {
                            runOnUiThread {
                                if (out.contains("Success", true) || out.contains("new state", true)) {
                                    dialog?.dismiss()
                                    showSnackbar("Success for ${app.name}", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                                    app.isDisabled = !isDisabled
                                    list.adapter?.notifyDataSetChanged()
                                } else {
                                    showSnackbar("Failed,\n$out", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                                    applicationContext.log(out)
                                }
                                dialog?.dismiss()
                            }
                        }
                    },
                    onError = { err ->
                        runOnUiThread {
                            showSnackbar("Failed,\n$err", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                            dialog?.dismiss()
                        }
                    }
                )
            }
        }
        
        disableBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 25, 0) }

        val uninstallBtn = MaterialButton(this)
        uninstallBtn.text = getString(R.string.confirm_to_uninstall)
        uninstallBtn.setOnClickListener {
            hapticConfirm()
            showSnackbar("Uninstalling ${app.name}")
            ShizukuRunner.execute("cmd package uninstall -k --user 0 $id",
                onResult = { output, done ->
                    if (done) {
                        runOnUiThread {
                            if (output.contains("Success", true)) {
                                HistoryManager(applicationContext).addHistory(id, app.name)
                                removeFromList(id)
                                showSnackbar("Uninstalled ${app.name}", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                            } else {
                                showSnackbar("Failed to uninstall ${app.name},\n$output", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                                applicationContext.log(output)
                            }
                            dialog?.dismiss()
                        }
                    }
                },
                onError = { err ->
                    runOnUiThread {
                        showSnackbar("Failed to uninstall,\n$err", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                        dialog?.dismiss()
                    }
                }
            )
        }
        val btnContainer = LinearLayout(this)
        btnContainer.orientation = LinearLayout.HORIZONTAL
        btnContainer.addView(disableBtn)
        btnContainer.addView(uninstallBtn)
        btnContainer.setPadding(20, 20, 20, 20)
        btnContainer.gravity = Gravity.END

        val dialogView = LinearLayout(this)
        dialogView.orientation = LinearLayout.VERTICAL
        dialogView.setPadding(20, 0, 20, 20)
        if (listOfLinks.isNotEmpty()) dialogView.addView(links)
        dialogView.addView(linkView)
        dialogView.addView(btnContainer)

        dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(true)
            .setTitle(app.name)
            .setMessage(
                "package: $id\n${
                    when (app.removal.ifEmpty { "X" }[0]) {
                        'R' -> "Recommended to uninstall"
                        'A' -> "Only advanced users should uninstall"
                        'E' -> "Only expert users should uninstall"
                        'U' -> "Unsafe to uninstall"
                        else -> "No info, uninstall at own risk"
                    }
                }."
            )
            .show()
    }
    
    private fun onAppLongClick(id: String, app: AppData) {
        if (!isBatchMode) {
            isBatchMode = true
            selectedPackages.clear()
        }
        if (selectedPackages.contains(id)) {
            selectedPackages.remove(id)
            if (selectedPackages.isEmpty()) {
                isBatchMode = false
            }
        } else {
            selectedPackages.add(id)
        }
        updateBatchUI()
        list.adapter?.notifyDataSetChanged()
    }
    
    private fun setupBatchActionBar() {
        val batchCancel = findViewById<View>(R.id.batch_cancel)
        val btnBatchDisable = findViewById<View>(R.id.btn_batch_disable)
        val btnBatchUninstall = findViewById<View>(R.id.btn_batch_uninstall)
        
        batchCancel.setOnClickListener {
            isBatchMode = false
            selectedPackages.clear()
            updateBatchUI()
            list.adapter?.notifyDataSetChanged()
        }
        
        btnBatchDisable.setOnClickListener {
            processBatchAction("disable-user", "Disabled")
        }
        
        btnBatchUninstall.setOnClickListener {
            processBatchAction("uninstall -k", "Uninstalled")
        }
    }
    
    private fun updateBatchUI() {
        val batchActionBar = findViewById<View>(R.id.batch_action_bar)
        val batchCount = findViewById<TextView>(R.id.batch_count)
        
        if (isBatchMode) {
            batchActionBar.visibility = View.VISIBLE
            batchCount.text = "${selectedPackages.size} selected"
        } else {
            batchActionBar.visibility = View.GONE
        }
    }
    
    private fun processBatchAction(commandAction: String, actionName: String) {
        if (selectedPackages.isEmpty()) return
        
        val dialog = ProgressDialog(this).apply {
            setMessage("Processing ${selectedPackages.size} apps...")
            setCancelable(false)
            show()
        }
        
        val targets = selectedPackages.toList()
        var completed = 0
        var successes = 0
        val historyManager = HistoryManager(applicationContext)
        
        Thread {
            for (pkg in targets) {
                // Synchronous Shizuku call equivalent loop, but ShizukuRunner is async so we have to wait or chain
                // Actually, since Shizuku commands can be chained in bash, we can run a single script!
                val app = cachedApps[pkg] ?: continue
                val isUninstall = commandAction.contains("uninstall")
                
                ShizukuRunner.command("cmd package $commandAction --user 0 $pkg", object : ShizukuRunner.CommandResultListener {
                    override fun onCommandResult(output: String, done: Boolean) {
                        if (done) {
                            if (output.contains("Success", true) || output.contains("new state", true)) {
                                successes++
                                if (isUninstall) {
                                    historyManager.addHistory(pkg, app.name)
                                } else {
                                    app.isDisabled = true // For disable
                                }
                            }
                            completed++
                            
                            if (completed >= targets.size) {
                                runOnUiThread {
                                    dialog.dismiss()
                                    showSnackbar("Batch $actionName: $successes/${targets.size} successful", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                                    isBatchMode = false
                                    if (isUninstall) {
                                        targets.forEach { t -> apps.remove(t) }
                                    }
                                    selectedPackages.clear()
                                    updateBatchUI()
                                    setupAdapter(apps)
                                }
                            }
                        }
                    }
                    override fun onCommandError(error: String) {
                        completed++
                        if (completed >= targets.size) {
                            runOnUiThread {
                                dialog.dismiss()
                                isBatchMode = false
                                selectedPackages.clear()
                                updateBatchUI()
                                list.adapter?.notifyDataSetChanged()
                            }
                        }
                    }
                })
            }
        }.start()
    }

    private fun removeFromList(id: String) {
        val lm = list.layoutManager as LinearLayoutManager
        val currentItem = lm.findFirstVisibleItemPosition()
        apps = apps.filterKeys { it != id } as HashMap<String, AppData>
        setupAdapter(apps)
        list.scrollToPosition(currentItem)
    }

    private fun loadDatabase(
        onComplete: (HashMap<String, AppData>) -> Unit,
        onFailure: () -> Unit
    ) {
        try {
            val url = URL(getString(R.string.url_uad_lists))
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val connection = url.openConnection()
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val jsonContent = reader.readText()
                    database = jsonContent
                    onComplete(jsonContent.asAppDatabase())
                } catch (e: Exception) {
                    e.printStackTrace()
                    if (database.isNullOrBlank()) onFailure()
                    else onComplete(database!!.asAppDatabase())
                }
            }
        } catch (e: Exception) {
            applicationContext.log(e.stackTraceToString(), true)
            if (database.isNullOrBlank()) onFailure()
            else onComplete(database!!.asAppDatabase())
        }
    }

    private fun String.asAppDatabase(): HashMap<String, AppData> {
        val type = object : TypeToken<HashMap<String, AppData?>?>() {}.type
        return GsonBuilder()
            .registerTypeAdapter(type, object : JsonDeserializer<HashMap<String, AppData>> {
                override fun deserialize(
                    json: JsonElement?,
                    typeOfT: Type?,
                    context: JsonDeserializationContext?
                ): HashMap<String, AppData> {
                    val map = HashMap<String, AppData>()
                    json?.asJsonObject?.entrySet()?.forEach {
                        map[it.key] = context?.deserialize(it.value, AppData::class.java)!!
                    }
                    return map
                }
            })
            .create()
            .fromJson(this, type)
    }

    private fun info(string: String) {
        runOnUiThread {
            findViewById<TextView>(R.id.infoView)?.text = string
        }
    }

    private fun restoreMode() {
        val activityContext = this
        ShizukuRunner.command("cmd package list packages -u",
            object : ShizukuRunner.CommandResultListener {
                override fun onCommandResult(output: String, done: Boolean) {
                    if (done) {
                        val allApps = output.replace("package:", "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                        loadApps(callback = { installed ->
                            val uninstalled = allApps.filter { !installed.contains(it) }
                            
                            val historyMap = HistoryManager(applicationContext).getHistory().associateBy { it.pkg }
                            val displayList = uninstalled.map { pkg ->
                                val entry = historyMap[pkg]
                                if (entry != null) {
                                    "${entry.name}\n$pkg\nUninstalled on: ${DateFormat.getDateTimeInstance().format(Date(entry.timestamp))}"
                                } else {
                                    pkg
                                }
                            }
                            
                            runOnUiThread {
                                val appsView = RecyclerView(activityContext)
                                val dialog = MaterialAlertDialogBuilder(activityContext)
                                    .setView(appsView)
                                    .setCancelable(true)
                                    .setTitle("Restore System Apps")
                                    .setMessage("Select the app to restore it to your device.")
                                    .show()
                                appsView.layoutManager = LinearLayoutManager(activityContext)
                                appsView.adapter = SimpleAdapter(displayList) { pos ->
                                    val pkg = uninstalled[pos]
                                    ShizukuRunner.command("cmd package install-existing $pkg",
                                        object : ShizukuRunner.CommandResultListener {
                                            override fun onCommandResult(out: String, isDone: Boolean) {
                                                if (isDone) {
                                                    runOnUiThread { showSnackbar(out, com.google.android.material.snackbar.Snackbar.LENGTH_LONG) }
                                                }
                                            }
                                            override fun onCommandError(error: String) {
                                                applicationContext.log(error)
                                            }
                                        })
                                    dialog.dismiss()
                                }
                            }
                        }, errorCallback = { err ->
                            runOnUiThread { showSnackbar("Error loading apps : $err", com.google.android.material.snackbar.Snackbar.LENGTH_LONG) }
                        })
                    }
                }
                override fun onCommandError(error: String) {
                    applicationContext.log(error)
                }
            })
    }

    companion object {
        const val FILENAME_DATABASE: String = "debloat-list.json"
    }
}