package com.hmx.pdf.docx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4.2 table kernels: rectangular grid invariants, content preservation through merge/split,
 * refusal of ambiguous or revision-bearing grids, schema-ordered property splices, and a real
 * writer → reader round trip.
 */
class DocxTablesTest {

    private fun fixture(): ByteArray =
        DocxFixtures.docx(body = "<w:p><w:r><w:t>A</w:t></w:r></w:p>")

    private fun parsed(bytes: ByteArray = fixture()): Pair<DocxPackage, DocState> =
        DocxReader.read(bytes.inputStream())!!

    private fun table(rows: Int = 2, cols: Int = 2): DocTable {
        val (_, state) = parsed()
        return DocxTables.create(state.blocks, 0, rows, cols)!!
            .filterIsInstance<DocTable>().first()
    }

    private fun replaceRow(t: DocTable, at: Int, row: DocRow): DocTable {
        val items = t.items.toMutableList()
        var seen = -1
        for ((i, item) in t.items.withIndex()) {
            if (item !is DocRow) continue
            seen++
            if (seen == at) items[i] = row
        }
        return t.withItems(items)
    }

    private fun replaceCell(t: DocTable, at: Int, c: Int, cell: DocCell): DocTable {
        val row = t.rows[at]
        val cells = row.items.toMutableList()
        cells[c] = cell
        return replaceRow(t, at, row.withItems(cells))
    }

    private fun setText(t: DocTable, r: Int, c: Int, text: String): DocTable {
        val cell = t.rows[r].cells[c]
        val p = cell.blocks.filterIsInstance<DocPara>().first()
            .edited(text = text, fmts = List(text.length) { CharFmt.Plain })
        return replaceCell(t, r, c, cell.withBlocks(listOf(p)))
    }

    @Test
    fun createBuildsRectangularGridAndTrailingParagraph() {
        val (_, state) = parsed()
        val blocks = DocxTables.create(state.blocks, state.blocks.size, 2, 3)!!
        val t = blocks.filterIsInstance<DocTable>().single()
        assertEquals(listOf(3120, 3120, 3120), t.grid)
        assertEquals(2, t.rows.size)
        assertTrue(t.rows.all { it.cells.size == 3 })
        assertEquals(3, DocxTables.columnCount(t))
        assertTrue(DocxTables.structuralSafe(t))
        // A body may not end on a table: a paragraph always follows it (before sectPr if present).
        assertTrue(blocks.getOrNull(blocks.indexOf(t) + 1) is DocPara)
    }

    @Test
    fun rowAndColumnOpsKeepTheGridRectangularAndPreserveContent() {
        var t = table()
        t = setText(t, 0, 0, "keep")

        val more = DocxTables.insertRow(t, 1)!!
        assertEquals(3, more.rows.size)
        assertEquals(2, DocxTables.columnCount(more))
        assertEquals("keep", more.rows[0].cells[0].blocks.filterIsInstance<DocPara>().first().text)
        assertEquals("", more.rows[1].cells[0].blocks.filterIsInstance<DocPara>().first().text)

        val fewer = DocxTables.deleteRow(more, 1)!!
        assertEquals(2, fewer.rows.size)
        assertEquals("keep", fewer.rows[0].cells[0].blocks.filterIsInstance<DocPara>().first().text)

        val wider = DocxTables.insertColumn(t, 1)!!
        assertEquals(3, DocxTables.columnCount(wider))
        assertEquals(3, wider.grid.size)
        assertEquals("keep", wider.rows[0].cells[0].blocks.filterIsInstance<DocPara>().first().text)

        val narrower = DocxTables.deleteColumn(wider, 0)!!
        assertEquals(2, DocxTables.columnCount(narrower))
        assertEquals(2, narrower.grid.size)
    }

    @Test
    fun mergeConservesCoveredContentAndSplitRestoresTheGrid() {
        var t = table()
        t = setText(t, 0, 0, "A")
        t = setText(t, 0, 1, "B")
        t = setText(t, 1, 0, "C")
        t = setText(t, 1, 1, "D")

        val anchor = t.rows[0].cells[0]
        val merged = DocxTables.mergeRange(t, anchor.id, 2, 2)!!
        assertEquals(1, DocxTables.positions(merged)!!.count { it.cell.id == anchor.id })
        assertEquals(2, DocxTables.columnCount(merged))
        assertEquals(
            listOf("A", "B", "C", "D"),
            merged.rows[0].cells[0].blocks.filterIsInstance<DocPara>().map { it.text }
        )
        assertEquals(DocxTables.VMerge.RESTART, DocxTables.vMergeOf(merged.rows[0].cells[0].tcPr))
        assertEquals(DocxTables.VMerge.CONTINUE, DocxTables.vMergeOf(merged.rows[1].cells[0].tcPr))

        val split = DocxTables.split(merged, anchor.id)!!
        assertEquals(2, DocxTables.columnCount(split))
        assertEquals(2, split.rows.size)
        assertEquals(2, split.rows[0].cells.size)
        assertEquals("A", split.rows[0].cells[0].blocks.filterIsInstance<DocPara>().first().text)
        assertEquals(DocxTables.VMerge.NONE, DocxTables.vMergeOf(split.rows[0].cells[0].tcPr))
        assertEquals(1, DocxTables.spanOf(split.rows[0].cells[0].tcPr))
    }

