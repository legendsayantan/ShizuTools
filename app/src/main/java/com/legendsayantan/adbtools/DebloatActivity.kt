package com.legendsayantan.adbtools

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textview.MaterialTextView
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.legendsayantan.adbtools.adapters.DebloatAdapter
import com.legendsayantan.adbtools.data.AppData
import com.legendsayantan.adbtools.data.HistoryManager
import com.legendsayantan.adbtools.lib.Logger.Companion.log
import com.legendsayantan.adbtools.lib.Utils.Companion.extractUrls
import com.legendsayantan.adbtools.lib.Utils.Companion.getAllInstalledApps
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import com.legendsayantan.adbtools.lib.Utils.Companion.setupEdgeToEdgeInsets
import com.legendsayantan.adbtools.lib.Utils.Companion.showSnackbar
import com.legendsayantan.adbtools.lib.Utils.Companion.hapticConfirm
import com.legendsayantan.adbtools.lib.ShizuToolsController
import com.legendsayantan.adbtools.services.IShizuToolsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStreamReader
import java.lang.reflect.Type
import java.net.URL

class DebloatActivity : AppCompatActivity() {
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

        info("Loading online database")
        loadDatabase({ databaseApps ->
            runOnUiThread {
                info("Scanning device apps via Shizuku service")
                ShizuToolsController.execute { service: IShizuToolsService ->
                    val packageStates = service.packageStates
                    val disabledSet = mutableSetOf<String>()
                    val hiddenSet = mutableSetOf<String>()
                    val uninstalledSet = mutableSetOf<String>()
                    
                    for (ps in packageStates) {
                        val split = ps.split(":")
                        if (split.size == 2) {
                            val pkg = split[0]
                            when (split[1]) {
                                "DISABLED" -> disabledSet.add(pkg)
                                "HIDDEN" -> hiddenSet.add(pkg)
                                "UNINSTALLED" -> uninstalledSet.add(pkg)
                            }
                        }
                    }
                    
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
                                        isHidden = hiddenSet.contains(app.packageName)
                                    }
                            } else {
                                finalApp[app.packageName] =
                                    AppData(
                                        appName, "",
                                        "", arrayListOf(), arrayListOf(),
                                        arrayListOf(), "", disabledSet.contains(app.packageName),
                                        hiddenSet.contains(app.packageName)
                                    )
                            }
                        }
                        
                        // Add hidden apps that might not appear in getAllInstalledApps
                        for (hiddenApp in hiddenSet) {
                            if (!finalApp.containsKey(hiddenApp)) {
                                val searchResult = databaseApps[hiddenApp]
                                if (searchResult != null && searchResult.removal != "Unsafe") {
                                    finalApp[hiddenApp] = searchResult.apply {
                                        name = hiddenApp
                                        isDisabled = disabledSet.contains(hiddenApp)
                                        isHidden = true
                                    }
                                } else if (searchResult == null) {
                                    finalApp[hiddenApp] = AppData(hiddenApp, "", "", arrayListOf(), arrayListOf(), arrayListOf(), "", disabledSet.contains(hiddenApp), true)
                                }
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
        }, {
            info("No local or online database found.\nPlease check your internet connection and try again.")
        })

        findViewById<ImageView>(R.id.imageRestore).setOnClickListener { restoreMode() }
        setupBatchActionBar()
    }

    private fun setupAdapter(data: HashMap<String, AppData>) {
        list.adapter = DebloatAdapter(
            this@DebloatActivity,
            data,
            { position, pkg, app -> onAppClick(position, pkg, app) },
            { position, pkg, app -> onAppLongClick(position, pkg, app) },
            { isBatchMode },
            { pkg -> selectedPackages.contains(pkg) }
        )
    }

