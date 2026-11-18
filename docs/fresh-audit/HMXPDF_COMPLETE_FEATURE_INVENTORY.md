# HMXPDF complete feature inventory

## Classification contract

Each numbered row is one capability group; totals are reproducible from these rows, not a count of individual buttons. **R** = IMPLEMENTED BUT RUNTIME-UNVERIFIED: execution path exists but was not run here. **P** = PARTIALLY IMPLEMENTED. **B** = BROKEN: a concrete source contradiction blocks or corrupts the stated flow; not a claim of device reproduction. **N** = NOT IMPLEMENTED in the inspected application path. IMPLEMENTED AND SOURCE-VERIFIED is reserved for bounded code behavior rather than implying a tested end-to-end product; to avoid overlapping totals, complete-looking user flows are conservatively R. No counted UI-only/backend-only/mock feature is inferred from a filename. Empty test bodies are reported separately.

Source notation: `A/` = `app/src/main/java/com/hmx/pdf/`; `P/` = `pdf-core/src/main/java/com/kyant/pdfcore/`; `O/` = `ocr-core/src/main/java/com/kyant/ocrcore/`. Line numbers refer to the downloaded source.

| ID | Capability | Status | UI → implementation evidence and limits |
|---|---|---|---|
| F01 | Open ordinary PDF | R | A/MainActivity.kt:99; navigation/DocsNavGraph.kt:504; ui/viewmodel/PdfViewerViewModel.kt:309; P/viewer/PdfViewer.kt:75 |
| F02 | PDF page rendering, prefetch and zoom tiles | R | A/ui/screen/PdfViewerScreen.kt:1227–1273 → VM renderPage:670/renderRegion:627 → P/viewer/PdfViewer.kt:99,157 |
| F03 | Page jump, scrubber and reading position | R | A/ui/screen/PdfViewerScreen.kt:1188,1791; PdfViewerDialogs.kt:119; data/repository/Preferences.kt:101 |
| F04 | Night pages, margin crop, book/scroll modes | R | A/ui/viewmodel/PdfViewerViewModel.kt:236,641; ui/screen/PdfViewerScreen.kt:411; Preferences.kt:219–235 |
| F05 | PDF full-document search | P | Search bar invokes searchText at Screen:2132; VM:848 searches loaded blocks, full-search block:875 is a no-op; separate searchTextInDocument:887 is not wired to this bar |
| F06 | PDF text selection/copy/process-text | R | A/ui/selection/PdfTextSelectionState.kt; PdfSelectionToolbar.kt; ui/screen/PdfViewerScreen.kt:2140–2195; P/text/PdfTextService.kt:59 |
| F07 | PDF highlights and drawing/shapes/lasso | R | A/ui/screen/PdfViewerBottomToolbar.kt:575,622; PdfViewerInternals.kt:598 → VM exportDocumentTo:1056 |
| F08 | Sticky-note annotations | R | A/ui/viewmodel/PdfViewerViewModel.kt:157,1278 emits PDAnnotationText; screen annotation editor and export overlay path |
| F09 | PDF in-place text editing | P | P/text/PdfTextEditor.kt:85–169: surgical removal for eligible glyphs, visual-cover fallback otherwise; not secure redaction |
| F10 | PDF image/signature stamps and signature library | R | A/ui/screen/PdfViewerScreen.kt:715; VM:1238; data/repository/SignatureManager.kt:29 |
| F11 | PDF page bookmarks | R | A/data/repository/PdfPageBookmarksManager.kt; ui/screen/PdfViewerDialogs.kt:177–190; app-local bookmarks, not PDF outline authoring |
| F12 | AcroForm viewing and filling | R | A/ui/screen/PdfViewerScreen.kt:674,1054; P/form/PdfFormWidgets.kt:78,130; standalone FillFormViewModel.kt:72 |
| F13 | Open password-protected PDF in viewer | B | VM:369–371 wraps cache/decrypted_pdfs through provider; app/src/main/res/xml/file_paths.xml:3–5 lacks that cache root (FA-01) |
| F14 | Encrypt/decrypt to a selected copy | R | A/ui/screen/EncryptPdfScreen.kt:106 / DecryptPdfScreen.kt:94 → P/security/PdfSecurityService.kt:179,197 |
| F15 | Merge PDFs | R | A/ui/screen/MergePdfScreen.kt:213 → MergePdfViewModel.onMerge:95 → P/merger/PdfMerger.kt:29 |
| F16 | Split/extract PDF pages | R | SplitPdfScreen.kt:318 → SplitPdfViewModel.kt:227; ExtractPagesScreen.kt:130 → P/splitter/PdfSplitter.kt:48 |
| F17 | PDF reorder/delete/rotate | R | PageOrganizerScreen.kt:240–351,457 → VM:162 → P/editor/PdfEditor.kt:33 |
| F18 | PDF compression | P | CompressPdfScreen.kt:181 → VM:147 → P/compressor/PdfCompressor.kt:50: rasterizes every page; text/vector/forms are not retained as original objects; estimate is fixed ratio |
| F19 | Create blank/text/image PDFs | R | CreatePdfScreen.kt:488 → CreatePdfViewModel.kt:97 → P/creator/PdfCreator.kt:63,85,107; stale “stub” comment is inaccurate |
| F20 | Images to PDF with rotation/order | R | ImagesToPdfScreen.kt:296 → VM:99 → P/converter/PdfConverter.kt:52 |
| F21 | PDF pages to images | B | PdfToImagesScreen.kt:171 → VM:60 → P/raster/PdfRasterizer.kt:64,99; missing pdf_images provider root (FA-01) |
| F22 | Text/image PDF watermark | R | WatermarkPdfScreen.kt:188 → VM:72 → P/watermark/PdfWatermarker.kt:32,98; non-Latin text limitations require fixture validation |
| F23 | PDF page numbering | R | PageNumbersScreen.kt:134 → VM:56 → P/pagenumber/PdfPageNumberer.kt:27 |
| F24 | Flatten PDF forms | P | FlattenPdfScreen.kt:101 → VM:51 → P/flatten/PdfFlattener.kt:18; flatten exception swallowed while copy is saved (FA-10) |
| F25 | PDF digital text extraction | R | ExtractTextScreen.kt → VM.onSelectFile:64 → P/converter/PdfConverter.kt:110 |
| F26 | OCR recognition and scanned-PDF text | P | ExtractTextScreen.kt:169 → VM:96; O/OcrServiceImpl.kt:21; bundled Latin ML Kit + English Tesseract only; cancellation concern FA-13 |
| F27 | Searchable PDF from OCR | P | ExtractTextScreen.kt:179 → VM:140 → P/searchable/PdfSearchableStamper.kt:29,85; Latin-1 filtering and rotation fidelity limits |
| F28 | Scan/import/filter/reorder/save pages | R | ScanDocumentScreen.kt:159,449,530,577; ScanViewModel.kt:26–110; GMS scanner source:24; F-Droid path blocked by F48/FA-01 |
| F29 | Local HTML to PDF | R | HtmlToPdfScreen.kt:148 → HtmlToPdfViewModel.kt:65 → A/util/HtmlToPdfConverter.kt:28; network blocked; 500-page cap |
| F30 | URL to PDF | P | Same flow → HtmlToPdfConverter.kt:37; JS/network enabled, no timeout/error/cancellation cleanup contract (FA-15) |
| F31 | DOCX read/view and rich text editing | R | DocxEditorScreen.kt:652–779 → VM:154,311 → docx/DocxReader.kt:56, DocxEdits.kt:50; fields/Strict files guarded |
| F32 | DOCX find/replace | P | Screen:536,1265 → VM:393,407 → DocxEdits.kt:107; stale-range clamping avoids some crashes but does not validate current match text |
| F33 | DOCX comments | P | Screen:1376–1406 → VM:419–483 → DocxComments.kt:49,100,124; existing range anchors collapse to paragraph start after edit (FA-07) |
| F34 | DOCX table-cell text and formatting | R | Screen:2299 → ParagraphBlock/ParaField → VM.typed → DocxModel.replacePara:168 → DocxWriter.table:222; nested containers retained |
| F35 | DOCX Track Changes/review | P | Screen:1440–1502 → VM:282,445 → DocxRevisions.kt:54–65; run insertion/deletion only, not full structural/property tracking |
| F36 | DOCX page/section setup and breaks | P | Screen:1516–1697 → VM:513–559 → DocxPageLayout.kt:123,157; internal preview/export uses final-section geometry |
| F37 | DOCX headers/footers | P | Screen:1721,1770 → VM:625–686 → DocxPageLayout.hfXml:190; namespace, session lookup, undo and observability defects FA-03/05/06 |
| F38 | DOCX undo/redo | P | VM:208–245 snapshots body only; package-side settings/HF edits excluded |
| F39 | DOCX/XLSX manual save and interrupted-save recovery | P | VM.save:740 / SpreadsheetViewModel.save:295 → SaveRecovery.begin:59; DocsApp.kt:148,655 recovery UI exists; immutable save capture/validation gaps |
| F40 | DOCX PDF export/print | P | Screen:821,829 → VM:871 → Office engine, DocxPdfWriter, web fallback; no HF rendering in internal exporter |
| F41 | XLSX cell editing, styles, borders, rows/columns | R | SpreadsheetViewerScreen.kt:356,876–893 → SpreadsheetViewModel.kt:181–267 → utils/xlsx/XlsxOps.kt:40, XlsxWriter.kt:24 |
| F42 | XLSX formula calculation | P | XlsxModel.kt:134,176 retains formulas; XlsxWriter.kt:115 forces external full calculation; no local general formula evaluator |
| F43 | XLSX view/PDF and legacy XLS view | R | SpreadsheetViewModel.kt:122,410; XlsxPainter.kt:339; XlsxPdfRenderer.kt:20; legacy workbook read-only |
| F44 | PPTX viewing/conversion | P | UniversalDocumentConverter.kt:74,416 → PptxRenderer.kt:57; optional LibreOffice preferred; no slide editing or full animation fidelity |
| F45 | Legacy DOC/PPT, ODT and text conversion | P | UniversalDocumentConverter.kt:75–79,531–567; fallback text/reflow conversion; RTF classified as plain text without Office engine |
| F46 | Optional Office engine installation/conversion | P | OfficeEngineFlavor.kt:8 → DownloadOfficeEngineInstaller.kt:134 → worker:60 → client:57 → service:57; ARM-only runtime, memory and distribution issues |
| F47 | Image editing, crop, filters, masks, EXIF and gallery export | R | ImageEditorScreen.kt:179–188,361 → ImageEditorViewModel.kt:384–545 → engine/EditPipeline.kt:42,72; bounded full decode; gallery null-stream defect FA-17 |
| F48 | F-Droid distribution-specific scanner/engine | B | app/src/fdroid/.../OfficeEngineFlavor.kt:13 duplicates main:6; Gradle:151 has no exclusion; camera cache/scans is also unmapped |
| F49 | Image sharing from editor | B | ImageEditorViewModel.kt:551 → ImageIo.kt:198 cache/edited_images → provider omits directory |
| F50 | Image format/resize tools | B | ImageToolsScreen.kt:145 → VM:58 → P/image/ImageProcessor.kt:78,92 cache/image_tools absent from provider |
| F51 | Share edited DOCX/XLSX | B | DocxEditorScreen.kt:1195 / SpreadsheetViewerScreen.kt:751 → buildPatched cache/docs or sheets → SpreadsheetScrolling.shareFile:280 falls back to file URI |
| F52 | PDF sharing/printing and selected-format export | P | PdfViewerScreen.kt:2585–2705; shared/ outputs mapped, but original converted-file and raster-image paths can hit FA-01; PdfPrint.kt:22 |
| F53 | Recents/history/pins/local copies/file actions | R | HomeScreen.kt:452–497; RecentContextMenu.kt:368–384; Preferences.kt:29–140; LocalDocumentMirror.kt:70,129 |
| F54 | Settings/themes/locales/onboarding/motion | R | SettingsScreen.kt:323,434,473; DocsApp.kt; OnboardingManager; MainActivity.kt:26; seven resource locales |
| F55 | DOCX autosave of unsaved edits | N | Commit:208 has no checkpoint/scheduling; save:740 is explicit; SaveRecovery begins only during save |
| F56 | DOCX advanced table structure editing | N | Model retains rows/cells/grid; full VM mutation inventory and screen ribbon have no table insertion/merge/split/row/column commands |
| F57 | Document spell-check engine/UI | N | No TextServicesManager/SpellChecker implementation, annotation model or suggestion command; IME behavior is not app-level proof |
| F58 | Footnote/endnote authoring | N | Reader opaque object fallback:231; writer preserves unrelated parts; no note-part edit model, navigation or commands |
| F59 | Read Aloud/TTS | N | No Android TextToSpeech engine, playback state or speech toolbar in source |
| F60 | App update manager | N | Settings external repository link:159, Office version update state is engine-specific; no application update installer/checker |
| F61 | Accounts/admin/remote configuration/cloud sync | N | No service client, authentication, admin navigation or backend module found in app, manifests and dependencies |
| F62 | Embedded PDF image-object extraction | N | PdfImageRegions locates image rectangles for night rendering; PdfRasterizer exports whole pages; neither exposes original image extraction UI |

## Totals

62 capability groups: **28 R, 20 P, 6 B, 8 N**. Zero additional groups classified source-verified-only, UI-only, backend-only or placeholder/mock. R + P + B means implementation exists to varying degrees; it does not mean 54 working features. All device/runtime outcomes remain unverified.

Compression and creation classes contain obsolete “stub” comments but real implementations. The image Compare icon has an empty click callback because a press-and-hold overlay implements the gesture (`ImageEditorScreen.kt:241–252`). Neither is classified as a mock. No full-feature implementation is inferred from a dependency alone.
