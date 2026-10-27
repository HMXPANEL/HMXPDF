package com.hmx.pdf.docx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Image runs are whole verbatim runs at U+FFFC. These tests use disposable fixtures only: a tiny
 * fake PNG, no personal documents. They cover the pure operations and a full save/reopen round
 * trip, because an image action that does not survive save is not an edit.
 */
class DocxImageEditsTest {

    private val wNs = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""

    /** 72 x 36 pt inline picture, relationship rIdImg, drawing id 7. */
    private val inline = """
        <w:r><w:rPr/><w:drawing>
        <wp:inline xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
          xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
          xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
          <wp:extent cx="914400" cy="457200"/><wp:docPr id="7" name="Picture 7"/>
          <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
            <pic:pic><pic:nvPicPr><pic:cNvPr id="0" name="image1.png"/><pic:cNvPicPr/></pic:nvPicPr>
              <pic:blipFill><a:blip r:embed="rIdImg"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
              <pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="914400" cy="457200"/></a:xfrm>
                <a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>
            </pic:pic>
          </a:graphicData></a:graphic>
        </wp:inline></w:drawing></w:r>
    """.trimIndent()

    /** Legacy VML picture, 72 x 36 pt, same relationship. */
    private val vml = """
        <w:r><w:rPr/><w:pict>
          <v:shape xmlns:v="urn:schemas-microsoft-com:vml"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            id="img1" style="width:72pt;height:36pt">
            <v:imagedata r:id="rIdImg"/>
          </v:shape>
        </w:pict></w:r>
    """.trimIndent()

