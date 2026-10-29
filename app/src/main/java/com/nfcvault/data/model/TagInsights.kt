package com.nfcvault.data.model

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Presentation helpers that describe hardware without leaking Android classes. */
object TagInsights {

    fun shortTech(tech: List<String>, ndefType: String?): String = when {
        tech.contains("MifareClassic") -> "MIFARE"
        tech.contains("MifareUltralight") -> "NTAG / UL"
        tech.contains("IsoDep") && tech.contains("NfcA") -> "ISO-DEP"
        tech.contains("IsoDep") -> "ISO-DEP"
        tech.contains("NfcV") -> "ISO 15693"
        tech.contains("NfcF") -> "FeliCa"
        tech.contains("NfcB") -> "ISO 14443-B"
        tech.contains("NfcA") && ndefType?.contains("Type 2", true) == true -> "NTAG"
        tech.contains("NfcA") -> "ISO 14443-A"
        else -> "NFC"
    }

    fun cardType(tech: List<String>, ndefType: String?): String = when {
        tech.contains("MifareClassic") -> "MIFARE Classic"
        tech.contains("MifareUltralight") && ndefType?.contains("Type 2", true) == true ->
            "NTAG / MIFARE Ultralight"
        tech.contains("MifareUltralight") -> "MIFARE Ultralight"
        tech.contains("NfcV") -> "ISO 15693 (NfcV)"
        tech.contains("NfcF") -> "FeliCa"
        tech.contains("NfcB") -> "ISO 14443-B"
        tech.contains("IsoDep") -> "ISO-DEP"
        tech.contains("NfcA") && ndefType?.contains("Type 2", true) == true ->
            "NTAG (ISO 14443-A)"
        tech.contains("NfcA") -> "ISO 14443-A"
        else -> "NFC tag"
    }

    fun writeStatus(ndefType: String?, writable: Boolean): String = when {
        ndefType == null -> "Not NDEF formatted"
        writable -> "Writable"
        else -> "Read-only"
    }

    fun memoryLabel(card: NfcCard): String {
        card.extras["MifareClassic.size"]?.let { return it }
        if (card.ndefMaxSize > 0) return "${card.ndefMaxSize} bytes NDEF"
        card.extras["NfcA.atqa"]?.let { return "ATQA $it" }
        return "Not reported"
    }

    fun payloadLabel(card: NfcCard): String = when {
        card.hasNdef -> card.ndefType ?: "NDEF data"
        else -> "Physical ID tag · UID only"
    }

    fun friendlyTime(timestamp: Long): String {
        if (timestamp <= 0L) return "Never"
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = timestamp }
        val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
        val sameDay = sameYear && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        val yesterday = sameYear && now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) == 1
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        return when {
            sameDay -> "Today, $time"
            yesterday -> "Yesterday, $time"
            sameYear -> SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(timestamp))
            else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(timestamp))
        }
    }

    fun fullTime(timestamp: Long): String {
        if (timestamp <= 0L) return "—"
        return SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
            .format(Date(timestamp))
    }

    /** Very short label for the header metric tile. */
    fun compactTime(timestamp: Long): String {
        if (timestamp <= 0L) return "Never"
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = timestamp }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        } else {
            SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestamp))
        }
    }

    fun searchableUid(uid: String): String = uid.replace(":", "").replace(" ", "").uppercase(Locale.US)

    fun searchable(card: NfcCard): String = buildString {
        append(card.uid)
        append(' ')
        append(searchableUid(card.uid))
        append(' ')
        append(card.name)
        append(' ')
        append(card.category.label)
        append(' ')
        append(card.cardType)
        append(' ')
        append(card.notes)
    }.lowercase(Locale.getDefault())
}
