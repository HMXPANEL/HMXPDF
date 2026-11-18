package com.hmx.pdf.ui.viewmodel

import com.hmx.pdf.docx.DocPara
import com.hmx.pdf.docx.DocxFixtures
import com.hmx.pdf.docx.DocxNotes
import com.hmx.pdf.docx.OBJ_CHAR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4.4 at the ViewModel: the mode/tracking gates, note reference and part element changing in
 * one undo step, dirty bookkeeping, and the save capture handing the writer the changed part.
 */
class DocxNotesViewModelTest {

    private fun vm(withPart: Boolean = false): DocxEditorViewModel {
        val rel = DocxFixtures.REL
        val bytes = DocxFixtures.docx(
            body = "<w:p><w:r><w:t>alpha</w:t></w:r></w:p>",
            rels = if (withPart) "<Relationship Id=\"rIdFn\" Type=\"$rel/footnotes\" Target=\"footnotes.xml\"/>" else "",
            parts = if (withPart) mapOf(
                "word/footnotes.xml" to
                    "<w:footnotes ${DocxFixtures.W_NS}>" +
                        "<w:footnote w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:separator/></w:r></w:p></w:footnote>" +
                        "<w:footnote w:type=\"continuationSeparator\" w:id=\"0\"><w:p><w:r><w:continuationSeparator/></w:r></w:p></w:footnote>" +
                        "</w:footnotes>"
            ) else emptyMap()
        )
        val (pkg, state) = DocxFixtures.read(bytes)
        return DocxEditorViewModel().apply {
            primeForTest(pkg to state)
            setEditMode(true)
        }
    }

    private fun DocxEditorViewModel.paragraph(): DocPara =
        doc!!.blocks.filterIsInstance<DocPara>().first()

    private fun refs(view: DocxEditorViewModel, kind: DocxNotes.Kind, id: Int): Boolean =
        view.doc!!.blocks.filterIsInstance<DocPara>().any { p ->
            p.fmts.any { it.obj?.contains("${if (kind == DocxNotes.Kind.FOOTNOTE) "footnote" else "endnote"}Reference w:id=\"$id\"") == true }
        }

    @Test
    fun noteEditsRequireEditModeAndAreRefusedWhileTracking() {
        val view = vm()
        view.setEditMode(false)
        assertNull(view.insertNote(DocxNotes.Kind.FOOTNOTE, null, "x"))
        assertFalse(view.deleteNote(DocxNotes.Kind.FOOTNOTE, 1))

        view.setEditMode(true)
        val para = view.paragraph()
        val id = view.insertNote(DocxNotes.Kind.FOOTNOTE, para.id, "x")!!
        view.setTracking(true)
        assertNull(view.insertNote(DocxNotes.Kind.FOOTNOTE, para.id, "y"))
        assertFalse(view.deleteNote(DocxNotes.Kind.FOOTNOTE, id))
        view.setTracking(false)
        assertTrue(view.deleteNote(DocxNotes.Kind.FOOTNOTE, id))
    }

    @Test
    fun insertAddsTheReferenceAndTheNoteInOneUndoStep() {
        val view = vm()
        assertFalse(view.dirty)
        val para = view.paragraph()
        val id = view.insertNote(DocxNotes.Kind.FOOTNOTE, para.id, "hello")!!

        assertTrue(view.dirty)
        assertTrue(refs(view, DocxNotes.Kind.FOOTNOTE, id))
        assertEquals(1, view.notesList(DocxNotes.Kind.FOOTNOTE).size)
        assertEquals("hello", view.noteFirstText(view.notesList(DocxNotes.Kind.FOOTNOTE).single()))
        // The number mark is not user text.
        assertFalse(view.noteFirstText(view.notesList(DocxNotes.Kind.FOOTNOTE).single()).contains(OBJ_CHAR))

        view.undo()
        assertEquals(0, view.notesList(DocxNotes.Kind.FOOTNOTE).size)
        assertFalse(refs(view, DocxNotes.Kind.FOOTNOTE, id))
        assertFalse(view.dirty)

        view.redo()
        assertEquals(1, view.notesList(DocxNotes.Kind.FOOTNOTE).size)
        assertTrue(refs(view, DocxNotes.Kind.FOOTNOTE, id))
    }

    @Test
    fun deleteRemovesReferenceAndNoteTogether() {
        val view = vm()
        val para = view.paragraph()
        val id = view.insertNote(DocxNotes.Kind.FOOTNOTE, para.id, "gone")!!
        assertTrue(view.deleteNote(DocxNotes.Kind.FOOTNOTE, id))
        assertEquals(0, view.notesList(DocxNotes.Kind.FOOTNOTE).size)
        assertFalse(refs(view, DocxNotes.Kind.FOOTNOTE, id))
        assertEquals("alpha", view.paragraph().text)

        view.undo()
        assertEquals(1, view.notesList(DocxNotes.Kind.FOOTNOTE).size)
        assertTrue(refs(view, DocxNotes.Kind.FOOTNOTE, id))
    }

    @Test
    fun typingInANoteKeepsItsNumberMarkAndIsUndoable() {
        val view = vm()
        val para = view.paragraph()
        val id = view.insertNote(DocxNotes.Kind.ENDNOTE, para.id, "one")!!
        val notePara = view.noteParas(DocxNotes.Kind.ENDNOTE, id).first()

        view.noteTyped(DocxNotes.Kind.ENDNOTE, id, notePara.id, "two")
        assertEquals("two", view.noteFirstText(view.notesList(DocxNotes.Kind.ENDNOTE).single()))
        assertTrue(view.noteParas(DocxNotes.Kind.ENDNOTE, id).first().text.contains(OBJ_CHAR))

        view.undo()
        assertEquals("one", view.noteFirstText(view.notesList(DocxNotes.Kind.ENDNOTE).single()))
        // The reference survived the edit and its undo.
        assertTrue(refs(view, DocxNotes.Kind.ENDNOTE, id))
    }

    @Test
    fun saveCaptureCarriesACreatedPartAndItsNotesBaseline() {
        val view = vm()
        val para = view.paragraph()
        val id = view.insertNote(DocxNotes.Kind.FOOTNOTE, para.id, "new")!!
        val capture = view.saveCapture()!!
        val xml = capture.extras.notes["word/footnotes.xml"]
        assertNotNull(xml)
        assertTrue(xml!!.contains("w:footnote w:id=\"$id\""))
        assertEquals("new", view.noteFirstText(capture.notes!!.visible(DocxNotes.Kind.FOOTNOTE).single()))
    }

    @Test
    fun existingEmptyPartIsUpdatedInPlace() {
        val view = vm(withPart = true)
        assertEquals("word/footnotes.xml", view.pkg!!.notes.pathOf(DocxNotes.Kind.FOOTNOTE))
        val para = view.paragraph()
        val id = view.insertNote(DocxNotes.Kind.FOOTNOTE, para.id, "x")!!
        val xml = view.saveCapture()!!.extras.notes["word/footnotes.xml"]!!
        assertTrue(xml.contains("w:footnote w:id=\"$id\""))
        // Reserved separators from the imported part survive.
        assertTrue(xml.contains("w:type=\"separator\""))
    }
}