    private val fakePng = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0
    )

    private fun docx(body: String): ByteArray {
        val parts = linkedMapOf(
            "[Content_Types].xml" to "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Default Extension=\"png\" ContentType=\"image/png\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>",
            "_rels/.rels" to "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
            "word/_rels/document.xml.rels" to "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rIdImg\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image1.png\"/></Relationships>",
            "word/document.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document $wNs><w:body>$body" +
                "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/></w:sectPr></w:body></w:document>"
        )
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, c) in parts) {
                z.putNextEntry(ZipEntry(n))
                z.write(c.toByteArray())
                z.closeEntry()
            }
            z.putNextEntry(ZipEntry("word/media/image1.png"))
            z.write(fakePng)
            z.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun read(bytes: ByteArray) = DocxReader.read(bytes.inputStream())!!

    private fun paras(state: DocState): List<DocPara> {
        val out = ArrayList<DocPara>()
        forEachPara(state.blocks, includePreview = false) { out.add(it) }
        return out
    }

    private fun save(bytes: ByteArray, pkg: DocxPackage, state: DocState): ByteArray {
        val src = File.createTempFile("hmx-img-src", ".docx").apply { writeBytes(bytes); deleteOnExit() }
        val out = File.createTempFile("hmx-img-out", ".docx").apply { deleteOnExit() }
        DocxWriter.write(pkg, state, src, out)
        return out.readBytes()
    }

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes()
            }
        }
        return out
    }

    private fun entryText(bytes: ByteArray, name: String) = entries(bytes)[name]?.toString(Charsets.UTF_8)

    private fun picturePara(state: DocState): DocPara =
        paras(state).first { p -> p.text.any { it == OBJ_CHAR } }

    @Test
    fun readsInlineAndLegacyPictures() {
        val (pkg, state) = read(docx("<w:p><w:r><w:t>Before </w:t></w:r>$inline<w:r><w:t> after</w:t></w:r></w:p>" +
            "<w:p>$vml</w:p>"))
        assertNotNull(pkg.image("rIdImg"))
        val ps = paras(state)
        assertEquals("Before \uFFFC after", ps[0].text)
        val run = ps[0].text.indexOf(OBJ_CHAR)
        assertTrue(DocxImageEdits.isImage(ps[0], run))
        assertEquals(72f to 36f, DocxImageEdits.size(ps[0], run))
        assertEquals(72f to 36f, DocxImageEdits.size(ps[1], 0))
        assertFalse(DocxImageEdits.isImage(ps[0], 0))
    }

    @Test
    fun removesOnlyTheChosenPicture() {
        val (_, state) = read(docx("<w:p><w:r><w:t>One </w:t></w:r>$inline<w:r><w:t> middle </w:t></w:r>$inline<w:r><w:t> end</w:t></w:r></w:p>"))
        val p = paras(state)[0]
        val first = p.text.indexOf(OBJ_CHAR)
        val second = p.text.lastIndexOf(OBJ_CHAR)
        val once = DocxImageEdits.remove(p, first)!!
        assertEquals("One  middle \uFFFC end", once.text)
        assertEquals(once.text.length, once.fmts.size)
        assertTrue(DocxImageEdits.isImage(once, once.text.lastIndexOf(OBJ_CHAR)))
        assertEquals(second - 1, once.text.lastIndexOf(OBJ_CHAR))
        assertNull(DocxImageEdits.remove(p, 0))
        assertNull(DocxImageEdits.remove(p, p.text.length))
    }

    @Test
    fun cutAndPasteKeepsTheSameMediaWithANewDrawingId() {
        val (_, state) = read(docx("<w:p><w:r><w:t>Before </w:t></w:r>$inline<w:r><w:t> after</w:t></w:r></w:p>"))
        val p = paras(state)[0]
        val run = p.text.indexOf(OBJ_CHAR)
        val clip = DocxImageEdits.imageRun(p, run)!!
        val rid = DocxObjects.info(clip.obj!!).imageRid
        assertEquals("rIdImg", rid)

        val cut = DocxImageEdits.remove(p, run)!!
        assertEquals("Before  after", cut.text)
        val pasted = DocxImageEdits.insert(cut, 7, clip.copy(obj = DocxImageEdits.withDrawingId(clip.obj!!, 42)))!!
        assertEquals("Before \uFFFC after", pasted.text)
        val newRun = pasted.text.indexOf(OBJ_CHAR)
        assertEquals(rid, DocxObjects.info(pasted.fmts[newRun].obj!!).imageRid)
        assertTrue(pasted.fmts[newRun].obj!!.contains("id=\"42\""))
        assertTrue(pasted.fmts[newRun].obj!!.contains("name=\"Picture 42\""))
        assertTrue(pasted.fmts[newRun].obj!!.contains("r:embed=\"rIdImg\""))
        // The original run's XML is untouched.
        assertTrue(clip.obj!!.contains("id=\"7\""))
    }

    @Test
    fun resizeRewritesBothExtentsAndClampsToThePage() {
        val (_, state) = read(docx("<w:p>$inline</w:p>"))
        val p = paras(state)[0]
        val resized = DocxImageEdits.resize(p, 0, 100f, 50f, maxWidthPt = 500f)!!
        assertEquals(100f to 50f, DocxImageEdits.size(resized, 0))
        val xml = resized.fmts[0].obj!!
        assertEquals(2, Regex("cx=\"1270000\"").findAll(xml).count())
        assertEquals(2, Regex("cy=\"635000\"").findAll(xml).count())
        assertEquals(p.text, resized.text)

        // A picture wider than the text column shrinks proportionally instead of overflowing.
        val fitted = DocxImageEdits.resize(p, 0, 600f, 300f, maxWidthPt = 300f, maxHeightPt = 500f)!!
        assertEquals(300f to 150f, DocxImageEdits.size(fitted, 0))

        assertNull(DocxImageEdits.resize(p, 0, 0f, 50f, 500f))
        assertNull(DocxImageEdits.resize(p, 0, Float.NaN, 50f, 500f))
        assertNull(DocxImageEdits.resize(p, 1, 100f, 50f, 500f))
    }

    @Test
    fun resizeRewritesLegacyVmlStyle() {
        val (_, state) = read(docx("<w:p>$vml</w:p>"))
        val p = paras(state)[0]
        val resized = DocxImageEdits.resize(p, 0, 144f, 72f, maxWidthPt = 500f)!!
        assertEquals(144f to 72f, DocxImageEdits.size(resized, 0))
        assertTrue(resized.fmts[0].obj!!.contains("style=\"width:144pt;height:72pt\""))
    }

    @Test
    fun pastedVmlPictureGetsANewShapeIdWithoutTouchingItsRelationship() {
        val (_, state) = read(docx("<w:p>$vml</w:p>"))
        val p = paras(state)[0]
        val obj = p.fmts[0].obj!!
        val pasted = DocxImageEdits.withDrawingId(obj, 42)
        assertTrue(pasted.contains("id=\"hmxImage42\""))
        assertTrue(pasted.contains("r:id=\"rIdImg\""))
        assertTrue(DocxImageEdits.isImage(CharFmt("", null, pasted)))
    }

    @Test
    fun fitOnlyShrinksAndKeepsProportions() {
        assertEquals(100f to 50f, DocxImageEdits.fit(200f, 100f, 100f, 100f))
        assertEquals(50f to 25f, DocxImageEdits.fit(200f, 100f, 500f, 25f))
        assertEquals(40f to 20f, DocxImageEdits.fit(40f, 20f, 500f, 500f))
        assertEquals(1f to 1f, DocxImageEdits.fit(Float.NaN, 20f, 500f, 500f))
    }

    @Test
    fun insertClampsAndMovesPageMarks() {
        val p = DocPara(1, null, "<w:p>", "", "", "abcdef", List(6) { CharFmt.Plain }, true, listOf(3))
        val f = CharFmt("", null, inline)
        val inserted = DocxImageEdits.insert(p, 1, f)!!
        assertEquals("a\uFFFCbcdef", inserted.text)
        assertEquals(7, inserted.fmts.size)
        assertEquals(listOf(4), inserted.marks)
        val end = DocxImageEdits.insert(p, 99, f)!!
        assertEquals("abcdef\uFFFC", end.text)
        assertEquals(listOf(3), end.marks)
        assertNull(DocxImageEdits.insert(p, 0, CharFmt.Plain))
    }

    @Test
    fun deletedPictureSurvivesSaveWithoutLosingSharedMedia() {
        val bytes = docx("<w:p><w:r><w:t>Keep </w:t></w:r>$inline<w:r><w:t> this</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>Other </w:t></w:r>$inline</w:p>")
        val (pkg, state) = read(bytes)
        val first = paras(state)[0]
        val run = first.text.indexOf(OBJ_CHAR)
        val cut = DocxImageEdits.remove(first, run)!!
        val out = save(bytes, pkg, DocState(replacePara(state.blocks, cut)!!))

        // Deleting a picture leaves its media and relationship in place: the other picture still
        // points at the same file, so removing the bytes would corrupt it.
        assertNotNull(entryText(out, "word/media/image1.png"))
        assertTrue(entryText(out, "word/_rels/document.xml.rels")!!.contains("rIdImg"))
        val (pkg2, state2) = read(out)
        assertNotNull(pkg2.image("rIdImg"))
        assertEquals("Keep  this", paras(state2)[0].text)
        assertEquals("Other \uFFFC", paras(state2)[1].text)
    }

    @Test
    fun pastedPictureRoundTripsWithUniqueDrawingId() {
        val bytes = docx("<w:p><w:r><w:t>Before </w:t></w:r>$inline<w:r><w:t> after</w:t></w:r></w:p>")
        val (pkg, state) = read(bytes)
        val p = paras(state)[0]
        val run = p.text.indexOf(OBJ_CHAR)
        val clip = DocxImageEdits.imageRun(p, run)!!
        val pasted = DocxImageEdits.insert(p, 0, clip.copy(obj = DocxImageEdits.withDrawingId(clip.obj!!, 99)))!!
        val out = save(bytes, pkg, DocState(replacePara(state.blocks, pasted)!!))

        val (pkg2, state2) = read(out)
        val again = paras(state2)[0]
        assertEquals("\uFFFCBefore \uFFFC after", again.text)
        assertTrue(DocxImageEdits.isImage(again, 0))
        assertTrue(DocxImageEdits.isImage(again, 8))
        assertEquals(72f to 36f, DocxImageEdits.size(again, 0))
        assertNotNull(pkg2.image("rIdImg"))
        assertTrue(again.fmts[0].obj!!.contains("id=\"99\""))
        assertTrue(again.fmts[8].obj!!.contains("id=\"7\""))
    }

    @Test
    fun pictureInsideATableCellCanBeDeleted() {
        val bytes = docx("<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid>" +
            "<w:tr><w:tc><w:tcPr/><w:p><w:r><w:t>Cell </w:t></w:r>$inline</w:p></w:tc></w:tr></w:tbl>")
        val (pkg, state) = read(bytes)
        val cell = picturePara(state)
        assertEquals("Cell \uFFFC", cell.text)
        val edited = DocxImageEdits.remove(cell, cell.text.indexOf(OBJ_CHAR))!!
        val out = save(bytes, pkg, DocState(replacePara(state.blocks, edited)!!))
        val again = paras(read(out).second).single()
        assertEquals("Cell ", again.text)
        assertFalse(again.fmts.any { DocxImageEdits.isImage(it) })
    }
}
