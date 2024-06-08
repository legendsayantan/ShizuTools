package com.legendsayantan.adbtools.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.legendsayantan.adbtools.R

/**
 * @author legendsayantan
 */
class VolumeBarAdapter(
    val context: Context,
    val data: HashMap<String, Float>,
    val onVolumeChanged: (String, Float) -> Unit,
    val onItemDetached: (String) -> Unit,
    val onSliderGet:(String,Int)->Float,
    val onSliderSet:(String,Int,Float)->Unit
) : RecyclerView.Adapter<VolumeBarAdapter.VolumeBarHolder>() {
    inner class VolumeBarHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image = itemView.findViewById<ImageView>(R.id.image)
        val volumeBar = itemView.findViewById<Slider>(R.id.volume)
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
        val currentItem = data.entries.elementAt(position)
        try {
            holder.image.setImageDrawable(
                context.packageManager.getApplicationIcon(currentItem.key)
            )
        } catch (_: Exception) {
        }
        holder.volumeBar.value = currentItem.value
        holder.volumeBar.addOnChangeListener { slider, value, fromUser ->
            onVolumeChanged(currentItem.key, value)
        }
        holder.expand.setOnClickListener {
            if (holder.expanded.visibility == View.VISIBLE) {
                holder.expanded.visibility = View.GONE
                holder.expand.animate().rotationX(0f)
            } else {
                holder.expanded.visibility = View.VISIBLE
                holder.expand.animate().rotationX(180f)
            }
        }

        holder.otherSliders.forEachIndexed { index, slider ->
            slider.value = onSliderGet(currentItem.key,index)
            slider.addOnChangeListener { s, value, fromUser ->
                onSliderSet(currentItem.key,index,value)
            }
        }

        //reset
        holder.resetBtns.forEachIndexed { index, imageView ->
            imageView.setOnClickListener {
                holder.otherSliders[index].value = if (index == 0) 0f else 50f
            }
        }

        //detach
        holder.detachBtn.setOnClickListener { onItemDetached(currentItem.key) }
    }
}