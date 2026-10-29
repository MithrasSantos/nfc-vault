package com.nfcvault.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * Serves the active vault entry as an NFC Forum Type 4 Tag over ISO-DEP.
 *
 * Only the NDEF payload is emulated. Android assigns a fresh NFC ID per tap,
 * so this cannot impersonate a card whose UID is checked by the reader, and
 * it cannot emulate MIFARE Classic/Ultralight at the RF layer.
 */
class NdefHostApduService : HostApduService() {

    private var selected: Int = SELECT_NONE

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) return FAILURE
        val tag = EmulationStore.active(applicationContext) ?: return FAILURE

        return when (commandApdu[1].toInt() and 0xFF) {
            INS_SELECT -> handleSelect(commandApdu)
            INS_READ_BINARY -> handleReadBinary(commandApdu, tag)
            INS_UPDATE_BINARY -> UPDATE_DENIED
            else -> FAILURE
        }
    }

    private fun handleSelect(commandApdu: ByteArray): ByteArray {
        if (commandApdu.size < 5) return FAILURE
        val p1 = commandApdu[2].toInt() and 0xFF
        val length = commandApdu[4].toInt() and 0xFF
        val start = 5
        val end = minOf(start + length, commandApdu.size)
        if (start > end) return FAILURE
        val data = commandApdu.copyOfRange(start, end)

        return when (p1) {
            0x04 -> {
                if (!data.contentEquals(NDEF_AID)) return FAILURE
                selected = SELECT_APPLICATION
                SUCCESS
            }

            0x00 -> when {
                data.contentEquals(CC_FILE_ID) -> {
                    selected = SELECT_CC
                    SUCCESS
                }

                data.contentEquals(NDEF_FILE_ID) -> {
                    selected = SELECT_NDEF
                    SUCCESS
                }

                else -> FAILURE
            }

            else -> FAILURE
        }
    }

    private fun handleReadBinary(
        commandApdu: ByteArray,
        tag: EmulationStore.ActiveTag,
    ): ByteArray {
        if (commandApdu.size < 5) return FAILURE
        val offset = ((commandApdu[2].toInt() and 0xFF) shl 8) or
            (commandApdu[3].toInt() and 0xFF)
        val requested = (commandApdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it }

        val file = when (selected) {
            SELECT_CC -> capabilityContainer(tag)
            SELECT_NDEF -> ndefFile(tag)
            else -> return FAILURE
        }
        if (offset >= file.size) return FAILURE

        val end = minOf(offset + requested, file.size)
        return file.copyOfRange(offset, end) + SUCCESS
    }

    private fun capabilityContainer(tag: EmulationStore.ActiveTag): ByteArray {
        val maxSize = tag.maxSize.coerceIn(0x00FF, 0xFFFF)
        return byteArrayOf(
            0x00, 0x0F, // CCLEN
            0x20, // Mapping version 2.0
            0x00, 0x3B, // maximum R-APDU size
            0x00, 0x34, // maximum C-APDU size
            0x04, 0x06, // NDEF File Control TLV
            0xE1.toByte(), 0x04, // NDEF file id
            (maxSize shr 8).toByte(), maxSize.toByte(),
            0x00, // read access granted
            0xFF.toByte(), // write access denied
        )
    }

    private fun ndefFile(tag: EmulationStore.ActiveTag): ByteArray {
        val length = tag.ndef.size.coerceAtMost(0xFFFF)
        val file = ByteArray(length + 2)
        file[0] = (length shr 8).toByte()
        file[1] = length.toByte()
        tag.ndef.copyInto(file, destinationOffset = 2, startIndex = 0, endIndex = length)
        return file
    }

    override fun onDeactivated(reason: Int) {
        selected = SELECT_NONE
    }

    private companion object {
        const val SELECT_NONE = 0
        const val SELECT_APPLICATION = 1
        const val SELECT_CC = 2
        const val SELECT_NDEF = 3

        const val INS_SELECT = 0xA4
        const val INS_READ_BINARY = 0xB0
        const val INS_UPDATE_BINARY = 0xD6

        val NDEF_AID = byteArrayOf(
            0xD2.toByte(), 0x76, 0x00, 0x00,
            0x85.toByte(), 0x01, 0x01,
        )
        val CC_FILE_ID = byteArrayOf(0xE1.toByte(), 0x03)
        val NDEF_FILE_ID = byteArrayOf(0xE1.toByte(), 0x04)

        val SUCCESS = byteArrayOf(0x90.toByte(), 0x00)
        val FAILURE = byteArrayOf(0x6A, 0x82.toByte())
        val UPDATE_DENIED = byteArrayOf(0x69, 0x82.toByte())
    }
}
