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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.materialswitch.MaterialSwitch
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.data.AudioOutputBase
import com.legendsayantan.adbtools.data.AudioOutputKey
import com.legendsayantan.adbtools.lib.SoundMasterPreferences
import com.legendsayantan.adbtools.services.SoundMasterService
import kotlin.math.abs

class SoundMasterBubble(private val service: SoundMasterService) {
    
    enum class State { HIDDEN, BUBBLE, MINI, EXPANDED }
    
    var currentState = State.HIDDEN
    private val bubblePaddingPx by lazy {
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f, service.resources.displayMetrics).toInt()
    }
    private var isBubbleAttached = false
    private var isMiniAttached = false
    private var isExpandedAttached = false
    
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val themedContext = android.view.ContextThemeWrapper(service, R.style.Theme_AdbTools)
    private val inflater = LayoutInflater.from(themedContext)
    
    private val bubbleContainer = object : FrameLayout(themedContext) {
        override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
            service.extendTimeout()
            return super.dispatchTouchEvent(ev)
        }
    }
    
    private val miniContainer = object : FrameLayout(themedContext) {
        override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
            service.extendTimeout()
            return super.dispatchTouchEvent(ev)
        }
    }.apply { layoutTransition = LayoutTransition() }
    
    private val expandedContainer = object : FrameLayout(themedContext) {
        override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
            service.extendTimeout()
            return super.dispatchTouchEvent(ev)
        }
    }.apply { layoutTransition = LayoutTransition() }
    
    private val bubbleView = inflater.inflate(R.layout.overlay_soundmaster_bubble, bubbleContainer, false)
    private val miniView = inflater.inflate(R.layout.overlay_soundmaster_mini, miniContainer, false)
    private val expandedView = inflater.inflate(R.layout.overlay_soundmaster_expanded, expandedContainer, false)
    
    private val rmsMeter: RmsMeterView = bubbleView.findViewById(R.id.rms_meter)
    private val switchDsp: MaterialSwitch = expandedView.findViewById(R.id.switch_dsp)
    
    private var bubbleParams: WindowManager.LayoutParams
    private var miniParams: WindowManager.LayoutParams
    private var expandedParams: WindowManager.LayoutParams
    
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
            try { windowManager.updateViewLayout(bubbleContainer, params) } catch (e: Exception) {}
        }
    }
    
    private val yProperty = object : FloatPropertyCompat<WindowManager.LayoutParams>("y") {
        override fun getValue(params: WindowManager.LayoutParams): Float = params.y.toFloat()
        override fun setValue(params: WindowManager.LayoutParams, value: Float) {
            params.y = value.toInt()
            try { windowManager.updateViewLayout(bubbleContainer, params) } catch (e: Exception) {}
        }
    }

    init {
        val savedPos = SoundMasterPreferences.loadBubblePosition(service)
        
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val panelFlags = baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (savedPos.first != -1) savedPos.first else 9999
            y = if (savedPos.second != -1) savedPos.second else 200
        }
        
        miniParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            panelFlags,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        
        expandedParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            panelFlags,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        bubbleContainer.addView(bubbleView)
        miniContainer.addView(miniView)
        expandedContainer.addView(expandedView)

        setupBubbleTouchListener()
        setupClickListeners()
        setupModeSwitch()
        setupOutsideTouchListeners()
    }

    private fun setupOutsideTouchListeners() {
        miniContainer.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                transitionTo(State.BUBBLE)
                true
            } else false
        }
        expandedContainer.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                transitionTo(State.BUBBLE)
                true
            } else false
        }
    }

    fun show(state: State) {
        if (!isBubbleAttached && state != State.HIDDEN) {
            try {
                windowManager.addView(bubbleContainer, bubbleParams)
                isBubbleAttached = true
            } catch (e: Exception) {
                e.printStackTrace()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(service, "Overlay permission required!", android.widget.Toast.LENGTH_LONG).show()
                }
                return
            }
        }
        transitionTo(state)
    }

    fun hide() {
        if (isBubbleAttached) try { windowManager.removeView(bubbleContainer); isBubbleAttached = false } catch (e: Exception) {}
        if (isMiniAttached) try { windowManager.removeView(miniContainer); isMiniAttached = false } catch (e: Exception) {}
        if (isExpandedAttached) try { windowManager.removeView(expandedContainer); isExpandedAttached = false } catch (e: Exception) {}
        currentState = State.HIDDEN
    }

    fun updateRms(rms: Float) {
        if (currentState == State.BUBBLE) {
            rmsMeter.updateRms(rms)
        }
    }

    private fun transitionTo(state: State) {
        currentState = state
        
        when (state) {
            State.BUBBLE -> {
                bubbleContainer.visibility = View.VISIBLE
                if (isMiniAttached) { try { windowManager.removeView(miniContainer); isMiniAttached = false } catch(e:Exception){} }
                if (isExpandedAttached) { try { windowManager.removeView(expandedContainer); isExpandedAttached = false } catch(e:Exception){} }
                
                bubbleView.post {
                    val metrics = windowManager.currentWindowMetrics.bounds
                    val maxX = metrics.width() - bubbleView.width
                    val maxY = metrics.height() - bubbleView.height
                    if (maxX > 0 && maxY > 0) {
                        val targetX = if (bubbleParams.x + bubbleView.width / 2 < metrics.width() / 2) -bubblePaddingPx else maxX + bubblePaddingPx
                        bubbleParams.x = targetX
                        bubbleParams.y = bubbleParams.y.coerceIn(0, maxY)
                        if (isBubbleAttached) try { windowManager.updateViewLayout(bubbleContainer, bubbleParams) } catch(e:Exception){}
                    }
                }
            }
            State.MINI -> {
                bubbleContainer.visibility = View.GONE
                if (isExpandedAttached) { try { windowManager.removeView(expandedContainer); isExpandedAttached = false } catch(e:Exception){} }
                
                populateSliders()
                
                val metrics = windowManager.currentWindowMetrics.bounds
                val screenWidth = metrics.width()
                val screenHeight = metrics.height()
                
                miniView.measure(
                    View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST)
                )
                
                val panelWidth = miniView.measuredWidth
                val panelHeight = miniView.measuredHeight
                
                val isLeft = bubbleParams.x + bubbleView.width / 2 < screenWidth / 2
                miniParams.x = if (isLeft) 0 else screenWidth - panelWidth
                miniParams.y = bubbleParams.y.coerceIn(0, screenHeight - panelHeight)
                
                if (!isMiniAttached) {
                    try { windowManager.addView(miniContainer, miniParams); isMiniAttached = true } catch(e:Exception){}
                } else {
                    try { windowManager.updateViewLayout(miniContainer, miniParams) } catch(e:Exception){}
                }
            }
            State.EXPANDED -> {
                bubbleContainer.visibility = View.GONE
                if (isMiniAttached) { try { windowManager.removeView(miniContainer); isMiniAttached = false } catch(e:Exception){} }
                
                val metrics = windowManager.currentWindowMetrics.bounds
                val screenWidth = metrics.width()
                val screenHeight = metrics.height()
                
                expandedView.measure(
                    View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST)
                )
                
                val panelWidth = expandedView.measuredWidth
                val panelHeight = expandedView.measuredHeight
                
                val isLeft = bubbleParams.x + bubbleView.width / 2 < screenWidth / 2
                expandedParams.x = if (isLeft) 0 else screenWidth - panelWidth
                expandedParams.y = bubbleParams.y.coerceIn(0, screenHeight - panelHeight)
                
                if (!isExpandedAttached) {
                    try { windowManager.addView(expandedContainer, expandedParams); isExpandedAttached = true } catch(e:Exception){}
                } else {
                    try { windowManager.updateViewLayout(expandedContainer, expandedParams) } catch(e:Exception){}
                }
            }
            State.HIDDEN -> hide()
        }
    }

    private fun setupBubbleTouchListener() {
        bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    springX?.cancel()
                    springY?.cancel()
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val metrics = windowManager.currentWindowMetrics.bounds
                    val maxX = metrics.width() - bubbleView.width
                    val maxY = metrics.height() - bubbleView.height
                    bubbleParams.x = (initialX + (event.rawX - initialTouchX).toInt()).coerceIn(-bubblePaddingPx, maxX + bubblePaddingPx)
                    bubbleParams.y = (initialY + (event.rawY - initialTouchY).toInt()).coerceIn(0, maxY)
                    if (isBubbleAttached) {
                        try { windowManager.updateViewLayout(bubbleContainer, bubbleParams) } catch (e: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moveDistance = abs(event.rawX - initialTouchX) + abs(event.rawY - initialTouchY)
                    if (moveDistance < 10) {
                        transitionTo(State.MINI)
                    } else {
                        val metrics = windowManager.currentWindowMetrics.bounds
                        val screenWidth = metrics.width()
                        
                        val targetX = if (bubbleParams.x + bubbleView.width / 2 < screenWidth / 2) -bubblePaddingPx else screenWidth - bubbleView.width + bubblePaddingPx
                        
                        springX?.cancel()
                        springX = SpringAnimation(bubbleParams, xProperty).apply {
                            spring = SpringForce(targetX.toFloat()).apply {
                                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                                stiffness = SpringForce.STIFFNESS_LOW
                            }
                            addEndListener { _, _, _, _ ->
                                SoundMasterPreferences.saveBubblePosition(service, bubbleParams.x, bubbleParams.y)
                            }
                            start()
                        }
                        
                        val targetY = bubbleParams.y.coerceIn(0, metrics.height() - bubbleView.height)
                        springY?.cancel()
                        springY = SpringAnimation(bubbleParams, yProperty).apply {
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
        expandedView.findViewById<View>(R.id.btn_exp_close).setOnClickListener {
            transitionTo(State.BUBBLE)
        }
        expandedView.findViewById<View>(R.id.btn_stop_engine)?.setOnClickListener {
            service.stopSelf()
        }
        expandedView.findViewById<View>(R.id.btn_stop_all).setOnClickListener {
            // "Close Overlay" should only dismiss the floating UI, not kill the audio engine -
            // that's what "Stop Engine" is for. It can be brought back via wakeBubble().
            hide()
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
            service.onModeChanged(isChecked)
        }
    }
    
    fun syncSwitchState() {
        val isDsp = SoundMasterPreferences.isAdvancedDspMode(service)
        if (switchDsp.isChecked != isDsp) {
            switchDsp.setOnCheckedChangeListener(null)
            switchDsp.isChecked = isDsp
            updateDspVisibility(isDsp)
            setupModeSwitch()
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
                    appIconView.imageTintList = null
                } catch(e:Exception){}
            } else {
                appIconView.setImageResource(R.drawable.baseline_equalizer_24)
                appIconView.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            }
        }
    }

    fun populateSliders() {
        val isDsp = SoundMasterPreferences.isAdvancedDspMode(service)
        val pm = service.packageManager
        
        val newPkgs = if (isDsp) {
            SoundMasterService.apps.map { it.pkg }.distinct()
        } else {
            SoundMasterService.activePackages
        }
        
        val container = miniView.findViewById<LinearLayout>(R.id.mini_sliders_container)
        
        if (newPkgs.isEmpty()) {
            container.removeAllViews()
            transitionTo(State.BUBBLE)
            return
        }
        
        renderSliders(container, newPkgs, pm)
    }
    
    private fun renderSliders(container: LinearLayout, newPkgs: List<String>, pm: android.content.pm.PackageManager) {
        val toRemove = mutableListOf<View>()
        for (i in 0 until container.childCount) {
            val view = container.getChildAt(i)
            val pkgTag = view.getTag(R.id.name) as? String
            if (pkgTag != null && !newPkgs.contains(pkgTag)) {
                toRemove.add(view)
            }
        }
        toRemove.forEach { container.removeView(it) }
        
        newPkgs.forEach { pkg ->
            var itemView = container.findViewWithTag<View>(pkg)
            if (itemView == null) {
                itemView = inflater.inflate(R.layout.item_mini_slider, container, false)
                itemView.tag = pkg
                itemView.setTag(R.id.name, pkg)
                
                val iconView = itemView.findViewById<ImageView>(R.id.app_icon)
                val slider = itemView.findViewById<SeekBar>(R.id.volume_slider)
                val btnMixedAudio = itemView.findViewById<ImageView>(R.id.btn_mixed_audio)
                
                try { iconView.setImageDrawable(pm.getApplicationIcon(pkg)) } catch(e:Exception){}
                
                val prefs = service.getSharedPreferences("soundmaster_vols", Context.MODE_PRIVATE)
                val savedVol = prefs.getFloat(pkg, 1.0f)
                val initialVol = if (SoundMasterPreferences.isAdvancedDspMode(service)) {
                    val rawAmplitude = SoundMasterService.getVolumeOf(AudioOutputKey(pkg, -1))
                    // Convert physical amplitude back to slider progress using base e logarithmic mapping
                    val y = (rawAmplitude / 100f).coerceIn(0f, 1f)
                    val x = kotlin.math.ln(y * (Math.E - 1.0) + 1.0)
                    (x * 100.0).toFloat()
                } else {
                    savedVol * 100f
                }
                slider.progress = initialVol.toInt()
                iconView.alpha = if (slider.progress == 0) 0.4f else 1.0f

                iconView.setOnClickListener {
                    val currentVol = slider.progress
                    if (currentVol > 0) {
                        itemView.setTag(R.id.app_icon, currentVol)
                        slider.progress = 0
                    } else {
                        val lastVol = itemView.getTag(R.id.app_icon) as? Int ?: 100
                        slider.progress = lastVol
                    }
                }

                com.legendsayantan.adbtools.lib.ShizukuRunner.command("appops get $pkg TAKE_AUDIO_FOCUS", object : com.legendsayantan.adbtools.lib.ShizukuRunner.CommandResultListener {
                    override fun onCommandResult(output: String, done: Boolean) {
                        if (done) {
                            val isMixed = output.contains("ignore") || output.contains("deny")
                            service.mainHandler.post {
                                btnMixedAudio.alpha = 1.0f
                                btnMixedAudio.setImageResource(if (isMixed) R.drawable.ic_audio_mixed else R.drawable.baseline_audiotrack_24)
                                val color = if (isMixed) service.getColor(R.color.tool_mixed_audio) else android.graphics.Color.WHITE
                                btnMixedAudio.imageTintList = android.content.res.ColorStateList.valueOf(color)
                                
                                val mode = if (isMixed) 0 else 1 
                                btnMixedAudio.setOnClickListener {
                                    btnMixedAudio.isEnabled = false
                                    btnMixedAudio.setImageResource(if (!isMixed) R.drawable.ic_audio_mixed else R.drawable.baseline_audiotrack_24)
                                    val newColor = if (!isMixed) service.getColor(R.color.tool_mixed_audio) else android.graphics.Color.WHITE
                                    btnMixedAudio.imageTintList = android.content.res.ColorStateList.valueOf(newColor)
                                    
                                    com.legendsayantan.adbtools.lib.ShizuToolsController.execute { s ->
                                        try {
                                            val uid = pm.getApplicationInfo(pkg, 0).uid
                                            s.setAppOpMode(pkg, uid, 32, mode)
                                            service.mainHandler.post { populateSliders() }
                                        } catch (e: Exception) {}
                                    }
                                }
                            }
                        }
                    }
                    override fun onCommandError(error: String) {}
                })

                slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) {
                        service.extendAppTimeout(pkg)
                        
                        iconView.alpha = if (p1 == 0) 0.4f else 1.0f
                        
                        val linearRatio = p1 / 100f
                        // Apply mild exponential curve (base e) for balanced sensitivity
                        val naturalVolume = ((Math.pow(Math.E, linearRatio.toDouble()) - 1.0) / (Math.E - 1.0)).toFloat()
                        
                        if (SoundMasterPreferences.isAdvancedDspMode(service)) {
                            SoundMasterService.setVolumeOf(AudioOutputKey(pkg, -1), naturalVolume * 100f)
                        } else {
                            prefs.edit().putFloat(pkg, p1 / 100f).apply()
                            com.legendsayantan.adbtools.lib.ShizuToolsController.execute { s ->
                                try {
                                    val uid = pm.getApplicationInfo(pkg, 0).uid
                                    s.setPlayerVolume(uid, naturalVolume)
                                } catch(e:Exception){}
                            }
                        }
                    }
                    override fun onStartTrackingTouch(p0: SeekBar?) {
                        service.pauseTimeout()
                        service.extendAppTimeout(pkg)
                    }
                    override fun onStopTrackingTouch(p0: SeekBar?) {
                        service.extendTimeout()
                        service.extendAppTimeout(pkg)
                    }
                })
                container.addView(itemView)
            }
        }

        val metrics = windowManager.currentWindowMetrics.bounds
        val maxWidth = metrics.width() - 64
        val scrollView = miniView.findViewById<android.widget.HorizontalScrollView>(R.id.mini_scroll_view)
        
        container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val lp = scrollView.layoutParams
        if (container.measuredWidth > maxWidth) {
            lp.width = maxWidth
        } else {
            lp.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        }
        scrollView.layoutParams = lp
        scrollView.requestLayout()
        
        if (currentState == State.MINI) {
            miniView.measure(
                View.MeasureSpec.makeMeasureSpec(metrics.width(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(metrics.height(), View.MeasureSpec.AT_MOST)
            )
            
            val panelWidth = miniView.measuredWidth
            val panelHeight = miniView.measuredHeight
            
            val isLeft = bubbleParams.x + bubbleView.width / 2 < metrics.width() / 2
            miniParams.x = if (isLeft) 0 else metrics.width() - panelWidth
            miniParams.y = bubbleParams.y.coerceIn(0, metrics.height() - panelHeight)
            
            if (isMiniAttached) {
                try { windowManager.updateViewLayout(miniContainer, miniParams) } catch(e:Exception){}
            }
        }
    }
}
