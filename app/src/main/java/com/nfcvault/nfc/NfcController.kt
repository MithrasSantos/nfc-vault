package com.nfcvault.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.nfc.Tag
import com.nfcvault.data.model.NfcStatus

/**
 * Turns reader mode on only while the app is visible *and* the scan sheet is
 * open. This avoids conflicts with payment wallets and battery drain.
 */
class NfcController(
    private val activity: Activity,
    private val onTag: (Tag) -> Unit,
) {
    private val adapter: NfcAdapter? =
        activity.getSystemService(NfcManager::class.java)?.defaultAdapter

    private var scanning = false
    private var resumed = false

    private val readerFlags =
        NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

    fun status(): NfcStatus = when {
        adapter == null -> NfcStatus.UNAVAILABLE
        !adapter.isEnabled -> NfcStatus.OFF
        else -> NfcStatus.READY
    }

    fun startScanning() {
        scanning = true
        apply()
    }

    fun stopScanning() {
        scanning = false
        disable()
    }

    fun onResume() {
        resumed = true
        apply()
    }

    fun onPause() {
        resumed = false
        disable()
    }

    fun release() {
        scanning = false
        resumed = false
        disable()
    }

    private fun apply() {
        if (scanning && resumed && adapter?.isEnabled == true) {
            runCatching {
                adapter?.enableReaderMode(activity, { tag -> onTag(tag) }, readerFlags, null)
            }
        } else {
            disable()
        }
    }

    private fun disable() {
        runCatching { adapter?.disableReaderMode(activity) }
    }
}
