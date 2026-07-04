package com.legendsayantan.adbtools

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.legendsayantan.adbtools.adapters.AudioStateAdapter
import com.legendsayantan.adbtools.bottomsheets.AudioStateBottomSheet
import com.legendsayantan.adbtools.data.AudioState
import com.legendsayantan.adbtools.lib.Logger.Companion.log
import com.legendsayantan.adbtools.lib.ShizukuRunner
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import com.legendsayantan.adbtools.lib.Utils.Companion.loadApps
import com.legendsayantan.adbtools.lib.Utils.Companion.showSnackbar
import com.legendsayantan.adbtools.lib.Utils.Companion.setupEdgeToEdgeInsets
import com.legendsayantan.adbtools.services.SoundMasterService

class MixedAudioActivity : AppCompatActivity() {
    val muteMap = HashMap<String, Boolean>()
    val focusMap = HashMap<String, AudioState>()

    lateinit var recyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_mixed_audio)
        initialiseStatusBar()
        
        val root = findViewById<View>(R.id.root_layout)
        setupEdgeToEdgeInsets(R.id.root_layout, R.id.header_content)
        ViewCompat.animate(root).alpha(1f).scaleX(1f).scaleY(1f).setDuration(280).start()

        recyclerView = findViewById(R.id.apps)
        recyclerView.layoutManager = LinearLayoutManager(this)

        Thread {
            reloadApps()
        }.start()

        MaterialAlertDialogBuilder(this).apply {
            setTitle("Warning!")
            setMessage(
                if (SoundMasterService.running) getString(R.string.do_not_un_mute_apps_that_are_being_controlled_by_soundmaster)
                else getString(R.string.force_applying_mixedaudio_may_crash)
            )
            setPositiveButton("understood") { _, _ -> }
            val dialog = create()
            dialog.show()
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.colorSecondary))
        }

        val searchBar = findViewById<android.widget.EditText>(R.id.search_bar)
        searchBar.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val filterBy = s.toString().lowercase()
                val filteredMap = focusMap.filter { it.key.lowercase().contains(filterBy) || it.value.name.lowercase().contains(filterBy)}
                val sortedFilteredMap = filteredMap.entries.sortedWith(compareBy { it.value.name }).associate { it.key to it.value } as java.util.HashMap<String, AudioState>
                
                recyclerView.adapter = AudioStateAdapter(this@MixedAudioActivity, sortedFilteredMap) { pkg, state, action ->
                    handleQuickAction(pkg, state, action)
                }
            }
        })
        findViewById<ImageView>(R.id.imageRestore).setOnClickListener { restoreAll() }
    }

    private fun setProgressText(text: String) {
        runOnUiThread {
            findViewById<View>(R.id.loading_container)?.visibility = View.VISIBLE
            findViewById<TextView>(R.id.loading_text)?.text = text
        }
    }
    
    private fun hideProgress() {
        runOnUiThread {
            findViewById<View>(R.id.loading_container)?.visibility = View.GONE
        }
    }

    private fun reloadApps() {
        setProgressText("Reading muted apps...")
        muteMap.clear()
        focusMap.clear()
        ShizukuRunner.command("appops query-op PLAY_AUDIO deny",
            object : ShizukuRunner.CommandResultListener {
                override fun onCommandResult(output: String, done: Boolean) {
                    if (done) {
                        output.split("\n").forEach { muteMap.putIfAbsent(it, true) }
                        ShizukuRunner.command("appops query-op PLAY_AUDIO allow",
                            object : ShizukuRunner.CommandResultListener {
                                override fun onCommandResult(out2: String, done2: Boolean) {
                                    if (done2) {
                                        setProgressText("Reading focus states...")
                                        out2.split("\n").forEach { muteMap.putIfAbsent(it, false) }
                                    }
                                    
                                    ShizukuRunner.command("appops query-op TAKE_AUDIO_FOCUS ignore ",
                                        object : ShizukuRunner.CommandResultListener {
                                            override fun onCommandResult(out3: String, done3: Boolean) {
                                                if (done3) {
                                                    out3.split("\n").forEach {
                                                        if (it.isNotBlank())
                                                            focusMap.putIfAbsent(it, AudioState(getAppName(it), muteMap[it] ?: false, AudioState.Focus.IGNORED))
                                                    }
                                                    ShizukuRunner.command("appops query-op TAKE_AUDIO_FOCUS deny ",
                                                        object : ShizukuRunner.CommandResultListener {
                                                            override fun onCommandResult(out4: String, done4: Boolean) {
                                                                if (done4) {
                                                                    out4.split("\n").forEach {
                                                                        if (it.isNotBlank())
                                                                            focusMap.putIfAbsent(it, AudioState(getAppName(it), muteMap[it] ?: false, AudioState.Focus.DENIED))
                                                                    }
                                                                    ShizukuRunner.command("appops query-op TAKE_AUDIO_FOCUS allow ",
                                                                        object : ShizukuRunner.CommandResultListener {
                                                                            override fun onCommandResult(out5: String, done5: Boolean) {
                                                                                if (done5) {
                                                                                    setProgressText("Loading all apps...")
                                                                                    out5.split("\n").forEach {
                                                                                        if (it.isNotBlank())
                                                                                            focusMap.putIfAbsent(it, AudioState(getAppName(it), muteMap[it] ?: false, AudioState.Focus.ALLOWED))
                                                                                    }

                                                                                    loadApps (callback = { installed ->
                                                                                        installed.forEach { pkg ->
                                                                                            focusMap.putIfAbsent(pkg, AudioState(getAppName(pkg), muteMap[pkg] ?: false, AudioState.Focus.ALLOWED))
                                                                                        }
                                                                                        focusMap.remove("")
                                                                                        
                                                                                        runOnUiThread {
                                                                                            hideProgress()
                                                                                            val searchBar = findViewById<android.widget.EditText>(R.id.search_bar)
                                                                                            val filterBy = searchBar.text.toString().lowercase()
                                                                                            val filteredMap = focusMap.filter { it.key.lowercase().contains(filterBy) || it.value.name.lowercase().contains(filterBy)}
                                                                                            val sortedFocusMap = filteredMap.entries.sortedWith(compareBy { it.value.name }).associate { it.key to it.value } as HashMap<String, AudioState>
                                                                                            recyclerView.adapter = AudioStateAdapter(this@MixedAudioActivity, sortedFocusMap) { pkg, state, action ->
                                                                                                handleQuickAction(pkg, state, action)
                                                                                            }
                                                                                            recyclerView.layoutAnimation = AnimationUtils.loadLayoutAnimation(this@MixedAudioActivity, R.anim.layout_animation_stagger)
                                                                                            recyclerView.scheduleLayoutAnimation()
                                                                                        }
                                                                                    }, errorCallback = { err -> onShizukuError(err) })
                                                                                }
                                                                            }
                                                                            override fun onCommandError(error: String) { onShizukuError(error) }
                                                                        })
                                                                }
                                                            }
                                                            override fun onCommandError(error: String) { onShizukuError(error) }
                                                        })
                                                }
                                            }
                                            override fun onCommandError(error: String) { onShizukuError(error) }
                                        })
                                }
                                override fun onCommandError(error: String) { onShizukuError(error) }
                            })
                    }
                }
                override fun onCommandError(error: String) { onShizukuError(error) }
            })
    }

    private fun getAppName(pkg: String): String {
        try {
            val appInfo = packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
            return appInfo.loadLabel(packageManager).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
            log(e.stackTraceToString())
        }
        return pkg
    }



    private fun restoreAll() {
        val builder = MaterialAlertDialogBuilder(this).apply {
            setPositiveButton("Cancel") { _, _ -> }
        }
        val dialog = builder.create()
        dialog.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.colorSecondary))
        dialog.setTitle("Restore settings")
        dialog.setMessage("Select operation for all apps:")
        val layout = LinearLayout(this)
        layout.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        layout.setPadding(50, 0, 50, 0)
        layout.orientation = LinearLayout.VERTICAL
        layout.addView(MaterialButton(this).apply {
            text = context.getString(R.string.unmute_all_apps)
            setOnClickListener {
                dialog.dismiss()
                setProgressText("Applying changes...")
                com.legendsayantan.adbtools.lib.ShizuToolsController.execute { controller ->
                    focusMap.filter { it.value.muted }.forEach { (t, _) ->
                        val uid = packageManager.getPackageInfo(t, 0).applicationInfo?.uid ?: -1
                        controller.setAppOpMode(t, uid, 28, 0)
                    }
                    runOnUiThread { reloadApps() }
                }
            }
        })
        layout.addView(MaterialButton(this).apply {
            text = context.getString(R.string.disable_all_mixedaudio)
            setOnClickListener {
                dialog.dismiss()
                setProgressText("Applying changes...")
                com.legendsayantan.adbtools.lib.ShizuToolsController.execute { controller ->
                    focusMap.filter { it.value.focus != AudioState.Focus.ALLOWED }.forEach { (t, _) ->
                        val uid = packageManager.getPackageInfo(t, 0).applicationInfo?.uid ?: -1
                        controller.setAppOpMode(t, uid, 32, 0)
                    }
                    runOnUiThread { reloadApps() }
                }
            }
        })
        dialog.setView(layout)
        dialog.show()
    }

    private fun handleQuickAction(pkg: String, state: AudioState, action: String) {
        setProgressText("Applying changes...")
        val uid = packageManager.getPackageInfo(pkg, 0).applicationInfo?.uid ?: -1
        if (action == "MUTE_TOGGLE") {
            val mode = if (state.muted) 0 else 2 // 0=allow, 2=deny
            com.legendsayantan.adbtools.lib.ShizuToolsController.execute { 
                it.setAppOpMode(pkg, uid, 28, mode)
                runOnUiThread { 
                    showSnackbar("Success", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                    reloadApps()
                }
            }
        } else if (action == "MIXED_TOGGLE") {
            val mode = if (state.focus != AudioState.Focus.ALLOWED) 0 else 1 // 0=allow, 1=ignore
            com.legendsayantan.adbtools.lib.ShizuToolsController.execute { 
                it.setAppOpMode(pkg, uid, 32, mode)
                runOnUiThread { 
                    showSnackbar("Success", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                    reloadApps()
                }
            }
        }
    }
    
    private fun onShizukuError(err: String) {
        applicationContext.log(err)
        runOnUiThread { showSnackbar("Error loading apps : $err", com.google.android.material.snackbar.Snackbar.LENGTH_LONG) }
    }
}