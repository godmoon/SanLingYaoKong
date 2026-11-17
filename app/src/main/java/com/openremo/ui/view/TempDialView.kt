package com.openremo.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.openremo.R
import com.openremo.protocol.TempRange
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 温度圆盘：可点击左右加减，也可以沿圆环拖动。
 */
class TempDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private companion object {
        const val START_ANGLE = 150f
        const val SWEEP_ANGLE = 240f
    }

    private val arcRect = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(12f)
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(12f)
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val tempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val signPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(2.5f)
    }

    var range: TempRange = TempRange()
        set(value) {
            field = value
            preview = preview?.coerceIn(value.lower, value.upper)
            invalidate()
        }

    var temperature: Double? = 25.0
        set(value) {
            field = value
            // 拖动过程中轮询回填的状态可能滞后，不能把用户正在选的温度冲掉
            if (!dragging) {
                preview = value?.coerceIn(range.lower, range.upper)
            }
            invalidate()
        }

    var powered: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var onStep: ((Double) -> Unit)? = null
    var onCommit: ((Double) -> Unit)? = null

    private var preview: Double? = temperature
    private var dragging = false
    private var minusPressed = false
    private var plusPressed = false

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height * 0.46f
        val radius = minOf(width * 0.38f, height * 0.34f)
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        val primary = ContextCompat.getColor(context, R.color.primary)
        val dim = ContextCompat.getColor(context, R.color.surface_higher)
        val secondary = ContextCompat.getColor(context, R.color.text_secondary)
        val tertiary = ContextCompat.getColor(context, R.color.text_tertiary)

        trackPaint.color = dim
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

        val value = preview
        val fraction = if (value == null || range.upper == range.lower) {
            0f
        } else {
            ((value - range.lower) / (range.upper - range.lower)).toFloat().coerceIn(0f, 1f)
        }
        if (value != null) {
            progressPaint.color = if (powered) primary else primary_dim()
            progressPaint.alpha = if (powered) 255 else 150
            canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE * fraction, false, progressPaint)
        }

        // 刻度
        tickPaint.color = tertiary
        for (i in 0..12) {
            val angle = Math.toRadians((START_ANGLE + SWEEP_ANGLE * i / 12.0))
            val outer = radius + dp(14f)
            val inner = radius + dp(7f)
            val sx = cx + cos(angle).toFloat() * inner
            val sy = cy + sin(angle).toFloat() * inner
            val ex = cx + cos(angle).toFloat() * outer
            val ey = cy + sin(angle).toFloat() * outer
            tickPaint.alpha = if (i % 3 == 0) 200 else 110
            canvas.drawLine(sx, sy, ex, ey, tickPaint)
        }

        // 中心温度
        val text = value?.let { formatTemp(it) } ?: "--"
        tempPaint.color = if (powered) {
            ContextCompat.getColor(context, R.color.text_primary)
        } else {
            secondary
        }
        tempPaint.textSize = radius * 0.62f
        canvas.drawText(text, cx, cy + tempPaint.textSize * 0.24f, tempPaint)

        unitPaint.color = secondary
        unitPaint.textSize = radius * 0.17f
        canvas.drawText("°C", cx, cy + tempPaint.textSize * 0.55f, unitPaint)

        hintPaint.color = tertiary
        hintPaint.textSize = dp(11f)
        canvas.drawText("设定温度", cx, cy - tempPaint.textSize * 0.62f, hintPaint)

        // 加减按钮
        val buttonY = height * 0.87f
        val buttonRadius = dp(24f)
        val gap = radius * 0.78f
        drawCircleButton(canvas, cx - gap, buttonY, buttonRadius, false)
        drawCircleButton(canvas, cx + gap, buttonY, buttonRadius, true)
    }

    private fun drawCircleButton(canvas: Canvas, cx: Float, cy: Float, radius: Float, plus: Boolean) {
        buttonPaint.color = if (powered) {
            ContextCompat.getColor(context, R.color.primary_container)
        } else {
            ContextCompat.getColor(context, R.color.surface_high)
        }
        canvas.drawCircle(cx, cy, radius, buttonPaint)
        signPaint.color = if (powered) {
            ContextCompat.getColor(context, R.color.primary)
        } else {
            ContextCompat.getColor(context, R.color.text_secondary)
        }
        canvas.drawLine(cx - radius * 0.36f, cy, cx + radius * 0.36f, cy, signPaint)
        if (plus) {
            canvas.drawLine(cx, cy - radius * 0.36f, cx, cy + radius * 0.36f, signPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val cx = width / 2f
        val cy = height * 0.46f
        val radius = minOf(width * 0.38f, height * 0.34f)
        val buttonY = height * 0.87f
        val buttonRadius = dp(32f)
        val gap = radius * 0.78f
        val minusX = cx - gap
        val plusX = cx + gap

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                minusPressed = distance(event.x, event.y, minusX, buttonY) <= buttonRadius
                plusPressed = distance(event.x, event.y, plusX, buttonY) <= buttonRadius
                if (minusPressed || plusPressed) {
                    invalidate()
                    return true
                }
                val d = distance(event.x, event.y, cx, cy)
                if (d in radius * 0.55f..(radius + dp(30f))) {
                    dragging = true
                    updateFromTouch(event.x, event.y, cx, cy)
                } else {
                    return false
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    updateFromTouch(event.x, event.y, cx, cy)
                }
                return dragging || minusPressed || plusPressed
            }

            MotionEvent.ACTION_UP -> {
                when {
                    minusPressed -> step(-0.5)
                    plusPressed -> step(0.5)
                    dragging -> preview?.let { onCommit?.invoke(it) }
                }
                dragging = false
                minusPressed = false
                plusPressed = false
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                minusPressed = false
                plusPressed = false
                preview = temperature
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouch(x: Float, y: Float, cx: Float, cy: Float) {
        val degrees = Math.toDegrees(atan2(y - cy, x - cx).toDouble())
        var angle = ((degrees % 360) + 360) % 360
        if (angle < 30.0) angle += 360.0
        val clamped = when {
            angle in 30.0..150.0 -> if (angle < 90.0) 390.0 else 150.0
            angle < START_ANGLE -> START_ANGLE.toDouble()
            else -> angle
        }
        val fraction = ((clamped - START_ANGLE) / SWEEP_ANGLE).coerceIn(0.0, 1.0)
        val value = range.lower + fraction * (range.upper - range.lower)
        val rounded = (value * 2).roundToInt() / 2.0
        preview = rounded
        invalidate()
    }

    /**
     * 加减按钮：先乐观更新预览再交给上层发送命令，
     * 这样按键反馈不用等一轮 BLE 往返。
     */
    private fun step(delta: Double) {
        val current = preview ?: temperature ?: range.lower
        val next = (current + delta).coerceIn(range.lower, range.upper)
        preview = next
        invalidate()
        onStep?.invoke(delta)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        kotlin.math.hypot(x1 - x2, y1 - y2)

    fun formatTemp(value: Double): String {
        val rounded = (value * 10).roundToInt() / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else String.format("%.1f", rounded)
    }

    private fun primary_dim(): Int = ContextCompat.getColor(context, R.color.primary_dim)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
