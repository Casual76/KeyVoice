package com.keyvoice.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.keyvoice.app.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class KeyVoiceAccessibilityOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class VisualState {
        ACTIVATING,
        IDLE,
        RECORDING,
        PROCESSING,
        SUCCESS,
        ERROR
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val bounds = RectF()
    private var visualState = VisualState.IDLE
    private var shimmerOffset = 0f

    private val orbView = AiOrbView(context)
    val titleView = TextView(context).apply {
        ellipsize = TextUtils.TruncateAt.END
        maxLines = 1
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        textSize = 13f
    }
    val subtitleView = TextView(context).apply {
        ellipsize = TextUtils.TruncateAt.END
        maxLines = 2
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        textSize = 12f
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setWillNotDraw(false)
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        setPadding(dp(10), dp(8), dp(14), dp(8))
        elevation = dp(12).toFloat()
        isClickable = true
        isFocusable = false

        val orbFrame = FrameLayout(context).apply {
            layoutParams = LayoutParams(dp(56), dp(56)).apply {
                marginEnd = dp(10)
            }
            addView(
                orbView,
                FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER)
            )
        }

        val textContainer = LinearLayout(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(subtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        addView(orbFrame)
        addView(textContainer)
    }

    fun setVisualState(state: VisualState) {
        if (visualState == state) return
        visualState = state
        orbView.setVisualState(state)
        invalidate()
    }

    fun setStatus(title: CharSequence, subtitle: CharSequence?) {
        titleView.text = title
        subtitleView.text = subtitle ?: ""
        subtitleView.visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        contentDescription = listOf(titleView.text, subtitleView.text)
            .filter { !it.isNullOrBlank() }
            .joinToString(". ")
    }

    fun updateAudioAmplitude(amplitude: Int) {
        orbView.updateAudioAmplitude(amplitude)
    }

    fun playEntrance() {
        alpha = 0f
        scaleX = 0.88f
        scaleY = 0.88f
        translationY = dp(10).toFloat()
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(260L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
            .start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        val radius = dp(24).toFloat()
        val primary = ContextCompat.getColor(context, R.color.primary)
        val secondary = ContextCompat.getColor(context, R.color.secondary)
        val surface = ContextCompat.getColor(context, R.color.surface)
        val error = ContextCompat.getColor(context, R.color.error)
        val success = ContextCompat.getColor(context, R.color.success)
        val accent = when (visualState) {
            VisualState.RECORDING -> error
            VisualState.SUCCESS -> success
            VisualState.ERROR -> error
            VisualState.PROCESSING -> secondary
            else -> primary
        }

        shimmerOffset = ((SystemClock.uptimeMillis() % 1800L) / 1800f)
        backgroundPaint.shader = LinearGradient(
            width * (shimmerOffset - 0.55f),
            0f,
            width * (shimmerOffset + 0.55f),
            height.toFloat(),
            intArrayOf(surface, blend(surface, accent, 0.10f), surface),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        backgroundPaint.setShadowLayer(dp(14).toFloat(), 0f, dp(5).toFloat(), 0x26000000)
        canvas.drawRoundRect(bounds, radius, radius, backgroundPaint)

        strokePaint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            blend(accent, Color.WHITE, 0.25f),
            blend(primary, secondary, 0.45f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(bounds.insetCopy(dp(0.75f)), radius, radius, strokePaint)
        postInvalidateOnAnimation()
    }

    private fun RectF.insetCopy(value: Float): RectF {
        return RectF(left + value, top + value, right - value, bottom - value)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    private fun blend(a: Int, b: Int, ratio: Float): Int {
        val inverse = 1f - ratio
        return Color.argb(
            (Color.alpha(a) * inverse + Color.alpha(b) * ratio).toInt(),
            (Color.red(a) * inverse + Color.red(b) * ratio).toInt(),
            (Color.green(a) * inverse + Color.green(b) * ratio).toInt(),
            (Color.blue(a) * inverse + Color.blue(b) * ratio).toInt()
        )
    }
}

private class AiOrbView(context: Context) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arcBounds = RectF()
    private val checkPath = Path()
    private var visualState = KeyVoiceAccessibilityOverlayView.VisualState.IDLE
    private var amplitudeLevel = 0.12f

    fun setVisualState(state: KeyVoiceAccessibilityOverlayView.VisualState) {
        visualState = state
        invalidate()
    }

    fun updateAudioAmplitude(amplitude: Int) {
        val target = (amplitude / 32767f).coerceIn(0f, 1f)
        amplitudeLevel += (target - amplitudeLevel) * 0.35f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val minSide = min(width, height).toFloat()
        val time = SystemClock.uptimeMillis()
        val primary = ContextCompat.getColor(context, R.color.primary)
        val secondary = ContextCompat.getColor(context, R.color.secondary)
        val error = ContextCompat.getColor(context, R.color.error)
        val success = ContextCompat.getColor(context, R.color.success)
        val accent = when (visualState) {
            KeyVoiceAccessibilityOverlayView.VisualState.RECORDING -> error
            KeyVoiceAccessibilityOverlayView.VisualState.SUCCESS -> success
            KeyVoiceAccessibilityOverlayView.VisualState.ERROR -> error
            KeyVoiceAccessibilityOverlayView.VisualState.PROCESSING -> secondary
            else -> primary
        }

        val pulse = (time % 1600L) / 1600f
        ringPaint.strokeWidth = dp(1.4f)
        repeat(2) { index ->
            val phase = (pulse + index * 0.42f) % 1f
            ringPaint.color = withAlpha(accent, ((1f - phase) * 95).toInt().coerceIn(0, 95))
            canvas.drawCircle(cx, cy, minSide * (0.32f + phase * 0.22f), ringPaint)
        }

        fillPaint.shader = RadialGradient(
            cx - minSide * 0.12f,
            cy - minSide * 0.16f,
            minSide * 0.58f,
            intArrayOf(Color.WHITE, blend(primary, secondary, 0.52f), accent),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, minSide * 0.31f, fillPaint)
        fillPaint.shader = null
        fillPaint.color = withAlpha(Color.WHITE, 72)
        canvas.drawCircle(cx - minSide * 0.09f, cy - minSide * 0.12f, minSide * 0.08f, fillPaint)

        when (visualState) {
            KeyVoiceAccessibilityOverlayView.VisualState.ACTIVATING -> drawSearching(canvas, cx, cy, minSide, time, secondary)
            KeyVoiceAccessibilityOverlayView.VisualState.RECORDING -> drawWaves(canvas, cx, cy, minSide, time, Color.WHITE)
            KeyVoiceAccessibilityOverlayView.VisualState.PROCESSING -> drawProcessingArc(canvas, cx, cy, minSide, time, Color.WHITE)
            KeyVoiceAccessibilityOverlayView.VisualState.SUCCESS -> drawCheck(canvas, cx, cy, minSide, Color.WHITE)
            KeyVoiceAccessibilityOverlayView.VisualState.ERROR -> drawError(canvas, cx, cy, minSide, Color.WHITE)
            KeyVoiceAccessibilityOverlayView.VisualState.IDLE -> drawIdleSpark(canvas, cx, cy, minSide, time, Color.WHITE)
        }

        postInvalidateOnAnimation()
    }

    private fun drawSearching(canvas: Canvas, cx: Float, cy: Float, minSide: Float, time: Long, color: Int) {
        ringPaint.strokeWidth = dp(2f)
        ringPaint.color = withAlpha(Color.WHITE, 210)
        val angle = (time % 1200L) / 1200f * 360f
        arcBounds.set(cx - minSide * 0.23f, cy - minSide * 0.23f, cx + minSide * 0.23f, cy + minSide * 0.23f)
        canvas.drawArc(arcBounds, angle, 95f, false, ringPaint)
        fillPaint.color = withAlpha(color, 180)
        canvas.drawCircle(cx, cy, minSide * 0.055f, fillPaint)
    }

    private fun drawWaves(canvas: Canvas, cx: Float, cy: Float, minSide: Float, time: Long, color: Int) {
        wavePaint.strokeWidth = dp(2.4f)
        wavePaint.color = withAlpha(color, 235)
        val spacing = minSide * 0.065f
        for (i in -2..2) {
            val phase = sin((time / 120f) + i).toFloat()
            val height = minSide * (0.12f + amplitudeLevel * 0.23f) * (0.72f + 0.28f * phase)
            val x = cx + i * spacing
            canvas.drawLine(x, cy - height, x, cy + height, wavePaint)
        }
    }

    private fun drawProcessingArc(canvas: Canvas, cx: Float, cy: Float, minSide: Float, time: Long, color: Int) {
        ringPaint.strokeWidth = dp(2.6f)
        ringPaint.color = withAlpha(color, 230)
        val rotation = (time % 1500L) / 1500f * 360f
        arcBounds.set(cx - minSide * 0.19f, cy - minSide * 0.19f, cx + minSide * 0.19f, cy + minSide * 0.19f)
        canvas.drawArc(arcBounds, rotation, 240f, false, ringPaint)
        val dotAngle = Math.toRadians(rotation.toDouble())
        fillPaint.color = color
        canvas.drawCircle(
            cx + cos(dotAngle).toFloat() * minSide * 0.19f,
            cy + sin(dotAngle).toFloat() * minSide * 0.19f,
            minSide * 0.035f,
            fillPaint
        )
    }

    private fun drawCheck(canvas: Canvas, cx: Float, cy: Float, minSide: Float, color: Int) {
        symbolPaint.strokeWidth = dp(3f)
        symbolPaint.color = color
        checkPath.reset()
        checkPath.moveTo(cx - minSide * 0.13f, cy)
        checkPath.lineTo(cx - minSide * 0.035f, cy + minSide * 0.105f)
        checkPath.lineTo(cx + minSide * 0.16f, cy - minSide * 0.12f)
        canvas.drawPath(checkPath, symbolPaint)
    }

    private fun drawError(canvas: Canvas, cx: Float, cy: Float, minSide: Float, color: Int) {
        symbolPaint.strokeWidth = dp(3f)
        symbolPaint.color = color
        canvas.drawLine(cx - minSide * 0.12f, cy - minSide * 0.12f, cx + minSide * 0.12f, cy + minSide * 0.12f, symbolPaint)
        canvas.drawLine(cx + minSide * 0.12f, cy - minSide * 0.12f, cx - minSide * 0.12f, cy + minSide * 0.12f, symbolPaint)
    }

    private fun drawIdleSpark(canvas: Canvas, cx: Float, cy: Float, minSide: Float, time: Long, color: Int) {
        fillPaint.color = withAlpha(color, 220)
        val phase = (time % 1400L) / 1400f
        for (i in 0 until 3) {
            val angle = Math.toRadians((phase * 360f + i * 120f).toDouble())
            canvas.drawCircle(
                cx + cos(angle).toFloat() * minSide * 0.16f,
                cy + sin(angle).toFloat() * minSide * 0.16f,
                minSide * 0.025f,
                fillPaint
            )
        }
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun blend(a: Int, b: Int, ratio: Float): Int {
        val inverse = 1f - ratio
        return Color.argb(
            (Color.alpha(a) * inverse + Color.alpha(b) * ratio).toInt(),
            (Color.red(a) * inverse + Color.red(b) * ratio).toInt(),
            (Color.green(a) * inverse + Color.green(b) * ratio).toInt(),
            (Color.blue(a) * inverse + Color.blue(b) * ratio).toInt()
        )
    }
}
