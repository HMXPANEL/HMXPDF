# OCR and Office engine audit

All results are source inspection. No engines, downloads or conversions were executed. `A/` denotes `app/src/main/java/com/hmx/pdf/`.

## OCR execution

`PdfServiceLocator.kt:32` injects `OcrFlavor.primaryEngine` into `ocr-core/.../OcrServiceImpl.kt:14`. Play/FOSS supplies bundled ML Kit Latin recognition (`app/src/gms/java/com/hmx/pdf/ocr/MlKitOcrEngine.kt:20–52`); F-Droid supplies no primary engine. Tesseract fallback uses `LANG = "eng"`, copies bundled trained data into private files, allocates TessBaseAPI per recognition and recycles it in finally (`ocr-core/src/main/java/com/kyant/ocrcore/TesseractOcrEngine.kt:18–74`). No language selector or additional trained data exists.

Recognition operates on bitmap words and normalized boxes. Viewer pages use digital text first and OCR second; standalone extraction offers OCR after digital extraction returns empty (`A/ui/viewmodel/ExtractTextViewModel.kt:64–134`). Scanning filters use ColorMatrix and bounded image decode (`ScanDocumentScreen.kt:577,671`). This is not a full adaptive OCR preprocessing pipeline for deskew/noise/language detection. Google scanner supplies its own perspective/edge-detection flow; F-Droid delegates one camera photo at a time.

`OcrServiceImpl.recognize:24` catches every primary failure with runCatching and permanently marks it broken. Cancellation is therefore treated as engine failure (FA-13). ML Kit callbacks use suspendCancellableCoroutine, but cancellation does not cancel the recognition task or explicitly close the singleton recognizer. Tesseract has no per-word coroutine cancellation check. Validate cancellation, empty-page iterator handling and simultaneous callers on device.

OCR cache is private cache JSON keyed by URI + file size (`A/ui/viewmodel/OcrPageCache.kt:20–43`), with no explicit quota/TTL or content-version invalidation. Full-document recognition retains all word lists. Searchable PDF output strips unsupported characters to spaces (`pdf-core/.../searchable/PdfSearchableStamper.kt:85`). Offline operation is source-supported for bundled recognition, not a claim that scanner/model provisioning needs no network.

## Office installer

Current `A/office/OfficeEngineFlavor.kt:8` returns DownloadOfficeEngineInstaller. It checks device ABI, connectivity/storage and enqueues unique work (`DownloadOfficeEngineInstaller.kt:76,96,134,159`). Worker downloads a pinned HTTPS GitHub archive, supports Range resumption, verifies exact size and SHA-256, then extracts into staging (`OfficeEngineDownloadWorker.kt:87–168`). No document upload occurs in that path.

Only ARM64/ARM32 engine bundles are declared (`OfficeEngineManifest.kt:27–65`). XZ uses a 256 MiB dictionary, with a 300 MiB decoder limit and a minimum 290 MiB maximum-heap check (worker:53,158). FOSS requests largeHeap; Play shares the installer without that manifest addition. Installation on low-memory devices is an explicit compatibility limitation, not a reason to increase all app memory allocations.

`EngineArchiveLayout.destinationFor:20–32` canonicalizes destinations and rejects escape from staging. Nonregular entries are skipped. GNU long-name allocation uses archive-declared size without a bound (:51); pinned hashes reduce hostile-download likelihood but do not replace parser limits. Cancellation is checked between entries. `OfficeEngineStore.commit:66–91` validates files and relocates libraries, but same-version reinstall moves the old target away before staging activation succeeds. A failed rename can leave `current` pointing to a missing directory (FA-14).

## Office runtime

Client copies input into private cache while hashing, serializes conversion, binds with a ten-second timeout and waits up to 90 seconds (`OfficeEngineClient.kt:36,57–116,118`). Service uses a HandlerThread and an idle kill timer (`OfficeEngineService.kt:39,48–85`). Runtime loads two `.so` files, configures fonts and invokes JNI documentLoad/saveAs (`OfficeEngineRuntime.kt:22–49,102–131`). Separate process containment protects the main process from many native crashes; both processes share the application UID and files.

Output validation checks nonempty file existence, not a complete PDF parse. Cache is nominally 150 MiB, keeping newest even if larger (client:203). Password outputs skip reuse but still remain in office-pdf until trimming/clear; there is no DecryptedSession-style ownership for them. The public Office.tryConvert path supplies no interactive password workflow and falls back on failure.

## Built-in formats and fidelity

`UniversalDocumentConverter.kt:64–83` tries Office first. DOCX uses local docx-preview/WebView then a basic reflow parser (:166–175); the editor separately prefers native Office then Compose-based DocxPdfWriter. XLSX uses XlsxReader/XlsxPdfRenderer before plain-table fallback (:126). PPTX uses PptxRenderer with shapes, text, pictures, tables and master/layout inheritance (:57,195,298). Legacy DOC/XLS/PPT uses POI extraction (:531–567); ODT has a content parser; RTF falls through text classification without Office. No PPTX edit writer exists.

XLSX editing has real reference shifts and style append logic (`utils/xlsx/XlsxWriter.kt:62–131`), while its in-memory structural operation shifts rows/columns/merges/validation ranges without the same formula rewrite (`XlsxOps.kt:102`). Saved and live formula views may diverge; external recalculation is requested, not computed locally.

## Dependencies and licensing

Declared versions: ML Kit text 16.0.1, Tesseract4Android 4.9.0, POI 5.4.1, XZ 1.10, WorkManager 2.10.1, Play delivery 2.1.0. Notices attribute Apache-2.0 to POI/PDFBox/Tesseract wrapper, BSD-2-Clause to Leptonica, MPL-2.0 to LibreOffice, OFL to fonts, Apache-2.0 to docx-preview and MIT option to JSZip. Google SDK terms need separate review; Settings calls text recognition Apache-2.0 while THIRD_PARTY_NOTICES.md:39–44 describes Google terms. No license conclusion is drawn from that conflicting attribution alone.

No new dependencies are proposed for repairs. Exact APK contribution, resolved transitive licenses, binary 16 KiB alignment and upstream maintenance status were not measured or fetched. CI must produce a resolved dependency/license inventory and inspect actual native artifacts. The archive checksum proves downloaded bytes match the pin, not source reproducibility or absence of vulnerabilities.
