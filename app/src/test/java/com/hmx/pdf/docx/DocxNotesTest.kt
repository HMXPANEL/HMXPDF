package com.hmx.pdf.docx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4.4 note kernels: separator preservation, independent id allocation, atomic
 * reference+note editing, verbatim splices for untouched notes, and real writer round trips
 * (existing part, created part, and the relationship/content-type entries a new part needs).
 */
class DocxNotesTest {

    private val w = DocxFixtures.W_NS
    private val rel = DocxFixtures.REL

    private fun part(vararg body: String) =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:footnotes $w>" +
            "<w:footnote w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:separator/></w:r></w:p></w:footnote>" +
            "<w:footnote w:type=\"continuationSeparator\" w:id=\"0\"><w:p><w:r><w:continuationSeparator/></w:r></w:p></w:footnote>" +
            body.joinToString("") +
            "</w:footnotes>"

    private val existingPart = part(
        "<w:footnote w:id=\"3\"><w:p><w:r><w:footnoteRef/></w:r>" +
            "<w:r><w:t xml:space=\"preserve\">existing note</w:t></w:r></w:p></w:footnote>"
    )

    private fun bodyDoc(
        body: String = "<w:p><w:r><w:t>alpha</w:t></w:r></w:p>",
        withPart: Boolean = true
    ): ByteArray = DocxFixtures.docx(
        body = body,
        rels = if (withPart) "<Relationship Id=\"rIdFn\" Type=\"$rel/footnotes\" Target=\"footnotes.xml\"/>" else "",
        contentTypes = if (withPart) "<Override PartName=\"/word/footnotes.xml\" " +
            "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml\"/>" else "",
        parts = if (withPart) mapOf("word/footnotes.xml" to existingPart) else emptyMap()
    )

    private fun loaded(withPart: Boolean = true): Pair<DocxPackage, DocState> =
        DocxReader.read(bodyDoc(withPart = withPart).inputStream())!!

    private fun textOf(note: DocxNote): String =
        note.blocks.filterIsInstance<DocPara>().firstOrNull()?.text?.filter { it != OBJ_CHAR } ?: ""

    @Test
    fun parsedPartKeepsReservedSeparatorsAndDoesNotRewriteItself() {
        val (pkg, _) = loaded()
        val notes = pkg.notes
        assertEquals(1, notes.visible(DocxNotes.Kind.FOOTNOTE).size)
        assertEquals(2, notes.notes(DocxNotes.Kind.FOOTNOTE).count { it.separator })
        assertEquals("existing note", textOf(notes.visible(DocxNotes.Kind.FOOTNOTE).single()))
        // Nothing was edited: the part is left byte-for-byte alone.
        assertNull(notes.document(DocxNotes.Kind.FOOTNOTE))
        assertFalse(notes.modified)
    }

    @Test
    fun idsAreIndependentPerPartAndNeverReuseSeparators() {
        val (pkg, _) = loaded(withPart = false)
        val empty = pkg.notes
        assertEquals(1, DocxNotes.nextId(empty, DocxNotes.Kind.FOOTNOTE))
        val (afterFn, fnId) = DocxNotes.add(empty, DocxNotes.Kind.FOOTNOTE, "n")
        assertEquals(1, fnId)
        assertEquals(1, DocxNotes.nextId(afterFn, DocxNotes.Kind.ENDNOTE))
        val (afterEn, enId) = DocxNotes.add(afterFn, DocxNotes.Kind.ENDNOTE, "e")
        assertEquals(1, enId)
        assertNotNull(afterEn.document(DocxNotes.Kind.FOOTNOTE))
        assertNotNull(afterEn.document(DocxNotes.Kind.ENDNOTE))

        val (pkg2, _) = loaded()
        val withExisting = pkg2.notes
        assertEquals(4, DocxNotes.nextId(withExisting, DocxNotes.Kind.FOOTNOTE))
        assertNull(DocxNotes.remove(withExisting, DocxNotes.Kind.FOOTNOTE, -1))
        assertNull(DocxNotes.remove(withExisting, DocxNotes.Kind.FOOTNOTE, 0))
        assertNull(DocxNotes.edit(withExisting, DocxNotes.Kind.FOOTNOTE, -1, emptyList()))
    }

