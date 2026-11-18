# HMXPDF final project verification report

Date: 2026-11-17
Repository: `https://github.com/bharti1233/Hmxpdf`
Verified branch: `release/hmxpdf-2.1.1-build` (the project's default development/publication branch)
Protected branch not touched: `main` remains at `ca0caa35761298df5912c95e2c9c815c241601c1`.

## 1. Verdict

Phase 4 (sub-phases 4.0, 4.1, 4.2, 4.4, 4.3, 4.5 and 4.6) is **complete in the JVM/CI scope and
verified on the published branch**. The final gate built exactly the published code head:

- Code/test head: `275c54ab328977a65f968cd4b94a53984e36131f`
- Final CI run: https://github.com/bharti1233/Hmxpdf/actions/runs/44124495265
- `build`, `release` and `release-guard`: **all completed/success**
- 375 JVM tests executed, 0 failed, 42 pre-existing skips (unchanged `OfficeEngineDownloadTest`
  classes that require an Android Context)
- Android lint passed with the existing baseline (12 warnings, 2 hints; 72 errors / 323 warnings /
  8 hints filtered by `lint-baseline.xml`, i.e. no new blocking findings)
- Debug APK, signed release APK set and signed AAB produced and published as CI artifacts

The documentation commit that contains this report is made after the gate and changes only
`docs/`. No code, test, resource, Gradle or workflow file differs between the built head
`275c54a` and the documentation head; the artifact-to-source identity below therefore remains
exact. The first documentation head, `690ad4c9e579b24019cddbead0ec1aca64b4a52e`, was itself gated
in run 44131731875 (build, release, release-guard all success); later documentation-only commits
change no code.

**Device smoke and external-Office compatibility are NOT verified.** They are listed in §8 and
must not be reported as passing.

## 2. Publication identity

| Item | Value |
|---|---|
| Development branch | `release/hmxpdf-2.1.1-build` |
| Branch head at final gate | `275c54ab328977a65f968cd4b94a53984e36131f` |
| Final workflow run | 44124495265 (`Android build`) |
| Run event / branch | `push` / `release/hmxpdf-2.1.1-build` |
| Built checkout | `275c54a…` (workflow `head_sha` equals the published head) |
| `main` | untouched at `ca0caa3…` |
| Force-push / history rewrite | none |

All publication was by normal push to the development branch. No tag, GitHub release or store
upload was created.

## 3. Phase gates

| Phase | Scope | Verification run | Head | Result |
|---|---|---|---|---|
| 4.0 | Save coordinator, package-safe splicing, package-inclusive session, fixtures | 43943430785 | `9463b53` | success |
| 4.1 | Autosave, checkpoints, recovery, disk-full/retry protocol | 44044415038 | `0d72242` | success (reopened and repaired) |
| 4.2 | Advanced tables | 44050687595 | `b9bb233` | success |
| 4.4 | Footnotes/endnotes | 44057796676 | `890d076` | success |
| 4.3 | Offline spell checking | 44120403325 | `4ded969` | success |
| 4.5 | Offline Read Aloud | 44120403325 | `4ded969` | success |
| 4.6 | Cross-feature integration + final gate | 44124495265 | `275c54a` | success |

Repair history is recorded per phase in `docs/phase-completion/PHASE4_FINAL_REPORT.md` and
`docs/AUTONOMOUS_DEVELOPMENT_MASTER_LOG.md`; every failure was diagnosed to a root cause and fixed,
never bypassed by disabling or skipping a test.

## 4. Test accounting

- Final suite: **375 executed, 0 failed, 42 skipped**.
- The 42 skips are the pre-existing `@Ignore`d `OfficeEngineDownloadTest` classes that need an
  Android Context; they were not counted as passes and were not added by Phase 4.
- Adds by sub-phase (JVM test methods): 4.2 = 12, 4.4 = 15, 4.3 = 17, 4.5 = 18, 4.6 = 1. The
  4.2 count is already included in its 324-test run; 324 + 15 + 17 + 18 + 1 = 375, matching the
  final suite. Earlier phases' tests run in the same suite.
- The final suite includes `DocxPhase4IntegrationTest`, which exercises tables, notes, spell
  replacement, save capture, writer round trip, undo/redo and a read-aloud pass in one session.

## 5. Artifacts and digests

Run 44124495265, code head `275c54a`:

| Artifact | Artifact ID | Size | SHA-256 |
|---|---|---|---|
| Debug APK (`hmx-pdf-debug-apk`) | 14346744273 | 171,426,738 B | (zip) not recorded |
| Signed release APK set (`hmx-pdf-release-apk`) | 14346409191 | 134,876,344 B | zip `5a2a662f3ada8c477b36459f8a9cdc753b04416198441db3d958bcbba987e4cc` |
| — universal APK inside the set | — | 86,075,076 B | `3b7b9360ce255e082791b8e95f7963ba8f73cc99f6c34a066816801b5949d9b4` |
| — arm64-v8a APK inside the set | — | 34,428,132 B | `e174ca32271dcfefa1a8d3ab5d2f0e868ad9157d0ce6fc7bd6a502b9671c1cff` |
| — armeabi-v7a / x86 / x86_64 APKs | — | 28,124,304 / 35,226,475 / 35,444,179 B | included in the same set |
| Signed AAB (`hmx-pdf-release-bundle`) | 14346728913 | 51,319,340 B | zip `5d8a8466d7c12ecc8692d4d167f7c2782bd1c13763b10621134f56bee0238e75`; inner AAB `8b4b7c008091803f63dd0983f3b0cc2024ecefde5cd45ce065166f87d1ba012c` |

The release job verified the APK with `apksigner --print-certs` and the AAB with
`jarsigner -verify`, refused debug signing, and `release-guard` confirmed that release signing is
impossible without the repository's protected secrets (its expected failure is the guard working,
and the job conclusion is success). Application ID, signing identity and version metadata were not
changed.

