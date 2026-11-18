package com.hmx.pdf.ui.viewmodel

import com.hmx.pdf.docx.DocPara
import com.hmx.pdf.docx.DocTable
import com.hmx.pdf.docx.DocxFixtures
import com.hmx.pdf.docx.DocxReader
import com.hmx.pdf.docx.DocxTables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4.2 at the ViewModel: Edit Mode and Track Changes gates, undo/redo through the existing
 * session snapshots, merged-cell transforms, and focus→table/cell targeting. Package persistence
 * is covered by `DocxTablesTest`'s writer→reader round trip.
 */
class DocxTablesViewModelTest {

    private fun parsed(): Pair<com.hmx.pdf.docx.DocxPackage, com.hmx.pdf.docx.DocState> {
        val bytes = DocxFixtures.docx(body = "<w:p><w:r><w:t>alpha</w:t></w:r></w:p>")
        return DocxReader.read(bytes.inputStream())!!
    }

    private fun vm(edit: Boolean = true): DocxEditorViewModel {
        val (pkg, state) = parsed()
        return DocxEditorViewModel().apply {
            primeForTest(pkg to state)
            if (edit) setEditMode(true)
        }
    }

    private fun DocxEditorViewModel.table(): DocTable =
        doc!!.blocks.filterIsInstance<DocTable>().first()

    @Test
    fun tableCommandsRequireEditMode() {
        val (pkg, state) = parsed()
        val view = DocxEditorViewModel().apply { primeForTest(pkg to state) }
        assertFalse(view.insertTable(1, 1, null))
        assertTrue(view.doc!!.blocks.none { it is DocTable })

        view.setEditMode(true)
        assertTrue(view.insertTable(2, 2, null))
        assertEquals(2, view.table().rows.size)
    }

    @Test
    fun trackChangesRefusesStructuralEdits() {
        val view = vm()
        assertTrue(view.insertTable(2, 2, null))
        val id = view.table().id

        view.setTracking(true)
        assertTrue(view.tracking)
        assertFalse(view.insertTableRow(id, 1))
        assertFalse(view.deleteTableColumn(id, 0))
        assertEquals(2, view.table().rows.size)

        view.setTracking(false)
        assertTrue(view.insertTableRow(id, 1))
        assertEquals(3, view.table().rows.size)
    }

    @Test
    fun structuralEditsUseTheSessionUndoAndRedoStacks() {
        val view = vm()
        assertTrue(view.insertTable(2, 2, null))
        val id = view.table().id

        assertTrue(view.insertTableRow(id, 1))
        assertEquals(3, view.table().rows.size)

        view.undo()
        assertEquals(2, view.table().rows.size)
        view.redo()
        assertEquals(3, view.table().rows.size)
    }

    @Test
    fun mergeAndSplitRoundTripThroughTheViewModel() {
        val view = vm()
        assertTrue(view.insertTable(2, 2, null))
        val id = view.table().id
        val anchor = view.table().rows[0].cells[0]

        assertTrue(view.mergeTableCells(id, anchor.id, 2, 2))
        // A 2x2 merge leaves one spanning cell in each covered row.
        assertEquals(1, view.table().rows[0].cells.size)
        assertEquals(1, view.table().rows[1].cells.size)
        assertEquals(2, DocxTables.columnCount(view.table()))

        assertTrue(view.splitTableCell(id, anchor.id))
        assertEquals(2, view.table().rows[0].cells.size)
        assertEquals(2, view.table().rows[1].cells.size)
    }

    @Test
    fun focusInsideACellResolvesToThatTableAndCell() {
        val view = vm()
        assertTrue(view.insertTable(1, 2, null))
        val t = view.table()
        val para = t.rows[0].cells[1].blocks.filterIsInstance<DocPara>().first()

        val location = view.tableLocation(para.id)
        assertNotNull(location)
        assertEquals(t.id, location!!.table.id)
        assertEquals(t.rows[0].cells[1].id, location.cell.id)
    }
}
