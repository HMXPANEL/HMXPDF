# Phase 1 Find and Replace implementation

`DocxEdits.replaceRange` rejects out-of-bounds, object-containing and expected-text-mismatched ranges. `DocxEditorViewModel.replaceOne` captures the searched `DocState` and original match, so a changed document cannot apply an old offset to unrelated text. Replace-all validates non-overlapping ranges and applies them right-to-left using the original match text.

The existing screen provides case toggle, count, navigation, Replace Current and Replace All. Search traverses body and table-cell paragraphs. Marker and page-break offsets are transformed through replacements. No new writer or search architecture was introduced.
