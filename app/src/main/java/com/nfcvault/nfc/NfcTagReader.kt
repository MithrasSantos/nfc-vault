package com.nfcvault.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import com.nfcvault.data.model.NdefRecordInfo
import com.nfcvault.data.model.NfcReadResult
import java.util.Locale
import java.util.UUID

/**
 * Hardware layer. Extracts identity, RF metadata and NDEF content. Runs on a
 * background thread because NDEF reads do I/O and can block until the tag moves.
 */
object NfcTagReader {

    private val URI_PREFIXES = arrayOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
        "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://",
        "nfs://", "ftp://", "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:",
        "pop:", "sip:", "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://",
        "tcpobex://", "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:",
        "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
    )

    fun read(tag: Tag): NfcReadResult {
        val uid = tag.id
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(":") { hexByte(it) }
            ?: "NOUID-${UUID.randomUUID()}"

        val tech = tag.techList.map { it.substringAfterLast('.') }.sorted()
        val extras = readExtras(tag)

        var ndefType: String? = null
        var maxSize = 0
        var writable = false
        var records: List<NdefRecordInfo> = emptyList()
        var raw: String? = null

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndefType = runCatching { ndef.type }.getOrNull()
            maxSize = runCatching { ndef.maxSize }.getOrDefault(0)
            writable = runCatching { ndef.isWritable }.getOrDefault(false)
            try {
                ndef.connect()
                ndef.ndefMessage?.let { message ->
                    raw = message.toByteArray().toHex()
                    records = message.records.map { parse(it) }
                }
            } catch (_: Exception) {
                // Tag moved away mid-read: keep the identity/hardware data.
            } finally {
                runCatching { ndef.close() }
            }
        }

        return NfcReadResult(
            uid = uid,
            tech = tech,
            extras = extras,
            ndefType = ndefType,
            ndefMaxSize = maxSize,
            ndefWritable = writable,
            records = records,
            rawNdefHex = raw,
            readAt = System.currentTimeMillis(),
        )
    }

    private fun readExtras(tag: Tag): Map<String, String> {
        val out = linkedMapOf<String, String>()

        NfcA.get(tag)?.let {
            out["NfcA.atqa"] = it.atqa.toHex()
            out["NfcA.sak"] = "0x" + hexByte(it.sak.toByte())
        }
        NfcB.get(tag)?.let {
            out["NfcB.applicationData"] = it.applicationData.toHex()
            out["NfcB.protocolInfo"] = it.protocolInfo.toHex()
        }
        NfcF.get(tag)?.let {
            out["NfcF.manufacturer"] = it.manufacturer.toHex()
            out["NfcF.systemCode"] = it.systemCode.toHex()
        }
        NfcV.get(tag)?.let {
            out["NfcV.dsfId"] = "0x" + hexByte(it.dsfId)
            out["NfcV.responseFlags"] = "0x" + hexByte(it.responseFlags)
        }
        IsoDep.get(tag)?.let {
            it.hiLayerResponse?.let { bytes -> out["IsoDep.hiLayerResponse"] = bytes.toHex() }
            it.historicalBytes?.let { bytes -> out["IsoDep.historicalBytes"] = bytes.toHex() }
        }
        MifareClassic.get(tag)?.let {
            out["MifareClassic.type"] = mifareClassicType(it.type)
            out["MifareClassic.size"] = "${it.size} bytes · ${it.sectorCount} sectors · ${it.blockCount} blocks"
        }
        MifareUltralight.get(tag)?.let {
            out["MifareUltralight.type"] = ultralightType(it.type)
        }
        return out
    }

    private fun parse(record: NdefRecord): NdefRecordInfo {
        val payload = record.payload
        val typeName = when (record.tnf) {
            NdefRecord.TNF_WELL_KNOWN -> String(record.type, Charsets.US_ASCII)
            NdefRecord.TNF_MIME_MEDIA,
            NdefRecord.TNF_ABSOLUTE_URI,
            NdefRecord.TNF_EXTERNAL_TYPE -> String(record.type, Charsets.UTF_8)
            else -> record.type.toHex()
        }
        val hex = payload.toHex()
        val tnf = record.tnf.toInt()

        return when {
            record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type.contentEquals(NdefRecord.RTD_TEXT) && payload.isNotEmpty() -> {
                val status = payload[0].toInt() and 0xFF
                val languageLength = status and 0x3F
                val start = 1 + languageLength
                val charset = if (status and 0x80 == 0) Charsets.UTF_8 else Charsets.UTF_16
                val text = if (start <= payload.size) {
                    String(payload, start, payload.size - start, charset)
                } else {
                    ""
                }
                NdefRecordInfo(tnf, "T", "text", text, hex)
            }

            record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type.contentEquals(NdefRecord.RTD_URI) && payload.isNotEmpty() -> {
                val prefix = payload[0].toInt() and 0xFF
                val rest = if (payload.size > 1) {
                    String(payload, 1, payload.size - 1, Charsets.UTF_8)
                } else {
                    ""
                }
                val value = URI_PREFIXES.getOrElse(prefix) { "" } + rest
                NdefRecordInfo(tnf, "U", "uri", value, hex)
            }

            record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type.contentEquals("Sp".toByteArray(Charsets.US_ASCII)) -> {
                val nested = runCatching {
                    NdefMessage(payload).records.map { parse(it) }
                }.getOrDefault(emptyList())
                val value = nested.joinToString("\n") { "${it.kind}: ${it.value}" }
                    .ifBlank { "Smart Poster · ${payload.size} bytes" }
                NdefRecordInfo(tnf, "Sp", "smartposter", value, hex)
            }

            record.tnf == NdefRecord.TNF_MIME_MEDIA -> {
                val mime = typeName.lowercase(Locale.US)
                val kind = if (mime == "text/vcard" || mime == "text/x-vcard") "vcard" else "mime"
                val value = when {
                    kind == "vcard" -> parseVCard(payload)
                    mime.startsWith("text/") -> textOrSize(payload)
                    else -> binaryPreview(payload)
                }
                NdefRecordInfo(tnf, typeName, kind, value, hex)
            }

            record.tnf == NdefRecord.TNF_ABSOLUTE_URI ->
                NdefRecordInfo(tnf, typeName, "uri", textOrSize(payload), hex)

            record.tnf == NdefRecord.TNF_EXTERNAL_TYPE ->
                NdefRecordInfo(
                    tnf,
                    typeName,
                    "external",
                    textOrSize(payload),
                    hex,
                )

            payload.isEmpty() ->
                NdefRecordInfo(tnf, typeName, "empty", "", "")

            else ->
                NdefRecordInfo(tnf, typeName, "raw", binaryPreview(payload), hex)
        }
    }

    private fun parseVCard(payload: ByteArray): String {
        val raw = String(payload, Charsets.UTF_8)
        val lines = mutableListOf<String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("FN:", ignoreCase = true) ->
                    lines += "Name: ${trimmed.substringAfter(':')}"
                trimmed.startsWith("ORG:", ignoreCase = true) ->
                    lines += "Org: ${trimmed.substringAfter(':')}"
                trimmed.startsWith("TITLE:", ignoreCase = true) ->
                    lines += "Title: ${trimmed.substringAfter(':')}"
                trimmed.startsWith("TEL", ignoreCase = true) && trimmed.contains(':') ->
                    lines += "Phone: ${trimmed.substringAfter(':')}"
                trimmed.startsWith("EMAIL", ignoreCase = true) && trimmed.contains(':') ->
                    lines += "Email: ${trimmed.substringAfter(':')}"
            }
        }
        return if (lines.isEmpty()) textOrSize(payload) else lines.joinToString("\n")
    }

    private fun textOrSize(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val text = String(payload, Charsets.UTF_8)
        val printable = text.count {
            it == '\n' || it == '\r' || it == '\t' || it.code in 32..126
        }
        return if (text.isNotEmpty() && printable * 100 / text.length >= 85) {
            text
        } else {
            binaryPreview(payload)
        }
    }

    private fun binaryPreview(payload: ByteArray): String =
        if (payload.isEmpty()) "" else "${payload.size} bytes (binary)"

    private fun mifareClassicType(type: Int): String = when (type) {
        MifareClassic.TYPE_CLASSIC -> "Classic"
        MifareClassic.TYPE_PLUS -> "Plus"
        MifareClassic.TYPE_PRO -> "Pro"
        else -> "Unknown ($type)"
    }

    private fun ultralightType(type: Int): String = when (type) {
        MifareUltralight.TYPE_ULTRALIGHT -> "Ultralight"
        MifareUltralight.TYPE_ULTRALIGHT_C -> "Ultralight C"
        else -> "Unknown ($type)"
    }
}

fun ByteArray.toHex(): String = joinToString(" ") { hexByte(it) }

fun hexByte(value: Byte): String = String.format(Locale.US, "%02X", value)
