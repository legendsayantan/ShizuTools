package com.legendsayantan.adbtools.dialog

import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.view.WindowManager
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.adapters.SimpleAdapter
import com.legendsayantan.adbtools.lib.Logger.Companion.log
import com.legendsayantan.adbtools.lib.Utils
import com.legendsayantan.adbtools.lib.Utils.Companion.loadApps

/**
 * @author legendsayantan
 */
class AppSelectionDialog(context: Context, val onSelection: (String) -> Unit) : Dialog(context) {
    init {
        setContentView(R.layout.dialog_new_slider)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    override fun show() {
        super.show()
        val list = findViewById<RecyclerView>(R.id.appSelection)
        val search = findViewById<TextInputEditText>(R.id.search)
        val handler = Handler(context.mainLooper);
        Toast.makeText(context, "Loading apps...", Toast.LENGTH_SHORT).show()
        Thread {
            loadApps({ packageList ->
                handler.post {
                    Toast.makeText(context, "${packageList.size} apps found", Toast.LENGTH_LONG)
                        .show()
                }
                val reloadApps: (String) -> Unit = { filter ->
                    val filteredMap = packageList.map { it to Utils.getAppNameFromPackage(context, it) }
                        .filter { filter.isBlank() || it.second.contains(filter, true) }
                    val adapter = SimpleAdapter(filteredMap.map { it.second }) { selectionIndex ->
                        dismiss()
                        onSelection(filteredMap[selectionIndex].first)
                    }
                    Handler(context.mainLooper).post {
                        list.adapter = adapter
                        list.invalidate()
                    }
                }
                reloadApps("")
                search.doAfterTextChanged { reloadApps(it.toString()) }
            }, {
                handler.post {
                    Toast.makeText(context, "Error loading apps : $it", Toast.LENGTH_LONG).show()
                }
                context.log(it)
            })
        }.start()
    }
}