    @Test
    fun removeSplicesOnlyTheTargetNoteAndKeepsSeparatorsVerbatim() {
        val (pkg, _) = loaded()
        val removed = DocxNotes.remove(pkg.notes, DocxNotes.Kind.FOOTNOTE, 3)!!
        val xml = removed.document(DocxNotes.Kind.FOOTNOTE)!!
        assertFalse(xml.contains("existing note"))
        assertFalse(xml.contains("w:id=\"3\""))
        // The reserved entries survive exactly as imported.
        assertTrue(xml.contains("<w:footnote w:type=\"separator\" w:id=\"-1\">"))
        assertTrue(xml.contains("<w:footnote w:type=\"continuationSeparator\" w:id=\"0\">"))
        assertTrue(xml.trimEnd().endsWith("</w:footnotes>"))
    }

    @Test
    fun editingOneNoteLeavesTheOthersAndUnknownAttributesAlone() {
        val (pkg, _) = loaded()
        val note = pkg.notes.visible(DocxNotes.Kind.FOOTNOTE).single()
        val p = note.blocks.filterIsInstance<DocPara>().first()
        val mark = p.fmts.first { it.obj != null }
        val typed = p.edited(text = OBJ_CHAR + "changed", fmts = listOf(mark) + List("changed".length) { CharFmt.Plain })
        val edited = DocxNotes.edit(pkg.notes, DocxNotes.Kind.FOOTNOTE, 3, listOf(typed))!!
        val xml = edited.document(DocxNotes.Kind.FOOTNOTE)!!
        assertTrue(xml.contains("changed"))
        assertFalse(xml.contains("existing note"))
        assertTrue(xml.contains("<w:footnoteRef/>"))
        assertTrue(xml.contains("w:id=\"3\""))
        assertTrue(xml.contains("<w:footnote w:type=\"separator\" w:id=\"-1\">"))
    }

    @Test
    fun referencesStripFromEditableParagraphsAndRefuseToOrphanRawOnes() {
        val (_, state) = loaded()
        val p = state.blocks.filterIsInstance<DocPara>().first()
        val anchored = DocState(listOf(DocxNotes.anchorPara(p, DocxNotes.Kind.FOOTNOTE, 7)))
        assertTrue(DocxNotes.hasReference(anchored.blocks, DocxNotes.Kind.FOOTNOTE, 7))
        val stripped = DocxNotes.stripReferences(anchored.blocks, DocxNotes.Kind.FOOTNOTE, 7)!!
        assertFalse(DocxNotes.hasReference(stripped, DocxNotes.Kind.FOOTNOTE, 7))
        assertEquals(p.text, stripped.filterIsInstance<DocPara>().first().text)

        val raw = listOf(DocRaw(1, "<w:sdt><w:r><w:footnoteReference w:id=\"9\"/></w:r></w:sdt>"))
        assertTrue(DocxNotes.hasReference(raw, DocxNotes.Kind.FOOTNOTE, 9))
        assertNull(DocxNotes.stripReferences(raw, DocxNotes.Kind.FOOTNOTE, 9))
    }

    @Test
    fun commentsAndNotesDoNotStripEachOther() {
        val (_, state) = loaded()
        var p = state.blocks.filterIsInstance<DocPara>().first()
        p = DocxNotes.anchorPara(p, DocxNotes.Kind.FOOTNOTE, 5)
        p = DocxComments.anchorPara(p, "c1")
        assertTrue(p.fmts.any { it.obj?.contains("commentReference") == true })
        val noComment = DocxComments.stripPara(p, "c1")
        assertFalse(noComment.fmts.any { it.obj?.contains("commentReference") == true })
        assertTrue(DocxNotes.hasReference(listOf(noComment), DocxNotes.Kind.FOOTNOTE, 5))
        val noNote = DocxNotes.stripReferences(listOf(noComment), DocxNotes.Kind.FOOTNOTE, 5)!!
        assertFalse(noNote.filterIsInstance<DocPara>().first().fmts.any { it.obj?.contains("footnoteReference") == true })
    }

