package com.nfcvault.nfc

import android.content.Context
import com.nfcvault.data.model.NfcCard

/**
 * Holds the tag that should be served by [NdefHostApduService].
 *
 * The NFC framework starts the HCE service on demand, possibly after the app
 * process was killed, so the active payload lives in SharedPreferences rather
 * than in memory. Activation expires automatically to avoid leaving a tag
 * emulated indefinitely.
 */
object EmulationStore {

    private const val PREFS = "nfc_emulation"
    private const val KEY_NAME = "name"
    private const val KEY_NDEF = "ndef_hex"
    private const val KEY_MAX = "max_size"
    private const val KEY_ACTIVATED_AT = "activated_at"
    private const val TIMEOUT_MS = 5 * 60 * 1000L

    data class ActiveTag(
        val name: String,
        val ndef: ByteArray,
        val maxSize: Int,
        val activatedAt: Long,
    )

    @Volatile
    private var cached: ActiveTag? = null

    /** Stores [card]'s raw NDEF message and returns it, or null when it has none. */
    fun activate(context: Context, card: NfcCard): ActiveTag? {
        val ndef = card.rawNdefHex?.hexToBytes() ?: return null
        if (ndef.isEmpty()) return null

        val maxSize = maxOf(ndef.size + 2, card.ndefMaxSize, 0x00FF)
            .coerceAtMost(0xFFFF)
        val activatedAt = System.currentTimeMillis()

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAME, card.displayName)
            .putString(KEY_NDEF, ndef.toHexCompact())
            .putInt(KEY_MAX, maxSize)
            .putLong(KEY_ACTIVATED_AT, activatedAt)
            .apply()

        return ActiveTag(card.displayName, ndef, maxSize, activatedAt)
            .also { cached = it }
    }

    /** Returns the tag currently being emulated, or null when inactive/expired. */
    fun active(context: Context): ActiveTag? {
        val now = System.currentTimeMillis()
        cached?.let { tag ->
            if (now - tag.activatedAt <= TIMEOUT_MS) return tag
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hex = prefs.getString(KEY_NDEF, null) ?: return null
        val ndef = hex.hexToBytes() ?: return null
        val activatedAt = prefs.getLong(KEY_ACTIVATED_AT, 0L)
        if (activatedAt == 0L || now - activatedAt > TIMEOUT_MS) {
            deactivate(context)
            return null
        }

        return ActiveTag(
            name = prefs.getString(KEY_NAME, "NFC Vault tag") ?: "NFC Vault tag",
            ndef = ndef,
            maxSize = prefs.getInt(KEY_MAX, maxOf(ndef.size + 2, 0x00FF)),
            activatedAt = activatedAt,
        ).also { cached = it }
    }

    fun deactivate(context: Context) {
        cached = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}

internal fun String.hexToBytes(): ByteArray? {
    val clean = filterNot { it.isWhitespace() || it == ':' || it == '-' }
    if (clean.isEmpty() || clean.length % 2 != 0) return null
    return runCatching {
        ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }.getOrNull()
}

internal fun ByteArray.toHexCompact(): String =
    joinToString("") { "%02x".format(it.toInt() and 0xFF) }
