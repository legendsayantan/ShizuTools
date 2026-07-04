package com.legendsayantan.adbtools.bottomsheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.data.AudioState
import com.legendsayantan.adbtools.lib.ShizukuRunner

class AudioStateBottomSheet(
    private val packageName: String,
    private val state: AudioState,
    private val onCommandComplete: (String) -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_audio_state, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialTextView>(R.id.dialogTitle).text = state.name

        val container = view.findViewById<LinearLayout>(R.id.buttonContainer)
        
        val btns = linkedMapOf(
            "Mute app audio" to Pair(28, 2),
            "Unmute app audio" to Pair(28, 0),
            "Disable MixedAudio" to Pair(32, 0),
            "Enable MixedAudio" to Pair(32, 1),
            "Force Enable MixedAudio" to Pair(32, 2)
        )
        btns.remove(btns.keys.toList()[if (state.muted) 0 else 1])
        btns.remove(btns.keys.toList()[state.focus.ordinal + 1])

        btns.forEach { (text, cmd) ->
            val btn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonStyle).apply {
                this.text = text
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 16)
                }
                setOnClickListener {
                    val uid = requireContext().packageManager.getPackageInfo(packageName, 0).applicationInfo?.uid ?: -1
                    com.legendsayantan.adbtools.lib.ShizuToolsController.execute { controller ->
                        controller.setAppOpMode(packageName, uid, cmd.first, cmd.second)
                        requireActivity().runOnUiThread {
                            onCommandComplete("Success")
                            dismiss()
                        }
                    }
                }
            }
            container.addView(btn)
        }
    }
}
