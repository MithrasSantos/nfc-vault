package com.nfcvault.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import com.nfcvault.data.model.NfcCard
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** JSON/CSV serialization and Android share/save plumbing. */
object Exporters {

    private val humanTime: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    fun json(cards: List<NfcCard>): String = JSONObject().apply {
        put("app", "NFC Vault")
        put("schema", 2)
        put("exportedAt", System.currentTimeMillis())
        put("count", cards.size)
        put("tags", JSONArray().apply { cards.forEach { put(it.toJson()) } })
    }.toString(2)

    fun csv(cards: List<NfcCard>): String {
        val header = listOf(
            "uid", "name", "category", "card_type", "tech", "ndef_type",
            "record_count", "ndef_summary", "first_seen", "last_scanned",
            "scan_count", "sync_status", "notes",
        )
        return buildString {
            appendLine(header.joinToString(","))
            cards.forEach { card ->
                appendLine(
                    listOf(
                        card.uid,
                        card.displayName,
                        card.category.label,
                        card.cardType,
                        card.tech.joinToString(" | "),
                        card.ndefType ?: "",
                        card.records.size.toString(),
                        card.records.joinToString(" | ") { "${it.kind}: ${it.value}" },
                        humanTime.format(Instant.ofEpochMilli(card.firstSeenAt)),
                        humanTime.format(Instant.ofEpochMilli(card.lastScannedAt)),
                        card.scanCount.toString(),
                        card.syncStatus.label,
                        card.notes,
                    ).joinToString(",") { escapeCsv(it) },
                )
            }
        }
    }

    fun share(context: Context, subject: String, mime: String, body: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        context.startActivity(Intent.createChooser(send, "Share NFC Vault export"))
    }

    fun saveToDownloads(
        context: Context,
        fileName: String,
        mime: String,
        body: String,
    ): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/NFC Vault",
            )
        }
        return try {
            val uri: Uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("could not create file")
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            } ?: error("could not open file")
            Toast.makeText(
                context,
                "Saved to Downloads/NFC Vault/$fileName",
                Toast.LENGTH_LONG,
            ).show()
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun escapeCsv(value: String): String {
        val clean = value.replace("\r", " ").replace("\n", " ")
        return if (clean.contains(',') || clean.contains('"')) {
            "\"" + clean.replace("\"", "\"\"") + "\""
        } else {
            clean
        }
    }
}
