package com.nfcvault.data.model

import org.json.JSONArray
import org.json.JSONObject

/** One decoded NDEF record, flattened for storage and display. */
data class NdefRecordInfo(
    val tnf: Int,
    val type: String,
    val kind: String,
    val value: String,
    val payloadHex: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("tnf", tnf)
        put("type", type)
        put("kind", kind)
        put("value", value)
        put("payloadHex", payloadHex)
    }

    companion object {
        fun fromJson(o: JSONObject): NdefRecordInfo = NdefRecordInfo(
            tnf = o.optInt("tnf"),
            type = o.optString("type"),
            kind = o.optString("kind"),
            value = o.optString("value"),
            payloadHex = o.optString("payloadHex"),
        )
    }
}

enum class TagCategory(val key: String, val label: String) {
    ACCESS("access", "Access Control"),
    ASSET("asset", "Equipment / Asset"),
    VISITOR("visitor", "Visitor"),
    OTHER("other", "Uncategorized");

    companion object {
        fun fromKey(key: String?): TagCategory =
            TagCategory.entries.firstOrNull { it.key == key } ?: OTHER

        val filters: List<TagCategory?> = listOf(null, ACCESS, ASSET, VISITOR, OTHER)
    }
}

enum class SyncStatus(val key: String, val label: String) {
    LOCAL_ONLY("local", "Local only"),
    PENDING("pending", "Pending sync"),
    SYNCED("synced", "Synced"),
    ERROR("error", "Sync failed");

    companion object {
        fun fromKey(key: String?): SyncStatus =
            SyncStatus.entries.firstOrNull { it.key == key } ?: LOCAL_ONLY
    }
}

enum class NfcStatus { READY, OFF, UNAVAILABLE, UNKNOWN }

/**
 * One physical tag in the vault. The UID is the identity: scanning the same
 * tag again updates this row instead of creating a duplicate.
 */
data class NfcCard(
    val uid: String,
    val name: String,
    val category: TagCategory,
    val notes: String,
    val tech: List<String>,
    val extras: Map<String, String>,
    val ndefType: String?,
    val ndefMaxSize: Int,
    val ndefWritable: Boolean,
    val records: List<NdefRecordInfo>,
    val rawNdefHex: String?,
    val firstSeenAt: Long,
    val lastScannedAt: Long,
    val scanCount: Int,
    val syncStatus: SyncStatus,
    val syncedAt: Long?,
) {
    val displayName: String get() = name.ifBlank { "Unidentified Tag" }
    val hasNdef: Boolean get() = ndefType != null || records.isNotEmpty()
    val hasContent: Boolean get() = records.isNotEmpty()
    val shortTech: String get() = TagInsights.shortTech(tech, ndefType)
    val cardType: String get() = TagInsights.cardType(tech, ndefType)

    fun toJson(): JSONObject = JSONObject().apply {
        put("uid", uid)
        put("name", name)
        put("category", category.key)
        put("notes", notes)
        put("techTypes", JSONArray(tech))
        put("extras", JSONObject().apply { extras.forEach { (k, v) -> put(k, v) } })
        put("ndefType", ndefType ?: JSONObject.NULL)
        put("ndefMaxSize", ndefMaxSize)
        put("ndefWritable", ndefWritable)
        put("ndefPayload", JSONArray().apply { records.forEach { put(it.toJson()) } })
        put("records", JSONArray().apply { records.forEach { put(it.toJson()) } })
        put("rawNdef", rawNdefHex ?: JSONObject.NULL)
        put("firstSeenAt", firstSeenAt)
        put("lastScannedAt", lastScannedAt)
        put("scanCount", scanCount)
        put("syncStatus", syncStatus.key)
        put("syncedAt", syncedAt ?: JSONObject.NULL)
    }

    /** Flattened payload used by the enterprise sync endpoint. */
    fun toSyncJson(): JSONObject = JSONObject().apply {
        put("uid", uid)
        put("name", name)
        put("category", category.key)
        put("techTypes", JSONArray(tech))
        put("ndefPayload", JSONArray().apply { records.forEach { put(it.toJson()) } })
        put("ndefType", ndefType ?: JSONObject.NULL)
        put("scannedAt", lastScannedAt)
        put("firstSeenAt", firstSeenAt)
        put("scanCount", scanCount)
    }

    companion object {
        fun fromJson(o: JSONObject): NfcCard {
            val techArr = o.optJSONArray("tech") ?: o.optJSONArray("techTypes")
            val extrasObj = o.optJSONObject("extras")
            val recordsArr = o.optJSONArray("records") ?: o.optJSONArray("ndefPayload")
            return NfcCard(
                uid = o.optString("uid"),
                name = o.optString("name"),
                category = TagCategory.fromKey(o.optString("category")),
                notes = o.optString("notes"),
                tech = techArr.toStringList(),
                extras = extrasObj.toStringMap(),
                ndefType = o.nullableString("ndefType"),
                ndefMaxSize = o.optInt("ndefMaxSize"),
                ndefWritable = o.optBoolean("ndefWritable"),
                records = recordsArr.toRecordList(),
                rawNdefHex = o.nullableString("rawNdef"),
                firstSeenAt = o.optLong("firstSeenAt", o.optLong("time")),
                lastScannedAt = o.optLong("lastScannedAt", o.optLong("time")),
                scanCount = o.optInt("scanCount", 1),
                syncStatus = SyncStatus.fromKey(o.optString("syncStatus")),
                syncedAt = if (o.has("syncedAt") && !o.isNull("syncedAt")) o.optLong("syncedAt") else null,
            )
        }
    }
}

internal fun JSONObject?.toStringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    return buildMap {
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, optString(key))
        }
    }
}

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return List(length()) { optString(it) }
}

internal fun JSONArray?.toRecordList(): List<NdefRecordInfo> {
    if (this == null) return emptyList()
    return List(length()) {
        val item = optJSONObject(it)
        if (item == null) NdefRecordInfo(0, "", "raw", "", "") else NdefRecordInfo.fromJson(item)
    }
}

internal fun JSONObject.nullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
