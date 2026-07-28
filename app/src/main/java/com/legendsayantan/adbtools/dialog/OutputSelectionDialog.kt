package com.legendsayantan.adbtools.dialog

import android.app.Dialog
import android.content.Context
import android.media.AudioDeviceInfo
import android.view.Gravity
import android.view.WindowManager
import androidx.recyclerview.widget.RecyclerView
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.adapters.SimpleAdapter
import com.legendsayantan.adbtools.lib.AudioOutputMap

/**
 * @author legendsayantan
 */
class OutputSelectionDialog(
    c: Context,
    private val devices: List<AudioDeviceInfo?>,
    val useOverlay: Boolean = false,
    val onDeviceSelected: (AudioDeviceInfo?) -> Unit
) : Dialog(if (useOverlay) android.view.ContextThemeWrapper(c, R.style.Theme_AdbTools) else c) {
    init {
        if (useOverlay) {
            window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }
        setContentView(R.layout.dialog_outputs)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setGravity(Gravity.CENTER)
    }

    override fun show() {
        super.show()
        val list = findViewById<RecyclerView>(R.id.outputs)
        val data = devices.map { AudioOutputMap.formatDevice(it) }
        val adapter = SimpleAdapter(data) {
            dismiss()
            onDeviceSelected(devices[it])
        }
        list.adapter = adapter
        list.invalidate()
    }
}