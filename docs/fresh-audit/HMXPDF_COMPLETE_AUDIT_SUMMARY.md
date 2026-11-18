# HMXPDF fresh audit executive summary

## Baseline and confidence

Audited source: `/storage/emulated/0/AIProjects/Hmxpdf`, also accessible as `/mnt/sdcard/AIProjects/Hmxpdf`. Source identifies HMX PDF, package `com.hmx.pdf`, version 2.1.1/code 6. **No Git metadata exists: remote, branch, HEAD, latest commits and working-tree status are unavailable.** HMXPANEL product links do not verify download provenance.

The starting tree contains 543 files, 358 Kotlin files and 78,854 Kotlin lines across five module directories. Four modules are always included; office_engine is conditional. Main architecture: Compose activities/navigation, manually constructed ViewModels/services, Android PdfRenderer/PDFBox, raw-preserving DOCX and XLSX writers, on-device OCR, operation-based image editing, SharedPreferences/files, SAF/MediaStore and optional downloaded LibreOffice in a separate same-UID process.

The audit inventories every path and traces major feature pipelines. It is not a formal line-by-line proof of all 78k lines, vendored JavaScript or closed native dependencies. **No local Android build, app run, Gradle test, instrumented test or remote CI verification was performed.** Source findings and architectural risks are labeled separately. Historical reports were cross-checked, not adopted as results for this copy.

## Feature inventory

The inventory contains 62 explicitly numbered capability groups: **28 implemented but runtime-unverified, 20 partially implemented, six broken and eight not implemented**. Counts were checked against the inventory rows. There are zero separately counted source-verified-only, UI-only, backend-only or placeholder/mock groups. Complete-looking user flows are conservatively runtime-unverified; partial/broken groups retain useful engine code. UI-only/backend-only/mock groups are not invented from empty cosmetic callbacks.

Existing functionality includes PDF viewing/navigation/selection/annotations/forms/tools, DOCX rich-text/table-cell editing/comments/run revisions/page layout, XLSX editing, image editing/scanning/OCR, Office conversion and manual save recovery. Missing Phase-4 additions are autosave checkpoints, advanced DOCX table commands, app-owned spell checking, note authoring and Read Aloud. No app-update/account/admin/cloud-sync subsystem or embedded-image-object extraction UI was found.

## Highest-priority findings

| Priority | Finding | Evidence |
|---|---|---|
| P1 | Provider roots block encrypted-PDF opening and multiple export/share flows | FA-01; file_paths.xml:3–5 versus PdfViewerViewModel.kt:371, PdfRasterizer.kt:99 |
| P1 | F-Droid has duplicate OfficeEngineFlavor declaration | FA-02; main:6 / fdroid:13 |
| P1 | Imported HF edits can lose required namespace declarations | FA-03; DocxPageLayout.hfXml:190 |
| P1 | Save generation/dirty acknowledgement can disagree with serialized edits | FA-04; DOCX VM:744,769; XLSX VM:322 |
| P1 | Package-external undo can restore missing HF references | FA-05; VM.undo:228 and hfRemove:657 |
| P1 | Editing paragraph relocates imported comment/bookmark anchors | FA-07; Reader:273, Writer:248 |
| P1 | Durable mirrors/signatures eligible for backup without explicit exclusions | FA-12; backup XML, LocalDocumentMirror:52, SignatureManager:16 |
| P1 | API-26 overlay color calls conflict with minimum API 23 | FA-19; PdfViewerViewModel.kt:1136 |

The register contains 24 grouped findings, including inferred risks. No P0 exploit or catastrophic loss was demonstrated. Remaining issues include renderer session ownership, incomplete search, swallowed flatten errors, PDF coordinate transforms, OCR cancellation, install rollback, WebView lifecycle, archive/memory bounds, false gallery success, shallow validation and release gating.

## Phase validation

Phase 1 and 1.1 mechanisms are present; Phase 2 supports run-level revisions with limitations; Phase 3 and later button repairs are present but retain HF/state/rendering defects. Phase 4 completion has no source evidence. No phase is certified end-to-end complete. Historical test totals, branch statements and distribution claims conflict in several places; the phase report preserves those conflicts explicitly.

## Quality, privacy and release

44 JVM test files contain 297 test declarations: 274 app, 23 pdf-core. 44 declarations are in ignored classes; 38 bodies are empty/comment-only. No androidTest source, OCR test suite or raw execution reports were found. There are **zero freshly verified passing application tests** in this audit.

Local document WebViews block network; no first-party upload/analytics/account backend was found. Explicit URL conversion, engine download, user sharing/external intents and Google provisioning can leave the device. Backup scope, native logs, parser hardening and third-party behavior require verification. Privacy and licensing descriptions contain distribution/branding inconsistencies.

CI currently covers Play tests/lint/build and independent signing jobs. F-Droid/FOSS, UI/provider integration and native alignment are not release-gated. Checked-in lint baseline has 410 entries, including GPUImage alignment concerns; these are not fresh lint results. Exact APK size, startup latency, memory peaks, native compatibility and signing environment protections remain unverified.

## Recommended next actions

1. Establish authorized source/CI lineage; retain actual test outputs and compile all supported variants.
2. Repair provider/flavor/API blockers before promoting builds.
3. Repair namespaces, anchors, package-inclusive undo and immutable save snapshots before autosave or structural document features.
4. Validate PDF/OCR/privacy/performance failures with meaningful engine, provider, UI and physical-device tests.
5. Implement Phase 4 in the roadmap order, reusing existing engines and UI; keep DOCX writer singular.

## Deliverables

All 14 requested reports are in `docs/fresh-audit/`: repository identity, project structure, feature inventory, phase validation, architecture, PDF engine, OCR/Office engine, security/privacy, tests, build/release, bugs/debt, gaps, this summary and the master roadmap. A final fingerprint check of all 543 files outside this new directory matched the starting fingerprint exactly. Existing documentation and application sources are preserved. Git status remains unavailable because `.git` is absent; this is not a clean-working-tree claim. No branch changes, downloads, credential use, commits or pushes occurred. Implementation stops at planning; future code work requires explicit authorization.
