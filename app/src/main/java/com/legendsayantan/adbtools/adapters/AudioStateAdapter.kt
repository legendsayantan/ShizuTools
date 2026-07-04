package com.legendsayantan.adbtools.adapters

import android.app.Activity
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.data.AudioState
import com.legendsayantan.adbtools.lib.Utils.Companion.loadApps
import com.legendsayantan.adbtools.services.SoundMasterService

class AudioStateAdapter(
    private val activity: Activity,
    private val appsMap: HashMap<String, AudioState>,
    private val onQuickAction: (String, AudioState, String) -> Unit // action can be "MUTE_TOGGLE" or "MIXED_TOGGLE"
) : RecyclerView.Adapter<AudioStateAdapter.ViewHolder>() {

    private val entries = appsMap.entries.toList()
    private var packageList: List<String>? = null
    
    // Check if SoundMaster is running and get its active packages
    private val isSoundMasterRunning = SoundMasterService.running
    private val soundMasterPackages = SoundMasterService.startingIntent?.getStringArrayExtra("packages")?.toList() ?: emptyList()

    init {
        loadApps(callback = {
            packageList = it
            activity.runOnUiThread { notifyDataSetChanged() }
        }, errorCallback = {})
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: MaterialCardView = view.findViewById(R.id.background)
        val nameTextView: MaterialTextView = view.findViewById(R.id.name)
        val stateTextView: MaterialTextView = view.findViewById(R.id.state)
        val iconImageView: ImageView = view.findViewById(R.id.icon)
        val warningContainer: LinearLayout = view.findViewById(R.id.warning_container)
        val btnQuickMute: MaterialButton = view.findViewById(R.id.btn_quick_mute)
        val btnQuickMixed: MaterialButton = view.findViewById(R.id.btn_quick_mixed)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_audiostate, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        val pkg = entry.key
        val audioState = entry.value

        holder.nameTextView.text = audioState.name

        val stateText = StringBuilder()
        if (audioState.muted) {
            stateText.append("Muted")
        } else {
            stateText.append("Not Muted")
        }
        stateText.append(" | ")
        when (audioState.focus) {
            AudioState.Focus.ALLOWED -> stateText.append("MixedAudio Disabled")
            AudioState.Focus.IGNORED -> stateText.append("MixedAudio Enabled")
            AudioState.Focus.DENIED -> stateText.append("MixedAudio Force-Enabled")
        }
        holder.stateTextView.text = stateText.toString()

        val color = if (audioState.muted || audioState.focus != AudioState.Focus.ALLOWED) {
            ContextCompat.getColor(activity, R.color.tool_mixed_audio)
        } else {
            Color.GRAY
        }
        holder.stateTextView.setTextColor(color)

        try {
            val icon = activity.packageManager.getApplicationIcon(pkg)
            holder.iconImageView.setImageDrawable(icon)
            holder.iconImageView.imageTintList = null // Remove tint for app icons

            if (packageList != null && !packageList!!.contains(pkg)) {
                val matrix = ColorMatrix()
                matrix.setSaturation(0f)
                holder.iconImageView.colorFilter = ColorMatrixColorFilter(matrix)
            } else {
                holder.iconImageView.clearColorFilter()
            }
        } catch (e: Exception) {
            holder.iconImageView.setImageResource(R.mipmap.ic_launcher)
            holder.iconImageView.clearColorFilter()
        }
        
        // UX 4.4: Quick toggle chips state
        if (audioState.muted) {
            holder.btnQuickMute.setIconResource(R.drawable.round_volume_mute_24)
            holder.btnQuickMute.iconTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#E53935")) // Red
        } else {
            holder.btnQuickMute.setIconResource(R.drawable.round_volume_up_24)
            val typedValue = android.util.TypedValue()
            activity.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true)
            holder.btnQuickMute.iconTint = android.content.res.ColorStateList.valueOf(typedValue.data)
        }
        
        if (audioState.focus != AudioState.Focus.ALLOWED) {
            holder.btnQuickMixed.setIconResource(R.drawable.round_volume_multi_force_24)
            holder.btnQuickMixed.iconTint = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.tool_mixed_audio))
        } else {
            holder.btnQuickMixed.setIconResource(R.drawable.round_volume_multi_24)
            val typedValue = android.util.TypedValue()
            activity.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true)
            holder.btnQuickMixed.iconTint = android.content.res.ColorStateList.valueOf(typedValue.data)
        }

        // UX 4.5: SoundMaster indicator & disabling controls
        val controlledBySM = isSoundMasterRunning && soundMasterPackages.contains(pkg)
        if (controlledBySM) {
            holder.warningContainer.visibility = View.VISIBLE
            holder.btnQuickMute.isEnabled = false
            holder.btnQuickMixed.isEnabled = false
            holder.btnQuickMute.alpha = 0.5f
            holder.btnQuickMixed.alpha = 0.5f
        } else {
            holder.warningContainer.visibility = View.GONE
            holder.btnQuickMute.isEnabled = true
            holder.btnQuickMixed.isEnabled = true
            holder.btnQuickMute.alpha = 1.0f
            holder.btnQuickMixed.alpha = 1.0f
        }

        holder.btnQuickMute.setOnClickListener {
            onQuickAction(pkg, audioState, "MUTE_TOGGLE")
        }
        
        holder.btnQuickMixed.setOnClickListener {
            onQuickAction(pkg, audioState, "MIXED_TOGGLE")
        }
    }

    override fun getItemCount(): Int = entries.size
}
