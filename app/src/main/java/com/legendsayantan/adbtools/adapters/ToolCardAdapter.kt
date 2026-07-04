package com.legendsayantan.adbtools.adapters

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

import com.google.android.material.card.MaterialCardView
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.data.ToolCard

class ToolCardAdapter(
    private val items: List<Any>,
    private val onClick: (ToolCard) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val animators = mutableMapOf<String, ObjectAnimator>()

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_TOOL = 1
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.header_title)
    }

    class ToolViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: MaterialCardView = view as MaterialCardView
        val accentStripe: View = view.findViewById(R.id.accent_stripe)
        val icon: ImageView = view.findViewById(R.id.tool_icon)
        val name: TextView = view.findViewById(R.id.tool_name)
        val desc: TextView = view.findViewById(R.id.tool_desc)
        val activeDot: ImageView = view.findViewById(R.id.active_dot)
        val betaChip: TextView = view.findViewById(R.id.beta_chip)
    }

    override fun getItemViewType(position: Int): Int {
        return if (items[position] is String) VIEW_TYPE_HEADER else VIEW_TYPE_TOOL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_header, parent, false))
        } else {
            ToolViewHolder(inflater.inflate(R.layout.item_tool_card, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderViewHolder) {
            holder.title.text = items[position] as String

        } else if (holder is ToolViewHolder) {
            val item = items[position] as ToolCard
            val ctx = holder.itemView.context

            holder.name.setText(item.nameRes)
            holder.desc.setText(item.descRes)
            holder.icon.setImageResource(item.iconRes)
            
            val accentColor = ContextCompat.getColor(ctx, item.accentColorRes)
            holder.accentStripe.setBackgroundColor(accentColor)
            holder.icon.imageTintList = ColorStateList.valueOf(accentColor)


            if (item.id == "pip") {
                holder.betaChip.visibility = View.VISIBLE
            } else {
                holder.betaChip.visibility = View.GONE
            }

            holder.root.setOnClickListener { onClick(item) }

            updateActiveState(holder, item)
        }
    }

    private fun updateActiveState(holder: ToolViewHolder, item: ToolCard) {
        val isActive = item.isServiceActive()
        if (isActive) {
            holder.activeDot.visibility = View.VISIBLE
            holder.root.strokeColor = ContextCompat.getColor(holder.itemView.context, R.color.colorSecondary)
            holder.root.strokeWidth = holder.itemView.resources.getDimensionPixelSize(R.dimen.stroke_gold)
            
            if (!animators.containsKey(item.id)) {
                val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.3f)
                val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.3f)
                val anim = ObjectAnimator.ofPropertyValuesHolder(holder.activeDot, scaleX, scaleY).apply {
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    duration = 600
                }
                animators[item.id] = anim
                anim.start()
            }
        } else {
            holder.activeDot.visibility = View.GONE
            holder.root.strokeColor = ContextCompat.getColor(holder.itemView.context, R.color.colorOutline)
            holder.root.strokeWidth = 2 // 1dp approximation
            animators[item.id]?.cancel()
            animators.remove(item.id)
        }
    }

    fun refreshActiveStates() {
        notifyItemRangeChanged(0, itemCount)
    }

    override fun getItemCount() = items.size
}
