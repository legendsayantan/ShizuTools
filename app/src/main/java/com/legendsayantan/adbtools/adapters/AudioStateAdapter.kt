package com.legendsayantan.adbtools.adapters

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.data.AudioState

/**
 * @author legendsayantan
 */
class AudioStateAdapter(val context: Context,private val data: HashMap<String, AudioState>, val onItemClick:(String,AudioState)->Unit) : RecyclerView.Adapter<AudioStateAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(R.layout.item_audiostate, parent, false)
        return ViewHolder(itemView)
    }

    private var dataList = data.entries.sortedBy { !it.value.muted && (it.value.focus == AudioState.Focus.ALLOWED) }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val currentItem = dataList[position].value
        holder.container.setOnClickListener { onItemClick(dataList[position].key,currentItem) }
        holder.appName.text = currentItem.name
        val txt = (if(currentItem.muted) "Muted, " else "") + when(currentItem.focus){
            AudioState.Focus.ALLOWED -> "MixedAudio disabled, interrupts media"
            AudioState.Focus.DENIED -> "MixedAudio enabled, forcing not to interrupt media"
            AudioState.Focus.IGNORED -> "MixedAudio enabled, does not interrupt media"
        }
        holder.appState.text = txt
        if(currentItem.muted){
            holder.image.setImageResource(R.drawable.round_volume_mute_24)
            holder.image.imageTintList = ColorStateList.valueOf(context.resources.getColor(R.color.red,null))
        }else when(currentItem.focus){
            AudioState.Focus.ALLOWED -> {
                holder.image.setImageResource(R.drawable.round_volume_up_24)
                holder.image.imageTintList = ColorStateList.valueOf(context.resources.getColor(R.color.green,null))
            }
            AudioState.Focus.DENIED -> {
                holder.image.setImageResource(R.drawable.round_volume_multi_force_24)
                holder.image.imageTintList = ColorStateList.valueOf(context.resources.getColor(R.color.red,null))
            }
            AudioState.Focus.IGNORED -> {
                holder.image.setImageResource(R.drawable.round_volume_multi_24)
                holder.image.imageTintList = ColorStateList.valueOf(context.resources.getColor(R.color.green,null))
            }
        }
    }

    override fun getItemCount(): Int {
        return data.size
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var container : LinearLayout = itemView.findViewById(R.id.container)
        var appName: TextView = itemView.findViewById(R.id.app_name)
        var appState: TextView = itemView.findViewById(R.id.app_state)
        var image: ImageView = itemView.findViewById(R.id.imageState)
    }
}
