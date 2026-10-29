package com.nfcvault.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.nfcvault.R

/**
 * Modal bottom sheet that arms reader mode. In batch mode it stays open and
 * keeps accepting tags; in single mode it flashes success and closes itself.
 */
class ScanSheet(
    context: Context,
    private val batchMode: Boolean,
    private val onFinished: () -> Unit,
) : BottomSheetDialog(context) {

    private val radar = RadarView(context)
    private val statusText = Ui.text(context, "", 16f, Ui.color(context, R.color.accent), bold = true)
    private val hintText = Ui.text(context, "", 12f, Ui.color(context, R.color.text_secondary))
    private val counterText = Ui.text(context, "", 11f, Ui.color(context, R.color.text_secondary))
    private var savedCount = 0
    private var completed = false

    init {
        setContentView(buildContent(context))
        setCancelable(true)
        setCanceledOnTouchOutside(false)
        window?.setDimAmount(0.72f)
        setOnDismissListener { onFinished() }
        setOnShowListener {
            val sheet = findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.setBackgroundColor(Color.TRANSPARENT)
            sheet?.let {
                BottomSheetBehavior.from(it).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                    isDraggable = false
                }
            }
        }
    }

    fun onResult(event: ScanEvent) {
        if (!isShowing) return
        if (event.success) {
            if (!batchMode && completed) return
            savedCount += 1
            completed = !batchMode
            radar.setSuccess(true)
            statusText.text = if (event.isNew) "New tag saved" else "Tag updated"
            statusText.setTextColor(Ui.color(context, R.color.emerald))
            hintText.text = "${event.displayName} · ${event.uid} · ${event.scanCount}×"
            counterText.text = "$savedCount tag(s) this session"

            if (batchMode) {
                statusText.postDelayed({
                    if (isShowing) {
                        radar.setSuccess(false)
                        statusText.text = "Waiting for the next tag…"
                        statusText.setTextColor(Ui.color(context, R.color.accent))
                        hintText.text = "Batch mode · keep tapping tags"
                        completed = false
                    }
                }, 900L)
            } else {
                statusText.postDelayed({ if (isShowing) dismiss() }, 1_050L)
            }
        } else {
            radar.setSuccess(false)
            statusText.text = "Read failed"
            statusText.setTextColor(Ui.color(context, R.color.danger))
            hintText.text = event.message
        }
    }

    fun savedCount(): Int = savedCount

    private fun buildContent(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 22), Ui.dp(context, 12), Ui.dp(context, 22), Ui.dp(context, 24))
            background = Ui.roundedTop(context, Ui.color(context, R.color.surface), 26)
        }

        val handle = View(context).apply {
            background = Ui.rounded(context, Ui.alpha(Ui.color(context, R.color.text_secondary), 90), 30)
            layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 38), Ui.dp(context, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = Ui.dp(context, 20)
            }
        }
        root.addView(handle)

        root.addView(
            Ui.text(
                context,
                if (batchMode) "Batch scan" else "Scan NFC tag",
                22f,
                bold = true,
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            Ui.text(
                context,
                if (batchMode) {
                    "Continuous inventory mode · tags are saved one after another."
                } else {
                    "Hold the NFC card or asset near the back of the device."
                },
                12.5f,
                Ui.color(context, R.color.text_secondary),
            ).apply {
                setPadding(0, Ui.dp(context, 5), 0, 0)
                setLineSpacing(Ui.dpF(context, 2f), 1f)
            },
        )

        val radarHolder = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(context, 214),
            ).apply { topMargin = Ui.dp(context, 6) }
        }
        radarHolder.addView(
            radar,
            FrameLayout.LayoutParams(Ui.dp(context, 214), Ui.dp(context, 214), Gravity.CENTER),
        )
        root.addView(radarHolder)

        statusText.text = if (batchMode) "Batch mode armed" else "Waiting for a tag…"
        statusText.gravity = Gravity.CENTER
        root.addView(statusText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        hintText.text = "Reader mode is active · NFC payments are paused while scanning"
        hintText.gravity = Gravity.CENTER
        hintText.setPadding(0, Ui.dp(context, 6), 0, 0)
        root.addView(hintText)
        counterText.text = "0 tags this session"
        counterText.gravity = Gravity.CENTER
        counterText.setPadding(0, Ui.dp(context, 10), 0, 0)
        root.addView(counterText)

        val cancel = MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = "Cancel"
            isAllCaps = false
            textSize = 14f
            setTextColor(Ui.color(context, R.color.text_primary))
            strokeColor = ColorStateList.valueOf(Ui.color(context, R.color.border))
            strokeWidth = Ui.dp(context, 1)
            cornerRadius = Ui.dp(context, 14)
            backgroundTintList = ColorStateList.valueOf(Ui.color(context, R.color.surface_alt))
            setOnClickListener { dismiss() }
        }
        root.addView(cancel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            Ui.dp(context, 50),
        ).apply { topMargin = Ui.dp(context, 18) })

        return root
    }
}
