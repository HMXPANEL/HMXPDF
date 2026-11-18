# HMXPDF feature gap analysis

Use the 62-row feature inventory for exact classifications and the FA register for root causes. Source notation is defined there. No missing feature below is inferred solely from a missing filename.

## Existing functionality to retain

Native PDF rendering and PDFBox edits/tools, Compose editors, DOCX character formatting and raw-preserving package patching, XLSX sparse model/operation writer, OCR fallback, signature library, image operation pipeline, local mirrors, manual Office save journals, URI permissions, recents and existing glass UI all have concrete implementation paths. Repair these rather than replacing them with a second engine or duplicating table-cell text editing.

## Repair versus enhancement

| Area | Existing reusable implementation | Gap / blocker | Dependency |
|---|---|---|---|
| Open/share/export | FileProvider, SAF/MediaStore helpers, editedSourceOrThrow | Producer roots mismatch; unsupported file URI fallback | FA-01, API 23 compatibility FA-19 |
| Safe save | SaveRecovery.begin/hash/readback, sole DocxWriter, XlsxWriter | Immutable save generation, comprehensive package validation, session conflicts | FA-03/04/05/18 |
| PDF search | PdfTextService.searchAll, loaded OCR blocks | Wire active query to extraction results; cancellation/bounded queue | FA-09 |
| PDF editing | Overlay export and text-stream surgery | Rotated/crop transforms, complete undo, explicit visual-cover semantics | FA-11/24 |
| OCR | Bundled ML Kit Latin + Tesseract English | Multi-language policy, cancellation, stale cache, Unicode output | FA-13; native/device tests |
| Office engine | Checksum downloader, staged store, IPC conversion | Flavor conflict, same-version rollback, low-memory archives, real distribution contract | FA-02/14/21/23 |
| DOCX HF/layout | Section kernels, part writer, existing panel | Root namespaces, lookup, undo/observability, boolean semantics, per-section rendering | FA-03/05/06/22 |
| Testing/release | JVM fixtures, Play CI | Empty ignored tests, no UI/provider/device coverage, no flavor matrix or gated release | FA-20 |

## Phase-4 foundations and missing work

### Autosave and recovery

Source: DocxEditorViewModel.commit:208, save:740; SpreadsheetViewModel.save:295; SaveRecovery:59; DocsApp:655. Existing recovery protects saves in progress, not every unsaved edit. Missing: debounced checkpoints, durable session identity/version, snapshot retention, startup session restore, conflict UX and checkpoint/save generation consistency. Use app-private noBackup storage and existing writer-generated complete packages. Write/checkpoint to temp, validate, sync and rename where supported. SAF destination replacement is not atomic; retain journal/backup/readback and explicit conflict handling. Foreground coroutine scheduling suits short debounce; WorkManager can handle opportunistic cleanup, not guaranteed per-keystroke persistence or execution after force-stop. Recovery occurs on next launch from already durable bytes.

### Advanced DOCX tables

Source: DocxModel:81–105, Reader.table:300, Writer.table:222, Screen.cellProps:2168/TableRowBlock:2242. Existing cells/nested paragraphs and gridSpan/vMerge display are reusable. No creation/row/column insertion/deletion/merge/split/shading/border command pipeline exists. Raw tcPr/tblPr retention is not editing support. Needed: validated logical grid, stable cell IDs, rectangular selection, atomic structural commands, merge metadata/content policy, property splices and contextual mobile toolbar. Preserve nested tables and unsupported markup; refuse ambiguous operations. Do not claim structural Track Changes support without modeling w:trPr/w:tcPr revisions. Body snapshot integration must extend to all affected package anchors.

### Spell checking

No app-owned TextServicesManager/SpellChecker path found. DocPara text/fmts, DocxLook annotations and replace pipeline are foundations. Add locale-aware paragraph/range analysis, non-document spelling annotations, cancellation/cache, suggestion popup and user-approved replacement. Cross-run words must map offsets without flattening run formatting. Exclude objects, unsupported fields and noneditable previews. Android installed services may be absent or network-dependent; select a verified offline provider or clearly disable when unavailable. A distributed dictionary needs explicit license/size/maintenance review; none is selected in this audit.

### Footnotes/endnotes

Reader preserves reference runs as opaque objects and writer copies unrelated note parts; no editable note model exists. Add relationship-resolved note-part models, reserved separator entries, stable IDs, reference navigation, section numbering settings and package-aware undo. Keep DocxWriter as sole DOCX writer; add part/relationship/content-type edits there. Imported unsupported notes remain preserved/read-only. External Word/LibreOffice fixtures must verify numbering, deletion and nested formatting, not just same-parser reopen.

### Read Aloud

No TTS engine/toolbar/playback state exists. Reuse document traversal with an explicit reading projection that omits pending deletions/object placeholders and handles tables/lists/optional HF/notes. Android TextToSpeech supports installed voices but availability/offline status is device-dependent. Use bounded chunks and stable block/range IDs, stop/requeue for pause/resume, audio-focus handling and lifecycle cleanup. Reading must never modify document state. Editing/deleting current paragraph invalidates/rebases reading position by generation. Background playback should be a deliberate later scope, not an accidental long-lived Activity reference.

## Cross-feature dependencies

| Interaction | Required invariant |
|---|---|
| Autosave + all edits | Same immutable generation serialized and acknowledged, including package parts |
| Tables + comments/revisions | Marker positions/IDs retained; structural tracking explicitly supported or disabled |
| Spelling + runs | Only approved live range changed; no unsupported object/field rewrite |
| Notes + comments | Independent ID domains and part-aware anchors |
| TTS + edits | Position refers to immutable reading projection; stale callbacks discarded |
| Undo + recovery | Checkpoints represent complete state; recover document even if undo history is not persisted |

## Out-of-scope capability gaps

No account/admin/cloud-sync system, app-update manager, dedicated embedded-image extraction or PPTX authoring exists. Their absence is not automatically a defect. Implement only if product requirements authorize them. RTF/ODS/ODP fallback behavior and full formula/Office layout fidelity need explicit support contracts before advertising broader compatibility.
