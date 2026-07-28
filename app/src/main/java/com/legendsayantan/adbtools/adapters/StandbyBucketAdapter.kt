package com.legendsayantan.adbtools.adapters

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.legendsayantan.adbtools.AppItem
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.data.StandbyBuckets

class StandbyBucketAdapter(
    private val appList: List<AppItem>,
    private val onBucketChanged: (AppItem, Int) -> Unit,
    private val onLockToggled: (AppItem, Boolean) -> Unit
) : RecyclerView.Adapter<StandbyBucketAdapter.ViewHolder>() {

    // Values/names filtered to what this device's API level actually supports - dropped from the
    // picker on older devices so the user can't select a bucket the running OS doesn't understand.
    private val bucketValues: IntArray = StandbyBuckets.supportedValues()
    private val bucketNames: Array<String> = StandbyBuckets.supportedDisplayNames()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val appIcon: ImageView = view.findViewById(R.id.app_icon)
        val appName: TextView = view.findViewById(R.id.app_name)
        val appPackage: TextView = view.findViewById(R.id.app_package)
        val switchLock: MaterialSwitch = view.findViewById(R.id.switch_lock)
        val spinnerBucket: MaterialAutoCompleteTextView = view.findViewById(R.id.spinner_bucket)
        val cardRoot: MaterialCardView = view.findViewById(R.id.card_root)
        val root: View = view
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_standby_bucket, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = appList[position]
        val context = holder.itemView.context
        
        holder.appIcon.setImageDrawable(app.icon)
        holder.appName.text = app.name
        holder.appPackage.text = app.packageName
        
        // Setup AutoCompleteTextView (Exposed Dropdown)
        val adapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, bucketNames)
        holder.spinnerBucket.setAdapter(adapter)
        
        val selectedIndex = bucketValues.indexOf(app.bucket)
        if (selectedIndex >= 0) {
            holder.spinnerBucket.setText(bucketNames[selectedIndex], false)
        } else {
            holder.spinnerBucket.setText("Unknown (${app.bucket})", false)
        }
        updateCardColor(holder.cardRoot, app.bucket, false)
        
        holder.switchLock.setOnCheckedChangeListener(null)
        holder.switchLock.isChecked = app.isLocked
        
        holder.spinnerBucket.setOnItemClickListener { _, _, pos, _ ->
            val newBucket = bucketValues[pos]
            if (newBucket != app.bucket) {
                app.bucket = newBucket
                holder.itemView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onBucketChanged(app, newBucket)
                updateCardColor(holder.cardRoot, newBucket, true)
                
                // Spring animation for feedback
                val spring = SpringAnimation(holder.root, SpringAnimation.SCALE_X, 1f)
                spring.spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                spring.spring.dampingRatio = SpringForce.DAMPING_RATIO_HIGH_BOUNCY
                holder.root.scaleX = 0.95f
                spring.start()
                
                val springY = SpringAnimation(holder.root, SpringAnimation.SCALE_Y, 1f)
                springY.spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                springY.spring.dampingRatio = SpringForce.DAMPING_RATIO_HIGH_BOUNCY
                holder.root.scaleY = 0.95f
                springY.start()
            }
        }
        
        holder.switchLock.setOnCheckedChangeListener { _, isChecked ->
            app.isLocked = isChecked
            onLockToggled(app, isChecked)
        }
    }
    
    private fun updateCardColor(card: MaterialCardView, bucket: Int, animated: Boolean) {
        val context = card.context
        
        // Use a base color depending on state. For premium feel, use subtle tints.
        // We'll use color resources if available, but for now we can tint with parsed colors
        val targetColor = when(bucket) {
            5 -> Color.parseColor("#1A00BCD4") // Subtle Cyan for Exempted
            10 -> Color.parseColor("#1A4CAF50") // Subtle Green for Active
            40 -> Color.parseColor("#1AFF9800") // Subtle Orange for Rare
            45 -> Color.parseColor("#1AF44336") // Subtle Red for Restricted
            50 -> Color.parseColor("#1A9E9E9E") // Subtle Gray for Never
            else -> {
                // Default surface color (fetch from theme attribute)
                val typedValue = android.util.TypedValue()
                context.theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceVariant, typedValue, true)
                typedValue.data
            }
        }
        
        if (animated) {
            val startColor = card.cardBackgroundColor.defaultColor
            val colorAnimation = ValueAnimator.ofObject(ArgbEvaluator(), startColor, targetColor)
            colorAnimation.duration = 300 // ms
            colorAnimation.addUpdateListener { animator ->
                card.setCardBackgroundColor(animator.animatedValue as Int)
            }
            colorAnimation.start()
        } else {
            card.setCardBackgroundColor(targetColor)
        }
    }

    override fun getItemCount() = appList.size
}
