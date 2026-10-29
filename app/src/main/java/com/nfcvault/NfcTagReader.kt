package com.nfcvault

import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import java.util.Locale
import java.util.UUID

/** Reads a [Tag] into a [ScanRecord]. Safe to call off the main thread. */
object NfcTagReader {

    private val URI_PREFIXES = arrayOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
        "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://",
        "nfs://", "ftp://", "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:",
        "pop:", "sip:", "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://",
        "tcpobex://", "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:",
        "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
    )

    fun read(tag: Tag): ScanRecord {
        val uid = tag.id?.joinToString(":") { hexByte(it) } ?: ""
        val tech = tag.techList.map { it.substringAfterLast('.') }
        val extras = readExtras(tag)

        var ndefType: String? = null
        var maxSize = 0
        var writable = false
        var records: List<NdefRecordInfo> = emptyList()
        var raw: String? = null

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                ndefType = ndef.type
                maxSize = ndef.maxSize
                writable = ndef.isWritable
                val message = ndef.ndefMessage
                if (message != null) {
                    raw = message.toByteArray().toHex()
                    records = message.records.map { parse(it) }
                }
            } catch (_: Exception) {
                // Tag may have moved away mid-read; keep the partial data.
            } finally {
                try {
                    ndef.close()
                } catch (_: Exception) {
                }
            }
        }

        return ScanRecord(
            id = UUID.randomUUID().toString(),
            time = System.currentTimeMillis(),
            uid = uid,
            tech = tech,
            extras = extras,
            ndefType = ndefType,
            ndefMaxSize = maxSize,
            ndefWritable = writable,
            records = records,
            rawNdefHex = raw,
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
            out["NfcV.responseFlags"] = "0x" + hexByte(it.responseFlags)
        }
        IsoDep.get(tag)?.let {
            it.hiLayerResponse?.let { bytes -> out["IsoDep.hiLayerResponse"] = bytes.toHex() }
            it.historicalBytes?.let { bytes -> out["IsoDep.historicalBytes"] = bytes.toHex() }
        }
        MifareClassic.get(tag)?.let {
            out["MifareClassic.type"] = it.type.toString()
            out["MifareClassic.size"] = "${it.size} bytes, ${it.sectorCount} sectors"
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
                val langLength = status and 0x3F
                val start = 1 + langLength
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
                NdefRecordInfo(tnf, "U", "uri", URI_PREFIXES.getOrElse(prefix) { "" } + rest, hex)
            }

            record.tnf == NdefRecord.TNF_MIME_MEDIA ->
                NdefRecordInfo(tnf, typeName, "mime", preview(payload), hex)

            record.tnf == NdefRecord.TNF_ABSOLUTE_URI ->
                NdefRecordInfo(tnf, typeName, "uri", String(payload, Charsets.UTF_8), hex)

            record.tnf == NdefRecord.TNF_EXTERNAL_TYPE ->
                NdefRecordInfo(tnf, typeName, "external", preview(payload), hex)

            payload.isEmpty() ->
                NdefRecordInfo(tnf, typeName, "empty", "", "")

            else ->
                NdefRecordInfo(tnf, typeName, "raw", hex, hex)
        }
    }

    /** Text when the payload looks like text, otherwise a size hint. */
    private fun preview(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val text = String(payload, Charsets.UTF_8)
        if (text.isEmpty()) return "${payload.size} bytes (binary)"
        val printable = text.count {
            it == '\n' || it == '\r' || it == '\t' || it.code in 32..126
        }
        return if (printable * 100 / text.length >= 90) text else "${payload.size} bytes (binary)"
    }
}

fun ByteArray.toHex(): String = joinToString(" ") { hexByte(it) }

fun hexByte(value: Byte): String = String.format(Locale.US, "%02X", value)
