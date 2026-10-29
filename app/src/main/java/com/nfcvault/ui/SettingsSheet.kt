package com.nfcvault.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.nfcvault.R

/** Backend endpoint, operator identity and continuous inventory mode. */
class SettingsSheet(
    context: Context,
    serverUrl: String,
    deviceId: String,
    operatorId: String,
    batchMode: Boolean,
    private val onSave: (serverUrl: String, operatorId: String, batchMode: Boolean) -> Unit,
    private val onSyncNow: () -> Unit,
) : BottomSheetDialog(context) {

    private val urlField: EditText
    private val operatorField: EditText
    private val batchSwitch: SwitchCompat

    init {
        val content = buildContent(context, serverUrl, deviceId, operatorId, batchMode)
        urlField = content.first
        operatorField = content.second
        batchSwitch = content.third
        setContentView(content.fourth)
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        window?.setDimAmount(0.7f)
        setOnShowListener {
            val sheet = findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.setBackgroundColor(Color.TRANSPARENT)
            sheet?.let {
                BottomSheetBehavior.from(it).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }
    }

    private fun buildContent(
        context: Context,
        serverUrl: String,
        deviceId: String,
        operatorId: String,
        batchMode: Boolean,
    ): Quad<EditText, EditText, SwitchCompat, View> {
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            background = Ui.roundedTop(context, Ui.color(context, R.color.surface), 26)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 22), Ui.dp(context, 12), Ui.dp(context, 22), Ui.dp(context, 28))
        }
        scroll.addView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        root.addView(View(context).apply {
            background = Ui.rounded(context, Ui.alpha(Ui.color(context, R.color.text_secondary), 90), 30)
            layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 38), Ui.dp(context, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = Ui.dp(context, 18)
            }
        })

        root.addView(Ui.text(context, "Settings", 22f, bold = true))
        root.addView(
            Ui.text(
                context,
                "Backend connection, operator identity and scan behaviour.",
                12.5f,
                Ui.color(context, R.color.text_secondary),
            ).apply { setPadding(0, Ui.dp(context, 5), 0, 0) },
        )

        val url = editField(
            context,
            root,
            "SYNC ENDPOINT",
            serverUrl,
            "https://erp.example.com/nfc/sync",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )
        val operator = editField(
            context,
            root,
            "OPERATOR ID",
            operatorId,
            "Employee badge or operator code",
            InputType.TYPE_CLASS_TEXT,
        )

        root.addView(sectionLabel(context, "DEVICE ID"), topMargin(context, 16))
        root.addView(
            Ui.text(context, deviceId, 12f, Ui.color(context, R.color.text_primary), mono = true)
                .apply { setTextIsSelectable(true) }
                .also { text ->
                    text.setPadding(
                        Ui.dp(context, 12),
                        Ui.dp(context, 10),
                        Ui.dp(context, 12),
                        Ui.dp(context, 10),
                    )
                    text.background = Ui.rounded(
                        context,
                        Ui.color(context, R.color.surface_alt),
                        12,
                        Ui.color(context, R.color.border),
                    )
                },
            topMargin(context, 5),
        )
        root.addView(
            Ui.text(
                context,
                "Sent with every sync payload so the backend can identify this handset.",
                10.5f,
                Ui.color(context, R.color.text_secondary),
            ).apply { setPadding(0, Ui.dp(context, 5), 0, 0) },
        )

        val switch = SwitchCompat(context).apply {
            text = "Continuous batch scan"
            textSize = 13f
            setTextColor(Ui.color(context, R.color.text_primary))
            isChecked = batchMode
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(
                states,
                intArrayOf(Ui.color(context, R.color.accent), Ui.color(context, R.color.text_secondary)),
            )
            trackTintList = ColorStateList(
                states,
                intArrayOf(
                    Ui.alpha(Ui.color(context, R.color.accent), 90),
                    Ui.alpha(Ui.color(context, R.color.text_secondary), 50),
                ),
            )
            setPadding(0, Ui.dp(context, 12), 0, 0)
        }
        root.addView(switch)
        root.addView(
            Ui.text(
                context,
                "Keeps the scan sheet open for sequential inventory. The same tag is ignored " +
                    "for 1.8 s to avoid duplicate taps.",
                11f,
                Ui.color(context, R.color.text_secondary),
            ).apply {
                setPadding(0, Ui.dp(context, 4), 0, 0)
                setLineSpacing(Ui.dpF(context, 1.5f), 1f)
            },
        )

        val save = MaterialButton(context).apply {
            text = "Save settings"
            isAllCaps = false
            textSize = 13f
            cornerRadius = Ui.dp(context, 13)
            backgroundTintList = ColorStateList.valueOf(Ui.color(context, R.color.accent))
            setTextColor(Color.WHITE)
            setOnClickListener {
                onSave(url.text.toString(), operator.text.toString(), switch.isChecked)
                dismiss()
            }
        }
        root.addView(save, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            Ui.dp(context, 50),
        ).apply { topMargin = Ui.dp(context, 22) })

        val syncNow = MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = "Save and sync now"
            isAllCaps = false
            textSize = 12.5f
            cornerRadius = Ui.dp(context, 13)
            setTextColor(Ui.color(context, R.color.text_primary))
            strokeColor = ColorStateList.valueOf(Ui.color(context, R.color.border))
            strokeWidth = Ui.dp(context, 1)
            backgroundTintList = ColorStateList.valueOf(Ui.color(context, R.color.surface_alt))
            setIconResource(R.drawable.ic_sync)
            iconSize = Ui.dp(context, 16)
            iconPadding = Ui.dp(context, 6)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            setIconTint(ColorStateList.valueOf(Ui.color(context, R.color.text_primary)))
            setOnClickListener {
                onSave(url.text.toString(), operator.text.toString(), switch.isChecked)
                onSyncNow()
                dismiss()
            }
        }
        root.addView(syncNow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            Ui.dp(context, 48),
        ).apply { topMargin = Ui.dp(context, 8) })

        root.addView(
            Ui.text(
                context,
                "Offline queue: tags are stored locally and marked Pending sync until the " +
                    "backend accepts them. Nothing is uploaded automatically.",
                10.5f,
                Ui.color(context, R.color.text_secondary),
            ).apply {
                setPadding(0, Ui.dp(context, 16), 0, 0)
                setLineSpacing(Ui.dpF(context, 1.5f), 1f)
            },
        )

        return Quad(url, operator, switch, scroll)
    }

    private fun editField(
        context: Context,
        parent: LinearLayout,
        label: String,
        value: String,
        hint: String,
        inputType: Int,
    ): EditText {
        parent.addView(sectionLabel(context, label), topMargin(context, 18))
        val field = EditText(context).apply {
            setText(value)
            this.hint = hint
            this.inputType = inputType
            isSingleLine = true
            textSize = 13f
            setTextColor(Ui.color(context, R.color.text_primary))
            setHintTextColor(Ui.color(context, R.color.text_secondary))
            setPadding(Ui.dp(context, 12), Ui.dp(context, 11), Ui.dp(context, 12), Ui.dp(context, 11))
            background = Ui.rounded(
                context,
                Ui.color(context, R.color.surface_alt),
                12,
                Ui.color(context, R.color.border),
            )
        }
        parent.addView(field, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Ui.dp(context, 5) })
        return field
    }

    private fun sectionLabel(context: Context, text: String): android.widget.TextView =
        Ui.text(context, text, 9.5f, Ui.color(context, R.color.text_secondary), bold = true)
            .apply { letterSpacing = 0.12f }

    private fun topMargin(context: Context, value: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Ui.dp(context, value) }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
