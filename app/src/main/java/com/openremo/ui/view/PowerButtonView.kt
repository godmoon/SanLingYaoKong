package com.openremo.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.openremo.R

/**
 * 大号圆形电源键。开启时带青色光晕，关闭时低调。
 */
class PowerButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(4f)
    }
    private val arcRect = RectF()

    private var powerOn = false
    private var glow = 0f
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
    }

    fun setPower(on: Boolean, animate: Boolean = true) {
        if (powerOn == on && glow == if (on) 1f else 0f) return
        powerOn = on
        val target = if (on) 1f else 0f
        if (!animate) {
            glow = target
            invalidate()
            return
        }
        animator?.cancel()
        animator = ValueAnimator.ofFloat(glow, target).apply {
            duration = 260
            addUpdateListener {
                glow = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f - dp(10f)

        val primary = ContextCompat.getColor(context, R.color.primary)
        val dim = ContextCompat.getColor(context, R.color.surface_higher)
        val idleRing = ContextCompat.getColor(context, R.color.text_tertiary)

        val blended = blend(dim, primary, glow * 0.30f)
        fillPaint.shader = RadialGradient(
            cx, cy - radius * 0.25f, radius * 1.15f,
            intArrayOf(blended, blend(ContextCompat.getColor(context, R.color.surface), primary, glow * 0.06f)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        fillPaint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, radius - dp(2f), fillPaint)

        ringPaint.color = blend(idleRing, primary, glow)
        ringPaint.alpha = (90 + 165 * glow).toInt()
        canvas.drawCircle(cx, cy, radius - dp(2f), ringPaint)

        if (glow > 0f) {
            ringPaint.color = primary
            ringPaint.alpha = (70 * glow).toInt()
            ringPaint.strokeWidth = dp(6f)
            canvas.drawCircle(cx, cy, radius + dp(3f), ringPaint)
            ringPaint.strokeWidth = dp(2f)
        }

        iconPaint.color = blend(idleRing, primary, glow)
        val glyphRadius = radius * 0.34f
        arcRect.set(cx - glyphRadius, cy - glyphRadius, cx + glyphRadius, cy + glyphRadius)
        canvas.drawArc(arcRect, -58f, 296f, false, iconPaint)
        canvas.drawLine(cx, cy - glyphRadius * 1.28f, cx, cy - glyphRadius * 0.12f, iconPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                return true
            }

            MotionEvent.ACTION_UP -> {
                animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                if (isEnabled) performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun blend(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f).toInt(),
            (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt(),
        )
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
