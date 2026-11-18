# HMXPDF phase history validation

Historical Markdown is treated as a claim. No Git history, CI logs, test XML, APK or device session was available to associate this tree with a historical release. No tests were executed in this audit.

Source prefixes below: `A/` = `app/src/main/java/com/hmx/pdf/`; `T/` = `app/src/test/java/com/hmx/pdf/`.

| Phase | Documented objective/completion | Current source evidence | Current verdict |
|---|---|---|---|
| Phase 1 | Find/replace, comments, table-cell editing; `docs/phase1/PHASE1_IMPLEMENTATION_PLAN.md:7,37,65`; test report:7 claims run 41079865164 | A/docx/DocxEdits.kt:87,107; DocxComments.kt:49,100; DocxModel.kt:150; editor screen:1265,1387,2299 | Implementation present; comments/range fidelity concerns remain. No fresh automated/runtime verification |
| Phase 1.1 | View-mode enforcement and comment target fallback; implementation report:5,31 | A/ui/viewmodel/DocxEditorViewModel.kt:112–119,311,419; screen:1389; T/ui/viewmodel/DocxEditorViewModeTest.kt:98 onward | Source guards and fallback present; not a claim of complete user-flow verification |
| Phase 2 | Run-level tracked insertions/deletions, display and accept/reject; final review:45 claims completion | A/docx/DocxReader.kt:248; DocxWriter.kt:282; DocxEdits.kt:64; DocxRevisions.kt:54; VM:445; screen:1440 | Run-level implementation present, structural/property/move tracking incomplete by scope; imported read-only preview revisions can be listed without resolvable commands |
| Phase 3 | Page setup, sections, breaks, HF parts and variants; final review:19–42 | A/docx/DocxPageLayout.kt:28,81,157,190; VM:486–716; screen:1516–1793 | Substantial implementation present; namespace preservation, package undo, session HF lookup, and multi-section rendering gaps prevent blanket completion claim |
| Phase-3 button repair | Fix dirty state, section break semantics, relationship allocation, stale ranges; `docs/phase3-button-audit/PHASE3_BUG_FIX_REPORT.md:14–22` | VM.dirty:98, effectiveSettings:599, freeRelId:669, commitSect:513; DocxPageLayout:157; DocxEdits:108; T/DocxPageSetupViewModelTest.kt:179,245 | Several repair mechanisms confirmed independently; not all neighboring edge cases fixed |
| False-dirty follow-up | `docs/phase3-button-audit/FALSE_DIRTY_FIX_REPORT.md` | VM:98–100,599–603,689–715; tests:179,200 | Effective-settings baseline logic exists; plain mutable HF/settings overrides still present |
| Phase 4 | Earlier conversation described autosave, tables, spelling, notes and speech | No phase4 documentation directory before this audit; full editor paths contain no dedicated implementation for these five additions | No evidence of completed Phase 4. Save-time recovery and existing table paragraphs are reusable foundations, not proof of Phase-4 delivery |
| General security/Office fixes | Root audit/fix reports exist | SaveRecovery, DecryptedSession, worker checksums, offline WebViews and source classification exist | Validate each mechanism separately; defects and missing test bodies remain |

## Conflicts that must remain explicit

1. `docs/phase3/PHASE3_TEST_REPORT.md:14` says 261 completed, 44 skipped, while :65 describes “261/261 executed tests passed” and still mentions 44 skips. These cannot be adopted as a fresh passed-test total. Current source has 297 declarations across two modules, including 44 in ignored classes.
2. `docs/phase1.1/PHASE1_1_TEST_REPORT.md:50` attributes skips to pdf-core/ocr-core; current ignored declarations are in `app/src/test`, and ocr-core has no test sources.
3. `docs/phase3-button-audit/PHASE3_BUG_FIX_REPORT.md:3` records commits and pushes, while :11 says no commits/history actions. The document appears to mix stages; this audit does not reconcile it into a verified history.
4. `PHASE3_CLAIMS_REVALIDATION.md:92` reports no page-setup VM tests at that historical baseline. Current `DocxPageSetupViewModelTest.kt` contains nine test declarations. Historical coverage is not current coverage.
5. The historical reset report claims removal of an experimental branch. No `.git` exists now, so branch deletion cannot be independently verified. Experimental identifiers were not found; ordinary Phase-3 Page Setup remains implemented.
6. `PRIVACY.md:12` and `THIRD_PARTY_NOTICES.md:119` describe Play Feature Delivery. Current `OfficeEngineFlavor.kt:8` selects a download installer. Distribution source is authoritative for this audit.

## Acceptance needed before declaring any phase complete

Bind a source fingerprint/commit to a CI run; retain actual JUnit pass/fail/skip output; exercise buttons on device; open saved DOCX/XLSX in Word/LibreOffice/Excel; validate unsupported-content preservation. Tests that merely save and reopen with the same parser do not establish OOXML semantic correctness. No phase is certified end-to-end complete by this audit.
