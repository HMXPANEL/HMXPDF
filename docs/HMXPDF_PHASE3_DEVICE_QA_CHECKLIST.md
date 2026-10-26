# HMXPDF Phase 3 — Manual Device QA Checklist

**Target device:** OPPO A38, Android 15
**App:** `com.hmx.pdf`, versionName `2.1.1`, versionCode `6`
**Artifact:** private CI `hmx-pdf-release-apk` from run [40225659634](https://github.com/HMXPANEL/HMXPDF/actions/runs/40225659634), commit `cc8129d` (Android source unchanged since)
**Status:** No device, emulator, or Android SDK was available in the development environment. Nothing in this checklist has been executed there. Record actual results only.

Use a test device or a device whose app data you can erase. `adb shell pm clear com.hmx.pdf` deletes app data, including saved report drafts, consent, recent files, and the report-only install ID. Do not use personal documents.

## Preparation

1. Enable Developer options and USB debugging on the OPPO A38.
2. Connect it and confirm it is visible:
   ```bash
   adb devices
   adb shell getprop ro.product.model
   adb shell getprop ro.build.version.release
   adb shell getprop ro.build.version.sdk
   ```
3. Install the arm64-v8a release APK from the CI artifact. If an older signed build is installed, confirm the signature matches before upgrading.
4. Record the installed version:
   ```bash
   adb shell dumpsys package com.hmx.pdf | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime'
   ```
5. For network observations, use a test HTTPS proxy or capture the server side. Never paste tokens, install IDs, report IDs, or document contents into the record.

## Checklist

| ID | Test | Steps | Expected result | Record |
|---|---|---|---|---|
| 1 | Seven-page tour preserved | Fresh install or `pm clear`; launch normally | Existing seven-page tour appears; page count reads `1 of 7` through `7 of 7`; no privacy screen before the tour ends | Pass / Fail / Blocked; page titles |
| 2 | Privacy after tour | Finish the seventh tour page | **Privacy & Security** step 1 appears next, not Home | Pass / Fail |
| 3 | Diagnostic Preferences next | On step 1, tap **Next** | Step 2 **Diagnostic Preferences** appears; step text reads `2 of 2`; both toggles are OFF | Pass / Fail |
| 4 | Next saves choices | On step 2, turn both toggles ON, tap **Next**; complete What's New if shown; relaunch | Home appears; Settings → Privacy & Security shows both **On**; no privacy flow on relaunch | Pass / Fail |
| 5 | Not now saves OFF | Fresh install or `pm clear`; reach step 2; turn both toggles ON, tap **Not now**; open Settings | Both choices show **Off**; report action buttons are disabled; no report was sent | Pass / Fail |
| 6 | Ordinary restart | Complete the flow; force-stop and relaunch normally twice | Tour and privacy flow do not repeat | Pass / Fail |
| 7 | Watch intro again | Settings → **Watch intro again**; finish the replay | Tour replays; privacy flow does not reappear; returning to Settings keeps the saved consent status | Pass / Fail |
| 8 | Settings placement | Open Settings | Exactly one informational **Privacy & Security** row, directly below **Office engine**; page contains no consent toggles | Pass / Fail; screenshot |
| 9 | Consent OFF blocks reports | With both choices OFF, open each report entry point | Each shows the consent-off message; no report POST is observed; no new server row | Pass / Fail |
| 10 | Remote flag cannot override consent | With local consent OFF, enable the matching flag in the test backend; relaunch and open the report screen | Report remains blocked by local consent; no report POST | Pass / Fail / Blocked (admin/config access required) |
| 11 | Preview before Send | Enable diagnostic consent; open Diagnostic test | Full preview lists type, report ID, created time, install ID, app version/build, Android API, locale, and self-checks; Send is not automatic | Pass / Fail |
| 12 | No report POST before Send | Keep a proxy or server log open; open the report screen and review the preview without tapping Send | No `report-submit` request occurs; a config request is allowed and must contain only flag keys plus app version code | Pass / Fail; redacted request log |
| 13 | Success only after acknowledgement | Send a diagnostic report to staging | UI shows **Report sent** and a server ID only after a `201`/`200` acknowledgement; server row exists | Pass / Fail; status code |
| 14 | Failure stays pending | Disable network or point the test build at an unreachable endpoint; send a report | UI shows **Report not sent**; the report appears in **Pending reports**; **Retry** and **Discard** are offered; nothing is reported as sent | Pass / Fail |
| 15 | Retry does not duplicate | Retry the same pending report after a lost response, or retry twice; inspect the server | Same client report ID is reused; server returns the existing row or `duplicate: true`; exactly one row exists | Pass / Fail; row count |
| 16 | Discard deletes local copy | In Pending reports, tap **Discard**, confirm | Local entry disappears; server row count does not increase; no request is sent by Discard | Pass / Fail |
| 17 | Backup exclusions | Inspect the installed app's backup configuration or run a backup/restore test on a test device | `clearpdf_privacy.xml` and `clearpdf_flags.xml` are excluded from cloud backup and device transfer; report queue is in no-backup storage | Pass / Fail / Blocked (device backup tooling) |
| 18 | Share launch bypasses onboarding | Fresh install or `pm clear`; share a test PDF to HMX PDF before completing onboarding | Document opens directly; tour/privacy flow is not marked complete; next ordinary launch still shows onboarding | Pass / Fail |
| 19 | Shortcut launch bypasses onboarding | Fresh install or `pm clear`; launch an app shortcut that targets a tool | Tool opens directly; onboarding remains pending for the next ordinary launch | Pass / Fail |
| 20 | Existing document features | Open a PDF; scan a page; edit/annotate; export/share; open a DOCX with and without the Office engine | Existing open, scan, edit, save, share, and Office-engine behavior is unchanged; no report is sent automatically | Pass / Fail |
| 21 | TalkBack basics | Enable TalkBack; traverse onboarding, Settings, report preview, confirmation, pending list, and errors | Toggles announce their title and On/Off state; headings and buttons are announced; errors and confirmation text are read; category chips announce their label (selected-state announcement is a known source-audit gap) | Pass / Fail |
| 22 | Signed upgrade | Install the previous signed release, then install this signed release over it | Upgrade succeeds with the same signer; consent and pending reports are preserved; existing installs see the privacy flow once | Pass / Fail |

## Reset and evidence commands

```bash
# Wipe app data on a test device only
adb shell pm clear com.hmx.pdf

# Capture only relevant logs, then redact IDs/tokens before sharing
adb logcat -c
# reproduce the case
adb logcat -d -v threadtime | grep -iE 'hmx|report|config|FATAL|AndroidRuntime'

# Confirm the package and signer
adb shell dumpsys package com.hmx.pdf | grep -E 'versionName|versionCode|signatures'
```

Record for each run:

- Date/time and tester.
- Device model, Android release/API, build fingerprint, security patch.
- APK artifact name, versionName, versionCode, and signer.
- Network condition and whether a proxy was used.
- Each checklist ID with **Pass**, **Fail**, **Blocked**, or **Not run**, plus a short observation.
- Redacted log excerpts or server status codes for failures.

Do not record document contents, filenames, paths, install IDs, report IDs, tokens, or signing secrets.
