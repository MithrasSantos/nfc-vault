package com.nfcvault

import org.json.JSONArray
import org.json.JSONObject

/** One NDEF record, flattened into something easy to store and display. */
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

/** A saved scan: identity of the tag plus everything we could read from it. */
data class ScanRecord(
    val id: String,
    val time: Long,
    val uid: String,
    val tech: List<String>,
    val extras: Map<String, String>,
    val ndefType: String?,
    val ndefMaxSize: Int,
    val ndefWritable: Boolean,
    val records: List<NdefRecordInfo>,
    val rawNdefHex: String?,
) {
    fun summary(): String {
        if (records.isEmpty()) return "no NDEF data"
        return records.joinToString(", ") { r ->
            val v = r.value.replace("\n", " ").trim()
            val short = if (v.length > 40) v.take(40) + "…" else v
            if (short.isEmpty()) r.kind else "${r.kind}: $short"
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("time", time)
        put("uid", uid)
        put("tech", JSONArray(tech))
        put("extras", JSONObject().apply { extras.forEach { (k, v) -> put(k, v) } })
        put("ndefType", ndefType ?: JSONObject.NULL)
        put("ndefMaxSize", ndefMaxSize)
        put("ndefWritable", ndefWritable)
        put("records", JSONArray().apply { records.forEach { put(it.toJson()) } })
        put("rawNdef", rawNdefHex ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): ScanRecord {
            val techArr = o.optJSONArray("tech")
            val extrasObj = o.optJSONObject("extras")
            val recArr = o.optJSONArray("records")
            return ScanRecord(
                id = o.optString("id"),
                time = o.optLong("time"),
                uid = o.optString("uid"),
                tech = if (techArr == null) emptyList() else List(techArr.length()) { techArr.optString(it) },
                extras = if (extrasObj == null) emptyMap() else buildMap {
                    val keys = extrasObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        put(k, extrasObj.optString(k))
                    }
                },
                ndefType = if (o.has("ndefType") && !o.isNull("ndefType")) o.optString("ndefType") else null,
                ndefMaxSize = o.optInt("ndefMaxSize"),
                ndefWritable = o.optBoolean("ndefWritable"),
                records = if (recArr == null) emptyList() else List(recArr.length()) {
                    NdefRecordInfo.fromJson(recArr.getJSONObject(it))
                },
                rawNdefHex = if (o.has("rawNdef") && !o.isNull("rawNdef")) o.optString("rawNdef") else null,
            )
        }
    }
}
