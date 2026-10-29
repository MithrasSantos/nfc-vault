package com.nfcvault.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.nfcvault.R
import com.nfcvault.data.model.NfcCard
import com.nfcvault.data.model.NdefRecordInfo
import com.nfcvault.data.model.TagInsights

/** Full inspector: hardware metadata, decoded NDEF, scan history and actions. */
class TagDetailsSheet(
    context: Context,
    private val card: NfcCard,
    private val logs: List<Long>,
    private val onEmulate: (NfcCard) -> Unit,
    private val onEdit: (NfcCard) -> Unit,
    private val onSync: (NfcCard) -> Unit,
    private val onDelete: (NfcCard) -> Unit,
    private val onExportJson: (NfcCard) -> Unit,
    private val onExportCsv: (NfcCard) -> Unit,
) : BottomSheetDialog(context) {

    init {
        setContentView(buildContent(context))
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
                    isDraggable = true
                }
            }
        }
    }

    private fun buildContent(context: Context): View {
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

        root.addView(
            Ui.text(context, card.displayName, 22f, bold = true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
        )

        val badges = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val categoryColor = Ui.categoryColor(context, card.category)
        badges.addView(Ui.pill(
            context,
            card.category.label.uppercase(),
            categoryColor,
            Ui.alpha(categoryColor, 30),
            Ui.alpha(categoryColor, 90),
        ))
        val syncColor = Ui.syncColor(context, card.syncStatus)
        badges.addView(Ui.pill(
            context,
            card.syncStatus.label.uppercase(),
            syncColor,
            Ui.alpha(syncColor, 26),
            Ui.alpha(syncColor, 80),
        ).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.marginStart = Ui.dp(context, 7)
        })
        badges.addView(Ui.text(
            context,
            "${card.scanCount} scans",
            11f,
            Ui.color(context, R.color.text_secondary),
        ).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.marginStart = Ui.dp(context, 9)
        })
        root.addView(badges, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Ui.dp(context, 9) })

        root.addView(uidBox(context), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Ui.dp(context, 16) })

        root.addView(sectionTitle(context, "Technology"), topMargin(context, 20))
        root.addView(techChips(context))

        if (!card.hasNdef) {
            root.addView(uidOnlyBox(context), topMargin(context, 14))
        }

        root.addView(sectionTitle(context, "Technical metadata"), topMargin(context, 20))
        root.addView(metadataBox(context))

        root.addView(sectionTitle(context, "NDEF payload"), topMargin(context, 20))
        if (card.records.isEmpty()) {
            root.addView(emptyNdefBox(context))
        } else {
            card.records.forEachIndexed { index, record ->
                root.addView(recordBox(context, index + 1, record), topMargin(context, 9))
            }
            card.rawNdefHex?.let { raw ->
                root.addView(sectionTitle(context, "Raw NDEF", small = true), topMargin(context, 16))
                root.addView(
                    Ui.text(context, raw, 10.5f, Ui.color(context, R.color.text_secondary), mono = true)
                        .apply { setTextIsSelectable(true) },
                )
            }
        }

        root.addView(sectionTitle(context, "Scan history"), topMargin(context, 20))
        root.addView(historyBox(context))

        if (card.notes.isNotBlank()) {
            root.addView(sectionTitle(context, "Notes"), topMargin(context, 20))
            root.addView(
                Ui.text(context, card.notes, 12.5f, Ui.color(context, R.color.text_primary))
                    .apply { setTextIsSelectable(true) },
            )
        }

        root.addView(sectionTitle(context, "Actions"), topMargin(context, 22))
        val emulate = actionButton(context, "Emulate NDEF on this phone", R.drawable.ic_nfc, filled = true)
        val canEmulate = card.hasNdef && !card.rawNdefHex.isNullOrBlank()
        emulate.isEnabled = canEmulate
        emulate.alpha = if (canEmulate) 1f else 0.38f
        emulate.setOnClickListener {
            dismiss()
            onEmulate(card)
        }
        root.addView(emulate, topMargin(context, 10))
        root.addView(
            Ui.text(
                context,
                "Phones emulate NDEF / Type 4 only. MIFARE Classic, Ultralight and " +
                    "readers that check the original card UID cannot be emulated by this hardware.",
                10f,
                Ui.color(context, R.color.text_secondary),
            ).apply {
                setPadding(0, Ui.dp(context, 7), 0, 0)
                setLineSpacing(Ui.dpF(context, 1.4f), 1f)
            },
        )

        val edit = actionButton(context, "Edit", R.drawable.ic_edit, filled = true)
        edit.setOnClickListener {
            dismiss()
            onEdit(card)
        }
        val sync = actionButton(context, "Sync", R.drawable.ic_sync)
        sync.setOnClickListener {
            dismiss()
            onSync(card)
        }
        root.addView(actionRow(context, edit, sync), topMargin(context, 10))

        val exportJson = actionButton(context, "Export JSON", R.drawable.ic_share)
        exportJson.setOnClickListener { onExportJson(card) }
        val exportCsv = actionButton(context, "Export CSV", R.drawable.ic_share)
        exportCsv.setOnClickListener { onExportCsv(card) }
        root.addView(actionRow(context, exportJson, exportCsv), topMargin(context, 8))

        val delete = actionButton(
            context,
            "Delete from vault",
            R.drawable.ic_delete,
            danger = true,
        )
        delete.setOnClickListener {
            dismiss()
            onDelete(card)
        }
        root.addView(delete, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Ui.dp(context, 8) })

        val close = Ui.text(
            context,
            "Close",
            12.5f,
            Ui.color(context, R.color.text_secondary),
            bold = true,
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(context, 14), 0, 0)
            isClickable = true
            setOnClickListener { dismiss() }
        }
        root.addView(close, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        return scroll
    }

    private fun uidBox(context: Context): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
            background = Ui.rounded(
                context,
                Ui.color(context, R.color.surface_alt),
                14,
                Ui.color(context, R.color.border),
            )
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            Ui.text(context, "UID", 9.5f, Ui.color(context, R.color.text_secondary), bold = true)
                .apply { letterSpacing = 0.12f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val copy = Ui.icon(context, R.drawable.ic_copy, Ui.color(context, R.color.accent), 36)
        copy.contentDescription = "Copy UID"
        copy.setOnClickListener { Ui.copy(context, "UID", card.uid) }
        header.addView(copy)
        box.addView(header)
        box.addView(
            Ui.text(context, card.uid, 15f, Ui.color(context, R.color.text_primary), mono = true)
                .apply {
                    letterSpacing = 0.05f
                    setTextIsSelectable(true)
                },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(context, 5) },
        )
        return box
    }

    private fun techChips(context: Context): View {
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (card.tech.isEmpty()) {
            row.addView(Ui.pill(
                context,
                "NFC",
                Ui.color(context, R.color.accent),
                Ui.alpha(Ui.color(context, R.color.accent), 26),
                Ui.alpha(Ui.color(context, R.color.accent), 70),
            ))
        } else {
            card.tech.forEachIndexed { index, tech ->
                row.addView(Ui.pill(
                    context,
                    tech.uppercase(),
                    Ui.color(context, R.color.accent),
                    Ui.alpha(Ui.color(context, R.color.accent), 24),
                    Ui.alpha(Ui.color(context, R.color.accent), 64),
                ).apply {
                    (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = Ui.dp(context, 6)
                })
            }
        }
        if (card.hasNdef) {
            row.addView(Ui.pill(
                context,
                "NDEF",
                Ui.color(context, R.color.emerald),
                Ui.alpha(Ui.color(context, R.color.emerald), 24),
                Ui.alpha(Ui.color(context, R.color.emerald), 64),
            ).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.marginStart = Ui.dp(context, 4)
            })
        }
        scroll.addView(row)
        return scroll
    }

    private fun metadataBox(context: Context): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
            background = Ui.rounded(
                context,
                Ui.color(context, R.color.surface_alt),
                14,
                Ui.color(context, R.color.border),
            )
        }
        metaRow(context, box, "Card type", card.cardType)
        metaRow(context, box, "Technology", card.tech.joinToString(", ").ifBlank { "—" })
        metaRow(context, box, "Memory", TagInsights.memoryLabel(card))
        metaRow(context, box, "Write status", TagInsights.writeStatus(card.ndefType, card.ndefWritable))
        metaRow(context, box, "Payload", TagInsights.payloadLabel(card))
        metaRow(context, box, "SAK", card.extras["NfcA.sak"] ?: "—")
        metaRow(context, box, "ATQA", card.extras["NfcA.atqa"] ?: "—")
        card.extras
            .filterKeys { it != "NfcA.sak" && it != "NfcA.atqa" && it != "MifareClassic.size" }
            .forEach { (key, value) -> metaRow(context, box, key, value) }
        return box
    }

    private fun metaRow(context: Context, parent: LinearLayout, label: String, value: String) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 4))
        }
        row.addView(
            Ui.text(context, label, 11.5f, Ui.color(context, R.color.text_secondary)).apply {
                maxLines = 2
            },
            LinearLayout.LayoutParams(Ui.dp(context, 118), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        row.addView(
            Ui.text(context, value, 11.5f, Ui.color(context, R.color.text_primary)).apply {
                setTextIsSelectable(true)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        parent.addView(row)
    }

    private fun uidOnlyBox(context: Context): View {
        val emerald = Ui.color(context, R.color.emerald)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
            background = Ui.rounded(context, Ui.alpha(emerald, 16), 14, Ui.alpha(emerald, 60))
            addView(Ui.text(context, "Physical ID tag · UID only", 12.5f, emerald, bold = true))
            addView(
                Ui.text(
                    context,
                    "This tag does not store NDEF data. Its unique ID is still a valid identifier " +
                        "for access control, assets and inventory.",
                    11.5f,
                    Ui.color(context, R.color.text_secondary),
                ).apply {
                    setPadding(0, Ui.dp(context, 4), 0, 0)
                    setLineSpacing(Ui.dpF(context, 1.5f), 1f)
                },
            )
        }
    }

    private fun emptyNdefBox(context: Context): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        background = Ui.rounded(
            context,
            Ui.color(context, R.color.surface_alt),
            14,
            Ui.color(context, R.color.border),
        )
        addView(Ui.text(context, "No NDEF records", 12.5f, bold = true))
        addView(
            Ui.text(
                context,
                "The tag was read successfully. Only its hardware identity is available.",
                11.5f,
                Ui.color(context, R.color.text_secondary),
            ).apply { setPadding(0, Ui.dp(context, 4), 0, 0) },
        )
    }

    private fun recordBox(context: Context, index: Int, record: NdefRecordInfo): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
            background = Ui.rounded(
                context,
                Ui.color(context, R.color.surface_alt),
                14,
                Ui.color(context, R.color.border),
            )
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(Ui.pill(
            context,
            record.kind.uppercase(),
            Ui.color(context, R.color.accent),
            Ui.alpha(Ui.color(context, R.color.accent), 24),
            Ui.alpha(Ui.color(context, R.color.accent), 64),
        ))
        top.addView(
            Ui.text(
                context,
                "#$index · ${record.type.ifBlank { "record" }}",
                10.5f,
                Ui.color(context, R.color.text_secondary),
            ).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.marginStart = Ui.dp(context, 8)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        box.addView(top)
        box.addView(
            Ui.text(
                context,
                record.value.ifBlank { "(empty payload)" },
                12.5f,
                Ui.color(context, R.color.text_primary),
                mono = record.kind == "raw",
            ).apply {
                setTextIsSelectable(true)
                setPadding(0, Ui.dp(context, 8), 0, 0)
                setLineSpacing(Ui.dpF(context, 1.5f), 1f)
            },
        )
        if (record.payloadHex.isNotBlank()) {
            box.addView(
                Ui.text(
                    context,
                    record.payloadHex,
                    10f,
                    Ui.color(context, R.color.text_secondary),
                    mono = true,
                ).apply {
                    setTextIsSelectable(true)
                    setPadding(0, Ui.dp(context, 6), 0, 0)
                },
            )
        }
        return box
    }

    private fun historyBox(context: Context): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        background = Ui.rounded(
            context,
            Ui.color(context, R.color.surface_alt),
            14,
            Ui.color(context, R.color.border),
        )
        addView(
            Ui.text(
                context,
                "Scanned ${card.scanCount} times",
                12.5f,
                Ui.color(context, R.color.text_primary),
                bold = true,
            ),
        )
        addView(metaLine(context, "First seen", TagInsights.fullTime(card.firstSeenAt)))
        addView(metaLine(context, "Last seen", TagInsights.fullTime(card.lastScannedAt)))
        if (logs.isNotEmpty()) {
            addView(
                Ui.text(
                    context,
                    "Recent taps",
                    10f,
                    Ui.color(context, R.color.text_secondary),
                    bold = true,
                ).apply {
                    letterSpacing = 0.1f
                    setPadding(0, Ui.dp(context, 9), 0, Ui.dp(context, 2))
                },
            )
            logs.take(8).forEach { timestamp ->
                addView(
                    Ui.text(
                        context,
                        "• ${TagInsights.friendlyTime(timestamp)}",
                        11f,
                        Ui.color(context, R.color.text_secondary),
                    ).apply { setPadding(0, Ui.dp(context, 2), 0, 0) },
                )
            }
        }
    }

    private fun metaLine(context: Context, label: String, value: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(context, 4), 0, 0)
            addView(
                Ui.text(context, label, 11f, Ui.color(context, R.color.text_secondary)),
                LinearLayout.LayoutParams(Ui.dp(context, 86), ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(
                Ui.text(context, value, 11f, Ui.color(context, R.color.text_primary)),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }

    private fun sectionTitle(context: Context, title: String, small: Boolean = false): TextView =
        Ui.text(
            context,
            title,
            if (small) 10f else 12f,
            Ui.color(context, R.color.text_secondary),
            bold = true,
        ).apply {
            letterSpacing = 0.1f
            text = title.uppercase()
        }

    private fun topMargin(context: Context, value: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Ui.dp(context, value) }

    private fun actionButton(
        context: Context,
        label: String,
        iconRes: Int,
        filled: Boolean = false,
        danger: Boolean = false,
    ): MaterialButton {
        val accent = Ui.color(context, R.color.accent)
        val dangerColor = Ui.color(context, R.color.danger)
        val tint = when {
            danger -> dangerColor
            filled -> Color.WHITE
            else -> Ui.color(context, R.color.text_primary)
        }
        val button = if (filled) {
            MaterialButton(context)
        } else {
            MaterialButton(
                context,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            )
        }
        button.apply {
            text = label
            isAllCaps = false
            textSize = 12f
            cornerRadius = Ui.dp(context, 12)
            minHeight = Ui.dp(context, 44)
            setTextColor(tint)
            setIconResource(iconRes)
            iconSize = Ui.dp(context, 16)
            iconPadding = Ui.dp(context, 6)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            setIconTint(ColorStateList.valueOf(tint))
            if (filled) {
                backgroundTintList = ColorStateList.valueOf(accent)
                strokeWidth = 0
            } else {
                backgroundTintList = ColorStateList.valueOf(Ui.color(context, R.color.surface_alt))
                strokeColor = ColorStateList.valueOf(
                    if (danger) Ui.alpha(dangerColor, 110) else Ui.color(context, R.color.border),
                )
                strokeWidth = Ui.dp(context, 1)
            }
        }
        return button
    }

    private fun actionRow(context: Context, first: View, second: View): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(second, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(context, 8)
            })
        }
}
