# HMXPDF testing and quality audit

## Evidence and accounting

This audit executed **zero Gradle tests, zero instrumentation tests, zero APK/AAB builds and zero app runs**. Read-only filesystem/source checks and report consistency checks are not application tests. No JUnit XML or generated test-report artifacts were present. Historical Markdown claims are separately identified in the phase report; their CI links were not fetched or attributed to this unversioned copy.

| Module | JVM test files | `@Test` declarations |
|---|---:|---:|
| app | 39 | 274 |
| pdf-core | 5 | 23 |
| ocr-core | 0 | 0 |
| backdrop | 0 | 0 |
| office_engine | 0 | 0 |
| Total | 44 | 297 |

Source counting found **44 declarations in class-level `@Ignore` suites** and **38 empty/comment-only test bodies**. These are not 44 executed skips in this audit; they are annotations that would skip cases in a compatible JUnit run. The two relocation tests in OfficeEngineDownloadTest.kt belong to a separate nonignored class. Nominal nonignored declarations: 253; this is not a passed-test count.

Ignored/empty evidence: `app/src/test/java/com/hmx/pdf/office/OfficeEngineErrorHandlingTest.kt:20–107`, `OfficeEngineDownloadTest.kt:13–141`, `data/repository/SaveRecoverySameUriTest.kt:15–24`, `ui/screen/PdfViewerScreenCopyTest.kt:17`, and `com/kyant/pdfcore/security/DecryptedSessionTest.kt:13` / `PdfSecurityServiceStartupTest.kt:13`. Merely enabling these suites would still leave many no-assertion bodies.

## Existing coverage map

All app paths below are under `app/src/test/java/com/hmx/pdf/`.

| Area | Files and source-declared scope | Coverage boundary |
|---|---|---|
| DOCX engine | docx/DocxRoundTripTest (11), Replace (9), Comments (8), Revisions (24), PageLayout (20), ParagraphLayout (7), TableCellEdit (4), TableLayout (3), Xml (2) | In-memory ZIP builders and reader→edit→writer→reader assertions; no Word semantic oracle |
| DOCX VM | ui/viewmodel/DocxEditorViewModeTest (17), DocxTrackingStateTest (9), DocxPageSetupViewModelTest (9) | primeForTest seam bypasses actual SAF/load/save and button interaction |
| XLSX | utils/xlsx/Colors (7), Devanagari (2), Refs (7), Writer (6) | KXML parser setup and ZIP fixtures; no Excel runtime or broad formula evaluation |
| PDF UI helpers | ui/screen/EditedSource (4), PdfExportOverlay (5), PdfViewerInternals (2), ShareFormat (3), TextEditSegment (7), WordSnap (5); ui/selection/PdfViewportTransform (5) | Pure mapping/source-choice behavior, not Compose interaction/rendering |
| Storage | OfficePackageValidation (4), SaveLocationManager (3), ScanSavePlan (1) | Shallow package checks/config/ordering; recovery provider behavior ignored |
| Office | OfficeEngineStateMapper (19), PptxPlaceholderFix (2), relocation (2) | State mapping and XML/alias helpers; actual downloads/native conversion unverified |
| Other | ImagesToPdfRotation (9), OfficeShareSource (3), DocKind (3), UniversalDocumentConverterClassify (4), ImageIoSampleSize (4) | Helper-level assertions |
| pdf-core | ConverterOrientation (6), ConverterSampleSize (3), RasterizerPageSize (4), DecryptedCacheCleanup (3), PdfBidi (7) | Arithmetic, filesystem cleanup and text helpers; not PDF rendering/export lifecycle |

No dedicated instrumented/UI/performance test source sets or emulator workflow exist. There is no measured flakiness evidence. Potential time/shared-global-state dependencies should be tested with repeatable clocks and concurrent document sessions rather than labeled flaky without runs.

## CI coverage

`.github/workflows/android.yml:34–55` requests Play JVM tests, pdf-core/ocr-core test tasks, Play lint and Play debug APK. An ocr-core task with no test source is not OCR coverage. Release and release-guard are independent jobs; release has no `needs: build`. CI does not exercise FOSS/F-Droid, connected devices, external Word/LibreOffice compatibility, memory benchmarks or fault injection. Test report artifacts are not uploaded explicitly.

## Required regression layers

1. **Engine:** DOCX namespaces/anchors/relationship syntax and malformed ZIP quotas; XLSX formulas/range shifts; PDF rotated/cropped coordinates, text extraction after edits, forms and compression fidelity. Fixtures must contain unknown parts and verify unaffected entry payloads.
2. **ViewModel/session:** deterministic slow-save barrier while edits/undo occur; all part state included in snapshots; multiple editor windows; stale query generation; cancellation retains truthful saving/error/dirty state.
3. **Android integration:** ContentResolver providers returning null, throwing, truncating, revoking grants, changing bytes; FileProvider roots; interrupted save and next-process recovery; gallery output verification; WorkManager install/cancel/reinstall.
4. **Compose interaction:** tap each editor action, mode gates, reopen newly created header, edit→undo→save, recovery buttons, share chooser result, search results arriving after initial query. An engine round-trip cannot substitute for these.
5. **Physical device:** API 23 and current target families, 32/64-bit ARM, 16 KiB-page device, no-GMS device, low-memory device, external SD/cloud providers, Word/LibreOffice/Excel reopen, print service and WebView availability.

## Future Phase-4 test plan

Autosave: virtual-time debounce, single writer, generation consistency, disk-full, crash between prepare/rename/journal, conflicts and cleanup. Tables: grid invariants, merge/split/insert/delete with nested tables, comments/revisions and undo. Spelling: cross-run words, mixed locales, stale annotations, user-approved replacement preserving formatting. Notes: IDs/separators/relationships/content types and external compatibility. TTS: fake engine for state/chunk/order/cancellation plus device offline-voice/audio-focus checks. Keep all execution in authorized CI/device environments, not local APK builds.
