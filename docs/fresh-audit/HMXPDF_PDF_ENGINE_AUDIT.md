# HMXPDF PDF engine audit

`P/` = `pdf-core/src/main/java/com/kyant/pdfcore/`; `V` = `app/src/main/java/com/hmx/pdf/ui/viewmodel/PdfViewerViewModel.kt`. Findings are source-derived, not device measurements.

## Rendering and resource ownership

`P/viewer/PdfViewer.kt:69–219` wraps Android PdfRenderer. A ReentrantLock serializes open/render/close and each opened page is closed in finally. File descriptors are closed when renderer creation fails. Region rendering supports sharp zoom without allocating the entire zoomed page. Rendering failures, including OOM, return null; the UI can remain on a spinner without a per-page retry/error state.

Renderer maps are keyed by URI, and `PdfServiceLocator` supplies one shared instance. `open:75` closes an existing URI renderer and `close:201` removes it. Two windows or another tool using the same URI can invalidate each other (FA-08). A session identifier is needed in addition to URI identity.

`V:287–306` uses 720 px first paint, 1200 px default rendering, radius-five page cache, two-page forward prefetch and a 150 MiB form-scan cutoff. These are page-count/dimension heuristics, not a global memory budget. `PdfViewer.renderPage:122` derives height from aspect ratio without a maximum pixel cap. Large/tall pages can exceed heap. Raster export/OCR has a separate 16-million-pixel cap (`P/raster/PdfRasterizer.kt:33–45`). Cache state still has one list slot per document page and text blocks accumulate across pages.

`V.openPdf:315` uses an open generation guard and adopts decrypted sessions only after successful opening. `onCleared:1297` closes renderer/decrypted session and recycles bitmaps. A fallback mirror copy at :615 does not wrap FileOutputStream itself in `use`, a descriptor/resource hygiene gap. Stale async page results compare URI rather than generation, leaving same-URI reopen races for future testing.

## Text, search and OCR

`P/text/PdfTextService.kt:59` reparses the PDF for each extracted page using temp-file-backed PDFBox loading. `searchAll:71` parses once and extracts pages sequentially. Glyph coordinates are grouped into line segments and per-character rectangles; RTL helpers are heuristic (`PdfBidi.kt:21`). Reading order across columns is approximate, not semantic tagged-PDF order.

Viewer extraction is serialized with a Mutex (`V:225,747`) and falls back to OCR when no digital text exists (:749–758). The search bar calls `searchText`, which searches only loaded blocks (:858). Opening search queues all pages (:943), but completion does not recompute the active query; the advertised full-search coroutine returns null (:875). Searching before extraction finishes can miss matches until the query changes (FA-09). OCR cache identity is URI + size, not content hash (`OcrPageCache.kt:20`), so same-size content changes can serve stale text.

## Modification and output

| Operation | Actual implementation | Important boundary |
|---|---|---|
| Merge | PDFMergerUtility, temp-file memory mode (`merger/PdfMerger.kt:29`) | Selected-page merge retains source documents through save (:59–82) |
| Split/extract | importPage (`splitter/PdfSplitter.kt:24,48`) | Preserves page content; invalid requested indexes are skipped |
| Reorder/rotate/delete | importPage and rotation (`editor/PdfEditor.kt:33`) | Structural copy, not rasterization |
| Compression | PdfRenderer → JPEG bitmap → new Android PDF (`compressor/PdfCompressor.kt:50–126`) | Lossy whole-page rasterization; searchable text/forms/vector semantics lost in output; size reduction not guaranteed |
| Forms | Read/apply AcroForm fields (`form/PdfFormWidgets.kt:78,130`) | Viewer verifies applied field count; XFA/full scripting compatibility not established |
| Flatten | AcroForm.flatten (`flatten/PdfFlattener.kt:18`) | Exceptions swallowed; success can mean unflattened copy (FA-10) |
| Markup | ExportOverlay mapped to PDFBox streams (`V:1056,1122`) | Drawing/highlighting generally baked into content; sticky notes are actual annotations |
| Text replacement | Content-stream glyph surgery (`text/PdfTextEditor.kt:85`) | Unsupported glyphs use cover/redraw; original text can remain extractable; not redaction |
| OCR text layer | Invisible Helvetica text (`searchable/PdfSearchableStamper.kt:29`) | Latin-1 filtering, rotation/box fidelity requires tests |
| Encryption | StandardProtectionPolicy AES-256 request with random owner password (`security/PdfSecurityService.kt:197–227`) | Permissions are viewer-enforced PDF permissions, not universal access control |

Export checks expected page count after reopening (`V:1108–1119`) and deletes failed new output (:996). It does not compare all annotations/content or establish semantic fidelity. Export and several tools use unbounded `PDDocument.load(input)` rather than temp-file mode. Overlay drawing uses crop width/height without consistently applying page rotation and lower-left origin (`V:1080,1130`), unlike normalized form/link reading: a rotated/cropped fixture is required (FA-11).

## Password lifecycle

`P/security/PdfSecurityService.kt:116` decrypts to private cache, owns the file via DecryptedSession and cleans failure paths. Active registry and sidecar protect live sessions from stale cleanup. Default abandoned-file age is 24 hours (:66), not immediate next-launch deletion. Passwords are not intentionally persisted by this service.

However, `V:371` asks FileProvider for this cache file and `app/src/main/res/xml/file_paths.xml` does not expose `decrypted_pdfs/`. This blocks the viewer's valid-password path in this source configuration (FA-01). Narrow internal-file handling or an explicitly scoped provider route is needed, not exposure of all cache data.

## Validation matrix

Future engine tests: malformed PDFs; rotated/cropped pages; encrypted PDFs; embedded/subset fonts; annotations/form values preserved; text fallback remains explicitly distinguishable from removal; output cancellation/disk-full cleanup; two viewers sharing a URI. Device tests: open/password/retry, zoom/scroll/jump, search while loading, selection/OCR, edit/save/share/print, low-memory and process recreation. No current engine or UI tests were run here; see the coverage report.
