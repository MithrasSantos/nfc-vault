# NFC Vault

Small Android app for scanning NFC tags, keeping what was read on the phone,
and sending the saved data out when you want to.

Built for a Samsung Galaxy S24 (Android 14), but works on any phone with NFC and
Android 10 or newer.

## What it does

- Scans NFC tags in reader mode (`NfcA/B/F/V`) and stores:
  - tag UID
  - technology list (`NfcA`, `Ndef`, `IsoDep`, `MifareClassic`, …)
  - low-level details (ATQA/SAK, ISO-DEP historical bytes, MIFARE size, …)
  - NDEF type, capacity and writable flag
  - every NDEF record (text, URI, MIME, external, raw hex) plus the raw NDEF bytes
- Keeps the last 1000 scans on the device as JSON (internal app storage).
- Tap a scan to see everything; share one scan, delete it, or clear all.
- Export everything:
  - **Save JSON to Downloads** → `Downloads/NFC Vault/nfc-vault-<time>.json`
  - **Share all** → send the JSON through any app
  - **Upload to URL…** → HTTP POST the JSON to a webhook you choose
    (Home Assistant, a small server, etc.)

Nothing leaves the phone unless you share, save or upload it.

## Data format

```json
{
  "app": "NFC Vault",
  "schema": 1,
  "exportedAt": 1760000000000,
  "count": 1,
  "scans": [
    {
      "id": "…uuid…",
      "time": 1760000000000,
      "uid": "04:A1:B2:C3:D4:E5:F6",
      "tech": ["NfcA", "Ndef"],
      "extras": { "NfcA.atqa": "44 00", "NfcA.sak": "0x00" },
      "ndefType": "NFC Forum Type 2",
      "ndefMaxSize": 137,
      "ndefWritable": true,
      "records": [
        { "tnf": 1, "type": "T", "kind": "text", "value": "hello", "payloadHex": "02 65 6E 68 65 6C 6C 6F" }
      ],
      "rawNdef": "03 0F D1 01 0B 54 02 65 6E 68 65 6C 6C 6F FE"
    }
  ]
}
```

The upload uses `Content-Type: application/json` with the same body.
Plain `http://` is allowed so local-network webhooks work; use `https://`
for anything on the internet.

## Install (phone only)

1. Download the latest APK:
   <https://github.com/MithrasSantos/nfc-vault/releases/download/latest/app-release.apk>
2. Open it on the S24. When Android asks, allow the browser/file manager to
   install unknown apps.
3. Open **NFC Vault**, press **Start scanning**, hold a tag against the back of
   the phone near the camera.

The APK is signed with a stable key stored in this repo, so newer builds
install as updates (your saved scans survive).

## Build

The APK is built by GitHub Actions on every push to `main`
(`.github/workflows/build.yml`) and published to the `latest` release.
Tag a commit with `v0.2.0` to also publish a normal release.

Local build (JDK 17 + Android SDK 34):

```bash
gradle assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

If `signing/nfcvault.jks` is missing, the release build falls back to the
Android debug key (fine for testing, not for updates).

## Notes

- `minSdk 29` / `targetSdk 34`, no third-party libraries.
- NFC is a normal permission, so there is no runtime permission prompt.
- Uninstalling the app deletes the saved scans — export first.
