package com.legendsayantan.adbtools.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.log10

class EqCurveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val path = Path()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#40C4FF")
    }
    
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#33FFFFFF")
    }

    private var bandFrequencies = floatArrayOf(60f, 230f, 910f, 3600f, 14000f)
    private var bandLevels = floatArrayOf(0f, 0f, 0f, 0f, 0f) // -12 to 12
    
    private val minFreq = 20f
    private val maxFreq = 20000f
    private val minLevel = -12f
    private val maxLevel = 12f

    fun setBands(frequencies: FloatArray, levels: FloatArray) {
        if (frequencies.size == levels.size) {
            bandFrequencies = frequencies
            bandLevels = levels
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.parseColor("#8040C4FF"), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f

        // Draw center zero line
        canvas.drawLine(0f, centerY, w, centerY, gridPaint)

        if (bandFrequencies.isEmpty()) return

        path.reset()
        
        // Calculate points
        val points = mutableListOf<Pair<Float, Float>>()
        
        // Add start point
        points.add(Pair(0f, centerY))

        val logMin = log10(minFreq)
        val logMax = log10(maxFreq)
        val logRange = logMax - logMin

        for (i in bandFrequencies.indices) {
            val freq = bandFrequencies[i].coerceIn(minFreq, maxFreq)
            val level = bandLevels[i].coerceIn(minLevel, maxLevel)

            val logFreq = log10(freq)
            val x = ((logFreq - logMin) / logRange) * w
            
            // Map level (-12 to 12) to Y (h to 0)
            val y = h - ((level - minLevel) / (maxLevel - minLevel)) * h
            points.add(Pair(x.toFloat(), y))
        }
        
        // Add end point
        points.add(Pair(w, centerY))

        // Draw cubic bezier curve through points
        path.moveTo(points.first().first, points.first().second)
        
        var prevX = points.first().first
        var prevY = points.first().second

        for (i in 1 until points.size) {
            val curX = points[i].first
            val curY = points[i].second
            
            val controlX1 = (prevX + curX) / 2f
            val controlY1 = prevY
            
            val controlX2 = (prevX + curX) / 2f
            val controlY2 = curY
            
            path.cubicTo(controlX1, controlY1, controlX2, controlY2, curX, curY)
            
            prevX = curX
            prevY = curY
        }

        // Draw line
        canvas.drawPath(path, linePaint)
        
        // Draw fill
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()
        canvas.drawPath(path, fillPaint)
    }
}
