package com.legendsayantan.adbtools

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.legendsayantan.adbtools.adapters.StandbyBucketAdapter
import com.legendsayantan.adbtools.data.StandbyBuckets
import com.legendsayantan.adbtools.lib.ShizuToolsController
import com.legendsayantan.adbtools.lib.Utils.Companion.androidVersionName
import com.legendsayantan.adbtools.lib.Utils.Companion.getAllInstalledApps
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar

class StandbyBucketActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyState: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var adapter: StandbyBucketAdapter
    private lateinit var prefs: SharedPreferences
    
    private val allAppList = mutableListOf<AppItem>()
    private val displayAppList = mutableListOf<AppItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_standby_bucket)
        initialiseStatusBar()

        val root = findViewById<View>(R.id.root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, 0, insets.right, 0)
            val header = findViewById<LinearLayout>(R.id.header_content)
            header?.setPadding(header.paddingLeft, insets.top + resources.getDimensionPixelSize(R.dimen.header_padding_top_extra), header.paddingRight, header.paddingBottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.animate(root).alpha(1f).scaleX(1f).scaleY(1f).setDuration(280).start()

        recyclerView = findViewById(R.id.recycler_apps)
        progressBar = findViewById(R.id.progressBar)
        emptyState = findViewById(R.id.empty_state)
        searchInput = findViewById(R.id.search_bar)
        prefs = getSharedPreferences("locked_buckets_prefs", Context.MODE_PRIVATE)

        recyclerView.layoutManager = LinearLayoutManager(this)
        
        adapter = StandbyBucketAdapter(displayAppList,
            onBucketChanged = { app, bucket ->
                setBucket(app.packageName, bucket)
                if (app.isLocked) {
                    prefs.edit().putInt(app.packageName, bucket).apply()
                    setBucketLockRemote(app.packageName, bucket, true)
                }
            },
            onLockToggled = { app, isLocked ->
                if (isLocked) {
                    prefs.edit().putInt(app.packageName, app.bucket).apply()
                    setBucketLockRemote(app.packageName, app.bucket, true)
                    recyclerView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                } else {
                    prefs.edit().remove(app.packageName).apply()
                    setBucketLockRemote(app.packageName, app.bucket, false)
                    recyclerView.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT)
                }
            }
        )
        recyclerView.adapter = adapter
        
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterApps(s.toString())
            }
        })
        
        if (!isBucketApiSupported()) {
            showUnsupportedState()
        } else {
            loadApps()
        }
    }

    /** App Standby Buckets (UsageStatsManager#getAppStandbyBucket / setAppStandbyBucket) don't exist before Android 9 (API 28). */
    private fun isBucketApiSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    private fun showUnsupportedState() {
        progressBar.visibility = View.GONE
        recyclerView.visibility = View.GONE
        searchInput.isEnabled = false
        emptyState.visibility = View.VISIBLE
        findViewById<ImageView>(R.id.empty_state_icon)?.setImageResource(R.drawable.outline_info_24)
        findViewById<TextView>(R.id.empty_state_text)?.text = getString(
            R.string.standby_bucket_unsupported,
            androidVersionName(Build.VERSION_CODES.P),
            androidVersionName(Build.VERSION.SDK_INT)
        )
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyState.visibility = View.GONE
        
        ShizuToolsController.execute { service ->

            // Sync all locked buckets to service on load
            val allPrefs = prefs.all
            var lockSyncFailures = 0
            for ((pkg, value) in allPrefs) {
                if (value is Int) {
                    try { service.setBucketLock(pkg, value, true) } catch (e: Exception) { lockSyncFailures++ }
                }
            }

            val pm = packageManager
            val apps = pm.getAllInstalledApps()
            val tempAppList = mutableListOf<AppItem>()
            val userId = StandbyBuckets.currentUserId()
            
            for (appInfo in apps) {
                // Ensure the app can be launched so we don't list weird system overlays
                if (pm.getLaunchIntentForPackage(appInfo.packageName) != null) {
                    try {
                        val bucket = service.getAppStandbyBucket(appInfo.packageName, userId)
                        val name = appInfo.loadLabel(pm).toString()
                        val icon = appInfo.loadIcon(pm)
                        val isLocked = prefs.contains(appInfo.packageName)
                        tempAppList.add(AppItem(appInfo.packageName, name, icon, bucket, isLocked))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            
            tempAppList.sortBy { it.name.lowercase() }
            
            runOnUiThread {
                allAppList.clear()
                allAppList.addAll(tempAppList)
                
                progressBar.visibility = View.GONE
                filterApps(searchInput.text.toString())
                
                recyclerView.layoutAnimation = android.view.animation.AnimationUtils.loadLayoutAnimation(this@StandbyBucketActivity, R.anim.layout_animation_stagger)
                recyclerView.scheduleLayoutAnimation()

                if (lockSyncFailures > 0) {
                    Snackbar.make(findViewById(R.id.root_layout), "Failed to sync $lockSyncFailures locked bucket(s).", Snackbar.LENGTH_LONG)
                        .setBackgroundTint(getColor(R.color.colorError))
                        .setTextColor(getColor(R.color.colorOnPrimary))
                        .show()
                }
            }
        }
    }

    private fun setBucketLockRemote(pkg: String, bucket: Int, locked: Boolean) {
        ShizuToolsController.execute { service ->
            try {
                service.setBucketLock(pkg, bucket, locked)
            } catch (e: Exception) {
                runOnUiThread {
                    Snackbar.make(findViewById(R.id.root_layout), "Failed to ${if (locked) "lock" else "unlock"} $pkg: ${e.message}", Snackbar.LENGTH_LONG)
                        .setBackgroundTint(getColor(R.color.colorError))
                        .setTextColor(getColor(R.color.colorOnPrimary))
                        .show()
                }
            }
        }
    }

    private fun filterApps(query: String) {
        val lowerQuery = query.lowercase()
        displayAppList.clear()
        if (lowerQuery.isEmpty()) {
            displayAppList.addAll(allAppList)
        } else {
            displayAppList.addAll(allAppList.filter { 
                it.name.lowercase().contains(lowerQuery) || it.packageName.lowercase().contains(lowerQuery) 
            })
        }
        adapter.notifyDataSetChanged()
        
        if (displayAppList.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
        }
    }

    private fun setBucket(packageName: String, bucket: Int) {
        ShizuToolsController.execute { service ->
            try {
                service.setAppStandbyBucket(packageName, bucket, StandbyBuckets.currentUserId())
            } catch (e: Exception) {
                runOnUiThread {
                    Snackbar.make(findViewById(R.id.root_layout), "Failed to set bucket: ${e.message}", Snackbar.LENGTH_LONG)
                        .setBackgroundTint(getColor(R.color.colorError))
                        .setTextColor(getColor(R.color.colorOnPrimary))
                        .show()
                }
            }
        }
    }
}
