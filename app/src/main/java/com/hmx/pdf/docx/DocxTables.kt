package com.hmx.pdf.docx

/**
 * Phase 4.2 immutable table kernels. Every function returns a new [DocTable] and never touches
 * the caller's model, so edits flow through the existing [DocState] commit/undo/autosave path.
 *
 * Safety rules, applied before any structural change:
 * - rows and cells must be parsed (no [DocRaw] children), and every row must describe the same
 *   logical column count (gridBefore/gridAfter are refused rather than guessed);
 * - revision-bearing tables (row/cell insert-delete markers and property changes) are refused:
 *   the editor has no flattened semantics for structural changes inside pending review;
 * - row/column insert and delete refuse already-merging grids; rectangular merge/split understand
 *   gridSpan and vMerge and refuse ambiguous grids.
 *
 * Property edits splice `tcPr`/`trPr` exactly as the reader/writer contract requires: the writer
 * emits `head` + blocks + `tail` verbatim, so `head` is rebuilt from its open tag plus the edited
 * properties, and unknown siblings keep their order and payload.
 */
object DocxTables {

    enum class VMerge { NONE, RESTART, CONTINUE }

    data class CellPos(
        val rowIndex: Int,
        val cell: DocCell,
        val start: Int,
        val span: Int,
        val vMerge: VMerge
    )

    data class Location(val table: DocTable, val rowIndex: Int, val cell: DocCell)

    private data class Found(var hit: Boolean = false)

    // ── discovery ──────────────────────────────────────────────────────────────

    fun findTable(blocks: List<DocBlock>, id: Long): DocTable? {
        for (b in blocks) {
            if (b !is DocTable) continue
            if (b.id == id) return b
            for (row in b.rows) for (cell in row.cells) {
                findTable(cell.blocks, id)?.let { return it }
            }
        }
        return null
    }

    /** The table, row index and cell that contain paragraph [paraId], if any. */
    fun locate(blocks: List<DocBlock>, paraId: Long): Location? {
        for (b in blocks) {
            if (b !is DocTable) continue
            for ((ri, row) in b.rows.withIndex()) for (cell in row.cells) {
                if (cell.blocks.any { it is DocPara && it.id == paraId }) {
                    return Location(b, ri, cell)
                }
                locate(cell.blocks, paraId)?.let { return it }
            }
        }
        return null
    }

    /**
     * Rebuilds only the path to [tableId] (nested tables included). Returns null when the id is
     * absent **or** [f] refuses the change; untouched blocks keep their identity.
     */
    fun mapTable(
        blocks: List<DocBlock>,
        tableId: Long,
        f: (DocTable) -> DocTable?
    ): List<DocBlock>? {
        val found = Found()
        fun walk(list: List<DocBlock>): List<DocBlock>? {
            var changed = false
            val out = ArrayList<DocBlock>(list.size)
            for (b in list) {
                if (b !is DocTable) { out.add(b); continue }
                if (b.id == tableId) {
                    found.hit = true
                    val next = f(b) ?: return null
                    out.add(next); changed = true
                    continue
                }
                var rowChanged = false
                val items = ArrayList<TableItem>(b.items.size)
                for (item in b.items) {
                    if (item !is DocRow) { items.add(item); continue }
                    var cellChanged = false
                    val cells = ArrayList<RowItem>(item.items.size)
                    for (ri in item.items) {
                        if (ri !is DocCell) { cells.add(ri); continue }
                        val inner = walk(ri.blocks)
                        if (inner != null && inner !== ri.blocks) {
                            cellChanged = true; cells.add(ri.withBlocks(inner))
                        } else if (inner == null) {
                            return null
                        } else {
                            cells.add(ri)
                        }
                    }
                    if (cellChanged) { rowChanged = true; items.add(item.withItems(cells)) }
                    else items.add(item)
                }
                if (rowChanged) { changed = true; out.add(b.withItems(items)) } else out.add(b)
            }
            return if (changed) out else list
        }
        val result = walk(blocks) ?: return null
        return if (found.hit) result else null
    }

    // ── grid validation ────────────────────────────────────────────────────────

