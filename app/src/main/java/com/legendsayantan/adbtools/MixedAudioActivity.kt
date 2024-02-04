package com.legendsayantan.adbtools

import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.legendsayantan.adbtools.adapters.SimpleAdapter
import com.legendsayantan.adbtools.lib.ShizukuShell
import com.legendsayantan.adbtools.lib.Utils.Companion.clearCommandOutputs
import com.legendsayantan.adbtools.lib.Utils.Companion.commandOutputPath
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import com.legendsayantan.adbtools.lib.Utils.Companion.lastCommandOutput

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
    }
    private fun reloadApps(){
        enabledApps.clear()
        disabledApps.clear()
        enabledNames.clear()
        disabledNames.clear()
        clearCommandOutputs()
        ShizukuShell(enabledApps, "appops query-op TAKE_AUDIO_FOCUS ignore | tee -a ${commandOutputPath()}").exec()
        ShizukuShell(enabledApps, "appops query-op TAKE_AUDIO_FOCUS deny | tee -a ${commandOutputPath()}").exec()
        lastCommandOutput().split("\n").forEach {
            if(!enabledApps.contains(it) && it.isNotEmpty())enabledApps.add(it)
        }
        clearCommandOutputs()
        ShizukuShell(disabledApps, "appops query-op TAKE_AUDIO_FOCUS allow | tee -a ${commandOutputPath()}").exec()
        lastCommandOutput().split("\n").forEach {
            if(!disabledApps.contains(it) && it.isNotEmpty()) disabledApps.add(it)
        }
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
        runOnUiThread {
            val enabledAdapter = SimpleAdapter(enabledNames){
                showChangeDialog(enabledApps[it],"allow")
            }
            val disabledAdapter = SimpleAdapter(disabledNames){
                showChangeDialog(disabledApps[it],"deny")
            }
            val enabledList = findViewById<RecyclerView>(R.id.enabled)
            enabledList.adapter = enabledAdapter
            val disabledList = findViewById<RecyclerView>(R.id.disabled)
            disabledList.adapter = disabledAdapter
        }
    }
    private fun showChangeDialog(packageName:String,mode:String){
        val dialog = MaterialAlertDialogBuilder(this)
        dialog.setTitle("MixedAudio")
        dialog.setMessage("Do you want to toggle MixedAudio for $packageName?")
        dialog.setPositiveButton("Yes"){_,_->
            if(setMode(packageName,mode).isBlank()){
                Toast.makeText(this,"Successful",Toast.LENGTH_SHORT).show()
                reloadApps()
            }else{
                Toast.makeText(this,"Failed",Toast.LENGTH_SHORT).show()
            }
        }
        dialog.setNegativeButton("No"){_,_->}
        dialog.create().show()
    }
    private fun setMode(packageName:String,mode:String):String{
        clearCommandOutputs()
        ShizukuShell(enabledApps, "appops set $packageName TAKE_AUDIO_FOCUS $mode | tee -a ${commandOutputPath()}").exec()
        return lastCommandOutput()
    }
}