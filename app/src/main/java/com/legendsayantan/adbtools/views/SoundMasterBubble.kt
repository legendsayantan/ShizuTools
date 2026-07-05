package com.legendsayantan.adbtools.views

import android.animation.LayoutTransition
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.switchmaterial.SwitchMaterial
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.data.AudioOutputBase
import com.legendsayantan.adbtools.data.AudioOutputKey
import com.legendsayantan.adbtools.lib.SoundMasterPreferences
import com.legendsayantan.adbtools.services.SoundMasterService
import kotlin.math.abs

class SoundMasterBubble(private val service: SoundMasterService) {
    
    enum class State { HIDDEN, BUBBLE, MINI, EXPANDED }
    
    private var currentState = State.HIDDEN
    private var isAttached = false
    
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val themedContext = android.view.ContextThemeWrapper(service, R.style.Theme_AdbTools)
    private val inflater = LayoutInflater.from(themedContext)
    
    // Main container added to WindowManager
    private val container = FrameLayout(themedContext).apply {
        layoutTransition = LayoutTransition() // Simple animate layout changes
    }
    
    private val bubbleView = inflater.inflate(R.layout.overlay_soundmaster_bubble, container, false)
    private val miniView = inflater.inflate(R.layout.overlay_soundmaster_mini, container, false)
    private val expandedView = inflater.inflate(R.layout.overlay_soundmaster_expanded, container, false)
    
    private val rmsMeter: RmsMeterView = bubbleView.findViewById(R.id.rms_meter)
    private val switchDsp: SwitchMaterial = expandedView.findViewById(R.id.switch_dsp)
    
    private var layoutParams: WindowManager.LayoutParams
    
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    
    private var springX: SpringAnimation? = null
    private var springY: SpringAnimation? = null
    
    private val xProperty = object : FloatPropertyCompat<WindowManager.LayoutParams>("x") {
        override fun getValue(params: WindowManager.LayoutParams): Float = params.x.toFloat()
        override fun setValue(params: WindowManager.LayoutParams, value: Float) {
            params.x = value.toInt()
            try { windowManager.updateViewLayout(container, params) } catch (e: Exception) {}
        }
    }
    
    private val yProperty = object : FloatPropertyCompat<WindowManager.LayoutParams>("y") {
        override fun getValue(params: WindowManager.LayoutParams): Float = params.y.toFloat()
        override fun setValue(params: WindowManager.LayoutParams, value: Float) {
            params.y = value.toInt()
            try { windowManager.updateViewLayout(container, params) } catch (e: Exception) {}
        }
    }

    init {
        val savedPos = SoundMasterPreferences.loadBubblePosition(service)
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (savedPos.first != -1) savedPos.first else 100
            y = if (savedPos.second != -1) savedPos.second else 200
        }

        setupBubbleTouchListener()
        setupClickListeners()
        setupModeSwitch()
        setupOutsideTouchListener()
        
        container.addView(bubbleView)
        container.addView(miniView)
        container.addView(expandedView)
        
