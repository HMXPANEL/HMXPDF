# Phase 1 Comments implementation

`DocMarker(xml, offset)` is now part of `DocPara`. The reader records supported range markers at character boundaries, the writer emits them at those boundaries, and run grouping stops at marker boundaries. Comment creation/removal uses this model; the VM lists both legacy `extras` markers and new markers for compatibility.

Typing, replacement, paragraph split/merge and revision resolution transform marker offsets. Whole-range deletion collapses the marker to the edit boundary. Existing comments relationships/content types and the sole writer remain in use.
