package com.nfcvault.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.nfcvault.R

/** Concentric NFC pulse used while reader mode is armed. */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dpF(context, 1.5f)
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val accent = Ui.color(context, R.color.accent)
    private val emerald = Ui.color(context, R.color.emerald)
    private val nfcIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_nfc)
    private val checkIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_check)

    private var phase = 0f
    private var success = false
    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (success) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2_400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    fun setSuccess(value: Boolean) {
        if (success == value) return
        success = value
        if (value) {
            animator?.cancel()
            animator = null
        } else if (isAttachedToWindow) {
            phase = 0f
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2_400
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = minOf(width, height) / 2f - Ui.dpF(context, 4f)
        val coreRadius = Ui.dpF(context, 34f)
        val activeColor = if (success) emerald else accent

        if (!success) {
            for (index in 0..2) {
                val progress = (phase + index / 3f) % 1f
                val radius = coreRadius + progress * (maxRadius - coreRadius)
                val alpha = ((1f - progress) * 105f).toInt()
                ringPaint.color = Ui.alpha(activeColor, alpha)
                canvas.drawCircle(cx, cy, radius, ringPaint)
            }
        }

        corePaint.color = if (success) emerald else Ui.alpha(activeColor, 42)
        canvas.drawCircle(cx, cy, coreRadius, corePaint)
        ringPaint.color = Ui.alpha(activeColor, if (success) 255 else 170)
        canvas.drawCircle(cx, cy, coreRadius, ringPaint)

        val icon = if (success) checkIcon else nfcIcon
        val iconSize = Ui.dp(context, if (success) 34 else 38)
        val left = (cx - iconSize / 2f).toInt()
        val top = (cy - iconSize / 2f).toInt()
        icon?.setTint(if (success) Color.WHITE else activeColor)
        icon?.setBounds(left, top, left + iconSize, top + iconSize)
        icon?.draw(canvas)
    }
}
