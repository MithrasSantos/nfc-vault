package com.nfcvault.ui

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nfcvault.R
import com.nfcvault.data.model.NfcCard
import com.nfcvault.data.model.SyncStatus
import com.nfcvault.data.model.TagCategory
import com.nfcvault.data.model.TagInsights

/** Wallet-style tag cards: one physical card per row, no plain text list. */
class WalletAdapter(
    private val onOpen: (NfcCard) -> Unit,
    private val onCopy: (NfcCard) -> Unit,
) : ListAdapter<NfcCard, WalletAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(parent, onOpen, onCopy)

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    class Holder(
        parent: ViewGroup,
        private val onOpen: (NfcCard) -> Unit,
        private val onCopy: (NfcCard) -> Unit,
    ) : RecyclerView.ViewHolder(
        LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 18), Ui.dp(context, 16), Ui.dp(context, 18), Ui.dp(context, 16))
            minimumHeight = Ui.dp(context, 166)
            clipToOutline = true
            elevation = Ui.dpF(context, 5f)
            foreground = Ui.ripple()
            isClickable = true
            isFocusable = true
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(context, 14) }
        },
    ) {
        private val context = parent.context
        private val root = itemView as LinearLayout
        private val techIcon: ImageView
        private val name: TextView
        private val categoryChip: TextView
        private val uid: TextView
        private val copy: ImageView
        private val techChip: TextView
        private val syncDot: View
        private val syncLabel: TextView
        private val time: TextView

        var card: NfcCard? = null
            private set

        init {
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            techIcon = ImageView(context).apply {
                setImageResource(R.drawable.ic_nfc)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 24), Ui.dp(context, 24)).apply {
                    rightMargin = Ui.dp(context, 9)
                }
            }
            name = Ui.text(context, "", 17f, bold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            categoryChip = Ui.text(context, "", 10.5f, bold = true).apply {
                setPadding(Ui.dp(context, 8), Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 4))
                isSingleLine = true
                letterSpacing = 0.03f
            }
            top.addView(techIcon)
            top.addView(name)
            top.addView(categoryChip)

            val uidRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            uid = Ui.text(context, "", 15f, mono = true).apply {
                letterSpacing = 0.06f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setTextIsSelectable(false)
            }
            copy = Ui.icon(context, R.drawable.ic_copy, Ui.color(context, R.color.text_secondary), 38)
            copy.contentDescription = "Copy UID"
            uidRow.addView(uid)
            uidRow.addView(copy)

            val bottom = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            techChip = Ui.text(context, "", 10f, bold = true).apply {
                setPadding(Ui.dp(context, 8), Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 4))
                letterSpacing = 0.05f
            }
            syncDot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 7), Ui.dp(context, 7)).apply {
                    marginStart = Ui.dp(context, 10)
                    marginEnd = Ui.dp(context, 5)
                }
            }
            syncLabel = Ui.text(context, "", 10.5f, Ui.color(context, R.color.text_secondary))
            time = Ui.text(context, "", 10.5f, Ui.color(context, R.color.text_secondary)).apply {
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            bottom.addView(techChip)
            bottom.addView(syncDot)
            bottom.addView(syncLabel)
            bottom.addView(time)

            root.addView(top)
            root.addView(uidRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(context, 12) })
            root.addView(bottom, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(context, 13) })

            root.setOnClickListener { card?.let(onOpen) }
            copy.setOnClickListener { card?.let(onCopy) }
        }

        fun bind(item: NfcCard) {
            card = item
            val categoryColor = Ui.categoryColor(context, item.category)
            root.background = Ui.gradient(
                context,
                Ui.color(context, item.category.cardStart()),
                Ui.color(context, item.category.cardEnd()),
                20,
                Ui.color(context, R.color.border),
            )
            techIcon.setColorFilter(categoryColor)

            name.text = item.displayName
            categoryChip.text = item.category.label.uppercase()
            categoryChip.setTextColor(categoryColor)
            categoryChip.background = Ui.rounded(
                context,
                Ui.alpha(categoryColor, 30),
                30,
                Ui.alpha(categoryColor, 90),
            )

            uid.text = item.uid
            techChip.text = item.shortTech.uppercase()
            techChip.setTextColor(Ui.color(context, R.color.accent))
            techChip.background = Ui.rounded(
                context,
                Ui.alpha(Ui.color(context, R.color.accent), 26),
                30,
                Ui.alpha(Ui.color(context, R.color.accent), 70),
            )

            val syncColor = Ui.syncColor(context, item.syncStatus)
            syncDot.background = Ui.rounded(context, syncColor, 30)
            syncDot.visibility = if (item.syncStatus == SyncStatus.LOCAL_ONLY) View.GONE else View.VISIBLE
            syncLabel.text = if (item.syncStatus == SyncStatus.LOCAL_ONLY) {
                "Local only"
            } else {
                item.syncStatus.label
            }
            syncLabel.setTextColor(syncColor)
            time.text = "${TagInsights.friendlyTime(item.lastScannedAt)} · ${item.scanCount}×"
            root.contentDescription =
                "${item.displayName}, ${item.cardType}, UID ${item.uid}, ${item.scanCount} scans"
            root.alpha = 1f
        }
    }

    private object Diff : DiffUtil.ItemCallback<NfcCard>() {
        override fun areItemsTheSame(oldItem: NfcCard, newItem: NfcCard): Boolean =
            oldItem.uid == newItem.uid

        override fun areContentsTheSame(oldItem: NfcCard, newItem: NfcCard): Boolean =
            oldItem == newItem
    }
}

private fun TagCategory.cardStart(): Int = when (this) {
    TagCategory.ACCESS -> R.color.card_access_start
    TagCategory.ASSET -> R.color.card_asset_start
    TagCategory.VISITOR -> R.color.card_visitor_start
    TagCategory.OTHER -> R.color.card_other_start
}

private fun TagCategory.cardEnd(): Int = when (this) {
    TagCategory.ACCESS -> R.color.card_access_end
    TagCategory.ASSET -> R.color.card_asset_end
    TagCategory.VISITOR -> R.color.card_visitor_end
    TagCategory.OTHER -> R.color.card_other_end
}
