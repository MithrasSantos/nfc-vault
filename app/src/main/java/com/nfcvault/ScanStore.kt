package com.nfcvault

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tiny JSON-file store. Scans never leave the device unless the user shares
 * or uploads them explicitly.
 */
class ScanStore(context: Context) {
    private val file = File(context.filesDir, "scans.json")
    private val lock = Any()

    fun load(): MutableList<ScanRecord> = synchronized(lock) { read() }

    fun add(record: ScanRecord): MutableList<ScanRecord> = synchronized(lock) {
        val list = read()
        list.add(0, record)
        while (list.size > MAX_RECORDS) {
            list.removeAt(list.size - 1)
        }
        write(list)
        list
    }

    fun remove(id: String): MutableList<ScanRecord> = synchronized(lock) {
        val list = read()
        list.removeAll { it.id == id }
        write(list)
        list
    }

    fun clear() {
        synchronized(lock) { write(mutableListOf()) }
    }

    private fun read(): MutableList<ScanRecord> {
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            MutableList(arr.length()) { ScanRecord.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun write(list: List<ScanRecord>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(file.absolutePath + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
        }
    }

    companion object {
        private const val MAX_RECORDS = 1000

        /** Stable export format used by share, "save to Downloads" and upload. */
        fun exportJson(records: List<ScanRecord>): String {
            val root = JSONObject().apply {
                put("app", "NFC Vault")
                put("schema", 1)
                put("exportedAt", System.currentTimeMillis())
                put("count", records.size)
                put("scans", JSONArray().apply { records.forEach { put(it.toJson()) } })
            }
            return root.toString(2)
        }
    }
}
