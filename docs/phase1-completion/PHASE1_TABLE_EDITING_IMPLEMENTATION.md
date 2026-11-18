# Phase 1 table-cell editing implementation

No duplicate table editor was created. Reader cell blocks, `mapContainerOf`, `TableRowBlock`, paragraph editing and `DocxWriter.table` remain the implementation path. Cell text, supported formatting, multiple paragraphs and nested traversal are retained. Structural table commands such as merge/split and row/column insertion remain outside Phase 1.
