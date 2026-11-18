package com.hmx.pdf.docx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4.0 integrity engines: header/footer part splicing must preserve the original root
 * envelope (FA-03), and the OOXML on/off semantics plus the containing-section lookup must
 * respect explicit false values and terminator paragraphs (FA-22).
 */
class DocxHeaderFooterIntegrityTest {

    /** Root carrying a foreign namespace and `mc:Ignorable` — exactly what edits used to drop. */
    private val hdrEnvelope = "<w:hdr ${DocxFixtures.W_NS} ${DocxFixtures.R_NS} " +
        "xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" mc:Ignorable=\"w14\">"
    private val headerXml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>$hdrEnvelope" +
            "<w:p><w:r><w:t>HDR</w:t></w:r></w:p></w:hdr>"

    private fun docxWithHeader(): ByteArray {
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

    private fun rootOpenTag(partXml: String): String {
        val body = partXml.substringAfter("?>").trimStart()
        return body.substringBefore(">") + ">"
    }

    // ── FA-03: the root envelope of an edited existing part survives the save ──

    @Test
    fun editingAnImportedHeaderKeepsItsRootNamespacesAndIgnorable() {
        val bytes = docxWithHeader()
        val (pkg, _) = DocxFixtures.read(bytes)
        val path = "word/header1.xml"
        val state = DocxPageLayout.hfState(pkg.hfParts[path])
        val edited = DocState(listOf(DocxEdits.typed(state.blocks[0] as DocPara, "Edited header", CharFmt.Plain)))
        val out = DocxFixtures.save(
            bytes, pkg, state,
            DocxWriter.Extras(
                hfSave = mapOf(path to edited),
                hfEnvelopes = mapOf(path to hdrEnvelope)
            )
        )
        val saved = DocxFixtures.entry(out, path)!!
        assertEquals(hdrEnvelope, rootOpenTag(saved))
        assertTrue(saved.contains("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""))
        assertTrue(saved.contains("mc:Ignorable=\"w14\""))
        assertTrue(saved.contains(">Edited header<"))
        assertTrue(saved.trimEnd().endsWith("</w:hdr>"))
    }

    @Test
    fun editingAHeaderWithoutEnvelopeKeepsTheMinimalRoot() {
        val bytes = docxWithHeader()
        val (pkg, _) = DocxFixtures.read(bytes)
        val path = "word/header1.xml"
        val state = DocxPageLayout.hfState(pkg.hfParts[path])
        val edited = DocState(listOf(DocxEdits.typed(state.blocks[0] as DocPara, "x", CharFmt.Plain)))
        val out = DocxFixtures.save(bytes, pkg, state, DocxWriter.Extras(hfSave = mapOf(path to edited)))
        val saved = DocxFixtures.entry(out, path)!!
        assertEquals(
            "<w:hdr ${DocxFixtures.W_NS}>",
            rootOpenTag(saved)
        )
        assertFalse(saved.contains("mc:Ignorable"))
    }

    // ── FA-22: explicit on/off values and terminator membership ────────────────

    @Test
    fun explicitFalseFlagsReadAsOff() {
        val on = "<w:sectPr><w:titlePg/><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>"
        val off = "<w:sectPr><w:titlePg w:val=\"0\"/><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>"
        val explicit = "<w:sectPr><w:titlePg w:val=\"true\"/></w:sectPr>"
        assertTrue(DocxPageLayout.titlePg(on))
        assertFalse(DocxPageLayout.titlePg(off))
        assertTrue(DocxPageLayout.titlePg(explicit))
        assertFalse(DocxPageLayout.titlePg("<w:sectPr/>"))
        assertTrue(DocxPageLayout.hasEvenOdd("<w:settings ${DocxFixtures.W_NS}><w:evenAndOddHeaders/></w:settings>"))
        assertFalse(DocxPageLayout.hasEvenOdd("<w:settings ${DocxFixtures.W_NS}><w:evenAndOddHeaders w:val=\"0\"/></w:settings>"))
        assertTrue(DocxPageLayout.hasEvenOdd("<w:settings ${DocxFixtures.W_NS}><w:evenAndOddHeaders w:val=\"1\"/></w:settings>"))
        assertFalse(DocxPageLayout.hasEvenOdd(null))
    }

    @Test
    fun aTerminatorParagraphBelongsToTheSectionItEnds() {
        val sectA = "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>"
        val sectB = "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"720\"/></w:sectPr>"
        val bytes = DocxFixtures.docx(
            body = "<w:p><w:r><w:t>alpha</w:t></w:r></w:p>" +
                "<w:p><w:pPr>$sectA</w:pPr></w:p>" +
                "<w:p><w:r><w:t>beta</w:t></w:r></w:p>" +
                "<w:p><w:pPr>$sectB</w:pPr></w:p>" +
                sectB
        )
        val (_, state) = DocxFixtures.read(bytes)
        val paras = mutableListOf<DocPara>()
        forEachPara(state.blocks, includePreview = false) { paras.add(it) }
        val alpha = paras[0]; val terminatorA = paras[1]; val beta = paras[2]; val terminatorB = paras[3]
        // Two paragraph-terminated sections plus the trailing body-level sectPr entry that
        // sections() always appends (the established Phase 3 model).
        val sections = DocxPageLayout.sections(state)
        assertEquals(3, sections.size)
        assertEquals(terminatorA.id, sections[0].paraId)
        assertEquals(terminatorB.id, sections[1].paraId)
        assertNull(sections[2].paraId)
        assertNotNull(sections[2].rawId)
        assertEquals(terminatorA.id, DocxPageLayout.sectionAt(state, alpha.id).paraId)
        // The FA-22 fix: the terminator itself is inside the section it terminates.
        assertEquals(terminatorA.id, DocxPageLayout.sectionAt(state, terminatorA.id).paraId)
        assertEquals(terminatorB.id, DocxPageLayout.sectionAt(state, beta.id).paraId)
        assertNull(DocxPageLayout.sectionAt(state, beta.id).rawId)
    }
}