    @Test
    fun bordersAreNotMistakenForRevisionMarkers() {
        var t = table()
        t = DocxTables.tableBorders(t, true)
        // <w:insideH>/<w:insideV> start with the characters of <w:ins>: a prefix test would lie.
        assertFalse(DocxTables.hasRevisions(t))
        assertNotNull(DocxTables.insertRow(t, 0))

        val row = t.rows[0]
        val tracked = DocRow(
            DocIds.next(), row.head, row.items, row.tail,
            row.trPr + "<w:ins w:id=\"1\" w:author=\"x\"/>"
        )
        val revised = replaceRow(t, 0, tracked)
        assertTrue(DocxTables.hasRevisions(revised))
        assertNull(DocxTables.insertRow(revised, 0))
        assertNull(DocxTables.deleteColumn(revised, 0))
        assertNull(DocxTables.mergeRange(revised, revised.rows[0].cells[0].id, 1, 2))
    }

    @Test
    fun irregularGridsAreRefusedRatherThanGuessed() {
        val t = table()
        val shortRow = t.rows[0].withItems(t.rows[0].items.dropLast(1))
        val bad = replaceRow(t, 0, shortRow)
        assertNull(DocxTables.positions(bad))
        assertNull(DocxTables.insertRow(bad, 0))
        assertNull(DocxTables.mergeRange(bad, shortRow.cells[0].id, 1, 2))
    }

    @Test
    fun propertyEditsKeepUnknownSiblingsAndSchemaOrder() {
        var t = table()
        val cell = t.rows[0].cells[0]
        val base = "<w:tcPr><w:tcW w:w=\"1000\" w:type=\"dxa\"/>" +
            "<w:vAlign w:val=\"center\"/></w:tcPr>"
        t = replaceCell(t, 0, 0, DocCell(cell.id, "<w:tc>" + base, cell.blocks, "</w:tc>", base))

        val shaded = DocxTables.shade(t, cell.id, "#fff2cc")!!
        val props = shaded.rows[0].cells[0].tcPr
        assertTrue(props.contains("w:fill=\"#FFF2CC\""))
        assertTrue(props.contains("<w:vAlign w:val=\"center\"/>"))

        val sized = DocxTables.cellWidth(shaded, cell.id, 2000)!!
        val bordered = DocxTables.cellBorders(sized, cell.id, true)!!
        val out = bordered.rows[0].cells[0].tcPr
        assertTrue(out.contains("w:w=\"2000\""))
        assertTrue(out.contains("<w:tcBorders>"))
        assertTrue(out.contains("<w:vAlign w:val=\"center\"/>"))
        // CT_TcPr sequence: tcW, tcBorders, shd, …  (vAlign stays after them).
        val w = out.indexOf("<w:tcW")
        val b = out.indexOf("<w:tcBorders>")
        val s = out.indexOf("<w:shd")
        val v = out.indexOf("<w:vAlign")
        assertTrue(w in 0 until b)
        assertTrue(b < s)
        assertTrue(s < v)
    }

    @Test
    fun editedTableSurvivesTheWriterAndReader() {
        val bytes = fixture()
        val (pkg, state) = parsed(bytes)
        val created = DocxTables.create(state.blocks, 0, 2, 2)!!
        val t0 = created.filterIsInstance<DocTable>().first()
        val merged = DocxTables.mergeRange(t0, t0.rows[0].cells[0].id, 1, 2)!!
        val shaded = DocxTables.shade(merged, merged.rows[0].cells[0].id, "#FFF2CC")!!

        val out = DocxFixtures.save(bytes, pkg, DocState(listOf(shaded) + state.blocks))
        val (_, reread) = DocxReader.read(out.inputStream())!!
        val table = reread.blocks.filterIsInstance<DocTable>().first()
        assertEquals(2, DocxTables.columnCount(table))
        assertEquals(2, table.rows.size)
        assertEquals(2, DocxTables.spanOf(table.rows[0].cells[0].tcPr))
        assertTrue(table.rows[0].cells[0].tcPr.contains("FFF2CC"))
    }
}
