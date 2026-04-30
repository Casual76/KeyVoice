package com.keyvoice.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.keyvoice.app.R

/**
 * Custom View that displays 5 animated equalizer bars representing audio input level.
 * Each bar's height is proportional to the current audio amplitude.
 * Uses smooth animation via interpolation toward target heights.
 */
class AudioLevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val NUM_BARS = 5
        private const val SMOOTHING_FACTOR = 0.3f
        private const val MIN_BAR_HEIGHT_FRACTION = 0.1f
        private const val MAX_AMPLITUDE = 32767f
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val barColors: IntArray = intArrayOf(
        ContextCompat.getColor(context, R.color.audio_bar_low),
        ContextCompat.getColor(context, R.color.audio_bar_mid),
        ContextCompat.getColor(context, R.color.audio_bar_high),
        ContextCompat.getColor(context, R.color.audio_bar_mid),
        ContextCompat.getColor(context, R.color.audio_bar_low)
    )

    // Current heights of each bar (0.0 to 1.0)
    private val currentHeights = FloatArray(NUM_BARS) { MIN_BAR_HEIGHT_FRACTION }
    // Target heights to animate toward
    private val targetHeights = FloatArray(NUM_BARS) { MIN_BAR_HEIGHT_FRACTION }

    private val barRect = RectF()
    private var barWidth = 0f
    private var barSpacing = 0f
    private var cornerRadius = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val totalSpacing = (NUM_BARS - 1) * resources.getDimension(R.dimen.audio_bar_spacing)
        barWidth = resources.getDimension(R.dimen.audio_bar_width)
        barSpacing = resources.getDimension(R.dimen.audio_bar_spacing)
        cornerRadius = resources.getDimension(R.dimen.audio_bar_corner_radius)

        // Recalculate bar width if total doesn't fit
        val totalBarWidth = NUM_BARS * barWidth + totalSpacing
        if (totalBarWidth > w) {
            barWidth = (w - totalSpacing) / NUM_BARS
        }
    }

    /**
     * Updates the audio level with a new amplitude value.
     * @param amplitude Raw amplitude from MediaRecorder.getMaxAmplitude() (0–32767)
     */
    fun updateLevel(amplitude: Int) {
        val normalizedLevel = (amplitude / MAX_AMPLITUDE).coerceIn(0f, 1f)

        // Distribute the amplitude across bars with some randomization for visual interest
        for (i in 0 until NUM_BARS) {
            // Center bars are slightly taller, edge bars slightly shorter
            val positionMultiplier = when (i) {
                0, NUM_BARS - 1 -> 0.7f
                1, NUM_BARS - 2 -> 0.85f
                else -> 1.0f
            }

            // Add subtle variation per bar
            val variation = 0.8f + (Math.random().toFloat() * 0.4f)
            val target = (normalizedLevel * positionMultiplier * variation)
                .coerceIn(MIN_BAR_HEIGHT_FRACTION, 1f)

            targetHeights[i] = target
        }

        // Trigger animation
        invalidate()
    }

    /** Resets all bars to minimum height */
    fun reset() {
        for (i in 0 until NUM_BARS) {
            targetHeights[i] = MIN_BAR_HEIGHT_FRACTION
            currentHeights[i] = MIN_BAR_HEIGHT_FRACTION
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val totalWidth = NUM_BARS * barWidth + (NUM_BARS - 1) * barSpacing
        val startX = (width - totalWidth) / 2f

        var needsRedraw = false

        for (i in 0 until NUM_BARS) {
            // Smoothly interpolate toward target height
            currentHeights[i] += (targetHeights[i] - currentHeights[i]) * SMOOTHING_FACTOR

            // Check if we need another frame
            if (Math.abs(currentHeights[i] - targetHeights[i]) > 0.01f) {
                needsRedraw = true
            }

            val barHeight = height * currentHeights[i]
            val left = startX + i * (barWidth + barSpacing)
            val top = height - barHeight
            val right = left + barWidth
            val bottom = height.toFloat()

            barRect.set(left, top, right, bottom)
            barPaint.color = barColors[i]
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint)
        }

        // Continue animation if heights haven't settled
        if (needsRedraw) {
            postInvalidateOnAnimation()
        }
    }
}