    @Test
    fun noteEditsDoNotTouchSectionProperties() {
        val body = "<w:p><w:r><w:t>one</w:t></w:r></w:p>" +
            "<w:p><w:pPr><w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/></w:sectPr></w:pPr></w:p>" +
            "<w:p><w:r><w:t>two</w:t></w:r></w:p>" +
            "<w:p><w:pPr><w:sectPr><w:pgSz w:w=\"15840\" w:h=\"12240\" w:orient=\"landscape\"/></w:sectPr></w:pPr></w:p>"
        val (pkg, state) = DocxFixtures.read(DocxFixtures.docx(body))
        val p = state.blocks.filterIsInstance<DocPara>().first()
        val blocks = replacePara(state.blocks, DocxNotes.anchorPara(p, DocxNotes.Kind.FOOTNOTE, 1))!!
        val xml = DocxWriter.documentXml(pkg, DocState(blocks))
        assertTrue(xml.contains("<w:pgSz w:w=\"12240\" w:h=\"15840\"/>"))
        assertTrue(xml.contains("w:orient=\"landscape\""))
        assertTrue(xml.contains("</w:sectPr>"))
    }

    @Test
    fun existingPartRoundTripsThroughTheSoleWriter() {
        val bytes = bodyDoc()
        val (pkg, state) = loaded()
        val (next, id) = DocxNotes.add(pkg.notes, DocxNotes.Kind.FOOTNOTE, "added note")
        val p = state.blocks.filterIsInstance<DocPara>().first()
        val out = DocxFixtures.save(
            bytes, pkg, DocState(listOf(DocxNotes.anchorPara(p, DocxNotes.Kind.FOOTNOTE, id))),
            DocxWriter.Extras(notes = mapOf("word/footnotes.xml" to next.document(DocxNotes.Kind.FOOTNOTE)!!))
        )
        val entry = DocxFixtures.entry(out, "word/footnotes.xml")!!
        assertTrue(entry.contains("added note"))
        assertTrue(entry.contains("existing note"))
        assertTrue(entry.contains("w:type=\"separator\""))

        val (reopened, reopenedState) = DocxReader.read(out.inputStream())!!
        assertEquals(2, reopened.notes.visible(DocxNotes.Kind.FOOTNOTE).size)
        assertTrue(reopened.notes.visible(DocxNotes.Kind.FOOTNOTE).any { textOf(it) == "added note" })
        val body = reopenedState.blocks.filterIsInstance<DocPara>().first()
        assertTrue(body.text.contains(OBJ_CHAR))
        assertTrue(body.fmts.any { it.obj?.contains("w:footnoteReference w:id=\"$id\"") == true })
    }

    @Test
    fun missingPartIsCreatedWithRelationshipAndContentType() {
        val bytes = bodyDoc(withPart = false)
        val (pkg, state) = loaded(withPart = false)
        assertNull(pkg.notes.pathOf(DocxNotes.Kind.FOOTNOTE))
        val (next, id) = DocxNotes.add(pkg.notes, DocxNotes.Kind.FOOTNOTE, "first")
        val path = DocxNotes.defaultPath(DocxNotes.Kind.FOOTNOTE, "word")
        assertEquals("word/footnotes.xml", path)
        val p = state.blocks.filterIsInstance<DocPara>().first()
        val out = DocxFixtures.save(
            bytes, pkg, DocState(listOf(DocxNotes.anchorPara(p, DocxNotes.Kind.FOOTNOTE, id))),
            DocxWriter.Extras(notes = mapOf(path to next.document(DocxNotes.Kind.FOOTNOTE)!!))
        )
        val rels = DocxFixtures.entry(out, "word/_rels/document.xml.rels")!!
        assertTrue(rels.contains("Type=\"$rel/footnotes\""))
        assertTrue(rels.contains("Target=\"footnotes.xml\""))
        val types = DocxFixtures.entry(out, "[Content_Types].xml")!!
        assertTrue(types.contains("PartName=\"/word/footnotes.xml\""))
        assertTrue(types.contains("footnotes+xml"))
        assertNotNull(DocxFixtures.entry(out, "word/footnotes.xml"))

        val (reopened, _) = DocxReader.read(out.inputStream())!!
        assertEquals("word/footnotes.xml", reopened.notes.pathOf(DocxNotes.Kind.FOOTNOTE))
        assertTrue(reopened.notes.visible(DocxNotes.Kind.FOOTNOTE).any { textOf(it) == "first" })
        // A created part carries the reserved separator entries Word expects.
        assertTrue(reopened.notes.notes(DocxNotes.Kind.FOOTNOTE).count { it.separator } >= 2)
    }
}
