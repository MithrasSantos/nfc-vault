package com.nfcvault.ui

import android.app.Application
import android.nfc.NfcManager
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nfcvault.NfcVaultApp
import com.nfcvault.data.HttpVaultSyncService
import com.nfcvault.data.model.NfcCard
import com.nfcvault.data.model.NfcStatus
import com.nfcvault.data.model.SyncStatus
import com.nfcvault.data.model.TagCategory
import com.nfcvault.data.model.TagInsights
import com.nfcvault.nfc.NfcTagReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class ScanEvent(
    val token: Long,
    val success: Boolean,
    val uid: String,
    val displayName: String,
    val scanCount: Int,
    val isNew: Boolean,
    val message: String,
)

data class UiMessage(val token: Long, val text: String)

data class WalletUiState(
    val cards: List<NfcCard> = emptyList(),
    val totalCount: Int = 0,
    val pendingCount: Int = 0,
    val lastSyncAt: Long = 0L,
    val nfcStatus: NfcStatus = NfcStatus.UNKNOWN,
    val batchMode: Boolean = false,
    val query: String = "",
    val category: TagCategory? = null,
    val scanning: Boolean = false,
    val syncing: Boolean = false,
    val lastScan: ScanEvent? = null,
    val message: UiMessage? = null,
)

class WalletViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NfcVaultApp
    private val repo = app.repository
    private val prefs = app.prefs

    private val _state = MutableStateFlow(
        WalletUiState(
            batchMode = prefs.batchMode,
            lastSyncAt = prefs.lastSyncAt,
            nfcStatus = readNfcStatus(),
        ),
    )
    val state: StateFlow<WalletUiState> = _state.asStateFlow()

    private var allCards: List<NfcCard> = emptyList()
    private var eventToken = 0L
    private var messageToken = 0L
    private var lastBatchUid: String? = null
    private var lastBatchAt = 0L

    init {
        viewModelScope.launch {
            repo.importLegacyIfNeeded()
            reload()
        }
    }

    fun refreshNfcStatus() {
        _state.update { it.copy(nfcStatus = readNfcStatus()) }
    }

    fun beginScan() {
        _state.update { it.copy(scanning = true) }
    }

    fun stopScan() {
        _state.update { it.copy(scanning = false) }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        applyFilter()
    }

    fun setCategory(category: TagCategory?) {
        _state.update { it.copy(category = category) }
        applyFilter()
    }

    fun onTagRead(tag: Tag) {
        viewModelScope.launch {
            val read = runCatching {
                withContext(Dispatchers.IO) { NfcTagReader.read(tag) }
            }.getOrElse { error ->
                emitScanFailure(error.message ?: "Could not read the tag")
                return@launch
            }

            val now = System.currentTimeMillis()
            if (_state.value.batchMode && read.uid == lastBatchUid && now - lastBatchAt < 1_800) {
                return@launch
            }
            lastBatchUid = read.uid
            lastBatchAt = now

            runCatching { repo.upsert(read) }
                .onSuccess { result ->
                    reload()
                    val message = if (result.isNew) {
                        "New tag saved"
                    } else {
                        "Tag updated · ${result.card.scanCount} scans"
                    }
                    _state.update {
                        it.copy(
                            lastScan = ScanEvent(
                                token = ++eventToken,
                                success = true,
                                uid = result.card.uid,
                                displayName = result.card.displayName,
                                scanCount = result.card.scanCount,
                                isNew = result.isNew,
                                message = message,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    emitScanFailure(error.message ?: "Could not save the tag")
                }
        }
    }

    fun updateMeta(uid: String, name: String, category: TagCategory, notes: String) {
        viewModelScope.launch {
            repo.updateMeta(uid, name, category, notes)
            reload()
            message("Tag details updated")
        }
    }

    fun delete(uid: String) {
        viewModelScope.launch {
            repo.delete(uid)
            reload()
            message("Tag removed from the vault")
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            repo.clearAll()
            reload()
            message("Vault cleared")
        }
    }

    fun syncAll() {
        if (_state.value.syncing) return
        val targets = allCards.filter { it.syncStatus != SyncStatus.SYNCED }
        if (targets.isEmpty()) {
            message("Everything is already synced.")
            return
        }
        val url = prefs.serverUrl.trim()
        if (url.isBlank()) {
            viewModelScope.launch {
                repo.markPending(targets.map { it.uid })
                reload()
                message("${targets.size} tag(s) queued. Set the server URL in Settings.")
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(syncing = true) }
            repo.markPending(targets.map { it.uid })
            reload()

            var okCount = 0
            var lastError: String? = null
            targets.chunked(100).forEach { chunk ->
                val result = HttpVaultSyncService(url).push(
                    chunk,
                    prefs.deviceId,
                    prefs.operatorId,
                )
                if (result.success) {
                    okCount += chunk.size
                    repo.markSynced(chunk.map { it.uid }, System.currentTimeMillis())
                } else {
                    lastError = result.message
                }
            }

            if (okCount > 0) prefs.lastSyncAt = System.currentTimeMillis()
            reload()
            _state.update { it.copy(syncing = false) }
            if (lastError == null) {
                message("Synced $okCount tag(s).")
            } else {
                message("$okCount synced, rest kept in queue: $lastError")
            }
        }
    }

    fun syncCard(uid: String) {
        val card = allCards.firstOrNull { it.uid == uid } ?: return
        if (card.syncStatus == SyncStatus.SYNCED) {
            message("This tag is already synced.")
            return
        }
        val url = prefs.serverUrl.trim()
        if (url.isBlank()) {
            viewModelScope.launch {
                repo.markPending(listOf(uid))
                reload()
                message("Tag queued. Set the server URL in Settings.")
            }
            return
        }
        if (_state.value.syncing) return

        viewModelScope.launch {
            _state.update { it.copy(syncing = true) }
            repo.markPending(listOf(uid))
            val result = HttpVaultSyncService(url).push(
                listOf(card),
                prefs.deviceId,
                prefs.operatorId,
            )
            if (result.success) {
                repo.markSynced(listOf(uid), System.currentTimeMillis())
                prefs.lastSyncAt = System.currentTimeMillis()
            }
            reload()
            _state.update { it.copy(syncing = false) }
            message(if (result.success) "Tag synced." else "Sync failed: ${result.message}")
        }
    }

    fun saveSettings(serverUrl: String, operatorId: String, batchMode: Boolean) {
        prefs.serverUrl = serverUrl
        prefs.operatorId = operatorId
        prefs.batchMode = batchMode
        _state.update { it.copy(batchMode = batchMode) }
        message("Settings saved")
    }

    fun cardsSnapshot(): List<NfcCard> = allCards

    fun loadLogs(uid: String, onReady: (List<Long>) -> Unit) {
        viewModelScope.launch {
            onReady(repo.scanLogs(uid))
        }
    }

    private suspend fun reload() {
        allCards = repo.all()
        applyFilter()
        _state.update {
            it.copy(
                totalCount = allCards.size,
                pendingCount = allCards.count { card -> card.syncStatus != SyncStatus.SYNCED },
                lastSyncAt = prefs.lastSyncAt,
                batchMode = prefs.batchMode,
            )
        }
    }

    private fun applyFilter() {
        val snapshot = _state.value
        val query = snapshot.query.trim()
        val normalized = query.lowercase(Locale.getDefault())
        val uidQuery = TagInsights.searchableUid(query)
        val filtered = allCards.filter { card ->
            val categoryOk = snapshot.category == null || card.category == snapshot.category
            val queryOk = query.isEmpty() ||
                TagInsights.searchable(card).contains(normalized) ||
                (uidQuery.isNotEmpty() && TagInsights.searchableUid(card.uid).contains(uidQuery))
            categoryOk && queryOk
        }
        _state.update { it.copy(cards = filtered) }
    }

    private fun emitScanFailure(message: String) {
        _state.update {
            it.copy(
                lastScan = ScanEvent(
                    token = ++eventToken,
                    success = false,
                    uid = "",
                    displayName = "Tag",
                    scanCount = 0,
                    isNew = false,
                    message = message,
                ),
            )
        }
    }

    private fun message(text: String) {
        _state.update { it.copy(message = UiMessage(++messageToken, text)) }
    }

    private fun readNfcStatus(): NfcStatus {
        val adapter = getApplication<Application>()
            .getSystemService(NfcManager::class.java)
            ?.defaultAdapter
        return when {
            adapter == null -> NfcStatus.UNAVAILABLE
            !adapter.isEnabled -> NfcStatus.OFF
            else -> NfcStatus.READY
        }
    }
}
