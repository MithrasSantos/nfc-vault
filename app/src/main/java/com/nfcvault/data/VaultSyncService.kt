package com.nfcvault.data

import com.nfcvault.data.model.NfcCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SyncResult(val success: Boolean, val message: String)

/** Contract the backend adapter has to satisfy. */
interface VaultSyncService {
    suspend fun push(
        cards: List<NfcCard>,
        deviceId: String,
        operatorId: String,
    ): SyncResult
}

/**
 * Dependency-free HTTP implementation. The endpoint receives one JSON body:
 * {
 *   deviceId, operatorId, syncedAt,
 *   tags: [{ uid, techTypes, ndefPayload, scannedAt, ... }]
 * }
 * Any 2xx response is treated as success; failures leave the queue intact.
 */
class HttpVaultSyncService(private val endpoint: String) : VaultSyncService {

    override suspend fun push(
        cards: List<NfcCard>,
        deviceId: String,
        operatorId: String,
    ): SyncResult = withContext(Dispatchers.IO) {
        if (cards.isEmpty()) return@withContext SyncResult(true, "Nothing to sync")
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            return@withContext SyncResult(false, "Server URL must start with http:// or https://")
        }

        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("operatorId", operatorId)
            put("syncedAt", System.currentTimeMillis())
            put("tags", JSONArray().apply { cards.forEach { put(it.toSyncJson()) } })
        }.toString()

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 25_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Device-Id", deviceId)
                setRequestProperty("X-Operator-Id", operatorId)
            }
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code in 200..299) {
                SyncResult(true, "Synced ${cards.size} tag(s) · HTTP $code")
            } else {
                val detail = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?.take(240)
                    ?.ifBlank { null }
                SyncResult(false, "HTTP $code${detail?.let { ": $it" } ?: ""}")
            }
        } catch (e: Exception) {
            SyncResult(false, e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }
}
