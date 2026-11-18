# HMXPDF bugs and technical debt

Priority: P0 critical proven loss/security emergency; P1 major failure or high-impact integrity risk; P2 significant gap; P3 maintainability. **No P0 is asserted.** S = confirmed source mechanism; I = inferred consequence requiring runtime/fault-injection confirmation. No issue below was reproduced by running the application. Root causes are grouped to avoid counting provider failures as unrelated bugs.

Paths: `A/` = `app/src/main/java/com/hmx/pdf/`; `P/` = `pdf-core/src/main/java/com/kyant/pdfcore/`.

## FA-01 — P1 — FileProvider roots do not match producers [S]

Evidence: `app/src/main/res/xml/file_paths.xml:3–5` maps only scan_images, shared and external files. Producers create cache/decrypted_pdfs (`P/security/PdfSecurityService.kt:118`, A/ui/viewmodel/PdfViewerViewModel.kt:371), pdf_images (`P/raster/PdfRasterizer.kt:64,99`), image_tools (`P/image/ImageProcessor.kt:78,92`), edited_images (`A/imageeditor/engine/ImageIo.kt:198`, ImageEditorViewModel.kt:551), docs/sheets (editor buildPatched), and F-Droid scans (`app/src/fdroid/java/com/hmx/pdf/scan/DocumentScanner.kt:39–41`).

Trigger: open encrypted PDF with valid password, export PDF images, use image tools/share, or share edited Office file. FileProvider rejects paths outside configured roots; shareFile falls back to file URI and swallows launch failure (`SpreadsheetScrolling.kt:280–291`). Impact: multiple major flows fail despite working engines. Fix: narrow explicit export staging/internal access contract and no file-URI fallback. Dependencies: provider, producers, share UI. Regression: avoid accidentally exposing journals/decrypted private data; test actual grants and recipient reads.

## FA-02 — P1 — F-Droid duplicate Kotlin declaration [S]

Evidence: main `office/OfficeEngineFlavor.kt:6` and fdroid equivalent :13 declare the same FQN; `app/build.gradle.kts:151–154` adds GMS paths but does not exclude main Office flavor. Expected failure: redeclaration during F-Droid compilation. Build was not run. Fix: mutually exclusive flavor implementation placement. Dependency: variant source configuration. Regression: Play/FOSS installer availability; CI compile all flavors.

## FA-03 — P1 — Editing imported headers/footers discards root namespaces [S]

Evidence: `A/docx/DocxPageLayout.kt:190–203` parses body blocks then emits a new root declaring only `xmlns:w`; `DocxWriter.kt:105–109` uses it for existing parts. Repro fixture: header root declares r/a/wp/w14, contains an image/hyperlink or w14 paragraph attributes; edit its text and save. Preserved child XML can contain now-unbound prefixes; root attributes such as mc:Ignorable also disappear. Impact: invalid/repair-prone OOXML and possible content loss. Fix: preserve original part root/namespace envelope and relationships; validate namespace-aware XML before destination write. Regression: simple new HF parts and unknown root attributes; external Word/LibreOffice reopen mandatory.

## FA-04 — P1 — Save does not capture one immutable generation [S/I]

Evidence: DOCX save captures body at :744 but buildPatched reads live doc/extras on IO (:718–726); recordSaved reads current package overrides after IO (:708–715,769). XLSX builds from mutable workbook/applied list (:273–280) and marks `savedAt = applied.size` at completion (:322). Editing is not globally disabled during saving. Slow-provider trigger: save, then edit HF or spreadsheet while write is in flight. Later edits can be marked clean although absent from output; concurrent collections also risk inconsistency. Fix: capture immutable body + package + operation snapshot and generation on owner thread, serialize that snapshot, acknowledge exactly it. Dependencies: session state and writers. Regression: undo during save, Save As, save failure/cancellation, concurrent document windows. Physical reproduction pending.

## FA-05 — P1 — Header/footer undo can restore dangling references [S/I]

Evidence: body-only undo at VM:228–245; hfRemove purges session part/relationship data (:657–666); hfCreate creates body reference plus side maps (:639). Trigger: create a new header, remove it, undo removal, save. Body reference can return while new part/relationship was removed from side maps. Header typing and even/odd settings have no undo state; plain HashMaps/overrides (:588–605) also do not trigger Compose observation. Fix: atomic session snapshots including package parts. Dependencies: FA-04 state model. Regression: create/remove/undo/redo/save/reopen across sections and dirty indicators.

## FA-06 — P2 — Newly created header/footer cannot be reopened in session [S]

Evidence: screen:1721 resolves rid via hfPathOf or hfCreate. `hfPathOf` at VM:683 consults only loaded pkg.rels/hfParts; `hfCreate:642` refuses an already referenced variant. Trigger: create header, close editor panel, tap its edit icon again. Both branches can return null. Fix: resolve session hfRelIds before loaded relationships. Regression: loaded versus newly created parts, shared section references and undo. No device execution performed.