    /** Logical grid positions, or null when the table is not rectangularly resolvable. */
    fun positions(table: DocTable): List<CellPos>? {
        if (table.items.any { it !is DocRow }) return null
        val rows = table.rows
        if (rows.isEmpty()) return null
        val out = ArrayList<CellPos>()
        var width = -1
        for ((ri, row) in rows.withIndex()) {
            if (row.items.any { it !is DocCell }) return null
            if (tableGridBefore(row.trPr) != 0 || hasTag(row.trPr, "gridAfter")) return null
            var col = 0
            for (cell in row.cells) {
                if (hasTag(cell.tcPr, "hMerge")) return null
                val span = spanOf(cell.tcPr)
                out += CellPos(ri, cell, col, span, vMergeOf(cell.tcPr))
                col += span
            }
            if (col <= 0) return null
            if (width < 0) width = col else if (col != width) return null
        }
        return out
    }

    fun columnCount(table: DocTable): Int? = positions(table)?.maxOfOrNull { it.start + it.span }

    /** Structural revision markers in table/row/cell containers (not ordinary text revisions). */
    fun hasRevisions(table: DocTable): Boolean {
        val names = listOf(
            "ins", "del", "cellIns", "cellDel", "cellMerge",
            "trPrChange", "tcPrChange", "tblPrChange"
        )
        fun hit(vararg xml: String) = xml.any { s -> names.any { hasTag(s, it) } }
        if (hit(table.tblPr, table.head, table.tail)) return true
        for (row in table.rows) {
            if (hit(row.trPr, row.head, row.tail)) return true
            for (cell in row.cells) if (hit(cell.tcPr, cell.head, cell.tail)) return true
        }
        return false
    }

    /** Every cell is single-column and unmerged. */
    fun isSimple(table: DocTable): Boolean {
        val pos = positions(table) ?: return false
        return pos.all { it.span == 1 && it.vMerge == VMerge.NONE }
    }

    /** Structural row/column changes need a simple, unrevised, fully parsed table. */
    fun structuralSafe(table: DocTable): Boolean = isSimple(table) && !hasRevisions(table)

    fun cellAt(table: DocTable, row: Int, col: Int): CellPos? =
        positions(table)?.firstOrNull { it.rowIndex == row && col >= it.start && col < it.start + it.span }

    // ── structure ──────────────────────────────────────────────────────────────

    /** Inserts a new table before block [index] (clamped), with a trailing body paragraph. */
    fun create(blocks: List<DocBlock>, index: Int, rows: Int, cols: Int): List<DocBlock>? {
        if (rows !in 1..100 || cols !in 1..100) return null
        val totalWidth = 9360
        val widths = List(cols) { (totalWidth / cols).coerceAtLeast(1) }
        val tblPr = "<w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/>" +
            tableBordersElement() + "<w:tblLook w:val=\"04A0\"/></w:tblPr>"
        val head = "<w:tbl>" + tblPr + gridElement(widths)
        val items = List(rows) {
            DocRow(DocIds.next(), "<w:tr>", List(cols) { c -> emptyCell(widths[c]) }, "</w:tr>", "")
        }
        val table = DocTable(DocIds.next(), null, head, items, "</w:tbl>", widths, tblPr)
        val out = blocks.toMutableList()
        var at = index.coerceIn(0, out.size)
        // Body-level section properties close the body; a table must not be inserted after them.
        if (at == out.size) {
            val sect = out.indexOfLast { it is DocRaw && it.xml.contains("<w:sectPr") }
            if (sect >= 0) at = sect
        }
        out.add(at, table)
        // A body cannot end on a table: Word expects a paragraph between the table and sectPr.
        val after = out.getOrNull(at + 1)
        if (after == null || (after is DocRaw && after.xml.contains("<w:sectPr"))) {
            out.add(at + 1, emptyPara())
        }
        return out
    }

