package com.legendsayantan.adbtools

import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.legendsayantan.adbtools.adapters.AudioStateAdapter
import com.legendsayantan.adbtools.data.AudioState
import com.legendsayantan.adbtools.lib.ShizukuRunner
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import com.legendsayantan.adbtools.lib.Utils.Companion.loadApps

/**
 * @author legendsayantan
 */
class MixedAudioActivity : AppCompatActivity() {
    val muteMap = HashMap<String,Boolean>()
    val focusMap = HashMap<String,AudioState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mixed_audio)
        initialiseStatusBar()
        Thread{
            reloadApps()
        }.start()
        MaterialAlertDialogBuilder(this).apply {
            setTitle("Warning!")
            setMessage(getString(R.string.force_applying_mixedaudio_may_crash))
            setPositiveButton("understood"){_,_->}
            create().show()
        }
    }
    private fun reloadApps(){
        //read muted status
        muteMap.clear()
        focusMap.clear()
        ShizukuRunner.runAdbCommand("appops query-op PLAY_AUDIO deny",object : ShizukuRunner.CommandResultListener {
            override fun onCommandResult(
                output: String,
                done: Boolean
            ) {
                if (done) {
                    output.split("\n").forEach { muteMap.putIfAbsent(it, true) }
                    ShizukuRunner.runAdbCommand("appops query-op PLAY_AUDIO allow",object : ShizukuRunner.CommandResultListener {
                        override fun onCommandResult(
                            output: String,
                            done: Boolean
                        ) {
                            if (done) {
                                output.split("\n").forEach { muteMap.putIfAbsent(it, false) }
                            }

                            //read focus status
                            ShizukuRunner.runAdbCommand("appops query-op TAKE_AUDIO_FOCUS ignore ",object : ShizukuRunner.CommandResultListener{
                                override fun onCommandResult(output: String, done: Boolean) {
                                    if(done){
                                        output.split("\n").forEach {
                                            if(it.isNotBlank())
                                                focusMap.putIfAbsent(it, AudioState(getAppName(it),muteMap[it]?:false, AudioState.Focus.IGNORED))
                                        }
                                        ShizukuRunner.runAdbCommand("appops query-op TAKE_AUDIO_FOCUS deny ",object : ShizukuRunner.CommandResultListener{
                                            override fun onCommandResult(output: String, done: Boolean) {
                                                if(done){
                                                    output.split("\n").forEach {
                                                        if(it.isNotBlank())
                                                            focusMap.putIfAbsent(it, AudioState(getAppName(it),muteMap[it]?:false, AudioState.Focus.DENIED))
                                                    }
                                                    ShizukuRunner.runAdbCommand("appops query-op TAKE_AUDIO_FOCUS allow ",object : ShizukuRunner.CommandResultListener{
                                                        override fun onCommandResult(output: String, done: Boolean) {
                                                            if(done){
                                                                output.split("\n").forEach {
                                                                    if(it.isNotBlank())
                                                                        focusMap.putIfAbsent(it, AudioState(getAppName(it),muteMap[it]?:false, AudioState.Focus.ALLOWED))
                                                                }

                                                                loadApps {
                                                                    it.forEach {  pkg->
                                                                        focusMap.putIfAbsent(pkg,AudioState(getAppName(pkg),muteMap[pkg]?:false, AudioState.Focus.ALLOWED))
                                                                    }

                                                                    //update UI
                                                                    runOnUiThread{
                                                                        val recyclerView = findViewById<RecyclerView>(R.id.apps)
                                                                        recyclerView.adapter = AudioStateAdapter(this@MixedAudioActivity,focusMap) { pkg,state->
                                                                            showChangeDialog(pkg,state)
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    })
                                                }
                                            }
                                        })
                                    }
                                }
                            })
                        }
                    })
                }
            }
        })
    }

    private fun getAppName(pkg:String):String{
        try {
            val appInfo = packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
            return appInfo.loadLabel(packageManager).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
        return pkg
    }
    private fun showChangeDialog(packageName:String,state:AudioState){
        val dialog = MaterialAlertDialogBuilder(this).apply {
            setPositiveButton("Cancel"){_,_->}
        }.create()
        dialog.setTitle(getString(R.string.mixedaudio))
        dialog.setMessage("Select operation for ${state.name} :")
        val layout = LinearLayout(this)
        layout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT
        )
        layout.setPadding(50,0,50,0)
        layout.orientation = LinearLayout.VERTICAL
        val btns = linkedMapOf(
            "Mute app audio" to "appops set $packageName PLAY_AUDIO deny",
            "Unmute app audio" to "appops set $packageName PLAY_AUDIO allow",
            "Disable MixedAudio" to "appops set $packageName TAKE_AUDIO_FOCUS allow",
            "Enable MixedAudio" to "appops set $packageName TAKE_AUDIO_FOCUS ignore",
            "Force Enable MixedAudio" to "appops set $packageName TAKE_AUDIO_FOCUS deny"
        )
        btns.remove(btns.keys.toList()[if(state.muted)0 else 1])
        btns.remove(btns.keys.toList()[state.focus.ordinal+1])

        btns.forEach { (t, u) ->
            layout.addView(MaterialButton(this).apply {
                text = t
                setOnClickListener {
                    ShizukuRunner.runAdbCommand(u,object : ShizukuRunner.CommandResultListener{
                        override fun onCommandResult(output: String, done: Boolean) {
                            if(done){
                                runOnUiThread {
                                    Toast.makeText(this@MixedAudioActivity,
                                        output.ifBlank { "Success" }, Toast.LENGTH_SHORT).show()
                                    dialog.dismiss()
                                }
                                reloadApps()
                            }
                        }
                    })
                }
            })
        }
        dialog.setView(layout)
        dialog.show()
    }
}