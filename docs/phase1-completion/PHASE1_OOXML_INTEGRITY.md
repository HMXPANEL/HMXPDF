# Phase 1 OOXML integrity

`DocxWriter` remains the sole DOCX write path. Untouched origin paragraphs/tables and unrelated ZIP entries retain existing preservation behavior. Replacement validates ranges and expected text; range markers have explicit offsets; table edits rebuild only the containing immutable path.

This is not a complete OOXML schema validator. Relationships, duplicate IDs, orphan ranges, table-grid invariants and external Office semantics still require dedicated fixtures and CI/external verification.