## 6. Source audit and invariants

- `DocxWriter` is the only DOCX writer. Tables, notes, revisions and spelling replacements all flow
  through it; there is no parallel writer or patched serialization path.
- Unknown OOXML is preserved: untouched parts are copied byte-for-byte, unknown table/paragraph
  siblings and foreign namespaces survive their property splices, and the integration test keeps
  an unrelated `word/customXml/item1.xml`.
- Reading is read-only: `ReadingProjection`/`ReadingSession` never touch `DocState`, rPr, undo or
  the dirty baseline; edits, undo/redo, document load and editor disposal call `stopReading`.
- Spell suggestions are annotations outside `DocState`; replacement requires an explicit tap,
  validates the paragraph instance and text, and uses the normal edit/Track-Changes path.
- Offline policies hold: spell checking uses only the installed on-device `SpellCheckerSession`
  and refuses packages requesting `INTERNET`; TTS selects only voices whose
  `isNetworkConnectionRequired` is false. No document text is sent over the network.
- Notes/parts/recovery: reserved separators are untouched, ids are per-part, and unsaved table,
  note and spelling edits are all present in the single save capture and in undo snapshots
  (integration test).
- No new dependency, no credential in source, no TODO/FIXME/placeholder in the new modules, no
  test disabled to go green.
- `main` was not pushed and no force-push/reset/clean was used.

## 7. Reproduction

```bash
git fetch origin release/hmxpdf-2.1.1-build
git checkout 275c54ab328977a65f968cd4b94a53984e36131f
./gradlew testPlayDebugUnitTest :pdf-core:test :ocr-core:test --rerun-tasks
./gradlew lintPlayDebug
./gradlew assemblePlayDebug
# signed release requires the repository's protected release secrets
```

Expected JVM result: 375 tests, 0 failures, 42 skipped. The CI workflow `.github/workflows/*` runs
these gates plus the signed release and `release-guard` jobs.

## 8. Explicitly unverified (not passes)

- Device/emulator smoke on API 23, a current API level, a low-RAM device and a no-GMS device.
- Real TTS: installed offline voice availability, voice changes/downloads, audio-focus transitions,
  Bluetooth/headset routing, calls/interruptions and editor lifecycle on hardware.
- Real spell service: availability, suggestion quality, mixed-language behavior, and the
  network-permission refusal path on a device.
- External Microsoft Word / LibreOffice reopen and rendering fidelity of generated tables, notes
  and corrected text.
- Device-only recovery scenarios from 4.1: real force-stop/reboot process death, revoked SAF URI,
  real ENOSPC, and sustained-edit memory/storage profiling.
- Store publication, signing-identity changes, version bumps or Play Console checks (none were
  performed).

## 9. Final statement

Within the JVM/CI scope the Phase 4 acceptance criteria are met: the sole writer and unknown-OOXML
invariants hold, unsaved cross-feature edits recover through one capture/undo generation, spelling
and Read Aloud are offline and non-mutating, and the final published code head is backed by a green
build with hash-recorded artifacts. The unverified device and external-Office items in §8 remain
open and are not claimed as verified.