    fun insertRow(table: DocTable, at: Int): DocTable? {
        if (!structuralSafe(table)) return null
        val rows = table.rows
        if (rows.isEmpty()) return null
        val source = rows.getOrNull(at) ?: rows.last()
        val row = DocRow(
            DocIds.next(), source.head,
            source.cells.map { it.withBlocks(listOf(emptyPara())) },
            source.tail, source.trPr
        )
        val items = table.items.toMutableList()
        items.add(at.coerceIn(0, items.size), row)
        return table.withItems(items)
    }

    fun deleteRow(table: DocTable, at: Int): DocTable? {
        if (!structuralSafe(table)) return null
        val rows = table.rows
        if (rows.size <= 1 || at !in rows.indices) return null
        val items = table.items.toMutableList()
        var seen = -1
        val index = items.indexOfFirst { item ->
            if (item is DocRow) { seen++; seen == at } else false
        }
        if (index < 0) return null
        items.removeAt(index)
        return table.withItems(items)
    }

    fun insertColumn(table: DocTable, at: Int): DocTable? {
        if (!structuralSafe(table)) return null
        val cols = columnCount(table) ?: return null
        if (at !in 0..cols) return null
        val grid = table.grid.ifEmpty { List(cols) { (9360 / cols).coerceAtLeast(1) } }.toMutableList()
        val width = grid.getOrNull(at) ?: grid.lastOrNull() ?: 1800
        grid.add(at.coerceIn(0, grid.size), width)
        val items = table.items.map { item ->
            if (item !is DocRow) item else {
                val out = ArrayList<RowItem>()
                var col = 0
                var inserted = false
                for (cell in item.items) {
                    if (col == at) { out.add(emptyCell(width)); inserted = true }
                    out.add(cell)
                    col += spanOf((cell as? DocCell)?.tcPr ?: "")
                }
                if (!inserted) out.add(emptyCell(width))
                item.withItems(out)
            }
        }
        return withGrid(table.withItems(items), grid)
    }

    fun deleteColumn(table: DocTable, at: Int): DocTable? {
        if (!structuralSafe(table)) return null
        val cols = columnCount(table) ?: return null
        if (cols <= 1 || at !in 0 until cols) return null
        val grid = table.grid.toMutableList()
        if (at in grid.indices) grid.removeAt(at)
        val items = table.items.map { item ->
            if (item !is DocRow) item else {
                val out = ArrayList<RowItem>()
                var col = 0
                for (cell in item.items) {
                    val span = spanOf((cell as? DocCell)?.tcPr ?: "")
                    if (col != at) out.add(cell)
                    col += span
                }
                item.withItems(out)
            }
        }
        return withGrid(table.withItems(items), grid)
    }

    /**
     * Rectangular merge from [anchorId] covering [rowSpan] x [colSpan]. Cells must be unmerged and
     * unreviewed; covered content is appended to the anchor in row-major order so nothing is lost.
     */
    fun mergeRange(table: DocTable, anchorId: Long, rowSpan: Int, colSpan: Int): DocTable? {
        if (rowSpan < 1 || colSpan < 1 || (rowSpan == 1 && colSpan == 1)) return null
        if (positions(table) == null || hasRevisions(table)) return null
        val pos = positions(table) ?: return null
        if (pos.any { it.span != 1 || it.vMerge != VMerge.NONE }) return null
        val anchor = pos.firstOrNull { it.cell.id == anchorId } ?: return null
        val lastRow = anchor.rowIndex + rowSpan - 1
        val lastCol = anchor.start + colSpan - 1
        val cols = columnCount(table) ?: return null
        if (lastRow >= table.rows.size || lastCol >= cols) return null

        val grid = table.grid
        val totalWidth = if (grid.isEmpty()) 1800 * colSpan else
            (anchor.start..lastCol).sumOf { grid.getOrElse(it) { 0 } }.coerceAtLeast(1)

        // Preserve every covered cell's content on the anchor (row-major, anchor excluded).
        val collected = ArrayList<DocBlock>()
        for (r in anchor.rowIndex..lastRow) {
            for (c in anchor.start..lastCol) {
                val cell = cellAt(table, r, c)?.cell ?: return null
                if (cell.id != anchorId) collected.addAll(cell.blocks)
            }
        }

        val items = table.items.toMutableList()
        var rowSeen = -1
        for ((i, item) in table.items.withIndex()) {
            if (item !is DocRow) continue
            rowSeen++
            if (rowSeen !in anchor.rowIndex..lastRow) continue
            val top = cellAt(table, rowSeen, anchor.start)?.cell ?: return null
            val linked = rowSeen == anchor.rowIndex
            val tcPr = if (linked) {
                propsWithSpan(
                    Props.setTc(anchor.cell.tcPr, "tcW",
                        "<w:tcW w:w=\"$totalWidth\" w:type=\"dxa\"/>"),
                    colSpan, if (rowSpan > 1) VMerge.RESTART else VMerge.NONE
                )
            } else {
                propsWithSpan(
                    "<w:tcPr><w:tcW w:w=\"$totalWidth\" w:type=\"dxa\"/></w:tcPr>",
                    colSpan, VMerge.CONTINUE
                )
            }
            val replacement = DocCell(
                if (linked) anchor.cell.id else DocIds.next(),
                openTag(top.head) + tcPr,
                if (linked) anchor.cell.blocks + collected else listOf(emptyPara()),
                top.tail,
                tcPr
            )
            items[i] = item.replaceSpan(anchor.start, colSpan, listOf(replacement))
        }
        return table.withItems(items)
    }

