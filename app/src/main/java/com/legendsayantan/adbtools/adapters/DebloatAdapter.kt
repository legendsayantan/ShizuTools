package com.legendsayantan.adbtools.adapters

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textview.MaterialTextView
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.data.AppData
import com.legendsayantan.adbtools.lib.Utils.Companion.removeUrls

class DebloatAdapter(
    private val activity: Activity,
    private val dataList: HashMap<String, AppData>,
    private val onItemClick: (String, AppData) -> Unit,
    private val onItemLongClick: (String, AppData) -> Unit,
    private val isBatchMode: () -> Boolean,
    private val isSelected: (String) -> Boolean
) : RecyclerView.Adapter<DebloatAdapter.ViewHolder>() {

    private val entries = dataList.entries.toList()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val appNameTextView: MaterialTextView = view.findViewById(R.id.app_name)
        val listModeTextView: MaterialTextView = view.findViewById(R.id.listMode)
        val disabledBadge: MaterialTextView = view.findViewById(R.id.disabled_badge)
        val descriptionTextView: MaterialTextView = view.findViewById(R.id.Description)
        val severityStrip: View = view.findViewById(R.id.severity_strip)
        val root: MaterialCardView = view.findViewById(R.id.background)
        val checkbox: MaterialCheckBox = view.findViewById(R.id.batch_checkbox)
        val actionIcon: ImageView = view.findViewById(R.id.action_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_debloat, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        val pkg = entry.key
        val app = entry.value

        val desc = if (app.description.isNotEmpty()) {
            app.description.replace("\n\n", "\n").removeUrls()
        } else {
            pkg
        }

        holder.appNameTextView.text = app.name
        holder.listModeTextView.text = if (app.list.isNullOrEmpty()) "Third-party" else app.list
        holder.descriptionTextView.text = desc

        val colorRes = when (app.removal) {
            "Recommended" -> R.color.green
            "Advanced" -> R.color.yellow
            "Expert" -> R.color.red
            else -> R.color.transparent
        }
        val color = ContextCompat.getColor(activity, colorRes)
        holder.severityStrip.setBackgroundColor(color)

        // Handle disabled state
        if (app.isDisabled) {
            holder.disabledBadge.visibility = View.VISIBLE
            holder.appNameTextView.alpha = 0.5f
            holder.descriptionTextView.alpha = 0.5f
        } else {
            holder.disabledBadge.visibility = View.GONE
            holder.appNameTextView.alpha = 1f
            holder.descriptionTextView.alpha = 1f
        }

        // Handle batch mode
        val batchMode = isBatchMode()
        if (batchMode) {
            holder.checkbox.visibility = View.VISIBLE
            holder.actionIcon.visibility = View.GONE
            holder.checkbox.isChecked = isSelected(pkg)
        } else {
            holder.checkbox.visibility = View.GONE
            holder.actionIcon.visibility = View.VISIBLE
            holder.checkbox.isChecked = false
        }

        holder.root.setOnClickListener {
            onItemClick(pkg, app)
        }
        
        holder.root.setOnLongClickListener {
            onItemLongClick(pkg, app)
            true
        }
    }

    override fun getItemCount(): Int = entries.size
}
