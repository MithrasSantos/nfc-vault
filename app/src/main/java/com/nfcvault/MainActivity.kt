package com.nfcvault

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Typeface
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.nfc.Tag
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var store: ScanStore
    private lateinit var scanButton: Button
    private lateinit var statusView: TextView
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var adapter: ScanAdapter

    private var nfc: NfcAdapter? = null
    private var scanning = false
    private var items: MutableList<ScanRecord> = mutableListOf()

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val readerFlags = NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_NFC_F or
        NfcAdapter.FLAG_READER_NFC_V

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ScanStore(this)
        nfc = getSystemService(NfcManager::class.java)?.defaultAdapter
        setContentView(buildUi())
        items = store.load()
        adapter.notifyDataSetChanged()
        updateEmpty()
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfc
        when {
            adapter == null -> {
                statusView.text = "This device has no NFC hardware."
                scanButton.isEnabled = false
            }

            !adapter.isEnabled -> {
                statusView.text = "NFC is turned off. Tap Start scanning to open settings."
            }

            scanning -> {
                adapter.enableReaderMode(this, { tag -> handleTag(tag) }, readerFlags, null)
                statusView.text = "Hold a tag near the back of the phone…"
            }

            else -> {
                statusView.text = "Ready. Press Start scanning."
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (scanning) nfc?.disableReaderMode(this)
    }

    // ---------------------------------------------------------------- NFC

    private fun toggleScan() {
        val adapter = nfc
        if (adapter == null) {
            toast("No NFC hardware on this device")
            return
        }
        if (!adapter.isEnabled) {
            toast("NFC is off — enable it in system settings")
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            return
        }
        if (scanning) stopScan() else startScan()
    }

    private fun startScan() {
        val adapter = nfc ?: return
        scanning = true
        adapter.enableReaderMode(this, { tag -> handleTag(tag) }, readerFlags, null)
        scanButton.text = "Stop scanning"
        statusView.text = "Hold a tag near the back of the phone…"
    }

    private fun stopScan() {
        scanning = false
        nfc?.disableReaderMode(this)
        scanButton.text = "Start scanning"
        statusView.text = "Scanning paused"
    }

    /** Runs on the NFC reader thread, not the UI thread. */
    private fun handleTag(tag: Tag) {
        try {
            val record = NfcTagReader.read(tag)
            val all = store.add(record)
            runOnUiThread {
                items = all
                adapter.notifyDataSetChanged()
                updateEmpty()
                statusView.text = "Saved ${record.uid.ifEmpty { "tag" }} · ${timeFmt.format(Date(record.time))}"
            }
        } catch (e: Exception) {
            runOnUiThread { statusView.text = "Read failed: ${e.message}" }
        }
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), 0)
        }

        val title = TextView(this).apply {
            text = "NFC Vault"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title)

        statusView = TextView(this).apply {
            text = "Ready"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(statusView)

        scanButton = Button(this).apply {
            text = "Start scanning"
            isAllCaps = false
            setOnClickListener { toggleScan() }
        }
        root.addView(
            scanButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val listContainer = FrameLayout(this)
        val containerParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ).apply { topMargin = dp(8) }
        root.addView(listContainer, containerParams)

        listView = ListView(this).apply {
            dividerHeight = dp(1)
            setOnItemClickListener { _, _, position, _ ->
                showDetails(items[position])
            }
        }
        adapter = ScanAdapter()
        listView.adapter = adapter
        listContainer.addView(
            listView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        emptyView = TextView(this).apply {
            text = "No scans yet.\n\nTap \u201CStart scanning\u201D and hold an NFC tag\nagainst the back of the phone."
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(24), 0, dp(24), 0)
        }
        listContainer.addView(
            emptyView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        return root
    }

    private fun updateEmpty() {
        val empty = items.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun showDetails(record: ScanRecord) {
        val text = TextView(this).apply {
            text = describe(record)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(text) }

        AlertDialog.Builder(this)
            .setTitle("Tag details")
            .setView(scroll)
            .setPositiveButton("Share") { _, _ ->
                shareText("NFC tag ${record.uid}", record.toJson().toString(2))
            }
            .setNeutralButton("Delete") { _, _ -> confirmDelete(record) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun describe(record: ScanRecord): String = buildString {
        appendLine("UID:   ${record.uid.ifEmpty { "-" }}")
        appendLine("Seen:  ${timeFmt.format(Date(record.time))}")
        appendLine("Tech:  ${record.tech.joinToString(", ").ifEmpty { "-" }}")
        record.extras.forEach { (key, value) -> appendLine("$key: $value") }
        val ndef = record.ndefType
        if (ndef != null) {
            val mode = if (record.ndefWritable) "writable" else "read-only"
            appendLine("NDEF:  $ndef, ${record.ndefMaxSize} bytes, $mode")
        } else {
            appendLine("NDEF:  none (tag is not formatted)")
        }
        if (record.records.isNotEmpty()) {
            appendLine()
            appendLine("Records:")
            record.records.forEachIndexed { index, r ->
                appendLine("  [${index + 1}] ${r.kind} (${r.type})")
                appendLine("      ${r.value}")
            }
        }
        record.rawNdefHex?.let {
            appendLine()
            appendLine("Raw NDEF:")
            appendLine(it)
        }
    }

    private fun confirmDelete(record: ScanRecord) {
        AlertDialog.Builder(this)
            .setTitle("Delete scan?")
            .setMessage(record.uid.ifEmpty { "This tag" })
            .setPositiveButton("Delete") { _, _ ->
                items = store.remove(record.id)
                adapter.notifyDataSetChanged()
                updateEmpty()
                toast("Deleted")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClear() {
        if (items.isEmpty()) {
            toast("Nothing saved yet")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Clear all scans?")
            .setMessage("This removes all ${items.size} saved scans from this phone.")
            .setPositiveButton("Clear") { _, _ ->
                store.clear()
                items = mutableListOf()
                adapter.notifyDataSetChanged()
                updateEmpty()
                statusView.text = "All scans cleared"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- export

    private fun shareAll() {
        if (items.isEmpty()) {
            toast("Nothing saved yet")
            return
        }
        shareText("NFC Vault export (${items.size} tags)", ScanStore.exportJson(items))
    }

    private fun shareText(subject: String, body: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(send, "Share NFC data"))
    }

    private fun saveToDownloads() {
        if (items.isEmpty()) {
            toast("Nothing saved yet")
            return
        }
        val json = ScanStore.exportJson(items)
        val name = "nfc-vault-${System.currentTimeMillis()}.json"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/NFC Vault",
            )
        }
        try {
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("could not create file")
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("could not open file")
            toast("Saved to Downloads/NFC Vault/$name")
        } catch (e: Exception) {
            toast("Save failed: ${e.message}")
        }
    }

    private fun uploadDialog() {
        if (items.isEmpty()) {
            toast("Nothing saved yet")
            return
        }
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val input = EditText(this).apply {
            hint = "https://example.com/nfc"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("upload_url", ""))
            setSingleLine(true)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Upload ${items.size} scans")
            .setMessage("Sends the saved scans as JSON with an HTTP POST request.")
            .setView(box)
            .setPositiveButton("Send") { _, _ ->
                val url = input.text.toString().trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    toast("Enter an http:// or https:// URL")
                } else {
                    prefs.edit().putString("upload_url", url).apply()
                    postJson(url, ScanStore.exportJson(items))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun postJson(url: String, body: String) {
        statusView.text = "Uploading…"
        Thread {
            val result = try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()
                "Upload done: HTTP $code"
            } catch (e: Exception) {
                "Upload failed: ${e.message}"
            }
            runOnUiThread {
                statusView.text = result
                toast(result)
            }
        }.start()
    }

    // ---------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_SAVE, 0, "Save JSON to Downloads")
        menu.add(Menu.NONE, MENU_SHARE, 1, "Share all")
        menu.add(Menu.NONE, MENU_UPLOAD, 2, "Upload to URL…")
        menu.add(Menu.NONE, MENU_CLEAR, 3, "Clear all")
        menu.add(Menu.NONE, MENU_ABOUT, 4, "About")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_SAVE -> {
            saveToDownloads()
            true
        }

        MENU_SHARE -> {
            shareAll()
            true
        }

        MENU_UPLOAD -> {
            uploadDialog()
            true
        }

        MENU_CLEAR -> {
            confirmClear()
            true
        }

        MENU_ABOUT -> {
            showAbout()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("NFC Vault 0.1.0")
            .setMessage(
                "Scans NFC tags and keeps their UID, technology and NDEF data on this phone.\n\n" +
                    "Nothing is sent anywhere unless you share, save or upload it yourself.",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun selectableBackground(): Int {
        val out = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return out.resourceId
    }

    private inner class ScanAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size

        override fun getItem(position: Int): ScanRecord = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = if (convertView is LinearLayout) {
                convertView
            } else {
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    setBackgroundResource(selectableBackground())
                    layoutParams = AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    addView(
                        TextView(this@MainActivity).apply {
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                            setTypeface(typeface, Typeface.BOLD)
                        },
                    )
                    addView(
                        TextView(this@MainActivity).apply {
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        },
                    )
                }
            }

            val record = items[position]
            (row.getChildAt(0) as TextView).text = record.uid.ifEmpty { "(no UID)" }
            (row.getChildAt(1) as TextView).text = buildString {
                append(timeFmt.format(Date(record.time)))
                append(" · ")
                append(record.summary())
            }
            return row
        }
    }

    private companion object {
        const val MENU_SAVE = 1
        const val MENU_SHARE = 2
        const val MENU_UPLOAD = 3
        const val MENU_CLEAR = 4
        const val MENU_ABOUT = 5
    }
}
