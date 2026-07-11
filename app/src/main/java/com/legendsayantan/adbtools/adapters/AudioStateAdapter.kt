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
    private val onQuickAction: (Int, String, AudioState, String) -> Unit // position, pkg, state, action
) : RecyclerView.Adapter<AudioStateAdapter.ViewHolder>() {

    private val entries = appsMap.entries.toList()
    private var packageList: List<String>? = null
    
    // Check if SoundMaster is running and get its active packages
    private val isSoundMasterRunning = SoundMasterService.running
    private val soundMasterPackages = SoundMasterService.apps.map { it.pkg }.toList()

    init {
        loadApps(callback = {
            packageList = it
            activity.runOnUiThread { notifyDataSetChanged() }
        }, errorCallback = {})
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: MaterialCardView = view.findViewById(R.id.background)
        val nameTextView: MaterialTextView = view.findViewById(R.id.name)

        val iconImageView: ImageView = view.findViewById(R.id.icon)
        val warningContainer: LinearLayout = view.findViewById(R.id.warning_container)
        val btnQuickMute: MaterialButton = view.findViewById(R.id.btn_quick_mute)
        val btnQuickMixed: MaterialButton = view.findViewById(R.id.btn_quick_mixed)
        val accentStrip: View = view.findViewById(R.id.accent_strip)
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
        
        when (audioState.focus) {
            AudioState.Focus.ALLOWED -> {
                holder.btnQuickMixed.setIconResource(R.drawable.baseline_audiotrack_24)
                val typedValue = android.util.TypedValue()
                activity.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true)
                holder.btnQuickMixed.iconTint = android.content.res.ColorStateList.valueOf(typedValue.data)
            }
            AudioState.Focus.IGNORED -> {
                holder.btnQuickMixed.setIconResource(R.drawable.ic_audio_mixed)
                holder.btnQuickMixed.iconTint = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.tool_mixed_audio))
            }
            AudioState.Focus.DENIED -> {
                holder.btnQuickMixed.setIconResource(R.drawable.ic_audio_forced)
                holder.btnQuickMixed.iconTint = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.tool_mixed_audio))
            }
        }
        
        // Dynamic Accent Strip Color based on 6 unique combinations
        val accentColorHex = if (audioState.muted) {
            when (audioState.focus) {
                AudioState.Focus.ALLOWED -> "#B5341A" // Red
                AudioState.Focus.IGNORED -> "#F2A2DA" // Pink
                AudioState.Focus.DENIED -> "#82A0BC"  // Blue-Grey
            }
        } else {
            when (audioState.focus) {
                AudioState.Focus.ALLOWED -> "#96A192" // Grey
                AudioState.Focus.IGNORED -> "#A3D9A5" // Green
                AudioState.Focus.DENIED -> "#E09B22"  // Orange
            }
        }
        holder.accentStrip.setBackgroundColor(android.graphics.Color.parseColor(accentColorHex))

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
            onQuickAction(holder.adapterPosition, pkg, audioState, "MUTE_TOGGLE")
        }
        
        holder.btnQuickMixed.setOnClickListener {
            onQuickAction(holder.adapterPosition, pkg, audioState, "MIXED_TOGGLE")
        }
    }

    override fun getItemCount(): Int = entries.size
}