## FA-07 — P1 — Comment/bookmark anchor positions are lost on paragraph edits [S]

Evidence: Reader:197,258,273 accumulates all markers into `extras`; Writer.para:248 emits extras before every run. Trigger: edit a paragraph with a mid-text comment range/bookmark. Start/end markers move to paragraph beginning, even if original text outside range is unchanged. Preservation of the comments part does not preserve anchor semantics. Fix: offset-aware marker model with edit transforms and round-trip fixtures. Regression: split/merge, tracked deletes, multiple runs, nested tables and unknown markers. Dependencies: safe range model before spelling/notes/table structural changes.

## FA-08 — P2 — Shared URI-keyed PDF renderer has conflicting owners [S/I]

Evidence: PdfServiceLocator.kt:23 singleton; P/viewer/PdfViewer.kt:75–89 closes prior URI; :201 removes it; multiple DocumentActivity windows and ExtractTextViewModel.kt:103–105 use the service. Trigger: open same URI in two consumers, close one. Other consumer loses renderer. Fix: session handles or reference-counted ownership. Regression: concurrent tools/viewers, cancellation and same-URI reopen.

## FA-09 — P2 — Search results do not follow asynchronous extraction [S]

Evidence: Screen:2132 calls VM.searchText:848; :875–884 is a no-op full-search coroutine. loadTextPage:768 updates blocks without re-searching. Trigger: open find, type query before queued OCR/extraction completes. Results can remain partial. Fix: query/document generation with incremental re-evaluation and bounded extraction queue; cancel obsolete work. Regression: rapid query changes, scans, mixed digital/OCR pages, close/reopen.

## FA-10 — P2 — Flatten errors can report success [S]

Evidence: P/flatten/PdfFlattener.kt:27 ignores acroForm.flatten exception then saves and returns field count; FlattenPdfViewModel.apply consumes the result. Trigger: flatten throws on malformed/unsupported appearance. Impact: output still interactive while UI reports completion. Fix: propagate failure or explicitly verify postconditions. Regression: empty forms, nested fields, appearance generation and partial-output cleanup.

## FA-11 — P2 — PDF overlays lack full page-coordinate transformation [S/I]

Evidence: VM.exportDocumentTo:1080 and drawOverlayOnPage:1130 use width/height and flipped Y, unlike form/link normalized readers which handle rotation and box origin. Trigger: annotate rotated page or nonzero CropBox lower-left. Impact: exported marks may move/rotate incorrectly. Fix: shared inverse viewport→PDF transform. Regression: 0/90/180/270 degrees, crop origins, shapes/text/images/notes. Device/export fixture verification required.

## FA-12 — P1 — Sensitive durable copies are not excluded from backup [S/I]

Evidence: manifest:37 allowBackup; backup_rules.xml and data_extraction_rules.xml omit mirrors/signatures/preferences; LocalDocumentMirror.kt:52 and SignatureManager.kt:16 store them outside noBackup. Impact: eligible documents/signatures/history may enter OS backups contrary to user expectations. Fix: explicit backup/retention policy, correct domains, privacy disclosure; do not conflate platform cache exclusions with file/cache paths. Regression: backup/restore and device transfer, recents usability. Actual transfer behavior unverified.

## FA-13 — P2 — OCR cancellation marks primary engine permanently broken [S]

Evidence: ocr-core/.../OcrServiceImpl.kt:24–29 catches CancellationException via runCatching and sets primaryKnownBroken. Fix: rethrow cancellation before engine fallback; retain recoverable/transient error semantics. Dependencies: ML Kit callback lifecycle. Regression: cancel/retry, native cleanup, no bitmap recycle while recognizer still uses it. No runtime test exists.

## FA-14 — P2 — Office reinstall is not failure-atomic [S/I]

Evidence: OfficeEngineStore.commit:78–87 renames existing same-version target to trash before staging rename/current switch. Failure between these steps breaks active pointer; no rollback. Fix: unique version-generation target and durable pointer swap, or rollback on failure. Regression: injected rename/disk-full/process-death failures; preserve old usable engine until commit. Existing named test is ignored and empty.

## FA-15 — P2 — HTML conversion has no bounded failure lifecycle [S/I]

Evidence: HtmlToPdfConverter.kt:59–76 only completes after onPageFinished, no deadline/error callback/cancellation disposal; :79–104 draws and writes on main thread; :102 silently caps at 500 pages. HtmlToPdfViewModel.kt:89 has no cancellation cleanup hook. Impact: hanging progress, retained WebView, jank or truncated “successful” export. Fix: exactly-once terminal callback, timeout, explicit truncation result, lifecycle cleanup and safe output handling. Regression: offline URL, redirects, error pages, enormous HTML and navigation away.