    /** Splits a horizontally or vertically merged cell back into single cells. */
    fun split(table: DocTable, cellId: Long): DocTable? {
        if (hasRevisions(table)) return null
        val pos = positions(table) ?: return null
        val anchor = pos.firstOrNull { it.cell.id == cellId } ?: return null
        if (anchor.span == 1 && anchor.vMerge == VMerge.NONE) return null
        var top = anchor.rowIndex
        if (anchor.vMerge == VMerge.CONTINUE) {
            var r = anchor.rowIndex - 1
            while (r >= 0) {
                val prev = pos.firstOrNull { it.rowIndex == r && it.start == anchor.start } ?: break
                if (prev.span != anchor.span) break
                if (prev.vMerge == VMerge.RESTART) { top = r; break }
                if (prev.vMerge != VMerge.CONTINUE) break
                r--
            }
            if (pos.none { it.rowIndex == top && it.start == anchor.start && it.vMerge == VMerge.RESTART }) {
                return null
            }
        }
        return splitRegion(table, pos, top, anchor.start, anchor.span)
    }

    private fun splitRegion(
        table: DocTable,
        pos: List<CellPos>,
        top: Int,
        startCol: Int,
        span: Int
    ): DocTable? {
        var lastRow = top
        while (lastRow + 1 < table.rows.size) {
            val next = pos.firstOrNull { it.rowIndex == lastRow + 1 && it.start == startCol } ?: break
            if (next.vMerge != VMerge.CONTINUE || next.span != span) break
            lastRow++
        }
        val grid = table.grid
        val items = table.items.toMutableList()
        var rowSeen = -1
        for ((i, item) in table.items.withIndex()) {
            if (item !is DocRow) continue
            rowSeen++
            if (rowSeen !in top..lastRow) continue
            val source = pos.firstOrNull { it.rowIndex == rowSeen && it.start == startCol }?.cell
                ?: return null
            val cellsHere = List(span) { offset ->
                val width = if (grid.isEmpty()) 1800 else
                    grid.getOrElse(startCol + offset) { 1800 }
                val base = if (offset == 0) source.tcPr else ""
                val tcPr = splitProps(base, width)
                DocCell(
                    if (rowSeen == top && offset == 0) source.id else DocIds.next(),
                    openTag(source.head) + tcPr,
                    if (offset == 0) source.blocks else listOf(emptyPara()),
                    source.tail,
                    tcPr
                )
            }
            items[i] = item.replaceSpan(startCol, span, cellsHere)
        }
        return table.withItems(items)
    }

    // ── cell/row/table properties ──────────────────────────────────────────────

    fun shade(table: DocTable, cellId: Long, fillHex: String?): DocTable? = mapCell(table, cellId) { cell ->
        val element = fillHex?.let {
            "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${it.uppercase()}\"/>"
        }
        cell.withTcPr(Props.setTc(cell.tcPr, "shd", element))
    }

