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
    val data: List<AudioOutputBase>,
    val onVolumeChanged: (Int, Float) -> Unit,
    val onItemDetached: (Int) -> Unit,
    val onSliderGet: (Int, Int) -> Float,
    val onSliderSet: (Int, Int, Float) -> Unit,
    val getDevices: () -> List<AudioDeviceInfo?>,
    val setDeviceFor: (Int, AudioDeviceInfo?) -> Unit
) : RecyclerView.Adapter<VolumeBarAdapter.VolumeBarHolder>() {
    val devices = getDevices()

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
        val detachBtn = itemView.findViewById<MaterialCardView>(R.id.detachBtn)
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
        holder.volumeBar.addOnChangeListener { _, value, _ ->
            onVolumeChanged(position, value)
        }
        devices.find { it?.id == currentItem.outputDevice }?.let {
            showDevice(holder.outputName, it)
        }
        holder.switchOutput.setOnClickListener {
            if (holder.outputExpanded.visibility == View.VISIBLE) {
                holder.outputExpanded.visibility = View.GONE
            } else if (SoundMasterService.running) {
                holder.expanded.visibility = View.GONE
                holder.outputExpanded.visibility = View.VISIBLE
                //spawn radiobuttons
                holder.outputGroup.removeAllViews()
                getDevices().forEach { device ->
                    if ((device?.id?:-1) != currentItem.outputDevice){
                        val rButton = RadioButton(context)
                        showDevice(rButton, device)
                        rButton.setOnClickListener {
                            setDeviceFor(position, device)
                            showDevice(holder.outputName, device)
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
                    slider.addOnChangeListener { s, value, fromUser ->
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

        //detach
        holder.detachBtn.setOnClickListener { onItemDetached(position) }


    }

    private fun showDevice(v: TextView, d: AudioDeviceInfo?) {
        v.text = formatDevice(d)
    }
    companion object{
        fun formatDevice(d:AudioDeviceInfo?):String{
            return if(d==null) "Default" else "${d.productName} (${AudioOutputMap.getName(d.type)})"
        }
    }
}