package com.hmx.pdf.reading

import com.hmx.pdf.docx.CharFmt
import com.hmx.pdf.docx.DocPara
import com.hmx.pdf.docx.DocState
import com.hmx.pdf.docx.DocxFixtures
import com.hmx.pdf.docx.OBJ_CHAR
import com.hmx.pdf.docx.forEachPara
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4.5 core: the reading script is a projection of the document. These tests pin the declared
 * policy — document order through tables, deleted text excluded, object runs silent, blanks
 * skipped, and bounded chunks that cover the text exactly.
 */
class ReadingProjectionTest {

    private fun read(body: String): DocState = DocxFixtures.read(DocxFixtures.docx(body)).second

    private fun para(text: String, vararg fmts: CharFmt, id: Long = 1) = DocPara(
        id = id,
        origin = null,
        openTag = "<w:p>",
        pPr = "",
        extras = "",
        text = text,
        fmts = fmts.toList().ifEmpty { List(text.length) { CharFmt.Plain } },
        editable = true
    )

    @Test
    fun documentOrderIncludesTableCells() {
        val body = "<w:p><w:r><w:t>First para.</w:t></w:r></w:p>" +
            "<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/></w:tblPr>" +
            "<w:tblGrid><w:gridCol w:w=\"4000\"/><w:gridCol w:w=\"4000\"/></w:tblGrid>" +
            "<w:tr><w:tc><w:p><w:r><w:t>A1</w:t></w:r></w:p></w:tc>" +
            "<w:tc><w:p><w:r><w:t>B1</w:t></w:r></w:p></w:tc></w:tr>" +
            "<w:tr><w:tc><w:p><w:r><w:t>A2</w:t></w:r></w:p></w:tc>" +
            "<w:tc><w:p><w:r><w:t>B2</w:t></w:r></w:p></w:tc></w:tr></w:tbl>" +
            "<w:p><w:r><w:t>Last para.</w:t></w:r></w:p>"

        val segments = ReadingProjection.segments(read(body).blocks)

        assertEquals(listOf("First para.", "A1", "B1", "A2", "B2", "Last para."), segments.map { it.text })
        // Paragraph ids are unique and in document order, which is what the highlight relies on.
        assertEquals(segments.size, segments.map { it.paraId }.distinct().size)
    }

    @Test
    fun blankParagraphsAreSkippedAndWhitespaceCollapsed() {
        val body = "<w:p/>" +
            "<w:p><w:r><w:t xml:space=\"preserve\">  spaced   out  </w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>Second</w:t></w:r><w:r><w:tab/><w:t>line</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t xml:space=\"preserve\">   </w:t></w:r></w:p>"

        val segments = ReadingProjection.segments(read(body).blocks)

        assertEquals(listOf("spaced out", "Second line"), segments.map { it.text })
    }

    @Test
    fun deletedTextIsNotReadUnlessExplicitlyIncluded() {
        val body = "<w:p><w:r><w:t xml:space=\"preserve\">Keep </w:t></w:r>" +
            "<w:del w:id=\"7\" w:author=\"Bob\" w:date=\"2026-02-01T10:00:00Z\">" +
            "<w:r><w:delText xml:space=\"preserve\">gone </w:delText></w:r></w:del>" +
            "<w:r><w:t>tail.</w:t></w:r></w:p>" +
            "<w:p><w:ins w:id=\"9\" w:author=\"Bob\" w:date=\"2026-02-01T10:00:00Z\">" +
            "<w:r><w:t>Added text.</w:t></w:r></w:ins></w:p>"

        val blocks = read(body).blocks

        assertEquals(listOf("Keep tail.", "Added text."), ReadingProjection.segments(blocks).map { it.text })
        assertEquals("Keep gone tail.", ReadingProjection.segments(blocks, includeDeleted = true).first().text)
    }

    @Test
    fun objectCharactersAndFieldsAreSilent() {
        // Comment/note anchors and pictures appear in the model as U+FFFC with an object format.
        val text = "hello$OBJ_CHAR world"
        val fmts = List(text.length) { i -> if (text[i] == OBJ_CHAR) CharFmt("", obj = "<w:drawing/>") else CharFmt.Plain }
        val p = para(text, *fmts.toTypedArray())

        val spoken = ReadingProjection.readableText(p)

        assertEquals("hello world", spoken)
        assertFalse(spoken.contains(OBJ_CHAR))
    }

    @Test
    fun chunksAreBoundedAndCoverTheTextExactly() {
        val text = (1..40).joinToString(" ") { "Sentence number $it ends here." }
        val chunks = ReadingProjection.chunks(listOf(ReadingSegment(7, text)))

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= ReadingProjection.MAX_CHUNK })
        assertTrue(chunks.all { it.paraId == 7L })
        assertEquals(text, chunks.joinToString("") { it.text })
    }

    @Test
    fun chunkPrefersSentenceThenWordBoundary() {
        val sentence = "A".repeat(100) + ". " + "B".repeat(300)
        val first = ReadingProjection.chunks(listOf(ReadingSegment(1, sentence))).first()
        assertEquals(101, first.text.length)
        assertTrue(first.text.endsWith("."))

        val words = "word ".repeat(100)
        val wordChunks = ReadingProjection.chunks(listOf(ReadingSegment(2, words)))
        assertTrue(wordChunks.size > 1)
        assertTrue(wordChunks.all { it.text.length <= ReadingProjection.MAX_CHUNK })
        assertEquals(words, wordChunks.joinToString("") { it.text })
    }

    @Test
    fun longDocumentKeepsParagraphOrderAndBounds() {
        val body = (1..120).joinToString("") { "<w:p><w:r><w:t>Paragraph $it with a few words.</w:t></w:r></w:p>" }
        val state = read(body)
        val segments = ReadingProjection.segments(state.blocks)
        val chunks = ReadingProjection.chunks(segments)

        assertEquals(120, segments.size)
        assertEquals(120, segments.map { it.paraId }.distinct().size)
        assertEquals((1..120).map { "Paragraph $it with a few words." }, segments.map { it.text })
        assertTrue(chunks.size >= segments.size)
        assertTrue(chunks.all { it.text.length <= ReadingProjection.MAX_CHUNK })
        // Chunks never leave their paragraph and follow document order.
        assertEquals(segments.size, chunks.map { it.segment }.distinct().size)
        assertEquals(chunks.sortedBy { it.segment }, chunks)
        for (c in chunks) assertEquals(segments[c.segment].paraId, c.paraId)
    }

    @Test
    fun emptyDocumentHasNoScript() {
        val state = read("")
        assertTrue(ReadingProjection.segments(state.blocks).isEmpty())
        assertTrue(ReadingProjection.chunks(ReadingProjection.segments(state.blocks)).isEmpty())
        // Sanity: the fixture parser really produced a document.
        var count = 0
        forEachPara(state.blocks) { count++ }
        assertEquals(0, count)
    }
}
