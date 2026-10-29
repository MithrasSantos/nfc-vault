package com.nfcvault.data

import android.content.Context
import android.provider.Settings
import java.util.UUID

/** Small settings surface shared by the repository, sync layer and UI. */
class Prefs(context: Context) {

    private val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences("vault_settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_SERVER_URL, value.trim()).apply()

    var operatorId: String
        get() = sp.getString(KEY_OPERATOR_ID, "") ?: ""
        set(value) = sp.edit().putString(KEY_OPERATOR_ID, value.trim()).apply()

    var batchMode: Boolean
        get() = sp.getBoolean(KEY_BATCH_MODE, false)
        set(value) = sp.edit().putBoolean(KEY_BATCH_MODE, value).apply()

    var lastSyncAt: Long
        get() = sp.getLong(KEY_LAST_SYNC, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_SYNC, value).apply()

    var legacyImported: Boolean
        get() = sp.getBoolean(KEY_LEGACY_IMPORTED, false)
        set(value) = sp.edit().putBoolean(KEY_LEGACY_IMPORTED, value).apply()

    val deviceId: String by lazy {
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        val base = androidId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        sp.getString(KEY_DEVICE_ID, null) ?: "ANDROID-$base".also {
            sp.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_OPERATOR_ID = "operator_id"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_BATCH_MODE = "batch_mode"
        const val KEY_LAST_SYNC = "last_sync_at"
        const val KEY_LEGACY_IMPORTED = "legacy_imported"
    }
}