    fun cellBorders(table: DocTable, cellId: Long, on: Boolean): DocTable? = mapCell(table, cellId) { cell ->
        cell.withTcPr(Props.setTc(cell.tcPr, "tcBorders", if (on) cellBordersElement() else null))
    }

    fun cellMargins(table: DocTable, cellId: Long, twips: Int): DocTable? = mapCell(table, cellId) { cell ->
        val v = twips.coerceIn(0, 1080)
        val element = "<w:tcMar><w:top w:w=\"$v\" w:type=\"dxa\"/>" +
            "<w:left w:w=\"$v\" w:type=\"dxa\"/><w:bottom w:w=\"$v\" w:type=\"dxa\"/>" +
            "<w:right w:w=\"$v\" w:type=\"dxa\"/></w:tcMar>"
        cell.withTcPr(Props.setTc(cell.tcPr, "tcMar", element))
    }

    fun cellWidth(table: DocTable, cellId: Long, twips: Int): DocTable? = mapCell(table, cellId) { cell ->
        val v = twips.coerceIn(120, 31680)
        cell.withTcPr(Props.setTc(cell.tcPr, "tcW", "<w:tcW w:w=\"$v\" w:type=\"dxa\"/>"))
    }

    fun rowHeight(table: DocTable, rowIndex: Int, twips: Int?, exact: Boolean = false): DocTable? {
        val rows = table.rows
        if (rowIndex !in rows.indices) return null
        val row = rows[rowIndex]
        val element = twips?.let {
            val rule = if (exact) "exact" else "atLeast"
            "<w:trHeight w:val=\"${it.coerceIn(0, 31680)}\" w:hRule=\"$rule\"/>"
        }
        val trPr = Props.setTr(row.trPr, "trHeight", element)
        val items = table.items.toMutableList()
        var seen = -1
        for ((i, item) in table.items.withIndex()) {
            if (item !is DocRow) continue
            seen++
            if (seen == rowIndex) items[i] = item.withTrPr(trPr)
        }
        return table.withItems(items)
    }

    fun tableBorders(table: DocTable, on: Boolean): DocTable {
        val tblPr = Props.setTbl(table.tblPr, "tblBorders", if (on) tableBordersElement() else null)
        return withTblPr(table, tblPr)
    }

