package com.nfcvault.data.model

/** Everything extracted from one physical tag tap, before it enters the vault. */
data class NfcReadResult(
    val uid: String,
    val tech: List<String>,
    val extras: Map<String, String>,
    val ndefType: String?,
    val ndefMaxSize: Int,
    val ndefWritable: Boolean,
    val records: List<NdefRecordInfo>,
    val rawNdefHex: String?,
    val readAt: Long,
)
