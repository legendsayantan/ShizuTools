package com.legendsayantan.adbtools.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

class RmsMeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    
    private var currentRadius = 0f
    private var targetRadius = 0f
    private var baseRadius = 50f
    
    private var animator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        baseRadius = minOf(w, h) / 4f
        
        val colors = intArrayOf(
            Color.parseColor("#00E676"),
            Color.parseColor("#FFAB40"),
            Color.parseColor("#FF5252"),
            Color.parseColor("#FFAB40"),
            Color.parseColor("#00E676")
        )
        
        ringPaint.shader = SweepGradient(w / 2f, h / 2f, colors, null)
    }

    fun updateRms(rms: Float) {
        // rms is 0.0 to 1.0 typically
        val clamped = rms.coerceIn(0f, 1f)
        val maxExpansion = minOf(width, height) / 2f - baseRadius - ringPaint.strokeWidth
        targetRadius = baseRadius + (clamped * maxExpansion)
        
        animator?.cancel()
        animator = ValueAnimator.ofFloat(currentRadius, targetRadius).apply {
            duration = 100
            interpolator = DecelerateInterpolator()
            addUpdateListener { 
                currentRadius = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        
        val cx = width / 2f
        val cy = height / 2f
        
        // Draw expanding RMS ring
        if (currentRadius > baseRadius + 1f) {
            val alpha = (1f - ((currentRadius - baseRadius) / (minOf(width, height) / 2f - baseRadius))).coerceIn(0f, 1f)
            ringPaint.alpha = (alpha * 255).toInt()
            canvas.drawCircle(cx, cy, currentRadius, ringPaint)
        }
    }
}