        hideAll()
    }

    private fun setupOutsideTouchListener() {
        container.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                if (currentState == State.MINI || currentState == State.EXPANDED) {
                    transitionTo(State.BUBBLE)
                }
                true
            } else {
                false
            }
        }
    }

    fun show(state: State) {
        if (!isAttached) {
            try {
                windowManager.addView(container, layoutParams)
                isAttached = true
            } catch (e: Exception) {
                e.printStackTrace()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(service, "Overlay permission required!", android.widget.Toast.LENGTH_LONG).show()
                }
                return // E.g., no SYSTEM_ALERT_WINDOW permission
            }
        }
        transitionTo(state)
    }

    fun hide() {
        if (isAttached) {
            try {
                windowManager.removeView(container)
                isAttached = false
            } catch (e: Exception) {}
        }
        currentState = State.HIDDEN
    }

    fun updateRms(rms: Float) {
        if (currentState == State.BUBBLE) {
            rmsMeter.updateRms(rms)
        }
    }

    private fun transitionTo(state: State) {
        currentState = state
        hideAll()
        when (state) {
            State.BUBBLE -> {
                bubbleView.visibility = View.VISIBLE
                updateLayoutParamsForState(focusable = false)
            }
            State.MINI -> {
                miniView.visibility = View.VISIBLE
                updateLayoutParamsForState(focusable = true)
                populateSliders()
            }
            State.EXPANDED -> {
                expandedView.visibility = View.VISIBLE
                updateLayoutParamsForState(focusable = true)
            }
            State.HIDDEN -> hide()
        }
    }

    private fun hideAll() {
        bubbleView.visibility = View.GONE
        miniView.visibility = View.GONE
        expandedView.visibility = View.GONE
    }

    private fun updateLayoutParamsForState(focusable: Boolean) {
        if (focusable) {
            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        }
        try {
            windowManager.updateViewLayout(container, layoutParams)
        } catch (e: Exception) {}
    }

    private fun setupBubbleTouchListener() {
        bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    springX?.cancel()
                    springY?.cancel()
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(container, layoutParams)
                    } catch (e: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moveDistance = abs(event.rawX - initialTouchX) + abs(event.rawY - initialTouchY)
                    if (moveDistance < 10) {
                        transitionTo(State.MINI)
                    } else {
                        val metrics = windowManager.currentWindowMetrics.bounds
                        val screenWidth = metrics.width()
                        
                        val targetX = if (layoutParams.x + bubbleView.width / 2 < screenWidth / 2) 0 else screenWidth - bubbleView.width
                        
                        springX?.cancel()
                        springX = SpringAnimation(layoutParams, xProperty).apply {
                            spring = SpringForce(targetX.toFloat()).apply {
                                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                                stiffness = SpringForce.STIFFNESS_LOW
                            }
                            addEndListener { _, _, _, _ ->
                                SoundMasterPreferences.saveBubblePosition(service, layoutParams.x, layoutParams.y)
                            }
                            start()
                        }
                        
                        val targetY = layoutParams.y.coerceIn(0, metrics.height() - bubbleView.height)
                        springY?.cancel()
                        springY = SpringAnimation(layoutParams, yProperty).apply {
                            spring = SpringForce(targetY.toFloat()).apply {
                                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                                stiffness = SpringForce.STIFFNESS_MEDIUM
                            }
                            start()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupClickListeners() {
        miniView.findViewById<View>(R.id.btn_more).setOnClickListener {
            transitionTo(State.EXPANDED)
        }
        expandedView.findViewById<ImageButton>(R.id.btn_exp_close).setOnClickListener {
            transitionTo(State.BUBBLE)
        }
        expandedView.findViewById<ImageButton>(R.id.btn_stop_engine)?.setOnClickListener {
            service.stopSelf()
        }
        expandedView.findViewById<View>(R.id.btn_stop_all).setOnClickListener {
            hide()
            service.stopSelf()
        }
        expandedView.findViewById<View>(R.id.dsp_expand_container).setOnClickListener {
            val dspContainer = expandedView.findViewById<View>(R.id.dsp_container)
            val icon = expandedView.findViewById<ImageView>(R.id.dsp_expand_icon)
            if (dspContainer.visibility == View.VISIBLE) {
                dspContainer.visibility = View.GONE
                icon.animate().rotation(0f).setDuration(200).start()
            } else {
                dspContainer.visibility = View.VISIBLE
                icon.animate().rotation(180f).setDuration(200).start()
            }
        }
    }

    private fun setupModeSwitch() {
        val isDsp = SoundMasterPreferences.isAdvancedDspMode(service)
        switchDsp.isChecked = isDsp
        updateDspVisibility(isDsp)

        switchDsp.setOnCheckedChangeListener { _, isChecked ->
            SoundMasterPreferences.setAdvancedDspMode(service, isChecked)
            updateDspVisibility(isChecked)
            // Notify service to restart routing logic based on new mode
            service.onModeChanged(isChecked)
        }
    }

    private fun updateDspVisibility(isDsp: Boolean) {
        val dspExpandContainer = expandedView.findViewById<View>(R.id.dsp_expand_container)
        val dspContainer = expandedView.findViewById<View>(R.id.dsp_container)
        if (isDsp) {
            dspExpandContainer.visibility = View.VISIBLE
            dspContainer.visibility = View.GONE
            expandedView.findViewById<ImageView>(R.id.dsp_expand_icon).rotation = 0f
        } else {
            dspExpandContainer.visibility = View.GONE
            dspContainer.visibility = View.GONE
        }
    }
    
    fun updateBubbleAppIcon(uids: IntArray?) {
        val pm = service.packageManager
        val pkgs = uids?.toList()?.mapNotNull { pm.getPackagesForUid(it)?.firstOrNull() }?.distinct() ?: emptyList()
        val appIconView = bubbleView.findViewById<ImageView>(R.id.app_icon)
        service.mainHandler.post {
            if (pkgs.isNotEmpty()) {
                try {
                    appIconView.setImageDrawable(pm.getApplicationIcon(pkgs.first()))
                } catch(e:Exception){}
            } else {
                appIconView.setImageResource(R.mipmap.ic_launcher)
            }
        }
    }

    private fun populateSliders() {
        com.legendsayantan.adbtools.lib.ShizuToolsController.execute { shizu ->
            val uids = shizu.activeAudioUids ?: return@execute
            val pm = service.packageManager
            val newPkgs = uids.toList().mapNotNull { pm.getPackagesForUid(it)?.firstOrNull() }.distinct()
            
            service.mainHandler.post {
                val container = miniView.findViewById<LinearLayout>(R.id.mini_sliders_container)
                container.removeAllViews()
                
                if (newPkgs.isEmpty()) {
                    transitionTo(State.BUBBLE)
                    return@post
                }
                
                newPkgs.forEach { pkg ->
                    val itemView = inflater.inflate(R.layout.item_mini_slider, container, false)
                    val iconView = itemView.findViewById<ImageView>(R.id.app_icon)
                    val slider = itemView.findViewById<SeekBar>(R.id.volume_slider)
                    
                    try { iconView.setImageDrawable(pm.getApplicationIcon(pkg)) } catch(e:Exception){}
                    
                    val prefs = service.getSharedPreferences("soundmaster_vols", Context.MODE_PRIVATE)
                    val savedVol = prefs.getFloat(pkg, 1.0f)
                    val initialVol = if (SoundMasterPreferences.isAdvancedDspMode(service)) {
                        SoundMasterService.getVolumeOf(AudioOutputKey(pkg, -1))
                    } else {
                        savedVol * 100f
                    }
                    slider.progress = initialVol.toInt()
                    
                    slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) {
                            if (p2) {
                                if (SoundMasterPreferences.isAdvancedDspMode(service)) {
                                    SoundMasterService.setVolumeOf(AudioOutputKey(pkg, -1), p1.toFloat())
                                } else {
                                    prefs.edit().putFloat(pkg, p1 / 100f).apply()
                                    com.legendsayantan.adbtools.lib.ShizuToolsController.execute { s ->
                                        try {
                                            val uid = pm.getApplicationInfo(pkg, 0).uid
                                            s.setPlayerVolume(uid, p1 / 100f)
                                        } catch(e:Exception){}
                                    }
                                }
                            }
                        }
                        override fun onStartTrackingTouch(p0: SeekBar?) {}
                        override fun onStopTrackingTouch(p0: SeekBar?) {}
                    })
                    container.addView(itemView)
                }
            }
        }
    }
}