    fun tableAlignment(table: DocTable, jc: String?): DocTable {
        val value = jc?.takeIf { it == "left" || it == "center" || it == "right" }
        val element = value?.let { "<w:jc w:val=\"$it\"/>" }
        return withTblPr(table, Props.setTbl(table.tblPr, "jc", element))
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    fun spanOf(tcPr: String): Int =
        tcPrRoot(tcPr)?.child("gridSpan")?.attr(tcPr, "val")?.toIntOrNull()?.coerceAtLeast(1) ?: 1

    fun vMergeOf(tcPr: String): VMerge {
        val node = tcPrRoot(tcPr)?.child("vMerge") ?: return VMerge.NONE
        return if ((node.attr(tcPr, "val") ?: "continue") == "restart") VMerge.RESTART else VMerge.CONTINUE
    }

    private fun mapCell(table: DocTable, cellId: Long, f: (DocCell) -> DocCell): DocTable? {
        var found = false
        val items = table.items.map { item ->
            if (item !is DocRow) item else {
                var rowChanged = false
                val cells = item.items.map { ri ->
                    if (ri is DocCell && ri.id == cellId) {
                        found = true; rowChanged = true; f(ri)
                    } else ri
                }
                if (rowChanged) item.withItems(cells) else item
            }
        }
        return if (found) table.withItems(items) else null
    }

    private fun DocRow.replaceSpan(startCol: Int, span: Int, replacement: List<DocCell>): DocRow {
        var col = 0
        var done = false
        val out = ArrayList<RowItem>(items.size + replacement.size)
        for (item in items) {
            if (item !is DocCell) { out.add(item); continue }
            val sp = spanOf(item.tcPr)
            when {
                !done && col == startCol -> { out.addAll(replacement); done = true }
                col >= startCol && col < startCol + span -> Unit // covered by the new cell(s)
                else -> out.add(item)
            }
            col += sp
        }
        if (!done && col == startCol) out.addAll(replacement)
        return withItems(out)
    }

    private fun DocCell.withTcPr(tcPr: String): DocCell =
        DocCell(id, openTag(head) + tcPr, blocks, tail, tcPr)

    private fun DocRow.withTrPr(trPr: String): DocRow =
        DocRow(id, openTag(head) + trPr, items, tail, trPr)

    private fun withTblPr(table: DocTable, tblPr: String): DocTable {
        val gridAt = table.head.indexOf("<w:tblGrid")
        val rest = if (gridAt >= 0) table.head.substring(gridAt) else ""
        return DocTable(table.id, null, openTag(table.head) + tblPr + rest,
            table.items, table.tail, table.grid, tblPr)
    }

    private fun withGrid(table: DocTable, grid: List<Int>): DocTable {
        val start = table.head.indexOf("<w:tblGrid")
        val end = table.head.indexOf("</w:tblGrid>")
        if (start < 0 || end <= start) {
            return DocTable(table.id, null, table.head, table.items, table.tail, grid, table.tblPr)
        }
        val close = end + "</w:tblGrid>".length
        val head = table.head.substring(0, start) + gridElement(grid) + table.head.substring(close)
        return DocTable(table.id, null, head, table.items, table.tail, grid, table.tblPr)
    }

    private fun propsWithSpan(tcPr: String, span: Int, vMerge: VMerge): String {
        var out = Props.setTc(tcPr, "gridSpan",
            if (span > 1) "<w:gridSpan w:val=\"$span\"/>" else null)
        out = Props.setTc(out, "vMerge", when (vMerge) {
            VMerge.RESTART -> "<w:vMerge w:val=\"restart\"/>"
            VMerge.CONTINUE -> "<w:vMerge/>"
            VMerge.NONE -> null
        })
        return out
    }

    private fun splitProps(base: String, width: Int): String {
        var out = Props.setTc(base, "gridSpan", null)
        out = Props.setTc(out, "vMerge", null)
        out = Props.setTc(out, "tcW", "<w:tcW w:w=\"$width\" w:type=\"dxa\"/>")
        return out
    }

    private fun gridElement(widths: List<Int>): String =
        "<w:tblGrid>" + widths.joinToString("") { "<w:gridCol w:w=\"$it\"/>" } + "</w:tblGrid>"

    private fun emptyPara(): DocPara =
        DocPara(DocIds.next(), null, "<w:p>", "", "", "", emptyList(), true)

    private fun emptyCell(width: Int): DocCell {
        val tcPr = "<w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/></w:tcPr>"
        return DocCell(DocIds.next(), "<w:tc>" + tcPr, listOf(emptyPara()), "</w:tc>", tcPr)
    }

    private fun openTag(head: String): String {
        val end = head.indexOf('>')
        if (end < 0) return head
        val tag = head.substring(0, end + 1)
        return if (tag.endsWith("/>")) tag.dropLast(2) + ">" else tag
    }

    private fun tcPrRoot(tcPr: String): XNode? =
        if (tcPr.isEmpty()) null else XmlScan.scan(tcPr).children.firstOrNull()

    /** True only for a real `<w:name>`, `<w:name …>` or `<w:name/>` element, not a prefix sibling. */
    private fun hasTag(xml: String, name: String): Boolean =
        xml.contains("<w:$name>") || xml.contains("<w:$name ") || xml.contains("<w:$name/>")

    private fun borderSides(sides: List<String>): String =
        sides.joinToString("") {
            "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>"
        }

    private fun cellBordersElement(): String =
        "<w:tcBorders>" + borderSides(listOf("top", "left", "bottom", "right")) + "</w:tcBorders>"

    private fun tableBordersElement(): String =
        "<w:tblBorders>" +
            borderSides(listOf("top", "left", "bottom", "right", "insideH", "insideV")) +
            "</w:tblBorders>"
}
