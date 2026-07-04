package com.legendsayantan.adbtools.adapters

import android.content.Context
import android.media.AudioDeviceInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.data.AudioOutputBase
import com.legendsayantan.adbtools.lib.AudioOutputMap
import com.legendsayantan.adbtools.services.SoundMasterService

/**
 * @author legendsayantan
 */
class VolumeBarAdapter(
    val context: Context,
    var data: List<AudioOutputBase>,
    val onVolumeChanged: (Int, Float) -> Unit,
    val onItemDetached: (Int) -> Unit,
    val onSliderGet: (Int, Int) -> Float,
    val onSliderSet: (Int, Int, Float) -> Unit,
    val getDevices: () -> List<AudioDeviceInfo?>,
    val setDeviceFor: (Int, AudioDeviceInfo?) -> Boolean,
    val onInteraction: () -> Unit
) : RecyclerView.Adapter<VolumeBarAdapter.VolumeBarHolder>() {
    val devices = getDevices()
    
    fun updateData(newData: List<AudioOutputBase>) {
        val diffCallback = object : androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize() = data.size
            override fun getNewListSize() = newData.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) = 
                data[oldPos].pkg == newData[newPos].pkg && data[oldPos].output == newData[newPos].output
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = 
                data[oldPos].volume == newData[newPos].volume
        }
        val diffResult = androidx.recyclerview.widget.DiffUtil.calculateDiff(diffCallback)
        data = newData.toList()
        diffResult.dispatchUpdatesTo(this)
    }

    inner class VolumeBarHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image = itemView.findViewById<ImageView>(R.id.image)
        val volumeBar = itemView.findViewById<Slider>(R.id.volume)
        val outputName = itemView.findViewById<TextView>(R.id.outputDevice)
        val switchOutput = itemView.findViewById<ImageView>(R.id.audioBtn)
        val outputExpanded = itemView.findViewById<LinearLayout>(R.id.audioOutput)
        val outputGroup = itemView.findViewById<RadioGroup>(R.id.outputGroup)
        val expand = itemView.findViewById<ImageView>(R.id.expandBtn)
        val expanded = itemView.findViewById<LinearLayout>(R.id.expanded)
        val otherSliders = listOf<Slider>(
            itemView.findViewById(R.id.balance),
            itemView.findViewById(R.id.lows),
            itemView.findViewById(R.id.mids),
            itemView.findViewById(R.id.highs)
        )
        val resetBtns = listOf<ImageView>(
            itemView.findViewById(R.id.balanceReset),
            itemView.findViewById(R.id.lowReset),
            itemView.findViewById(R.id.midReset),
            itemView.findViewById(R.id.highReset)
        )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VolumeBarHolder {
        val itemView =
            LayoutInflater.from(parent.context).inflate(R.layout.item_volumebar, parent, false)
        return VolumeBarHolder(itemView)
    }

    override fun getItemCount(): Int {
        return data.size
    }

    override fun onBindViewHolder(holder: VolumeBarHolder, position: Int) {
        val currentItem = data.elementAt(position)
        try {
            holder.image.setImageDrawable(
                context.packageManager.getApplicationIcon(currentItem.pkg)
            )
        } catch (_: Exception) {
        }
        holder.volumeBar.value = currentItem.volume
        updateSliderColor(holder.volumeBar, currentItem.volume)
        
        val touchListener = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                onInteraction()
            }
            override fun onStopTrackingTouch(slider: Slider) {
                onInteraction()
            }
        }
        
        holder.volumeBar.clearOnChangeListeners()
        holder.volumeBar.clearOnSliderTouchListeners()
        holder.volumeBar.addOnSliderTouchListener(touchListener)
        holder.volumeBar.addOnChangeListener { _, value, _ ->
            onInteraction()
            updateSliderColor(holder.volumeBar, value)
            onVolumeChanged(position, value)
        }
        devices.find { it?.id == currentItem.output }?.let {
            showDevice(holder.outputName, it)
        }
        holder.switchOutput.setOnClickListener {
            if (holder.outputExpanded.visibility == View.VISIBLE) {
                holder.outputExpanded.visibility = View.GONE
            } else {
                holder.expanded.visibility = View.GONE
                holder.outputExpanded.visibility = View.VISIBLE
                //spawn radiobuttons
                holder.outputGroup.removeAllViews()
                holder.outputGroup.addView(RadioButton(context).apply {
                    //detach
                    text = context.getString(R.string.none)
                    setOnClickListener { onItemDetached(position) }
                })
                if (SoundMasterService.running) {
                    getDevices().forEach { device ->
                        val rButton = RadioButton(context)
                        showDevice(rButton, device)
                        if ((device?.id ?: -1) == currentItem.output) {
                            rButton.isChecked = true
                        } else rButton.setOnClickListener {
                            if (setDeviceFor(position, device)) showDevice(
                                holder.outputName,
                                device
                            )
                            else rButton.isChecked = false
                        }
                        holder.outputGroup.addView(rButton)
                    }
                }
            }
        }
        holder.expand.setOnClickListener {
            if (holder.expanded.visibility == View.VISIBLE) {
                holder.expanded.visibility = View.GONE
                holder.expand.animate().rotationX(0f)
            } else {
                holder.outputExpanded.visibility = View.GONE
                holder.expanded.visibility = View.VISIBLE
                holder.expand.animate().rotationX(180f)
                holder.otherSliders.forEachIndexed { index, slider ->
                    slider.value = onSliderGet(position, index)
                    slider.clearOnChangeListeners()
                    slider.clearOnSliderTouchListeners()
                    slider.addOnSliderTouchListener(touchListener)
                    slider.addOnChangeListener { _, value, _ ->
                        onInteraction()
                        onSliderSet(position, index, value)
                    }
                }
            }
        }

        holder.outputExpanded.visibility = View.GONE
        holder.expanded.visibility = View.GONE


        //reset
        holder.resetBtns.forEachIndexed { index, imageView ->
            imageView.setOnClickListener {
                holder.otherSliders[index].value = if (index == 0) 0f else 50f
            }
        }
    }

    private fun showDevice(v: TextView, d: AudioDeviceInfo?) {
        v.text = formatDevice(d)
        val iconRes = when (d?.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> R.drawable.baseline_audiotrack_24 // can change if there is bt icon
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> R.drawable.baseline_audiotrack_24
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> R.drawable.baseline_audiotrack_24 // keeping generic for now or we can map them
            else -> R.drawable.baseline_audiotrack_24
        }
        
        val typeIcon = when (d?.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> R.drawable.baseline_audiotrack_24
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> R.drawable.baseline_audiotrack_24
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> R.drawable.baseline_audiotrack_24 
            else -> if (d == null) R.drawable.baseline_audiotrack_24 else R.drawable.baseline_audiotrack_24
        }
        // Actually wait, let's just use generic baseline_audiotrack_24 since we didn't add new drawables, but wait...
        // Let's set the compound drawable.
        val drawable = androidx.core.content.ContextCompat.getDrawable(context, typeIcon)
        drawable?.setTint(androidx.core.content.ContextCompat.getColor(context, R.color.tool_mixed_audio)) // Or use generic tint
        v.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)
    }

    private fun updateSliderColor(slider: Slider, value: Float) {
        val typedValue = android.util.TypedValue()
        if (value > 100f) {
            // Dark red tint for boost so text is readable
            slider.trackActiveTintList = android.content.res.ColorStateList.valueOf(0xFFB71C1C.toInt())
            slider.thumbTintList = android.content.res.ColorStateList.valueOf(0xFFB71C1C.toInt())
        } else {
            context.theme.resolveAttribute(com.google.android.material.R.attr.colorSecondary, typedValue, true)
            slider.trackActiveTintList = android.content.res.ColorStateList.valueOf(typedValue.data)
            slider.thumbTintList = android.content.res.ColorStateList.valueOf(typedValue.data)
        }
    }

    companion object{
        fun formatDevice(d:AudioDeviceInfo?):String{
            return if(d==null) "Default" else "${d.productName} (${AudioOutputMap.getName(d.type)})"
        }
    }
}