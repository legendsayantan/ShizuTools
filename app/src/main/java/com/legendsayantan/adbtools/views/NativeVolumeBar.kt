package com.legendsayantan.adbtools.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * A vertical, capsule-shaped volume control matching Android's native system volume pill: a
 * rounded track filled bottom-up, dragged directly by touch position rather than a repurposed
 * horizontal Slider rotated 270 degrees (which needs manual touch-coordinate remapping to feel
 * right, and easily ends up feeling "off"). Value range defaults to 0..150 to match this app's
 * existing volume-boost convention (values above 100 render in the boost color).
 */
class NativeVolumeBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var valueFrom = 0f
    var valueTo = 150f
    var boostThreshold = 100f

    var fillColor: Int = Color.parseColor("#A5813E")
    var boostColor: Int = Color.parseColor("#B71C1C")
    var trackColor: Int = Color.parseColor("#26FFFFFF")
        set(v) { field = v; trackPaint.color = v; invalidate() }

    /** (value, fromUser) - fromUser is false when set programmatically via the `value` setter. */
    var onValueChange: ((Float, Boolean) -> Unit)? = null
    var onTrackingStart: (() -> Unit)? = null
    var onTrackingStop: (() -> Unit)? = null

    private var _value = 100f
    var value: Float
        get() = _value
        set(v) {
            val clamped = v.coerceIn(valueFrom, valueTo)
            if (_value != clamped) {
                _value = clamped
                invalidate()
            }
        }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = trackColor
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val trackRect = RectF()
    private val fillClipRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val radius = width / 2f

        trackRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        val fraction = ((_value - valueFrom) / (valueTo - valueFrom)).coerceIn(0f, 1f)
        if (fraction <= 0f) return
        val fillHeight = height * fraction
        fillPaint.color = if (_value > boostThreshold) boostColor else fillColor

        fillClipRect.set(0f, height - fillHeight, width.toFloat(), height.toFloat())
        canvas.save()
        canvas.clipRect(fillClipRect)
        canvas.drawRoundRect(trackRect, radius, radius, fillPaint)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onTrackingStart?.invoke()
                updateFromTouch(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                onTrackingStop?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouch(y: Float) {
        if (height == 0) return
        val fraction = 1f - (y / height).coerceIn(0f, 1f)
        value = valueFrom + fraction * (valueTo - valueFrom)
        onValueChange?.invoke(_value, true)
    }
}
