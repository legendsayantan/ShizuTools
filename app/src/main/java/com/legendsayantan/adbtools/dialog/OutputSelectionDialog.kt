package com.legendsayantan.adbtools.dialog

import android.app.Dialog
import android.content.Context
import android.media.AudioDeviceInfo
import android.view.Gravity
import androidx.recyclerview.widget.RecyclerView
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.adapters.SimpleAdapter
import com.legendsayantan.adbtools.adapters.VolumeBarAdapter

/**
 * @author legendsayantan
 */
class OutputSelectionDialog(
    c: Context,
    private val devices: List<AudioDeviceInfo?>,
    val onDeviceSelected: (AudioDeviceInfo?) -> Unit
) : Dialog(c) {
    init {
        setContentView(R.layout.dialog_outputs)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setGravity(Gravity.CENTER)
    }

    override fun show() {
        super.show()
        val list = findViewById<RecyclerView>(R.id.outputs)
        val data = devices.map { VolumeBarAdapter.formatDevice(it) }
        val adapter = SimpleAdapter(data) {
            dismiss()
            onDeviceSelected(devices[it])
        }
        list.adapter = adapter
        list.invalidate()
    }
}