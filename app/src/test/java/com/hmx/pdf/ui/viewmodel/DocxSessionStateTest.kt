package com.hmx.pdf.ui.viewmodel

import com.hmx.pdf.docx.DocxFixtures
import com.hmx.pdf.docx.DocxReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4.0 session-state guarantees at the ViewModel:
 * - FA-04: a save serializes exactly the pre-capture generation (body + package), and edits made
 *   after the capture stay dirty instead of being acknowledged as saved.
 * - FA-05: undo/redo restore the package-level session state together with the body — a removed
 *   header/footer's reference and its part/relationship claims come back as one unit.
 * - FA-06: a part created this session resolves through the session relationship claims.
 * - FA-03 (capture half): the root envelope of a loaded part is captured for later saves.
 */
class DocxSessionStateTest {

    private fun docxWithHeader(): ByteArray {
        val headerXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:hdr ${DocxFixtures.W_NS} ${DocxFixtures.R_NS} mc:Ignorable=\"w14\"" +
            " xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\">" +
            "<w:p><w:r><w:t>HDR</w:t></w:r></w:p></w:hdr>"
        val sectPr = "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rId5\"/>" +
            "<w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>"
        return DocxFixtures.docx(
            body = "<w:p><w:r><w:t>alpha</w:t></w:r></w:p>" + sectPr,
            rels = "<Relationship Id=\"rId5\" Type=\"${DocxFixtures.REL}/header\" Target=\"header1.xml\"/>",
            contentTypes = "<Override PartName=\"/word/header1.xml\" ContentType=\"" +
                "application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml\"/>",
            parts = mapOf("word/header1.xml" to headerXml)
        )
    }

    private fun vm(bytes: ByteArray = DocxFixtures.docx(
        body = "<w:p><w:r><w:t>alpha</w:t></w:r></w:p>" +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>"
    )): DocxEditorViewModel {
        val v = DocxEditorViewModel()
        v.primeForTest(DocxReader.read(bytes.inputStream())!!)
        return v
    }

    private fun edit(v: DocxEditorViewModel) {
        v.setEditMode(true)
        assertTrue(v.editMode)
    }

    // ── FA-06: session-created parts resolve ───────────────────────────────────

    @Test
    fun aSessionCreatedHeaderResolvesThroughTheSessionClaims() {
        val v = vm()
        edit(v)
        val path = v.hfCreate(v.sectionsOf().first(), true, "default")!!
        val rid = v.writerExtras().hfRelIds.getValue(path)
        assertEquals(path, v.hfPathOf(rid))
    }

    // ── FA-05: package-inclusive undo ──────────────────────────────────────────

    @Test
    fun undoingARemovedHeaderBringsBackTheReferenceAndItsPartClaims() {
        val v = vm()
        edit(v)
        val path = v.hfCreate(v.sectionsOf().first(), true, "default")!!
        val rid = v.writerExtras().hfRelIds.getValue(path)
        assertTrue(v.dirty)

        v.hfRemove(v.sectionsOf().first(), true, "default")
        assertFalse(v.writerExtras().hfRelIds.containsKey(path))
        assertFalse(v.sectionsOf().first().sectPr.contains("headerReference"))
        assertNull(v.hfPathOf(rid))

        // One undo step: the body reference AND the part/relationship claims return together —
        // the next save writes a complete, non-dangling pair.
        v.undo()
        assertTrue(v.sectionsOf().first().sectPr.contains("headerReference"))
        assertEquals(rid, v.writerExtras().hfRelIds[path])
        assertEquals(path, v.hfPathOf(rid))

        v.redo()
        assertFalse(v.writerExtras().hfRelIds.containsKey(path))
        assertFalse(v.sectionsOf().first().sectPr.contains("headerReference"))
    }

    @Test
    fun bodyUndoLeavesNoDanglingReferenceBehindARemove() {
        val v = vm()
        edit(v)
        val path = v.hfCreate(v.sectionsOf().first(), true, "default")!!
        v.hfRemove(v.sectionsOf().first(), true, "default")
        // A body edit after the remove, then undoing it must not resurrect the reference either.
        val body = v.doc!!.blocks.filterIsInstance<com.hmx.pdf.docx.DocPara>().first()
        v.typed(body.id, "alphax", com.hmx.pdf.docx.CharFmt.Plain)
        v.undo()
        assertFalse(v.sectionsOf().first().sectPr.contains("headerReference"))
        assertFalse(v.writerExtras().hfRelIds.containsKey(path))
    }

    // ── FA-04: one capture per save ────────────────────────────────────────────

    @Test
    fun saveAcknowledgesExactlyTheCapturedGeneration() {
        val v = vm()
        edit(v)
        val path = v.hfCreate(v.sectionsOf().first(), true, "default")!!
        val pid = v.hfParas(path).first().id
        v.hfTyped(path, pid, "v1")
        val cap = v.saveCapture()!!
        assertEquals("v1", hfText(cap, path))

        // An edit made after the capture (during a slow write) is not in the captured payload…
        v.hfTyped(path, pid, "v2 longer than v1")
        assertEquals("v1", hfText(cap, path))
        // …and is not acknowledged: the document stays dirty after recording the capture.
        v.recordSaved(cap)
        assertTrue(v.dirty)
    }

    @Test
    fun recordingTheCaptureWithNoLaterEditLeavesTheDocumentClean() {
        val v = vm()
        edit(v)
        val path = v.hfCreate(v.sectionsOf().first(), true, "default")!!
        v.hfTyped(path, v.hfParas(path).first().id, "v1")
        val cap = v.saveCapture()!!
        v.recordSaved(cap)
        assertFalse(v.dirty)
        // A genuine later edit dirties again, on body or package level.
        v.hfTyped(path, v.hfParas(path).first().id, "v2 longer than v1")
        assertTrue(v.dirty)
    }

    // ── FA-03 (capture half): the loaded part's root envelope is remembered ────

    @Test
    fun anEditedLoadedHeaderCarriesItsOriginalEnvelope() {
        val v = vm(docxWithHeader())
        edit(v)
        val path = "word/header1.xml"
        v.hfParas(path) // first parse captures the envelope
        v.hfTyped(path, v.hfParas(path).first().id, "Edited")
        val extras = v.writerExtras()
        val envelope = extras.hfEnvelopes[path]!!
        assertTrue(envelope.startsWith("<w:hdr "))
        assertTrue(envelope.contains("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""))
        assertTrue(envelope.contains("mc:Ignorable=\"w14\""))
        assertTrue(extras.hfSave.containsKey(path))
    }

    private fun hfText(cap: DocxEditorViewModel.SaveCapture, path: String): String {
        val st = cap.extras.hfSave.getValue(path)
        return (st.blocks[0] as com.hmx.pdf.docx.DocPara).text
    }
}
