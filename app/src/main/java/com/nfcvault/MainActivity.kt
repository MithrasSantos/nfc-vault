package com.nfcvault

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.nfcvault.data.Exporters
import com.nfcvault.data.model.NfcCard
import com.nfcvault.data.model.NfcStatus
import com.nfcvault.data.model.TagCategory
import com.nfcvault.data.model.TagInsights
import com.nfcvault.nfc.EmulationStore
import com.nfcvault.nfc.NfcController
import com.nfcvault.ui.ScanEvent
import com.nfcvault.ui.ScanSheet
import com.nfcvault.ui.SettingsSheet
import com.nfcvault.ui.TagDetailsSheet
import com.nfcvault.ui.Ui
import com.nfcvault.ui.WalletAdapter
import com.nfcvault.ui.WalletUiState
import com.nfcvault.ui.WalletViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: WalletViewModel by viewModels<WalletViewModel>()

    private lateinit var nfcController: NfcController
    private lateinit var adapter: WalletAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var emptyTitle: TextView
    private lateinit var emptyBody: TextView
    private lateinit var emptyState: View
    private lateinit var totalValue: TextView
    private lateinit var lastSyncValue: TextView
    private lateinit var lastSyncSub: TextView
    private lateinit var nfcValue: TextView
    private lateinit var nfcSub: TextView
    private lateinit var searchInput: EditText
    private lateinit var syncButton: ImageView
    private lateinit var fab: ExtendedFloatingActionButton
    private val categoryChips = linkedMapOf<TagCategory?, TextView>()

    private var scanSheet: ScanSheet? = null
    private var emulationDialog: AlertDialog? = null
    private var lastScanToken = 0L
    private var lastMessageToken = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.color(this, R.color.bg)
        window.navigationBarColor = Ui.color(this, R.color.bg)

        nfcController = NfcController(this) { tag -> viewModel.onTagRead(tag) }
        setContentView(buildUi())
        collectState()
        viewModel.refreshNfcStatus()
    }

    override fun onResume() {
        super.onResume()
        nfcController.onResume()
        viewModel.refreshNfcStatus()
    }

    override fun onPause() {
        nfcController.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        nfcController.release()
        scanSheet = null
        emulationDialog?.dismiss()
        emulationDialog = null
        EmulationStore.deactivate(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Ui.color(this@MainActivity, R.color.bg))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }
        root.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        content.addView(buildHeader())
        content.addView(buildSearch(), linearParams(top = 14, left = 20, right = 20))
        content.addView(buildCategoryChips(), linearParams(top = 10, left = 20, right = 20))
        content.addView(buildListArea(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ).apply { topMargin = Ui.dp(this@MainActivity, 10) })

        fab = ExtendedFloatingActionButton(this).apply {
            text = "Scan tag"
            setIconResource(R.drawable.ic_nfc)
            isAllCaps = false
            textSize = 14f
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                Ui.color(this@MainActivity, R.color.accent),
            )
            setTextColor(Color.WHITE)
            setIconTint(android.content.res.ColorStateList.valueOf(Color.WHITE))
            contentDescription = "Start NFC scan"
            setOnClickListener { requestScan() }
        }
        root.addView(fab, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = Ui.dp(this@MainActivity, 24) })

        return root
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 20), 0)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleColumn.addView(Ui.text(this, "NFC Vault", 26f, bold = true))
        titleColumn.addView(
            Ui.text(
                this,
                "Enterprise tag wallet",
                12f,
                Ui.color(this, R.color.text_secondary),
            ).apply { setPadding(0, Ui.dp(this@MainActivity, 2), 0, 0) },
        )
        titleRow.addView(titleColumn, LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ))

        syncButton = Ui.icon(this, R.drawable.ic_sync, Ui.color(this, R.color.accent), 42).apply {
            contentDescription = "Sync all tags"
            setOnClickListener { viewModel.syncAll() }
        }
        titleRow.addView(syncButton)
        val overflow = Ui.icon(
            this,
            R.drawable.ic_more,
            Ui.color(this, R.color.text_secondary),
            42,
        ).apply {
            contentDescription = "More actions"
            setOnClickListener { showOverflow(this) }
        }
        titleRow.addView(overflow)
        header.addView(titleRow)

        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val total = metricTile("SAVED TAGS", "0", "in the vault")
        val sync = metricTile("LAST SYNC", "Never", "queue empty")
        val nfc = metricTile("NFC HARDWARE", "Checking…", "reader mode")
        totalValue = total.value
        lastSyncValue = sync.value
        lastSyncSub = sync.sub
        nfcValue = nfc.value
        nfcSub = nfc.sub
        val tileParams = { index: Int ->
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = if (index == 0) 0 else Ui.dp(this@MainActivity, 6)
                marginEnd = if (index == 2) 0 else Ui.dp(this@MainActivity, 6)
            }
        }
        metrics.addView(total.tile, tileParams(0))
        metrics.addView(sync.tile, tileParams(1))
        metrics.addView(nfc.tile, tileParams(2))
        header.addView(metrics, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Ui.dp(this@MainActivity, 16) })

        return header
    }

    private fun metricTile(label: String, initial: String, initialSub: String): MetricTile {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Ui.dp(this@MainActivity, 12),
                Ui.dp(this@MainActivity, 10),
                Ui.dp(this@MainActivity, 12),
                Ui.dp(this@MainActivity, 10),
            )
            minimumHeight = Ui.dp(this@MainActivity, 70)
            background = Ui.rounded(
                this@MainActivity,
                Ui.color(this@MainActivity, R.color.surface),
                16,
                Ui.color(this@MainActivity, R.color.border),
            )
        }
        tile.addView(
            Ui.text(
                this,
                label,
                8.5f,
                Ui.color(this, R.color.text_secondary),
                bold = true,
            ).apply {
                letterSpacing = 0.08f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        val value = Ui.text(this, initial, 15f, Ui.color(this, R.color.text_primary), bold = true)
            .apply {
                setPadding(0, Ui.dp(this@MainActivity, 4), 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        val sub = Ui.text(this, initialSub, 9.5f, Ui.color(this, R.color.text_secondary))
            .apply {
                setPadding(0, Ui.dp(this@MainActivity, 2), 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        tile.addView(value)
        tile.addView(sub)
        return MetricTile(tile, value, sub)
    }

    private fun buildSearch(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                Ui.dp(this@MainActivity, 10),
                Ui.dp(this@MainActivity, 2),
                Ui.dp(this@MainActivity, 10),
                Ui.dp(this@MainActivity, 2),
            )
            background = Ui.rounded(
                this@MainActivity,
                Ui.color(this@MainActivity, R.color.surface),
                14,
                Ui.color(this@MainActivity, R.color.border),
            )
        }
        box.addView(Ui.icon(this, R.drawable.ic_search, Ui.color(this, R.color.text_secondary), 34))
        searchInput = EditText(this).apply {
            hint = "Search name, UID or category"
            setHintTextColor(Ui.color(this@MainActivity, R.color.text_secondary))
            setTextColor(Ui.color(this@MainActivity, R.color.text_primary))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f)
            background = null
            isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@MainActivity, 5), 0, 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    viewModel.setQuery(s?.toString().orEmpty())
                }
            })
        }
        box.addView(searchInput, LinearLayout.LayoutParams(0, Ui.dp(this@MainActivity, 44), 1f))
        return box
    }

    private fun buildCategoryChips(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        TagCategory.filters.forEach { category ->
            val label = when (category) {
                null -> "All"
                TagCategory.ACCESS -> "Access"
                TagCategory.ASSET -> "Assets"
                TagCategory.VISITOR -> "Visitors"
                TagCategory.OTHER -> "Other"
            }
            val chip = Ui.text(this, label, 11.5f, bold = true).apply {
                setPadding(
                    Ui.dp(this@MainActivity, 13),
                    Ui.dp(this@MainActivity, 7),
                    Ui.dp(this@MainActivity, 13),
                    Ui.dp(this@MainActivity, 7),
                )
                foreground = Ui.ripple()
                isClickable = true
                isFocusable = true
                setOnClickListener { viewModel.setCategory(category) }
            }
            categoryChips[category] = chip
            row.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = Ui.dp(this@MainActivity, 7) })
        }
        scroll.addView(row)
        return scroll
    }

    private fun buildListArea(): View {
        val area = FrameLayout(this)
        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setPadding(
                Ui.dp(this@MainActivity, 16),
                Ui.dp(this@MainActivity, 2),
                Ui.dp(this@MainActivity, 16),
                Ui.dp(this@MainActivity, 112),
            )
            clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT)
        }
        adapter = WalletAdapter(
            onOpen = { card -> openDetails(card) },
            onCopy = { card -> Ui.copy(this@MainActivity, "UID", card.uid) },
        )
        recycler.adapter = adapter
        area.addView(recycler, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                Ui.dp(this@MainActivity, 28),
                0,
                Ui.dp(this@MainActivity, 28),
                Ui.dp(this@MainActivity, 70),
            )
        }
        val emptyIcon = Ui.icon(
            this,
            R.drawable.ic_tag,
            Ui.alpha(Ui.color(this, R.color.text_secondary), 180),
            64,
        ).apply { isClickable = false }
        empty.addView(emptyIcon, LinearLayout.LayoutParams(
            Ui.dp(this@MainActivity, 64),
            Ui.dp(this@MainActivity, 64),
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })
        emptyTitle = Ui.text(this, "Your vault is empty", 16f, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@MainActivity, 12), 0, 0)
        }
        emptyBody = Ui.text(
            this,
            "Tap Scan tag and hold an NFC card near the back of the phone. " +
                "Everything stays on this device until you export or sync.",
            12f,
            Ui.color(this, R.color.text_secondary),
        ).apply {
            gravity = Gravity.CENTER
            setLineSpacing(Ui.dpF(this@MainActivity, 1.6f), 1f)
            setPadding(0, Ui.dp(this@MainActivity, 6), 0, 0)
        }
        empty.addView(emptyTitle)
        empty.addView(emptyBody)
        emptyState = empty
        area.addView(empty, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        return area
    }

    // ------------------------------------------------------------- state

    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: WalletUiState) {
        totalValue.text = state.totalCount.toString()
        lastSyncValue.text = if (state.syncing) {
            "Syncing…"
        } else {
            TagInsights.compactTime(state.lastSyncAt)
        }
        lastSyncSub.text = when {
            state.syncing -> "uploading queue"
            state.pendingCount > 0 -> "${state.pendingCount} pending"
            else -> "queue empty"
        }
        lastSyncSub.setTextColor(
            Ui.color(
                this,
                if (state.pendingCount > 0) R.color.warning else R.color.text_secondary,
            ),
        )
        when (state.nfcStatus) {
            NfcStatus.READY -> {
                nfcValue.text = "Ready"
                nfcValue.setTextColor(Ui.color(this, R.color.emerald))
                nfcSub.text = "reader mode"
            }

            NfcStatus.OFF -> {
                nfcValue.text = "Off"
                nfcValue.setTextColor(Ui.color(this, R.color.danger))
                nfcSub.text = "tap to enable"
            }

            NfcStatus.UNAVAILABLE -> {
                nfcValue.text = "N/A"
                nfcValue.setTextColor(Ui.color(this, R.color.text_secondary))
                nfcSub.text = "no hardware"
            }

            NfcStatus.UNKNOWN -> {
                nfcValue.text = "Checking…"
                nfcValue.setTextColor(Ui.color(this, R.color.text_secondary))
                nfcSub.text = "reader mode"
            }
        }
        syncButton.alpha = if (state.syncing) 0.45f else 1f
        syncButton.isEnabled = !state.syncing

        adapter.submitList(state.cards)
        val noCards = state.totalCount == 0
        val noMatches = !noCards && state.cards.isEmpty()
        emptyState.visibility = if (noCards || noMatches) View.VISIBLE else View.GONE
        emptyTitle.text = if (noCards) "Your vault is empty" else "No tags match your filters"
        emptyBody.text = if (noCards) {
            "Tap Scan tag and hold an NFC card near the back of the phone. " +
                "Everything stays on this device until you export or sync."
        } else {
            "Try a different name, UID fragment or category."
        }
        renderCategoryChips(state.category)

        state.lastScan?.let { event ->
            if (event.token != lastScanToken) {
                lastScanToken = event.token
                if (event.success) {
                    Ui.hapticSuccess(fab)
                    if (scanSheet?.isShowing == true) {
                        scanSheet?.onResult(event)
                    } else {
                        toast(event.message)
                    }
                } else {
                    Ui.hapticError(fab)
                    if (scanSheet?.isShowing == true) {
                        scanSheet?.onResult(event)
                    } else {
                        toast(event.message)
                    }
                }
            }
        }
        state.message?.let { message ->
            if (message.token != lastMessageToken) {
                lastMessageToken = message.token
                toast(message.text)
            }
        }
    }

    private fun renderCategoryChips(selected: TagCategory?) {
        val accent = Ui.color(this, R.color.accent)
        val border = Ui.color(this, R.color.border)
        categoryChips.forEach { (category, chip) ->
            val isSelected = category == selected
            chip.setTextColor(if (isSelected) accent else Ui.color(this, R.color.text_secondary))
            chip.background = Ui.rounded(
                this,
                if (isSelected) Ui.alpha(accent, 28) else Ui.color(this, R.color.surface),
                30,
                if (isSelected) Ui.alpha(accent, 100) else border,
            )
        }
    }

    // ------------------------------------------------------------- scanning

    private fun requestScan() {
        viewModel.refreshNfcStatus()
        when (viewModel.state.value.nfcStatus) {
            NfcStatus.UNAVAILABLE -> toast("This phone does not report NFC hardware.")
            NfcStatus.OFF -> showNfcOffDialog()
            NfcStatus.READY -> startScanning()
            NfcStatus.UNKNOWN -> {
                toast("Checking NFC hardware…")
                viewModel.refreshNfcStatus()
            }
        }
    }

    private fun startScanning() {
        if (scanSheet?.isShowing == true) return
        viewModel.beginScan()
        val sheet = ScanSheet(this, viewModel.state.value.batchMode) { endScanning() }
        scanSheet = sheet
        sheet.show()
        nfcController.startScanning()
    }

    private fun endScanning() {
        nfcController.stopScanning()
        viewModel.stopScan()
        scanSheet = null
    }

    private fun showNfcOffDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("NFC is off")
            .setMessage(
                "Turn on NFC to scan tags. Reader mode pauses the system payment wallet " +
                    "only while the scan sheet is open.",
            )
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    // ------------------------------------------------------------- details

    private fun openDetails(card: NfcCard) {
        viewModel.loadLogs(card.uid) { logs ->
            if (isFinishing || isDestroyed) return@loadLogs
            TagDetailsSheet(
                context = this,
                card = card,
                logs = logs,
                onEmulate = { startEmulation(it) },
                onEdit = { showEditDialog(it) },
                onSync = { viewModel.syncCard(it.uid) },
                onDelete = { confirmDelete(it) },
                onExportJson = { exportOne(it, json = true) },
                onExportCsv = { exportOne(it, json = false) },
            ).show()
        }
    }

    // ----------------------------------------------------------- emulation

    private fun startEmulation(card: NfcCard) {
        if (card.rawNdefHex.isNullOrBlank()) {
            toast("This tag has no NDEF payload to emulate.")
            return
        }
        if (viewModel.state.value.nfcStatus != NfcStatus.READY) {
            toast("Turn on NFC to emulate a tag.")
            return
        }
        if (scanSheet?.isShowing == true) endScanning()

        val active = EmulationStore.activate(this, card)
        if (active == null) {
            toast("Could not prepare this tag for emulation.")
            return
        }

        Ui.hapticSuccess(window.decorView)
        emulationDialog?.dismiss()
        emulationDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Emulating ${card.displayName}")
            .setMessage(
                "Hold the top of your phone against the reader.\n\n" +
                    "NDEF / Type 4 emulation only. Android generates its own NFC ID, " +
                    "so a reader that checks the original card UID will reject it. " +
                    "MIFARE Classic and Ultralight cards cannot be emulated by phone hardware.\n\n" +
                    "Emulation stops when you tap Stop or after 5 minutes.",
            )
            .setPositiveButton("Stop") { _, _ -> stopEmulation() }
            .setCancelable(false)
            .show()
    }

    private fun stopEmulation() {
        EmulationStore.deactivate(this)
        emulationDialog?.dismiss()
        emulationDialog = null
        toast("Emulation stopped")
    }

    private fun showEditDialog(card: NfcCard) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Ui.dp(this@MainActivity, 20),
                Ui.dp(this@MainActivity, 6),
                Ui.dp(this@MainActivity, 20),
                0,
            )
        }
        val nameField = dialogField(
            container,
            "TAG NAME",
            card.name,
            "e.g. Main Office Badge",
            singleLine = true,
        )
        val notesField = dialogField(
            container,
            "NOTES",
            card.notes,
            "Optional notes for this tag",
            singleLine = false,
        )

        container.addView(dialogLabel("CATEGORY"), linearParams(top = 16))
        var selected = card.category
        val pillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val pills = linkedMapOf<TagCategory, TextView>()
        TagCategory.entries.forEach { category ->
            val pill = Ui.text(this, category.label, 10.5f, bold = true).apply {
                setPadding(
                    Ui.dp(this@MainActivity, 10),
                    Ui.dp(this@MainActivity, 6),
                    Ui.dp(this@MainActivity, 10),
                    Ui.dp(this@MainActivity, 6),
                )
                isClickable = true
                foreground = Ui.ripple()
                setOnClickListener {
                    selected = category
                    styleDialogPills(pills, selected)
                }
            }
            pills[category] = pill
            pillRow.addView(pill, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = Ui.dp(this@MainActivity, 6) })
        }
        styleDialogPills(pills, selected)
        val pillScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        pillScroll.addView(pillRow)
        container.addView(pillScroll, linearParams(top = 7))

        MaterialAlertDialogBuilder(this)
            .setTitle("Edit tag")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                viewModel.updateMeta(
                    uid = card.uid,
                    name = nameField.text.toString(),
                    category = selected,
                    notes = notesField.text.toString(),
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dialogField(
        parent: LinearLayout,
        label: String,
        value: String,
        hint: String,
        singleLine: Boolean,
    ): EditText {
        parent.addView(dialogLabel(label), linearParams(top = 16))
        val field = EditText(this).apply {
            setText(value)
            this.hint = hint
            setHintTextColor(Ui.color(this@MainActivity, R.color.text_secondary))
            setTextColor(Ui.color(this@MainActivity, R.color.text_primary))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            inputType = InputType.TYPE_CLASS_TEXT or
                (if (singleLine) 0 else InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            if (singleLine) {
                isSingleLine = true
            } else {
                minLines = 3
                gravity = Gravity.TOP
            }
            setPadding(
                Ui.dp(this@MainActivity, 12),
                Ui.dp(this@MainActivity, 10),
                Ui.dp(this@MainActivity, 12),
                Ui.dp(this@MainActivity, 10),
            )
            background = Ui.rounded(
                this@MainActivity,
                Ui.color(this@MainActivity, R.color.surface_alt),
                12,
                Ui.color(this@MainActivity, R.color.border),
            )
            setSelection(text.length)
        }
        parent.addView(field, linearParams(top = 5))
        return field
    }

    private fun dialogLabel(text: String): TextView = Ui.text(
        this,
        text,
        9.5f,
        Ui.color(this, R.color.text_secondary),
        bold = true,
    ).apply { letterSpacing = 0.12f }

    private fun styleDialogPills(pills: Map<TagCategory, TextView>, selected: TagCategory) {
        val accent = Ui.color(this, R.color.accent)
        pills.forEach { (category, pill) ->
            val active = category == selected
            pill.setTextColor(if (active) accent else Ui.color(this, R.color.text_secondary))
            pill.background = Ui.rounded(
                this,
                if (active) Ui.alpha(accent, 28) else Ui.color(this, R.color.surface_alt),
                12,
                if (active) Ui.alpha(accent, 100) else Ui.color(this, R.color.border),
            )
        }
    }

    private fun confirmDelete(card: NfcCard) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove tag?")
            .setMessage(
                "${card.displayName} (${card.uid}) will be removed from this device. " +
                    "Any pending sync for it is discarded.",
            )
            .setPositiveButton("Remove") { _, _ -> viewModel.delete(card.uid) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClear() {
        val count = viewModel.state.value.totalCount
        if (count == 0) {
            toast("Nothing saved yet.")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear the vault?")
            .setMessage("This permanently removes all $count saved tags and their scan history.")
            .setPositiveButton("Clear vault") { _, _ -> viewModel.clearAll() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------- exports

    private fun exportAll(json: Boolean, saveToDownloads: Boolean) {
        val cards = viewModel.cardsSnapshot()
        if (cards.isEmpty()) {
            toast("Nothing saved yet.")
            return
        }
        val body = if (json) Exporters.json(cards) else Exporters.csv(cards)
        val mime = if (json) "application/json" else "text/csv"
        if (saveToDownloads) {
            val extension = if (json) "json" else "csv"
            Exporters.saveToDownloads(
                this,
                "nfc-vault-${System.currentTimeMillis()}.$extension",
                mime,
                body,
            )
        } else {
            Exporters.share(this, "NFC Vault export (${cards.size} tags)", mime, body)
        }
    }

    private fun exportOne(card: NfcCard, json: Boolean) {
        val body = if (json) Exporters.json(listOf(card)) else Exporters.csv(listOf(card))
        val mime = if (json) "application/json" else "text/csv"
        Exporters.share(this, "NFC Vault tag ${card.uid}", mime, body)
    }

    // ------------------------------------------------------------- overflow

    private fun showOverflow(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(Menu.NONE, MENU_EXPORT_JSON, 0, "Export all as JSON")
        popup.menu.add(Menu.NONE, MENU_EXPORT_CSV, 1, "Export all as CSV")
        popup.menu.add(Menu.NONE, MENU_SAVE_JSON, 2, "Save JSON to Downloads")
        popup.menu.add(Menu.NONE, MENU_SYNC, 3, "Sync all")
        popup.menu.add(Menu.NONE, MENU_SETTINGS, 4, "Settings")
        popup.menu.add(Menu.NONE, MENU_CLEAR, 5, "Clear vault")
        popup.menu.add(Menu.NONE, MENU_ABOUT, 6, "About")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EXPORT_JSON -> exportAll(json = true, saveToDownloads = false)
                MENU_EXPORT_CSV -> exportAll(json = false, saveToDownloads = false)
                MENU_SAVE_JSON -> exportAll(json = true, saveToDownloads = true)
                MENU_SYNC -> viewModel.syncAll()
                MENU_SETTINGS -> showSettings()
                MENU_CLEAR -> confirmClear()
                MENU_ABOUT -> showAbout()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun showSettings() {
        val app = application as NfcVaultApp
        SettingsSheet(
            context = this,
            serverUrl = app.prefs.serverUrl,
            deviceId = app.prefs.deviceId,
            operatorId = app.prefs.operatorId,
            batchMode = app.prefs.batchMode,
            onSave = { url, operator, batch ->
                viewModel.saveSettings(url, operator, batch)
            },
            onSyncNow = { viewModel.syncAll() },
        ).show()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("NFC Vault 0.2.0")
            .setMessage(
                "Local-first enterprise NFC wallet.\n\n" +
                    "• Scans NfcA/B/F/V and NDEF tags\n" +
                    "• Deduplicates by UID and keeps a scan history\n" +
                    "• Exports JSON/CSV and syncs to your own endpoint\n" +
                    "• Data stays on this phone until you share, export or sync it.",
            )
            .setPositiveButton("Close", null)
            .show()
    }

    // ------------------------------------------------------------- helpers

    private fun linearParams(
        top: Int = 0,
        left: Int = 0,
        right: Int = 0,
        bottom: Int = 0,
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = Ui.dp(this@MainActivity, top)
        leftMargin = Ui.dp(this@MainActivity, left)
        rightMargin = Ui.dp(this@MainActivity, right)
        bottomMargin = Ui.dp(this@MainActivity, bottom)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private data class MetricTile(
        val tile: LinearLayout,
        val value: TextView,
        val sub: TextView,
    )

    private companion object {
        const val MENU_EXPORT_JSON = 1
        const val MENU_EXPORT_CSV = 2
        const val MENU_SAVE_JSON = 3
        const val MENU_SYNC = 4
        const val MENU_SETTINGS = 5
        const val MENU_CLEAR = 6
        const val MENU_ABOUT = 7
    }
}
