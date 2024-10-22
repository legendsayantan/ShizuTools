package com.legendsayantan.adbtools

import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.legendsayantan.adbtools.adapters.AudioStateAdapter
import com.legendsayantan.adbtools.data.AudioState
import com.legendsayantan.adbtools.lib.Logger.Companion.log
import com.legendsayantan.adbtools.lib.ShizukuRunner
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import com.legendsayantan.adbtools.lib.Utils.Companion.loadApps
import com.legendsayantan.adbtools.services.SoundMasterService

/**
 * @author legendsayantan
 */
class MixedAudioActivity : AppCompatActivity() {
    val muteMap = HashMap<String, Boolean>()
    val focusMap = HashMap<String, AudioState>()
    lateinit var filterBtn : ImageView
    var filterBy = ""
    lateinit var recyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mixed_audio)
        initialiseStatusBar()
        Toast.makeText(applicationContext, "Loading apps...", Toast.LENGTH_SHORT).show()
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
            create().show()
        }
        filterBtn = findViewById(R.id.imageSearch)
        filterBtn.setOnClickListener { filter() }
        findViewById<ImageView>(R.id.imageRestore).setOnClickListener { restoreAll() }
    }

    private fun reloadApps() {
        //read muted status
        muteMap.clear()
        focusMap.clear()
        ShizukuRunner.command("appops query-op PLAY_AUDIO deny",
            object : ShizukuRunner.CommandResultListener {
                override fun onCommandResult(
                    output: String,
                    done: Boolean
                ) {
                    if (done) {
                        output.split("\n").forEach { muteMap.putIfAbsent(it, true) }
                        ShizukuRunner.command("appops query-op PLAY_AUDIO allow",
                            object : ShizukuRunner.CommandResultListener {
                                override fun onCommandResult(
                                    output: String,
                                    done: Boolean
                                ) {
                                    if (done) {
                                        output.split("\n")
                                            .forEach { muteMap.putIfAbsent(it, false) }
                                    }

                                    //read focus status
                                    ShizukuRunner.command("appops query-op TAKE_AUDIO_FOCUS ignore ",
                                        object : ShizukuRunner.CommandResultListener {
                                            override fun onCommandResult(
                                                output: String,
                                                done: Boolean
                                            ) {
                                                if (done) {
                                                    output.split("\n").forEach {
                                                        if (it.isNotBlank())
                                                            focusMap.putIfAbsent(
                                                                it,
                                                                AudioState(
                                                                    getAppName(it),
                                                                    muteMap[it] ?: false,
                                                                    AudioState.Focus.IGNORED
                                                                )
                                                            )
                                                    }
                                                    ShizukuRunner.command("appops query-op TAKE_AUDIO_FOCUS deny ",
                                                        object :
                                                            ShizukuRunner.CommandResultListener {
                                                            override fun onCommandResult(
                                                                output: String,
                                                                done: Boolean
                                                            ) {
                                                                if (done) {
                                                                    output.split("\n").forEach {
                                                                        if (it.isNotBlank())
                                                                            focusMap.putIfAbsent(
                                                                                it,
                                                                                AudioState(
                                                                                    getAppName(it),
                                                                                    muteMap[it]
                                                                                        ?: false,
                                                                                    AudioState.Focus.DENIED
                                                                                )
                                                                            )
                                                                    }
                                                                    ShizukuRunner.command("appops query-op TAKE_AUDIO_FOCUS allow ",
                                                                        object :
                                                                            ShizukuRunner.CommandResultListener {
                                                                            override fun onCommandResult(
                                                                                output: String,
                                                                                done: Boolean
                                                                            ) {
                                                                                if (done) {
                                                                                    output.split("\n")
                                                                                        .forEach {
                                                                                            if (it.isNotBlank())
                                                                                                focusMap.putIfAbsent(
                                                                                                    it,
                                                                                                    AudioState(
                                                                                                        getAppName(
                                                                                                            it
                                                                                                        ),
                                                                                                        muteMap[it]
                                                                                                            ?: false,
                                                                                                        AudioState.Focus.ALLOWED
                                                                                                    )
                                                                                                )
                                                                                        }

                                                                                    loadApps (callback = {
                                                                                        runOnUiThread {
                                                                                            Toast.makeText(applicationContext, "${it.size} apps found", Toast.LENGTH_LONG).show()
                                                                                        }
                                                                                        it.forEach { pkg ->
                                                                                            focusMap.putIfAbsent(
                                                                                                pkg,
                                                                                                AudioState(
                                                                                                    getAppName(
                                                                                                        pkg
                                                                                                    ),
                                                                                                    muteMap[pkg]
                                                                                                        ?: false,
                                                                                                    AudioState.Focus.ALLOWED
                                                                                                )
                                                                                            )
                                                                                        }

                                                                                        focusMap.remove("")
                                                                                        //update UI
                                                                                        runOnUiThread {
                                                                                            recyclerView =
                                                                                                findViewById(
                                                                                                    R.id.apps
                                                                                                )
                                                                                            recyclerView.adapter =
                                                                                                AudioStateAdapter(
                                                                                                    this@MixedAudioActivity,
                                                                                                    focusMap
                                                                                                ) { pkg, state ->
                                                                                                    showChangeDialog(
                                                                                                        pkg,
                                                                                                        state
                                                                                                    )
                                                                                                }
                                                                                        }
                                                                                    }, errorCallback = {
                                                                                        onShizukuError(it)
                                                                                    })
                                                                                }
                                                                            }
                                                                            override fun onCommandError(error: String) {
                                                                                onShizukuError(error)
                                                                            }
                                                                        })
                                                                }
                                                            }
                                                            override fun onCommandError(error: String) {
                                                                onShizukuError(error)
                                                            }
                                                        })
                                                }
                                            }
                                            override fun onCommandError(error: String) {
                                                onShizukuError(error)
                                            }
                                        })
                                }
                                override fun onCommandError(error: String) {
                                    onShizukuError(error)
                                }
                            })
                    }
                }

                override fun onCommandError(error: String) {
                    onShizukuError(error)
                }
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

    private fun filter() = if (filterBy.isNotBlank()) {
        filterBy=""
        recyclerView.adapter =
            AudioStateAdapter(
                this@MixedAudioActivity,
                focusMap
            ) { pkg, state ->
                showChangeDialog(
                    pkg,
                    state
                )
            }
        filterBtn.setImageResource(R.drawable.baseline_filter_list_24)
    } else {
        val layout = TextInputLayout(this)
        val editText = TextInputEditText(this)
        editText.hint = "Enter app name/package"
        layout.addView(editText)
        layout.setPadding(50, 0, 50, 0)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(layout)
            .setCancelable(true)
            .setTitle("Filter")
            .setPositiveButton(
                "Apply"
            ) { dialog, which ->
                filterBy = editText.text.toString()
                recyclerView.adapter =
                    AudioStateAdapter(
                        this@MixedAudioActivity,
                        focusMap.filter { it.key.contains(filterBy, true) || it.value.name.contains(filterBy, true)} as HashMap<String, AudioState>
                    ) { pkg, state ->
                        showChangeDialog(
                            pkg,
                            state
                        )
                    }
                dialog.dismiss()
                filterBtn.setImageResource(R.drawable.baseline_filter_list_off_24)
            }
            .show()
    }

    private fun restoreAll(){
        val dialog = MaterialAlertDialogBuilder(this).apply {
            setPositiveButton("Cancel") { _, _ -> }
        }.create()
        dialog.setTitle("Restore settings")
        dialog.setMessage("Select operation for all apps:")
        val layout = LinearLayout(this)
        layout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        layout.setPadding(50, 0, 50, 0)
        layout.orientation = LinearLayout.VERTICAL
        layout.addView(MaterialButton(this).apply {
            text = context.getString(R.string.unmute_all_apps)
            setOnClickListener {
                focusMap.filter { it.value.muted }.forEach { (t, u) ->
                    runCommand("appops set $t PLAY_AUDIO allow",false)
                }
                dialog.dismiss()
                reloadApps()
            }
        })
        layout.addView(MaterialButton(this).apply {
            text = context.getString(R.string.disable_all_mixedaudio)
            setOnClickListener {
                focusMap.filter { it.value.focus != AudioState.Focus.ALLOWED }.forEach { (t, u) ->
                    runCommand("appops set $t TAKE_AUDIO_FOCUS allow",false)
                }
                dialog.dismiss()
                reloadApps()
            }
        })
        dialog.setView(layout)
        dialog.show()
    }

    private fun showChangeDialog(packageName: String, state: AudioState) {
        val dialog = MaterialAlertDialogBuilder(this).apply {
            setPositiveButton("Cancel") { _, _ -> }
        }.create()
        dialog.setTitle(getString(R.string.mixedaudio))
        dialog.setMessage("Select operation for ${state.name} :")
        val layout = LinearLayout(this)
        layout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        layout.setPadding(50, 0, 50, 0)
        layout.orientation = LinearLayout.VERTICAL
        val btns = linkedMapOf(
            "Mute app audio" to "appops set $packageName PLAY_AUDIO deny",
            "Unmute app audio" to "appops set $packageName PLAY_AUDIO allow",
            "Disable MixedAudio" to "appops set $packageName TAKE_AUDIO_FOCUS allow",
            "Enable MixedAudio" to "appops set $packageName TAKE_AUDIO_FOCUS ignore",
            "Force Enable MixedAudio" to "appops set $packageName TAKE_AUDIO_FOCUS deny"
        )
        btns.remove(btns.keys.toList()[if (state.muted) 0 else 1])
        btns.remove(btns.keys.toList()[state.focus.ordinal + 1])

        btns.forEach { (t, u) ->
            layout.addView(MaterialButton(this).apply {
                text = t
                setOnClickListener {
                    runCommand(u)
                    dialog.dismiss()
                }
            })
        }
        dialog.setView(layout)
        dialog.show()
    }
    private fun runCommand(cmd:String,reload:Boolean=true){
        ShizukuRunner.command(cmd, object : ShizukuRunner.CommandResultListener {
            override fun onCommandResult(output: String, done: Boolean) {
                if (done) {
                    runOnUiThread {
                        Toast.makeText(this@MixedAudioActivity,
                            output.ifBlank { "Success" }, Toast.LENGTH_SHORT
                        ).show()
                    }
                    if(reload)reloadApps()
                }
            }

            override fun onCommandError(error: String) {
                onShizukuError(error)
            }
        })
    }
    private fun onShizukuError(err:String){
        applicationContext.log(err)
        runOnUiThread {
            Toast.makeText(this@MixedAudioActivity,
                "Error loading apps : $err", Toast.LENGTH_LONG
            ).show()
        }
    }
}