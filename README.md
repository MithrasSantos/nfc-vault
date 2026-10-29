# NFC Vault

Enterprise NFC wallet for Android. Scans cards and tags, keeps a deduplicated
vault on the phone, and exports or syncs the data when you decide to.

Built for a Samsung Galaxy S24 (Android 14), and works on any NFC phone running
Android 10 or newer.

## What's new in 0.3.0

- **NDEF tag emulation** over Android Host Card Emulation (HCE). Open a saved
  tag, tap **Emulate NDEF on this phone**, and hold the phone to a reader.
- Emulation serves the stored NDEF message as an NFC Forum Type 4 Tag for five
  minutes, then expires automatically.
- This is payload emulation, not RF replay. Android generates its own NFC ID,
  so readers that check the original UID will reject it. MIFARE Classic and
  MIFARE Ultralight cards cannot be emulated by phone hardware.
- The HCE service only responds while a tag is explicitly activated; otherwise
  it returns `6A82`.

## What's new in 0.2.0

- **Dark-first wallet UI** (`#0D1117` background, `#161B22` surfaces, electric
  blue / emerald accents) with card-shaped tag rows.
- Header metrics: saved tags, last sync, pending queue and NFC hardware status.
- Search by name, full/partial UID or category; category filters.
- Tag inspector with hardware metadata, decoded NDEF, raw payload, scan history
  and per-tag actions (edit, sync, export, delete).
- Deduplication by UID: rescanning a tag updates `lastScannedAt`, increments
  `scanCount` and appends to its scan history instead of creating a duplicate.
- Continuous **batch scan** mode for inventory.
- Offline sync queue with `Local only`, `Pending sync`, `Synced` and
  `Sync failed` states.
- Legacy `files/scans.json` data is imported automatically on first launch and
  kept as `scans.json.imported`.

## Architecture

```
app/src/main/java/com/nfcvault/
├── MainActivity.kt              Wallet home, search, filters, actions
├── NfcVaultApp.kt               Application + dependency holder
├── data/
│   ├── NfcDatabase.kt           SQLite schema, upsert and scan log
│   ├── NfcRepository.kt         Single source of truth, legacy import
│   ├── Prefs.kt                 Endpoint, operator, device id, batch mode
│   ├── Exporters.kt             JSON/CSV, share and Downloads output
│   ├── VaultSyncService.kt      REST contract + HttpURLConnection adapter
│   └── model/                   NfcCard, NdefRecordInfo, enums, insights
├── nfc/
│   ├── NfcTagReader.kt          Hardware/NDEF parsing (NfcA/B/F/V, IsoDep)
│   └── NfcController.kt         Reader-mode lifecycle (resumed + scanning only)
└── ui/
    ├── WalletViewModel.kt       StateFlow<WalletUiState>, MVVM
    ├── WalletAdapter.kt         Wallet cards (RecyclerView)
    ├── ScanSheet.kt             Radar bottom sheet + haptics
    ├── TagDetailsSheet.kt       Inspector and card actions
    ├── SettingsSheet.kt         Endpoint, operator, batch scan
    ├── RadarView.kt             Pulse animation
    └── UiKit.kt                 Colors, shapes, text, haptics, clipboard
```

Reader mode is only enabled while the activity is resumed **and** the scan sheet
is open, so system payment wallets keep working the rest of the time.

## Data model

`cards` (one row per physical tag, primary key `uid`):
`uid, name, category, notes, tech, extras, ndef_type, ndef_max_size,
ndef_writable, records, raw_ndef, first_seen_at, last_scanned_at, scan_count,
sync_status, synced_at`.

`scan_logs` holds the tap history (`card_uid`, `scanned_at`), capped at the last
200 entries per tag.

## Export format (schema 2)

```json
{
  "app": "NFC Vault",
  "schema": 2,
  "exportedAt": 1760000000000,
  "count": 1,
  "tags": [
    {
      "uid": "04:86:3C:AA:4F:75:80",
      "name": "Main Office Badge",
      "category": "access",
      "techTypes": ["NfcA", "Ndef"],
      "ndefType": "NFC Forum Type 2",
      "ndefMaxSize": 137,
      "ndefWritable": true,
      "ndefPayload": [
        { "tnf": 1, "type": "T", "kind": "text", "value": "hello", "payloadHex": "02 65 6E 68 65 6C 6C 6F" }
      ],
      "scannedAt": 1760000000000,
      "firstSeenAt": 1760000000000,
      "scanCount": 4,
      "syncStatus": "local"
    }
  ]
}
```

CSV export contains the same fields flattened for spreadsheets.

## Backend sync

Set the endpoint in **Settings**. Every request is a `POST` with
`Content-Type: application/json`:

```json
{
  "deviceId": "ANDROID-…",
  "operatorId": "OP-42",
  "syncedAt": 1760000000000,
  "tags": [
    {
      "uid": "04:86:3C:AA:4F:75:80",
      "techTypes": ["NfcA", "Ndef"],
      "ndefPayload": [],
      "scannedAt": 1760000000000,
      "name": "Server Keyring",
      "category": "asset",
      "scanCount": 2
    }
  ]
}
```

Any `2xx` marks the batch as synced. Failures keep the tags in the pending
queue and are retried on the next **Sync all**. Plain `http://` is allowed for
local-network endpoints; use `https://` on the internet.

## Install

1. Download the latest APK:
   <https://github.com/MithrasSantos/nfc-vault/releases/download/latest/app-release.apk>
2. Open it on the phone and allow installs from the browser/file manager if
   Android asks.
3. Open **NFC Vault**, tap **Scan tag**, hold a card against the back of the
   phone.

The APK is signed with the stable key committed under `signing/`, so upgrades
install over older versions and keep the vault.

## Build

GitHub Actions builds on every push to `main` and publishes the `latest`
release. To build locally (JDK 17 + Android SDK 34):

```bash
gradle assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

`minSdk 29` / `targetSdk 34`. No third-party NFC/DB libraries; SQLite, NDEF
parsing and HTTP are platform APIs.

## Privacy

The vault is stored in the app's private database. Nothing leaves the device
unless you use Share, Save to Downloads or Sync. NFC reader mode is paused
whenever the app is not actively scanning.
