package com.nfcvault.data

import android.content.Context
import com.nfcvault.data.model.NfcCard
import com.nfcvault.data.model.NfcReadResult
import com.nfcvault.data.model.SyncStatus
import com.nfcvault.data.model.TagCategory
import com.nfcvault.data.model.nullableString
import com.nfcvault.data.model.toRecordList
import com.nfcvault.data.model.toStringList
import com.nfcvault.data.model.toStringMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class UpsertResult(val card: NfcCard, val isNew: Boolean)

/**
 * Single source of truth for cards. All database work runs on Dispatchers.IO
 * and a mutex serializes writes so simultaneous NFC reads cannot race.
 */
class NfcRepository(context: Context, private val prefs: Prefs) {

    private val appContext = context.applicationContext
    private val db = NfcDatabase(appContext)
    private val writeLock = Mutex()

    suspend fun all(): List<NfcCard> = withContext(Dispatchers.IO) { db.loadCards() }

    suspend fun find(uid: String): NfcCard? = withContext(Dispatchers.IO) { db.findCard(uid) }

    suspend fun count(): Int = withContext(Dispatchers.IO) { db.cardCount() }

    suspend fun upsert(read: NfcReadResult): UpsertResult = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val existing = db.findCard(read.uid)
            val card = NfcCard(
                uid = read.uid,
                name = existing?.name ?: "",
                category = existing?.category ?: TagCategory.OTHER,
                notes = existing?.notes ?: "",
                tech = read.tech,
                extras = read.extras,
                ndefType = read.ndefType,
                ndefMaxSize = read.ndefMaxSize,
                ndefWritable = read.ndefWritable,
                records = read.records,
                rawNdefHex = read.rawNdefHex,
                firstSeenAt = existing?.firstSeenAt ?: read.readAt,
                lastScannedAt = read.readAt,
                scanCount = (existing?.scanCount ?: 0) + 1,
                syncStatus = when {
                    prefs.serverUrl.isBlank() -> SyncStatus.LOCAL_ONLY
                    existing?.syncStatus == SyncStatus.SYNCED -> SyncStatus.PENDING
                    existing?.syncStatus == SyncStatus.PENDING -> SyncStatus.PENDING
                    else -> SyncStatus.PENDING
                },
                syncedAt = existing?.syncedAt,
            )
            val isNew = db.upsertCard(card)
            val saved = db.findCard(read.uid) ?: card
            UpsertResult(saved, isNew)
        }
    }

    suspend fun updateMeta(uid: String, name: String, category: TagCategory, notes: String) =
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                db.updateMeta(
                    uid = uid,
                    name = name,
                    category = category,
                    notes = notes,
                    syncStatus = if (prefs.serverUrl.isBlank()) {
                        SyncStatus.LOCAL_ONLY
                    } else {
                        SyncStatus.PENDING
                    },
                )
            }
        }

    suspend fun delete(uid: String) = withContext(Dispatchers.IO) {
        writeLock.withLock { db.deleteCard(uid) }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        writeLock.withLock { db.clearAll() }
    }

    suspend fun markPending(uids: Collection<String>) = withContext(Dispatchers.IO) {
        writeLock.withLock { db.setSyncStatus(uids, SyncStatus.PENDING, null) }
    }

    suspend fun markSynced(uids: Collection<String>, syncedAt: Long) = withContext(Dispatchers.IO) {
        writeLock.withLock { db.setSyncStatus(uids, SyncStatus.SYNCED, syncedAt) }
    }

    suspend fun scanLogs(uid: String): List<Long> = withContext(Dispatchers.IO) {
        db.scanLogs(uid)
    }

    /**
     * Imports the pre-0.2 JSON store (files/scans.json) exactly once, keeping
     * the original scan timestamps. The old file is kept as a .imported backup.
     */
    suspend fun importLegacyIfNeeded() = withContext(Dispatchers.IO) {
        if (prefs.legacyImported) return@withContext
        val legacy = File(appContext.filesDir, "scans.json")
        if (!legacy.exists()) {
            prefs.legacyImported = true
            return@withContext
        }
        try {
            val array = JSONArray(legacy.readText())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                db.importLegacyCard(item.toLegacyCard())
            }
            val backup = File(appContext.filesDir, "scans.json.imported")
            if (!legacy.renameTo(backup)) {
                legacy.copyTo(backup, overwrite = true)
                legacy.delete()
            }
        } catch (_: Exception) {
            // A malformed legacy file must never block the new vault.
        }
        prefs.legacyImported = true
    }

    private fun JSONObject.toLegacyCard(): NfcCard {
        val timestamp = optLong("time", System.currentTimeMillis())
        val rawUid = optString("uid").ifBlank {
            "NOUID-" + optString("id").ifBlank { UUID.randomUUID().toString() }
        }
        return NfcCard(
            uid = rawUid,
            name = "",
            category = TagCategory.OTHER,
            notes = "",
            tech = optJSONArray("tech").toStringList(),
            extras = optJSONObject("extras").toStringMap(),
            ndefType = nullableString("ndefType"),
            ndefMaxSize = optInt("ndefMaxSize"),
            ndefWritable = optBoolean("ndefWritable"),
            records = optJSONArray("records").toRecordList(),
            rawNdefHex = nullableString("rawNdef"),
            firstSeenAt = timestamp,
            lastScannedAt = timestamp,
            scanCount = 1,
            syncStatus = SyncStatus.LOCAL_ONLY,
            syncedAt = null,
        )
    }
}