    private fun onAppClick(position: Int, id: String, app: AppData) {
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
            list.adapter?.notifyItemChanged(position)
            return
        }

        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_debloat_action, null)
        
        val appIcon = view.findViewById<ImageView>(R.id.app_icon)
        val appName = view.findViewById<MaterialTextView>(R.id.app_name)
        val appPackage = view.findViewById<MaterialTextView>(R.id.app_package)
        val stateBadge = view.findViewById<MaterialTextView>(R.id.state_badge)
        val severityDesc = view.findViewById<MaterialTextView>(R.id.severity_description)
        val linksContainer = view.findViewById<LinearLayout>(R.id.links_container)
        val btnDisable = view.findViewById<MaterialButton>(R.id.btn_disable)
        val btnHide = view.findViewById<MaterialButton>(R.id.btn_hide)
        val btnUninstall = view.findViewById<MaterialButton>(R.id.btn_uninstall)

        try {
            val pInfo = packageManager.getPackageInfo(id, 0)
            appIcon.setImageDrawable(pInfo.applicationInfo.loadIcon(packageManager))
        } catch (e: Exception) {}

        appName.text = app.name
        appPackage.text = id
        
        when {
            app.isDisabled -> {
                stateBadge.text = "Disabled"
                stateBadge.visibility = View.VISIBLE
            }
            app.isHidden -> {
                stateBadge.text = "Hidden"
                stateBadge.visibility = View.VISIBLE
            }
            else -> stateBadge.visibility = View.GONE
        }

        severityDesc.text = when (app.removal.ifEmpty { "X" }[0]) {
            'R' -> "Recommended to uninstall."
            'A' -> "Only advanced users should uninstall."
            'E' -> "Only expert users should uninstall."
            'U' -> "It's Unsafe to uninstall."
            else -> "No information available."
        }

        val listOfLinks = app.description.extractUrls()
        if (listOfLinks.isNotEmpty()) {
            linksContainer.visibility = View.VISIBLE
            for (link in listOfLinks) {
                val tv = MaterialTextView(this).apply {
                    text = link
                    setTextColor(getColor(R.color.colorSecondary))
                    setPadding(0, 8, 0, 8)
                    setOnClickListener {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                    }
                }
                linksContainer.addView(tv)
            }
        }

        btnDisable.text = if (app.isDisabled) "Enable" else "Disable"
        btnHide.text = if (app.isHidden) "Unhide" else "Hide"

        btnDisable.setOnClickListener {
            hapticConfirm()
            ShizuToolsController.execute { service: IShizuToolsService ->
                service.setAppEnabledState(id, app.isDisabled)
                runOnUiThread {
                    app.isDisabled = !app.isDisabled
                    showSnackbar(if (app.isDisabled) "Disabled ${app.name}" else "Enabled ${app.name}")
                    bottomSheet.dismiss()
                    list.adapter?.notifyItemChanged(position)
                }
            }
        }

        btnHide.setOnClickListener {
            hapticConfirm()
            ShizuToolsController.execute { service: IShizuToolsService ->
                service.setAppHiddenState(id, !app.isHidden)
                runOnUiThread {
                    app.isHidden = !app.isHidden
                    showSnackbar(if (app.isHidden) "Hidden ${app.name}" else "Unhidden ${app.name}")
                    bottomSheet.dismiss()
                    list.adapter?.notifyItemChanged(position)
                }
            }
        }

        btnUninstall.setOnClickListener {
            hapticConfirm()
            showSnackbar("Uninstalling ${app.name}...")
            
            val action = "com.legendsayantan.adbtools.UNINSTALL_RESULT_${System.currentTimeMillis()}"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_FAILURE)
                    val msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
                    
                    runOnUiThread {
                        if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                            HistoryManager(applicationContext).addHistory(id, app.name)
                            removeFromList(id)
                            showSnackbar("Uninstalled ${app.name}", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                        } else {
                            showSnackbar("Failed to uninstall: $msg", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                        }
                    }
                    try { unregisterReceiver(this) } catch (e: Exception) {}
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(receiver, IntentFilter(action))
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                this, 0, Intent(action).setPackage(packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            
            ShizuToolsController.execute { service: IShizuToolsService ->
                service.uninstallApp(id, pendingIntent.intentSender)
                runOnUiThread { bottomSheet.dismiss() }
            }
        }

        bottomSheet.setContentView(view)
        bottomSheet.show()
    }
    
    private fun onAppLongClick(position: Int, id: String, app: AppData) {
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
        
        // Let's add Hide button dynamically to the action bar if it's a LinearLayout
        val batchActionBarCard = findViewById<ViewGroup>(R.id.batch_action_bar)
        val batchActionBar = batchActionBarCard?.getChildAt(0) as? LinearLayout
        
        val btnBatchHide = ImageView(this).apply {
            setImageResource(R.drawable.baseline_visibility_off_24)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.colorSecondary))
            layoutParams = LinearLayout.LayoutParams(
                resources.displayMetrics.density.toInt() * 48,
                resources.displayMetrics.density.toInt() * 48
            )
            val padding = resources.displayMetrics.density.toInt() * 12
            setPadding(padding, padding, padding, padding)
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            background = getDrawable(outValue.resourceId)
            setOnClickListener {
                processBatchAction("hide")
            }
        }
        
        // Add it before the uninstall button
        if(batchActionBar != null) {
            batchActionBar.addView(btnBatchHide, batchActionBar.childCount - 1)
        }
        
        batchCancel.setOnClickListener {
            isBatchMode = false
            selectedPackages.clear()
            updateBatchUI()
            list.adapter?.notifyDataSetChanged()
        }
        
        btnBatchDisable.setOnClickListener {
            processBatchAction("disable")
        }
        
        btnBatchUninstall.setOnClickListener {
            processBatchAction("uninstall")
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
    
    private fun processBatchAction(actionType: String) {
        if (selectedPackages.isEmpty()) return
        val targets = selectedPackages.toList()

        if (actionType == "uninstall") {
            processBatchUninstall(targets)
            return
        }

        ShizuToolsController.execute { service: IShizuToolsService ->
            var successCount = 0

            for (pkg in targets) {
                val app = cachedApps[pkg] ?: continue
                try {
                    when (actionType) {
                        "disable" -> {
                            service.setAppEnabledState(pkg, false)
                            app.isDisabled = true
                            successCount++
                        }
                        "hide" -> {
                            service.setAppHiddenState(pkg, true)
                            app.isHidden = true
                            successCount++
                        }
                    }
                } catch(e:Exception){}
            }

            runOnUiThread {
                showSnackbar("Batch action complete. Processed $successCount apps.")
                isBatchMode = false
                selectedPackages.clear()
                updateBatchUI()
                setupAdapter(apps)
            }
        }
    }

    /**
     * Each package gets its own PendingIntent/BroadcastReceiver pair so the batch actually waits
     * for PackageInstaller's real result per app - the list is only pruned for packages that
     * genuinely reported success, instead of assuming every uninstall worked.
     */
    private fun processBatchUninstall(targets: List<String>) {
        val historyManager = HistoryManager(applicationContext)
        val remaining = java.util.concurrent.atomic.AtomicInteger(targets.size)
        val succeeded = java.util.Collections.synchronizedList(mutableListOf<String>())
        val failed = java.util.Collections.synchronizedList(mutableListOf<String>())

        fun finishIfDone() {
            if (remaining.get() > 0) return
            runOnUiThread {
                if (succeeded.isNotEmpty()) {
                    apps = apps.filterKeys { it !in succeeded } as HashMap<String, AppData>
                }
                val msg = if (failed.isEmpty()) {
                    "Uninstalled ${succeeded.size} apps."
                } else {
                    "Uninstalled ${succeeded.size} apps, ${failed.size} failed."
                }
                showSnackbar(msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                isBatchMode = false
                selectedPackages.clear()
                updateBatchUI()
                setupAdapter(apps)
            }
        }

        showSnackbar("Uninstalling ${targets.size} apps...")

        targets.forEach { pkg ->
            val app = cachedApps[pkg]
            val action = "com.legendsayantan.adbtools.BATCH_UNINSTALL_RESULT_${pkg.hashCode()}_${System.currentTimeMillis()}"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_FAILURE)
                    if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                        succeeded.add(pkg)
                        if (app != null) historyManager.addHistory(pkg, app.name)
                    } else {
                        failed.add(pkg)
                    }
                    try { unregisterReceiver(this) } catch (e: Exception) {}
                    remaining.decrementAndGet()
                    finishIfDone()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, IntentFilter(action))
            }

            val pendingIntent = PendingIntent.getBroadcast(
                this, pkg.hashCode(), Intent(action).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            ShizuToolsController.execute { service: IShizuToolsService ->
                try {
                    service.uninstallApp(pkg, pendingIntent.intentSender)
                } catch (e: Exception) {
                    try { unregisterReceiver(receiver) } catch (e2: Exception) {}
                    failed.add(pkg)
                    remaining.decrementAndGet()
                    runOnUiThread { finishIfDone() }
                }
            }
        }
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
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_debloat_restore, null)
        
        val tabLayout = view.findViewById<TabLayout>(R.id.tab_layout)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_restore)
        val loader = view.findViewById<ProgressBar>(R.id.restore_loader)
        val emptyText = view.findViewById<MaterialTextView>(R.id.empty_text)
        val btnClose = view.findViewById<ImageView>(R.id.btn_close_restore)
        
        recycler.layoutManager = LinearLayoutManager(this)
        
        btnClose.setOnClickListener { bottomSheet.dismiss() }
        
        loader.visibility = View.VISIBLE
        recycler.visibility = View.GONE
        emptyText.visibility = View.GONE
        
        bottomSheet.setContentView(view)
        bottomSheet.show()
        
        ShizuToolsController.execute { service: IShizuToolsService ->
            val packageStates = service.packageStates
            val disabledSet = mutableSetOf<String>()
            val hiddenSet = mutableSetOf<String>()
            val uninstalledSet = mutableSetOf<String>()
            
            for (ps in packageStates) {
                val split = ps.split(":")
                if (split.size == 2) {
                    val pkg = split[0]
                    when (split[1]) {
                        "DISABLED" -> disabledSet.add(pkg)
                        "HIDDEN" -> hiddenSet.add(pkg)
                        "UNINSTALLED" -> uninstalledSet.add(pkg)
                    }
                }
            }
            
            val historyMap = HistoryManager(applicationContext).getHistory().associateBy { it.pkg }
            
            runOnUiThread {
                loader.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                
                fun updateList(type: Int) {
                    val targetSet = when(type) {
                        0 -> hiddenSet
                        1 -> disabledSet
                        else -> uninstalledSet
                    }
                    
                    if (targetSet.isEmpty()) {
                        recycler.visibility = View.GONE
                        emptyText.visibility = View.VISIBLE
                    } else {
                        recycler.visibility = View.VISIBLE
                        emptyText.visibility = View.GONE
                        
                        val displayList = targetSet.map { pkg ->
                            val h = historyMap[pkg]
                            val name = h?.name ?: if (::cachedApps.isInitialized) cachedApps[pkg]?.name ?: pkg else pkg
                            Triple(pkg, name, type)
                        }.sortedBy { it.second }
                        
                        recycler.adapter = RestoreAdapter(displayList) { pkg, itemType ->
                            ShizuToolsController.execute { s: IShizuToolsService ->
                                when(itemType) {
                                    0 -> { s.setAppHiddenState(pkg, false); hiddenSet.remove(pkg) }
                                    1 -> { s.setAppEnabledState(pkg, true); disabledSet.remove(pkg) }
                                    2 -> {
                                        val action = "com.legendsayantan.adbtools.RESTORE_RESULT_${System.currentTimeMillis()}"
                                        val pendingIntent = PendingIntent.getBroadcast(
                                            this@DebloatActivity, 0, Intent(action).setPackage(packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                                        )
                                        s.restoreUninstalledApp(pkg, pendingIntent.intentSender)
                                        uninstalledSet.remove(pkg)
                                    }
                                }
                                runOnUiThread {
                                    showSnackbar("Restore triggered for $pkg")
                                    updateList(itemType) // Refresh
                                }
                            }
                        }
                    }
                }
                
                tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                    override fun onTabSelected(tab: TabLayout.Tab?) {
                        tab?.position?.let { updateList(it) }
                    }
                    override fun onTabUnselected(tab: TabLayout.Tab?) {}
                    override fun onTabReselected(tab: TabLayout.Tab?) {}
                })
                
                updateList(0)
            }
        }
    }
    
    inner class RestoreAdapter(
        private val items: List<Triple<String, String, Int>>, 
        private val onRestore: (String, Int) -> Unit
    ) : RecyclerView.Adapter<RestoreAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.app_icon)
            val name: MaterialTextView = view.findViewById(R.id.app_name)
            val pkg: MaterialTextView = view.findViewById(R.id.app_package)
            val btn: MaterialButton = view.findViewById(R.id.btn_restore)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_restore, parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.name.text = item.second
            holder.pkg.text = item.first
            try {
                holder.icon.setImageDrawable(packageManager.getApplicationIcon(item.first))
            } catch(e:Exception){
                holder.icon.setImageResource(R.mipmap.ic_launcher)
            }
            
            holder.btn.text = when(item.third) {
                0 -> "Show"
                1 -> "Enable"
                else -> "Restore"
            }
            
            holder.btn.setOnClickListener {
                onRestore(item.first, item.third)
            }
        }

        override fun getItemCount() = items.size
    }

    companion object {
        const val FILENAME_DATABASE: String = "debloat-list.json"
    }
}
