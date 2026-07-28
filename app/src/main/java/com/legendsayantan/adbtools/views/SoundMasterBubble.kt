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
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.data.AudioOutputBase
import com.legendsayantan.adbtools.data.AudioOutputKey
import com.legendsayantan.adbtools.lib.AppOps
import com.legendsayantan.adbtools.lib.AudioOutputMap
import com.legendsayantan.adbtools.lib.ShizuToolsController
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

    /** Which app's balance/EQ/output controls are shown in the shared detail panel below the
     *  row of volume bars (DSP mode only) - null when nothing is selected/expanded. */
    private var selectedDspPkg: String? = null
    
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
        miniView.findViewById<View>(R.id.btn_close_detail).setOnClickListener {
            selectedDspPkg = null
            populateSliders()
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
        expandedView.findViewById<View>(R.id.dsp_hint).visibility = if (isDsp) View.VISIBLE else View.GONE
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
        val isDsp = SoundMasterPreferences.isAdvancedDspMode(service)

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
                container.addView(itemView)
            }
            bindCard(itemView, pkg, isDsp, pm)
        }

        if (!isDsp) {
            selectedDspPkg = null
            miniView.findViewById<View>(R.id.shared_dsp_detail).visibility = View.GONE
        } else {
            updateSharedDetail(pm)
        }

        // Native volume-panel rows can outgrow the screen width once there are enough apps -
        // clamp the scroll strip the same way the panel itself is clamped below.
        val metrics = windowManager.currentWindowMetrics.bounds
        val maxWidth = metrics.width() - 64
        val scrollView = miniView.findViewById<android.widget.HorizontalScrollView>(R.id.mini_scroll_view)
        container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val scrollLp = scrollView.layoutParams
        scrollLp.width = if (container.measuredWidth > maxWidth) maxWidth else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        scrollView.layoutParams = scrollLp
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

    private fun applyVolume(pkg: String, isDsp: Boolean, value: Float, pm: android.content.pm.PackageManager) {
        if (isDsp) {
            SoundMasterService.apps.filter { it.pkg == pkg }.forEach { SoundMasterService.setVolumeOf(AudioOutputKey(pkg, it.output), value) }
        } else {
            val prefs = service.getSharedPreferences("soundmaster_vols", Context.MODE_PRIVATE)
            prefs.edit().putFloat(pkg, value / 100f).apply()
            val linearRatio = value / 100f
            // Apply mild exponential curve (base e) for balanced sensitivity
            val naturalVolume = ((Math.pow(Math.E, linearRatio.toDouble()) - 1.0) / (Math.E - 1.0)).toFloat()
            ShizuToolsController.execute { s ->
                try {
                    val uid = pm.getApplicationInfo(pkg, 0).uid
                    s.setPlayerVolume(uid, naturalVolume)
                } catch (e: Exception) {}
            }
        }
    }

    /** The always-visible compact control: app icon (tap to mute) and a native-style vertical
     *  volume bar. A package's volume applies uniformly across all of its current outputs, if
     *  it has more than one attached. DSP mode adds a select button that opens/closes this
     *  app's balance/EQ/output controls in the shared detail panel below the row. */
    private fun bindCard(itemView: View, pkg: String, isDsp: Boolean, pm: android.content.pm.PackageManager) {
        val iconView = itemView.findViewById<ImageView>(R.id.app_icon)
        val bar = itemView.findViewById<NativeVolumeBar>(R.id.volume_bar)
        val selectBtn = itemView.findViewById<ImageView>(R.id.select_btn)
        val cardRoot = itemView.findViewById<MaterialCardView>(R.id.card_root)

        try { iconView.setImageDrawable(pm.getApplicationIcon(pkg)) } catch (e: Exception) {}

        val prefs = service.getSharedPreferences("soundmaster_vols", Context.MODE_PRIVATE)
        val savedVol = prefs.getFloat(pkg, 1.0f)
        val initialVol = if (isDsp) {
            val rawAmplitude = SoundMasterService.apps.firstOrNull { it.pkg == pkg }
                ?.let { SoundMasterService.getVolumeOf(AudioOutputKey(pkg, it.output)) } ?: 100f
            // Convert physical amplitude back to bar position using base e logarithmic mapping
            val y = (rawAmplitude / 100f).coerceIn(0f, 1f)
            val x = kotlin.math.ln(y * (Math.E - 1.0) + 1.0)
            (x * 100.0).toFloat()
        } else {
            savedVol * 100f
        }
        bar.value = initialVol.coerceIn(0f, 150f)
        iconView.alpha = if (bar.value <= 0f) 0.4f else 1.0f

        iconView.setOnClickListener {
            val currentVol = bar.value
            val newVol = if (currentVol > 0f) {
                itemView.setTag(R.id.app_icon, currentVol)
                0f
            } else {
                itemView.getTag(R.id.app_icon) as? Float ?: 100f
            }
            bar.value = newVol
            iconView.alpha = if (newVol <= 0f) 0.4f else 1.0f
            applyVolume(pkg, isDsp, newVol, pm)
        }

        bar.onTrackingStart = { service.pauseTimeout(); service.extendAppTimeout(pkg) }
        bar.onTrackingStop = { service.extendTimeout(); service.extendAppTimeout(pkg) }
        bar.onValueChange = { value, fromUser ->
            service.extendAppTimeout(pkg)
            iconView.alpha = if (value <= 0f) 0.4f else 1.0f
            if (fromUser) applyVolume(pkg, isDsp, value, pm)
        }

        if (isDsp) {
            selectBtn.visibility = View.VISIBLE
            val isSelected = selectedDspPkg == pkg
            cardRoot.strokeColor = if (isSelected) ContextCompat.getColor(itemView.context, R.color.colorSecondary) else android.graphics.Color.TRANSPARENT
            selectBtn.rotation = if (isSelected) 180f else 0f
            selectBtn.setOnClickListener {
                selectedDspPkg = if (selectedDspPkg == pkg) null else pkg
                populateSliders()
            }
        } else {
            selectBtn.visibility = View.GONE
            cardRoot.strokeColor = android.graphics.Color.TRANSPARENT
        }

        val btnMixedAudio = itemView.findViewById<ImageView>(R.id.btn_mixed_audio)
        bindMixedAudioToggle(btnMixedAudio, pkg, pm)
    }

    /** Quick access to this app's TAKE_AUDIO_FOCUS state, cycling default -> ignore (quiet
     *  mixing) -> deny (fully forced) -> default on each tap, independent of Smart/DSP mode -
     *  this is the same "MixedAudio" concept as the dedicated MixedAudio tool, just reachable
     *  directly from the compact overlay instead of opening a separate screen. */
    private fun bindMixedAudioToggle(btn: ImageView, pkg: String, pm: android.content.pm.PackageManager) {
        val uid = try { pm.getApplicationInfo(pkg, 0).uid } catch (e: Exception) { -1 }
        if (uid == -1) {
            btn.visibility = View.GONE
            return
        }
        btn.visibility = View.VISIBLE
        ShizuToolsController.execute { s ->
            val initialMode = try { s.getAppOpMode(pkg, uid, AppOps.TAKE_AUDIO_FOCUS) } catch (e: Exception) { AppOps.MODE_ALLOWED }
            service.mainHandler.post {
                var currentMode = initialMode
                updateMixedAudioIcon(btn, currentMode)
                btn.setOnClickListener {
                    val nextMode = when (currentMode) {
                        AppOps.MODE_ALLOWED -> AppOps.MODE_IGNORED
                        AppOps.MODE_IGNORED -> AppOps.MODE_ERRORED
                        else -> AppOps.MODE_ALLOWED
                    }
                    btn.isEnabled = false
                    ShizuToolsController.execute { s2 ->
                        val applied = try { s2.setAppOpMode(pkg, uid, AppOps.TAKE_AUDIO_FOCUS, nextMode) } catch (e: Exception) { false }
                        service.mainHandler.post {
                            btn.isEnabled = true
                            if (applied) {
                                currentMode = nextMode
                                updateMixedAudioIcon(btn, currentMode)
                            } else {
                                Toast.makeText(
                                    btn.context,
                                    "Couldn't change $pkg's mixing mode - your Shizuku permission level may not allow this.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateMixedAudioIcon(btn: ImageView, mode: Int) {
        val active = mode == AppOps.MODE_IGNORED || mode == AppOps.MODE_ERRORED
        btn.setImageResource(if (active) R.drawable.ic_audio_mixed else R.drawable.baseline_audiotrack_24)
        val color = if (active) ContextCompat.getColor(btn.context, R.color.tool_mixed_audio) else android.graphics.Color.parseColor("#80FFFFFF")
        btn.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }

    /** Balance, 3-band EQ (with a live curve preview), and output-device chips for whichever
     *  app is currently selected - one shared panel below the row of bars rather than a
     *  per-card section, so expanding one app's controls never disrupts the compact row. */
    private fun updateSharedDetail(pm: android.content.pm.PackageManager) {
        val sharedDetail = miniView.findViewById<LinearLayout>(R.id.shared_dsp_detail)
        val pkg = selectedDspPkg
        if (pkg == null || SoundMasterService.apps.none { it.pkg == pkg }) {
            selectedDspPkg = null
            sharedDetail.visibility = View.GONE
            return
        }
        sharedDetail.visibility = View.VISIBLE

        val context = miniView.context
        val iconView = miniView.findViewById<ImageView>(R.id.detail_app_icon)
        val nameView = miniView.findViewById<TextView>(R.id.detail_app_name)
        try { iconView.setImageDrawable(pm.getApplicationIcon(pkg)) } catch (e: Exception) {}
        try { nameView.text = pm.getApplicationInfo(pkg, 0).loadLabel(pm) } catch (e: Exception) { nameView.text = pkg }

        val eqCurve = miniView.findViewById<EqCurveView>(R.id.eq_curve)
        val bandLow = miniView.findViewById<Slider>(R.id.band_low)
        val bandMid = miniView.findViewById<Slider>(R.id.band_mid)
        val bandHigh = miniView.findViewById<Slider>(R.id.band_high)
        val balanceSlider = miniView.findViewById<Slider>(R.id.balance_slider)
        val resetBtn = miniView.findViewById<TextView>(R.id.reset_eq)
        val outputChips = miniView.findViewById<ChipGroup>(R.id.output_chips)

        fun outputsFor() = SoundMasterService.apps.filter { it.pkg == pkg }
        fun primaryKey() = outputsFor().firstOrNull()?.let { AudioOutputKey(pkg, it.output) } ?: AudioOutputKey(pkg, -1)

        fun updateCurve() {
            eqCurve.setBands(
                floatArrayOf(60f, 910f, 14000f),
                floatArrayOf((bandLow.value - 50f) * 0.24f, (bandMid.value - 50f) * 0.24f, (bandHigh.value - 50f) * 0.24f)
            )
        }

        val key = primaryKey()
        bandLow.value = SoundMasterService.getBandValueOf(key, 0) ?: 50f
        bandMid.value = SoundMasterService.getBandValueOf(key, 1) ?: 50f
        bandHigh.value = SoundMasterService.getBandValueOf(key, 2) ?: 50f
        balanceSlider.value = SoundMasterService.getBalanceOf(key) ?: 0f
        updateCurve()

        listOf(bandLow to 0, bandMid to 1, bandHigh to 2).forEach { (bandSlider, bandIndex) ->
            bandSlider.clearOnChangeListeners()
            bandSlider.addOnChangeListener { _, value, _ ->
                service.extendTimeout(); service.extendAppTimeout(pkg)
                outputsFor().forEach { SoundMasterService.setBandValueOf(AudioOutputKey(pkg, it.output), bandIndex, value) }
                updateCurve()
            }
        }
        balanceSlider.clearOnChangeListeners()
        balanceSlider.addOnChangeListener { _, value, _ ->
            service.extendTimeout(); service.extendAppTimeout(pkg)
            outputsFor().forEach { SoundMasterService.setBalanceOf(AudioOutputKey(pkg, it.output), value) }
        }

        resetBtn.setOnClickListener {
            bandLow.value = 50f; bandMid.value = 50f; bandHigh.value = 50f; balanceSlider.value = 0f
        }

        // Output chips: current outputs shown as removable chips (closeIcon hidden if it's the
        // only one, so a package is never left with zero outputs by accident), plus a trailing
        // "+" chip that lists devices not yet used by this app - tapping one adds it as an
        // additional simultaneous output. Switching a device is just remove-then-add.
        outputChips.removeAllViews()
        val devices = SoundMasterService.getAudioDevices()
        val currentOutputs = outputsFor()
        currentOutputs.forEach { base ->
            val device = devices.find { it?.id == base.output }
            val chip = Chip(context)
            chip.text = AudioOutputMap.formatDevice(device)
            chip.isCloseIconVisible = currentOutputs.size > 1
            chip.setOnCloseIconClickListener {
                SoundMasterService.onDynamicDetach(AudioOutputKey(pkg, base.output))
                service.mainHandler.post { populateSliders() }
            }
            outputChips.addView(chip)
        }
        val addChip = Chip(context)
        addChip.text = context.getString(R.string.add_output_device).removeSuffix(":")
        addChip.chipIcon = ContextCompat.getDrawable(context, R.drawable.baseline_add_24)
        addChip.isChipIconVisible = true
        addChip.setOnClickListener {
            val used = currentOutputs.map { it.output }
            val candidates = devices.filter { (it?.id ?: -1) !in used }
            if (candidates.isEmpty()) {
                Toast.makeText(context, context.getString(R.string.no_additional_outputs_available), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val popup = PopupMenu(context, addChip)
            candidates.forEachIndexed { i, d -> popup.menu.add(0, i, i, AudioOutputMap.formatDevice(d)) }
            popup.setOnMenuItemClickListener { item ->
                val device = candidates.getOrNull(item.itemId)
                val newKey = AudioOutputKey(pkg, device?.id ?: -1)
                if (SoundMasterService.isAttachable(newKey)) {
                    SoundMasterService.onDynamicAttach(AudioOutputBase(pkg, device?.id ?: -1, 100f), device)
                    service.mainHandler.post { populateSliders() }
                }
                true
            }
            popup.show()
        }
        outputChips.addView(addChip)
    }
}