## FA-16 — P2 — Unbounded document archive expansion [S/I]

Evidence: DocxReader.kt:65–70 reads each XML/media entry fully; 60 MiB media check happens before entry read. XlsxReader.kt:32 and UniversalDocumentConverter.kt:127,167 also read whole entries/files. Impact: malicious or simply large packages can exhaust memory; no exploit was run. Fix: bounded streaming input, per-entry/total budgets, count/depth limits and clear unsupported-size errors. Regression: normal image-heavy docs and unknown-part byte preservation.

## FA-17 — P2 — Gallery null output stream can be reported saved [S]

Evidence: ImageIo.publishToGallery:219–225 uses nullable openOutputStream then returns item unconditionally. ImageEditorViewModel.saveToGallery:545–547 reports success for nonnull URI. Trigger: provider returns null output stream. Fix: require stream, verify output and delete only owned failed item. Regression: disk-full, null provider, cancellation, valid gallery output; no provider test exists.

## FA-18 — P2 — Recovery is shallow and storage growth is not bounded [S/I]

Evidence: SaveRecovery.validateOfficePackage:247 checks ZIP entry existence/size, not XML/CRC across parts; pending:161 leaves journal-less directories indefinitely. LocalDocumentMirror.mirror:164 uses size-only cache freshness and :184 deletes old file before rename; recents trim at Preferences:79 does not remove trimmed mirrors. PDF Save Copy backups have no next-launch mapping. Fix: integrity validation and ownership-aware quota/cleanup plus durable recovery metadata. Never discard last recoverable edits solely to meet quota. Regression: interrupted prepare, corrupt journal, same-size source updates, active sessions, storage-full.

## FA-19 — P1 — PDF overlay export uses API 26 calls on minimum API 23 [S]

Evidence: app minSdk 23; PdfViewerViewModel.kt:1136,1154,1178,1212,1253,1280 directly call android.graphics.Color.valueOf without SDK guards. Trigger: export respective overlay on API 23–25. Expected NoSuchMethodError is caught by outer export and becomes failure. Fix: integer color component extraction or API-compatible helpers. Regression: lowest API export and color fidelity. Not executed on a device.

## FA-20 — P2 — Release verification does not gate signed artifacts [S]

Evidence: .github/workflows/android.yml:63 release job has no needs dependency on build or release-guard; tests/lint only Play; no test XML artifact step. Fix: gate artifact generation, matrix supported variants and archive test outcomes. Regression: manual/tag/branch workflow conditions and secret isolation. Actual Actions configuration/permissions unverified.

## FA-21 — P3 — Product/distribution claims drift [S]

Evidence: PRIVACY.md:12 and THIRD_PARTY_NOTICES.md:119 claim Play delivery, OfficeEngineFlavor.kt:8 downloads; fdroid recipe points upstream; Preferences.kt:337 star URL points Chethan616/ClearPDF while Settings:159 points HMXPANEL. Fix product links and distribution descriptions after deciding actual distribution architecture; preserve licensing attribution. Regression: external-link targets and variant descriptions.

## FA-22 — P2 — Header/footer boolean and section boundary edge cases [S/I]

Evidence: DocxPageLayout.titlePg:84 / hasEvenOdd:208 test existence, ignoring explicit w:val="0"; sectionAt:140 uses strictly greater paragraph index, excluding a target that itself terminates the section. Fix OOXML on/off semantics and inclusive containing-section lookup. Regression: explicit false values, inserting immediately before terminator, multiple sections and unknown attributes.

## FA-23 — P2 — Compatibility and parser-hardening evidence incomplete [I]

Evidence: lint-baseline.xml:1182 GPUImage alignment; PptxPlaceholderFix.kt:168 unhardened DOM factory; Office runtime native binaries unavailable. These are investigation items, not confirmed exploits. Verify actual native alignment, DTD/entity behavior and dependency reachability in CI/device tests. Fix only demonstrated paths, retaining pinned checksums and local-content privacy. Regression: legitimate PPTX namespaces and native initialization.

## FA-24 — P3 — Incomplete edit semantics and concentrated responsibilities [S]

Evidence: PDF undo removes last added markup rather than reversing arbitrary changes (PdfViewerScreen.kt:696); DOCX replaceRange clamps a stale range without checking matched text (DocxEdits.kt:107); XlsxOps.shiftLines:102 does not mirror writer formula shifts; large screens mix storage/UI. Fix targeted command/session boundaries and generation-aware edits. Regression: eraser/move/recolor/form undo, stale replacement, formula references and live/saved agreement. This is bounded technical debt, not a mandate for wholesale refactoring.
