package com.nfcvault.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.nfcvault.R
import com.nfcvault.data.model.SyncStatus
import com.nfcvault.data.model.TagCategory

/** Small, dependency-free design system shared by every screen. */
object Ui {

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun dpF(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    fun color(context: Context, res: Int): Int = ContextCompat.getColor(context, res)

    fun alpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    fun text(
        context: Context,
        value: CharSequence = "",
        sizeSp: Float = 14f,
        textColor: Int = color(context, R.color.text_primary),
        bold: Boolean = false,
        mono: Boolean = false,
    ): TextView = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(textColor)
        includeFontPadding = false
        typeface = when {
            mono -> Typeface.MONOSPACE
            bold -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
    }

    fun rounded(
        context: Context,
        fill: Int,
        radiusDp: Int,
        stroke: Int? = null,
        strokeDp: Int = 1,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpF(context, radiusDp.toFloat())
        setColor(fill)
        if (stroke != null) setStroke(dp(context, strokeDp), stroke)
    }

    fun roundedTop(
        context: Context,
        fill: Int,
        radiusDp: Int,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = floatArrayOf(
            dpF(context, radiusDp.toFloat()), dpF(context, radiusDp.toFloat()),
            dpF(context, radiusDp.toFloat()), dpF(context, radiusDp.toFloat()),
            0f, 0f, 0f, 0f,
        )
        setColor(fill)
    }

    fun gradient(
        context: Context,
        start: Int,
        end: Int,
        radiusDp: Int,
        stroke: Int? = null,
        strokeDp: Int = 1,
    ): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(start, end),
    ).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpF(context, radiusDp.toFloat())
        if (stroke != null) setStroke(dp(context, strokeDp), stroke)
    }

    fun ripple(): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(Color.argb(38, 255, 255, 255)),
        null,
        null,
    )

    fun borderlessBackground(context: Context): Int {
        val out = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)
        return out.resourceId
    }

    fun icon(
        context: Context,
        drawableRes: Int,
        tint: Int = color(context, R.color.text_secondary),
        sizeDp: Int = 40,
    ): ImageView = ImageView(context).apply {
        setImageResource(drawableRes)
        setColorFilter(tint)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = dp(context, 9)
        setPadding(pad, pad, pad, pad)
        setBackgroundResource(borderlessBackground(context))
        layoutParams = android.widget.LinearLayout.LayoutParams(
            dp(context, sizeDp),
            dp(context, sizeDp),
        )
        isClickable = true
        isFocusable = true
    }

    fun pill(
        context: Context,
        value: CharSequence,
        textColor: Int,
        fill: Int,
        stroke: Int? = null,
    ): TextView = text(context, value, 10.5f, textColor, bold = true).apply {
        val h = dp(context, 7)
        val v = dp(context, 4)
        setPadding(h, v, h, v)
        background = rounded(context, fill, 30, stroke)
        isSingleLine = true
        letterSpacing = 0.04f
    }

    fun categoryColor(context: Context, category: TagCategory): Int = color(
        context,
        when (category) {
            TagCategory.ACCESS -> R.color.cat_access
            TagCategory.ASSET -> R.color.cat_asset
            TagCategory.VISITOR -> R.color.cat_visitor
            TagCategory.OTHER -> R.color.cat_other
        },
    )

    fun syncColor(context: Context, status: SyncStatus): Int = color(
        context,
        when (status) {
            SyncStatus.SYNCED -> R.color.emerald
            SyncStatus.PENDING -> R.color.warning
            SyncStatus.ERROR -> R.color.danger
            SyncStatus.LOCAL_ONLY -> R.color.text_secondary
        },
    )

    fun hapticSuccess(view: View) {
        vibrate(view, longArrayOf(0, 28), intArrayOf(0, 150))
    }

    fun hapticError(view: View) {
        vibrate(view, longArrayOf(0, 34, 60, 34), intArrayOf(0, 180, 0, 180))
    }

    private fun vibrate(view: View, timings: LongArray, amplitudes: IntArray) {
        val context = view.context
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        }
    }

    fun copy(context: Context, label: String, value: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    }
}
