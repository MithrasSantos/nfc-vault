package com.nfcvault.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nfcvault.data.model.NfcCard
import com.nfcvault.data.model.NdefRecordInfo
import com.nfcvault.data.model.SyncStatus
import com.nfcvault.data.model.TagCategory
import com.nfcvault.data.model.toRecordList
import com.nfcvault.data.model.toStringList
import com.nfcvault.data.model.toStringMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * SQLite schema for the vault. Cards are keyed by UID so a tag can only exist
 * once; every tap appends a row to scan_logs and bumps the card counters.
 */
class NfcDatabase(context: Context) : SQLiteOpenHelper(context, "nfc-vault.db", null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE cards (
                uid TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL DEFAULT 'other',
                notes TEXT NOT NULL DEFAULT '',
                tech TEXT NOT NULL DEFAULT '[]',
                extras TEXT NOT NULL DEFAULT '{}',
                ndef_type TEXT,
                ndef_max_size INTEGER NOT NULL DEFAULT 0,
                ndef_writable INTEGER NOT NULL DEFAULT 0,
                records TEXT NOT NULL DEFAULT '[]',
                raw_ndef TEXT,
                first_seen_at INTEGER NOT NULL,
                last_scanned_at INTEGER NOT NULL,
                scan_count INTEGER NOT NULL DEFAULT 1,
                sync_status TEXT NOT NULL DEFAULT 'local',
                synced_at INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE scan_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                card_uid TEXT NOT NULL,
                scanned_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_cards_last_seen ON cards(last_scanned_at DESC)")
        db.execSQL("CREATE INDEX idx_logs_card ON scan_logs(card_uid, scanned_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema 1 is the first persistent release. Future migrations go here.
    }

    fun upsertCard(card: NfcCard): Boolean {
        writableDatabase.beginTransaction()
        return try {
            val existing = findCard(card.uid)
            val values = card.toValues().apply {
                put("first_seen_at", existing?.firstSeenAt ?: card.firstSeenAt)
                put("scan_count", (existing?.scanCount ?: 0) + 1)
                put("last_scanned_at", card.lastScannedAt)
                if (existing != null) {
                    put("name", existing.name)
                    put("category", existing.category.key)
                    put("notes", existing.notes)
                }
            }
            writableDatabase.insertWithOnConflict(
                TABLE_CARDS,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            writableDatabase.insert(
                TABLE_LOGS,
                null,
                ContentValues().apply {
                    put("card_uid", card.uid)
                    put("scanned_at", card.lastScannedAt)
                },
            )
            pruneLogs(card.uid)
            writableDatabase.setTransactionSuccessful()
            existing == null
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /** Import path for the old JSON store; keeps the original timestamps. */
    fun importLegacyCard(card: NfcCard) {
        writableDatabase.beginTransaction()
        try {
            val existing = findCard(card.uid)
            val values = card.toValues().apply {
                if (existing != null) {
                    put("first_seen_at", minOf(existing.firstSeenAt, card.firstSeenAt))
                    put("last_scanned_at", maxOf(existing.lastScannedAt, card.lastScannedAt))
                    put("scan_count", existing.scanCount + card.scanCount)
                    put("name", existing.name.ifBlank { card.name })
                    put("category", existing.category.key)
                    put("notes", existing.notes.ifBlank { card.notes })
                }
            }
            writableDatabase.insertWithOnConflict(
                TABLE_CARDS,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            writableDatabase.insert(
                TABLE_LOGS,
                null,
                ContentValues().apply {
                    put("card_uid", card.uid)
                    put("scanned_at", card.lastScannedAt)
                },
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun findCard(uid: String): NfcCard? = readableDatabase.query(
        TABLE_CARDS,
        null,
        "uid = ?",
        arrayOf(uid),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.toCard() else null
    }

    fun loadCards(): List<NfcCard> = readableDatabase.query(
        TABLE_CARDS,
        null,
        null,
        null,
        null,
        null,
        "last_scanned_at DESC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.toCard())
        }
    }

    fun updateMeta(
        uid: String,
        name: String,
        category: TagCategory,
        notes: String,
        syncStatus: SyncStatus,
    ) {
        writableDatabase.update(
            TABLE_CARDS,
            ContentValues().apply {
                put("name", name.trim())
                put("category", category.key)
                put("notes", notes.trim())
                put("sync_status", syncStatus.key)
            },
            "uid = ?",
            arrayOf(uid),
        )
    }

    fun deleteCard(uid: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete(TABLE_LOGS, "card_uid = ?", arrayOf(uid))
            writableDatabase.delete(TABLE_CARDS, "uid = ?", arrayOf(uid))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun clearAll() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete(TABLE_LOGS, null, null)
            writableDatabase.delete(TABLE_CARDS, null, null)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun setSyncStatus(uids: Collection<String>, status: SyncStatus, syncedAt: Long?) {
        if (uids.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            uids.forEach { uid ->
                writableDatabase.update(
                    TABLE_CARDS,
                    ContentValues().apply {
                        put("sync_status", status.key)
                        put("synced_at", syncedAt)
                    },
                    "uid = ?",
                    arrayOf(uid),
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun scanLogs(uid: String, limit: Int = 50): List<Long> = readableDatabase.query(
        TABLE_LOGS,
        arrayOf("scanned_at"),
        "card_uid = ?",
        arrayOf(uid),
        null,
        null,
        "scanned_at DESC",
        limit.toString(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getLong(0))
        }
    }

    fun cardCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM cards", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    private fun pruneLogs(uid: String) {
        writableDatabase.execSQL(
            """
            DELETE FROM scan_logs
            WHERE card_uid = ?
              AND id NOT IN (
                SELECT id FROM scan_logs
                WHERE card_uid = ?
                ORDER BY scanned_at DESC
                LIMIT $MAX_LOGS_PER_CARD
              )
            """.trimIndent(),
            arrayOf(uid, uid),
        )
    }

    private fun NfcCard.toValues(): ContentValues = ContentValues().apply {
        put("uid", uid)
        put("name", name)
        put("category", category.key)
        put("notes", notes)
        put("tech", JSONArray(tech).toString())
        put("extras", JSONObject().apply {
            extras.forEach { (key, value) -> put(key, value) }
        }.toString())
        put("ndef_type", ndefType)
        put("ndef_max_size", ndefMaxSize)
        put("ndef_writable", if (ndefWritable) 1 else 0)
        put("records", JSONArray().apply { records.forEach { put(it.toJson()) } }.toString())
        put("raw_ndef", rawNdefHex)
        put("first_seen_at", firstSeenAt)
        put("last_scanned_at", lastScannedAt)
        put("scan_count", scanCount)
        put("sync_status", syncStatus.key)
        put("synced_at", syncedAt)
    }

    private fun Cursor.toCard(): NfcCard {
        val tech = getString(indexOf("tech")).toJsonArray().toStringList()
        val extras = getString(indexOf("extras")).toJsonObject().toStringMap()
        val records: List<NdefRecordInfo> =
            getString(indexOf("records")).toJsonArray().toRecordList()
        return NfcCard(
            uid = getString(indexOf("uid")),
            name = getString(indexOf("name")),
            category = TagCategory.fromKey(getString(indexOf("category"))),
            notes = getString(indexOf("notes")),
            tech = tech,
            extras = extras,
            ndefType = getStringOrNull(indexOf("ndef_type")),
            ndefMaxSize = getInt(indexOf("ndef_max_size")),
            ndefWritable = getInt(indexOf("ndef_writable")) == 1,
            records = records,
            rawNdefHex = getStringOrNull(indexOf("raw_ndef")),
            firstSeenAt = getLong(indexOf("first_seen_at")),
            lastScannedAt = getLong(indexOf("last_scanned_at")),
            scanCount = getInt(indexOf("scan_count")),
            syncStatus = SyncStatus.fromKey(getString(indexOf("sync_status"))),
            syncedAt = getLongOrNull(indexOf("synced_at")),
        )
    }

    private fun Cursor.indexOf(column: String): Int = getColumnIndexOrThrow(column)

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun Cursor.getLongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)

    private fun String.toJsonArray(): JSONArray = runCatching { JSONArray(this) }.getOrElse { JSONArray() }

    private fun String.toJsonObject(): JSONObject = runCatching { JSONObject(this) }.getOrElse { JSONObject() }

    companion object {
        private const val VERSION = 1
        private const val TABLE_CARDS = "cards"
        private const val TABLE_LOGS = "scan_logs"
        private const val MAX_LOGS_PER_CARD = 200
    }
}
