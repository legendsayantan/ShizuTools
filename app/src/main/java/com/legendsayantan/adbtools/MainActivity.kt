package com.legendsayantan.adbtools

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.legendsayantan.adbtools.adapters.ToolCardAdapter
import com.legendsayantan.adbtools.data.ToolCard
import com.legendsayantan.adbtools.dialog.IntentShellBottomSheet
import com.legendsayantan.adbtools.dialog.LocalShellBottomSheet
import com.legendsayantan.adbtools.dialog.LogBottomSheetDialog

import com.legendsayantan.adbtools.dialog.UniversalPipDialog
import com.legendsayantan.adbtools.lib.Logger.Companion.log
import com.legendsayantan.adbtools.lib.Utils.Companion.getNotiPerms
import com.legendsayantan.adbtools.lib.Utils.Companion.showSnackbar
import com.legendsayantan.adbtools.receivers.PipReceiver
import com.legendsayantan.adbtools.services.SoundMasterService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.legendsayantan.adbtools.lib.GradleUpdate
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.timerTask
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    private lateinit var toolAdapter: ToolCardAdapter
    private val tools = mutableListOf<Any>()

    @SuppressLint("LaunchActivityFromNotification")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        getNotiPerms()
        registerGlobalExceptionLogger()

        val root = findViewById<View>(R.id.root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Only apply side + bottom padding to root; let AppBarLayout handle the top
            view.setPadding(insets.left, 0, insets.right, 0)
            // Push the header content below the status bar
            val header = findViewById<android.widget.LinearLayout>(R.id.header_content)
            header?.let {
                it.setPadding(it.paddingLeft, insets.top + resources.getDimensionPixelSize(R.dimen.header_padding_top_extra), it.paddingRight, it.paddingBottom)
            }
            WindowInsetsCompat.CONSUMED
        }
        
        // Header entrance animation
        ViewCompat.animate(root).alpha(1f).scaleX(1f).scaleY(1f).setDuration(280).start()
        
        try {
            findViewById<TextView>(R.id.version_chip)?.text = 
                "v${packageManager.getPackageInfo(packageName, 0).versionName}"
        } catch (e: Exception) {}

        findViewById<ImageView>(R.id.logs)?.setOnClickListener {
            LogBottomSheetDialog(this).show()
        }
        findViewById<ImageView>(R.id.github)?.setOnClickListener {
            val shizukuUrl = "https://github.com/legendsayantan/shizutools"
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(shizukuUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                log(e.stackTraceToString(), true)
            }
        }

        setupTools()
        setupRecyclerView()
        setupDebug()
        setupShortcuts()
        checkUpdates()
        
        if (intent?.action == "OPEN_SOUNDMASTER") {
            com.legendsayantan.adbtools.dialog.SoundMasterBottomSheet().show(supportFragmentManager, "SoundMaster")
        }
    }

    override fun onResume() {
        super.onResume()
        if (::toolAdapter.isInitialized) {
            toolAdapter.refreshActiveStates()
        }
    }

    private fun setupTools() {
        tools.add("System")
        tools.add(ToolCard(
            id = "debloater",
            nameRes = R.string.debloater,
            descRes = R.string.desc_debloater,
            iconRes = R.drawable.ic_debloater,
            accentColorRes = R.color.tool_debloater,
            activityClass = DebloatActivity::class.java
        ))
        tools.add(ToolCard(
            id = "standbybucket",
            nameRes = R.string.standby_bucket,
            descRes = R.string.desc_standby_bucket,
            iconRes = R.drawable.ic_standby_bucket,
            accentColorRes = R.color.tool_standby_bucket,
            activityClass = StandbyBucketActivity::class.java,
            minSdk = Build.VERSION_CODES.P // App Standby Buckets don't exist before Android 9
        ))
        tools.add(ToolCard(
            id = "themepatcher",
            nameRes = R.string.themepatcher,
            descRes = R.string.desc_themepatcher,
            iconRes = R.drawable.ic_theme_patcher,
            accentColorRes = R.color.tool_theme_patcher,
            activityClass = ThemePatcherActivity::class.java
        ))
        tools.add(ToolCard(
            id = "lookback",
            nameRes = R.string.lookback,
            descRes = R.string.desc_lookback,
            iconRes = R.drawable.ic_lookback,
            accentColorRes = R.color.tool_lookback,
            activityClass = LookbackActivity::class.java
        ))
        tools.add("Media")
        tools.add(ToolCard(
            id = "soundmaster",
            nameRes = R.string.soundmaster,
            descRes = R.string.desc_soundmaster,
            iconRes = R.drawable.ic_sound_master,
            accentColorRes = R.color.tool_sound_master,
            activityClass = null,
            onClickOverride = { com.legendsayantan.adbtools.dialog.SoundMasterBottomSheet().show(supportFragmentManager, "SoundMaster") },
            isServiceActive = { SoundMasterService.running },
            minSdk = Build.VERSION_CODES.Q // relies on per-app audio playback capture, only available from Android 10
        ))
        tools.add(ToolCard(
            id = "mixedaudio",
            nameRes = R.string.mixedaudio,
            descRes = R.string.desc_mixedaudio,
            iconRes = R.drawable.ic_mixed_audio,
            accentColorRes = R.color.tool_mixed_audio,
            activityClass = MixedAudioActivity::class.java
        ))
        tools.add(ToolCard(
            id = "pip",
            nameRes = R.string.universalpip,
            descRes = R.string.desc_universalpip,
            iconRes = R.drawable.ic_universal_pip,
            accentColorRes = R.color.tool_universal_pip,
            activityClass = null,
            onClickOverride = { UniversalPipDialog(this).show() }
        ))
        tools.add("Advanced")
        tools.add(ToolCard(
            id = "virtualmount",
            nameRes = R.string.virtualmount,
            descRes = R.string.desc_virtualmount,
            iconRes = R.drawable.ic_virtual_mount,
            accentColorRes = R.color.tool_local_shell,
            activityClass = VirtualMountActivity::class.java
        ))
        tools.add(ToolCard(
            id = "localshell",
            nameRes = R.string.localshell,
            descRes = R.string.desc_adb_shell,
            iconRes = R.drawable.ic_local_shell,
            accentColorRes = R.color.tool_local_shell,
            activityClass = null,
            onClickOverride = { LocalShellBottomSheet(this).show() }
        ))
        tools.add(ToolCard(
            id = "intentshell",
            nameRes = R.string.intent_shell,
            descRes = R.string.desc_intent_shell,
            iconRes = R.drawable.ic_intent_shell,
            accentColorRes = R.color.tool_intent_shell,
            activityClass = null,
            onClickOverride = { IntentShellBottomSheet(this).show() }
        ))

    }

    private fun setupRecyclerView() {
        val recycler = findViewById<RecyclerView>(R.id.recycler_tools)
        val spanCount = resources.getInteger(R.integer.tool_grid_span)
        val gridLayoutManager = GridLayoutManager(this, spanCount)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val item = tools[position]
                return if (item is String || (item is ToolCard && (item.id == "soundmaster" || item.id == "virtualmount"))) {
                    spanCount
                } else {
                    1
                }
            }
        }
        recycler.layoutManager = gridLayoutManager
        toolAdapter = ToolCardAdapter(tools) { tool ->
            if (tool.minSdk > 0 && Build.VERSION.SDK_INT < tool.minSdk) {
                showSnackbar(
                    getString(
                        R.string.tool_unsupported_version,
                        getString(tool.nameRes),
                        com.legendsayantan.adbtools.lib.Utils.androidVersionName(tool.minSdk),
                        com.legendsayantan.adbtools.lib.Utils.androidVersionName(Build.VERSION.SDK_INT)
                    ),
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                )
            } else if (tool.onClickOverride != null) {
                tool.onClickOverride.invoke()
            } else if (tool.activityClass != null) {
                startActivity(Intent(applicationContext, tool.activityClass))
            }
        }
        recycler.adapter = toolAdapter
        recycler.layoutAnimation = AnimationUtils.loadLayoutAnimation(this, R.anim.layout_animation_stagger)
    }


    private fun registerGlobalExceptionLogger() {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            e.printStackTrace()
            applicationContext.log(e.stackTraceToString(), true)
            val intent = Intent(applicationContext, CrashActivity::class.java).apply {
                putExtra("stacktrace", e.stackTraceToString())
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            exitProcess(0)
        }
    }

    fun setupDebug() {
        val debug = findViewById<TextView>(R.id.app_title)
        var counter = 0
        val timer = Timer()
        val counterReset = {
            timerTask {
                counter = 0
            }
        }
        var lastTask: TimerTask = timerTask { }
        debug?.setOnClickListener {
            counter++
            if (counter > 5) {
                counter = 0
                startActivity(Intent(this, DebugSettingsActivity::class.java))
                lastTask.cancel()
            } else {
                lastTask = counterReset()
                timer.schedule(lastTask, 5000)
            }
        }
    }

    private fun setupShortcuts() {
        val debloatIntent = Intent(this, DebloatActivity::class.java).apply {
            action = Intent.ACTION_VIEW
        }
        val debloatShortcut = ShortcutInfoCompat.Builder(this, "shortcut_debloat")
            .setShortLabel("Debloater")
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_debloater))
            .setIntent(debloatIntent)
            .build()

        val soundMasterIntent = Intent(this, MainActivity::class.java).apply {
            action = "OPEN_SOUNDMASTER"
        }
        val soundMasterShortcut = ShortcutInfoCompat.Builder(this, "shortcut_soundmaster")
            .setShortLabel("SoundMaster")
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_sound_master))
            .setIntent(soundMasterIntent)
            .build()
            
        ShortcutManagerCompat.addDynamicShortcuts(this, listOf(debloatShortcut, soundMasterShortcut))
    }

    private fun checkUpdates() {
        val banner = findViewById<View>(R.id.update_banner)
        val btnUpdate = findViewById<View>(R.id.btn_update)
        GradleUpdate(
            applicationContext,
            "https://cdn.jsdelivr.net/gh/legendsayantan/ShizuTools@master/app/build.gradle",
            86400000
        ).check(updateAvailable = { version ->
            runOnUiThread {
                banner?.visibility = View.VISIBLE
                findViewById<TextView>(R.id.update_text)?.text = "Version $version available"
                btnUpdate?.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/legendsayantan/ShizuTools/releases/latest")))
                }
            }
        })
    }
}