package com.nfcvault

import android.app.Application
import com.nfcvault.data.NfcRepository
import com.nfcvault.data.Prefs

/** Process-wide dependency holder; small enough that a DI framework is noise. */
class NfcVaultApp : Application() {

    lateinit var prefs: Prefs
        private set

    lateinit var repository: NfcRepository
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        repository = NfcRepository(this, prefs)
    }
}
