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
import com.legendsayantan.adbtools.adapters.SimpleAdapter
import com.legendsayantan.adbtools.lib.ShizukuRunner
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar

class MixedAudioActivity : AppCompatActivity() {
    private val enabledApps = arrayListOf<String>()
    private val disabledApps = arrayListOf<String>()
    private val enabledNames = arrayListOf<String>()
    private val disabledNames = arrayListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mixed_audio)
        initialiseStatusBar()
        Thread{
            reloadApps()
        }.start()
        MaterialAlertDialogBuilder(this).apply {
            setTitle("Force Apply Warning!")
            setMessage(getString(R.string.force_applying_mixedaudio_may_crash))
            setPositiveButton("understood"){_,_->}
            create().show()
        }
    }
    private fun reloadApps(){
        enabledApps.clear()
        disabledApps.clear()
        enabledNames.clear()
        disabledNames.clear()
        ShizukuRunner.runAdbCommand("appops query-op TAKE_AUDIO_FOCUS ignore ",object : ShizukuRunner.CommandResultListener{
            override fun onCommandResult(output: String, done: Boolean) {
                if(done){
                    output.split("\n").forEach {
                        if(!enabledApps.contains(it) && it.isNotEmpty())enabledApps.add(it)
                    }
                    ShizukuRunner.runAdbCommand("appops query-op TAKE_AUDIO_FOCUS deny ",object : ShizukuRunner.CommandResultListener{
                        override fun onCommandResult(output: String, done: Boolean) {
                            if(done){
                                output.split("\n").forEach {
                                    if(!enabledApps.contains(it) && it.isNotEmpty())enabledApps.add(it)
                                }
                                ShizukuRunner.runAdbCommand("appops query-op TAKE_AUDIO_FOCUS allow ",object : ShizukuRunner.CommandResultListener{
                                    override fun onCommandResult(output: String, done: Boolean) {
                                        if(done){
                                            output.split("\n").forEach {
                                                if(!disabledApps.contains(it) && it.isNotEmpty()) disabledApps.add(it)
                                            }
                                            runOnUiThread{
                                                enabledApps.forEach {
                                                    try {
                                                        val name = packageManager.getApplicationInfo(it.split(" ")[0], PackageManager.GET_META_DATA).loadLabel(packageManager).toString()
                                                        enabledNames.add(name.ifEmpty { it })
                                                    } catch (e: PackageManager.NameNotFoundException) {
                                                        enabledNames.add(it.split(" ")[0])
                                                    }
                                                }
                                                disabledApps.forEach {
                                                    try {
                                                        val name = packageManager.getApplicationInfo(it.split(" ")[0], PackageManager.GET_META_DATA).loadLabel(packageManager).toString()
                                                        disabledNames.add(name.ifEmpty { it })
                                                    } catch (e: PackageManager.NameNotFoundException) {
                                                        disabledNames.add(it.split(" ")[0])
                                                    }
                                                }
                                                val enabledAdapter = SimpleAdapter(enabledNames){
                                                    showChangeDialog(enabledApps[it],
                                                        arrayOf("allow")
                                                    )
                                                }
                                                val disabledAdapter = SimpleAdapter(disabledNames){
                                                    showChangeDialog(disabledApps[it],
                                                        arrayOf("ignore","deny")
                                                    )
                                                }
                                                val enabledList = findViewById<RecyclerView>(R.id.enabled)
                                                enabledList.adapter = enabledAdapter
                                                val disabledList = findViewById<RecyclerView>(R.id.disabled)
                                                disabledList.adapter = disabledAdapter
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
    private fun showChangeDialog(packageName:String,modes:Array<String>){
        val dialog = MaterialAlertDialogBuilder(this).apply {
            setPositiveButton("Cancel"){_,_->}
        }.create()
        dialog.setTitle("MixedAudio")
        dialog.setMessage("Toggle MixedAudio support for $packageName?")
        val layout = LinearLayout(this)
        layout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT
        )
        layout.setPadding(50,0,50,0)
        layout.orientation = LinearLayout.VERTICAL
        val btns = listOf("Apply","Force Apply")
        modes.forEachIndexed { index, mode ->
            val btn = MaterialButton(this)
            btn.text = btns[index]
            btn.setOnClickListener {
                if(setMode(packageName,mode).isBlank()){
                    Toast.makeText(this,"Successful",Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    Thread{
                        reloadApps()
                    }.start()
                }else{
                    Toast.makeText(this,"Failed",Toast.LENGTH_SHORT).show()
                }
            }
            layout.addView(btn)
        }
        dialog.setView(layout)
        dialog.show()
    }
    private fun setMode(packageName:String,mode:String):String{
        var o = ""
        ShizukuRunner.runAdbCommand("appops set $packageName TAKE_AUDIO_FOCUS $mode",object : ShizukuRunner.CommandResultListener{
            override fun onCommandResult(output: String, done: Boolean) {
                o = output
            }
        })
        return o
    }
}