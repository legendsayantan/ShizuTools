package com.legendsayantan.adbtools.dialog

import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.view.WindowManager
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.adapters.SimpleAdapter
import com.legendsayantan.adbtools.lib.Logger.Companion.log
import com.legendsayantan.adbtools.lib.Utils
import com.legendsayantan.adbtools.lib.Utils.Companion.loadApps

/**
 * @author legendsayantan
 */
class AppSelectionDialog(context: Context, val onSelection:(String)->Unit) : Dialog(context) {
    init {
        setContentView(R.layout.dialog_new_slider)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT)
    }

    override fun show() {
        super.show()
        val list = findViewById<RecyclerView>(R.id.appSelection)
        val handler = Handler(context.mainLooper);
        Toast.makeText(context, "Loading apps...", Toast.LENGTH_SHORT).show()
        Thread {
            loadApps ({ packageList ->
                handler.post {
                    Toast.makeText(context, "${packageList.size} apps found", Toast.LENGTH_LONG).show()
                }
                val packageMap = linkedMapOf<String, String>()
                val orderMap = linkedMapOf<String, String>()
                packageList.forEach {
                    if (it.isNotBlank() && (it != context.packageName)) orderMap.putIfAbsent(
                        it,
                        Utils.getAppNameFromPackage(context, it)
                    )
                }
                orderMap.entries.sortedBy { it.value }.forEach { (k, v) ->
                    packageMap.putIfAbsent(k, v)
                }
                Handler(context.mainLooper).post {
                    val adapter = SimpleAdapter(
                        packageMap.values.toList()
                    ){
                        dismiss()
                        onSelection(packageMap.keys.toList()[it])
                    }
                    list.adapter = adapter
                    list.invalidate()
                }
            },{
                handler.post {
                    Toast.makeText(context, "Error loading apps : $it", Toast.LENGTH_LONG).show()
                }
                context.log(it)
            })
        }.start()
    }
}