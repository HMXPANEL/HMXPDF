package com.hmx.pdf.docx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun rotationReadsDrawingMlAndLegacyVml() {
        val rotatedInline = inline.replace(
            "<a:xfrm>",
            "<a:xfrm rot=\"2700000\">"
        )
        val rotatedVml = vml.replace(
            "style=\"width:72pt;height:36pt\"",
            "style=\"width:72pt;height:36pt;rotation:90\""
        )
        val (_, state) = read(docx("<w:p>$rotatedInline</w:p><w:p>$rotatedVml</w:p>"))
        val ps = paras(state)
        assertEquals(45f, DocxImageEdits.rotation(ps[0], 0)!!, 0.01f)
        assertEquals(90f, DocxImageEdits.rotation(ps[1], 0)!!, 0.01f)
    }

    @Test
    fun rotateRewritesOnlyTheTransformAndSurvivesRepeatedSaves() {
        val bytes = docx("<w:p><w:r><w:t>Before </w:t></w:r>$inline<w:r><w:t> after</w:t></w:r></w:p>")
        val (pkg, state) = read(bytes)
        val p = paras(state)[0]
        val run = p.text.indexOf(OBJ_CHAR)
        assertEquals(0f, DocxImageEdits.rotation(p, run)!!, 0.01f)

        val rotated = DocxImageEdits.rotate(p, run, 45f)!!
        assertEquals(45f, DocxImageEdits.rotation(rotated, run)!!, 0.01f)
        val xml = rotated.fmts[run].obj!!
        assertTrue(xml.contains("rot=\"2700000\""))
        // Rotation must never resize the inline box.
        assertEquals(2, Regex("cx=\"914400\"").findAll(xml).count())
        assertEquals(2, Regex("cy=\"457200\"").findAll(xml).count())
        assertEquals(72f to 36f, DocxImageEdits.size(rotated, run))

        // Resizing after a rotation keeps the angle; the two edits must not overwrite each other.
        val resized = DocxImageEdits.resize(rotated, run, 36f, 18f, 500f)!!
        assertEquals(45f, DocxImageEdits.rotation(resized, run)!!, 0.01f)
        assertEquals(36f to 18f, DocxImageEdits.size(resized, run))

        val first = save(bytes, pkg, DocState(replacePara(state.blocks, rotated)!!))
        val (pkg2, state2) = read(first)
        val again = paras(state2)[0]
        val againRun = again.text.indexOf(OBJ_CHAR)
        assertEquals(45f, DocxImageEdits.rotation(again, againRun)!!, 0.01f)
        assertEquals(72f to 36f, DocxImageEdits.size(again, againRun))
        assertEquals("Before \uFFFC after", again.text)
        assertNotNull(pkg2.image("rIdImg"))

        // Saving the reopened document again must keep the same single rotation value.
        val second = save(first, pkg2, state2)
        val (_, state3) = read(second)
        val third = paras(state3)[0]
        val thirdRun = third.text.indexOf(OBJ_CHAR)
        assertEquals(45f, DocxImageEdits.rotation(third, thirdRun)!!, 0.01f)
        assertEquals(1, Regex("rot=\"2700000\"").findAll(third.fmts[thirdRun].obj!!).count())
    }

    @Test
    fun rotatingOneOfTwoPicturesLeavesTheOtherAndSharedMediaAlone() {
        val bytes = docx("<w:p>$inline$inline</w:p>")
        val (pkg, state) = read(bytes)
        val p = paras(state)[0]
        val first = p.text.indexOf(OBJ_CHAR)
        val second = p.text.lastIndexOf(OBJ_CHAR)
        val rotated = DocxImageEdits.rotate(p, first, 30f)!!
        assertEquals(30f, DocxImageEdits.rotation(rotated, first)!!, 0.01f)
        assertEquals(0f, DocxImageEdits.rotation(rotated, second)!!, 0.01f)
        assertFalse(rotated.fmts[second].obj!!.contains("rot="))

        val out = save(bytes, pkg, DocState(replacePara(state.blocks, rotated)!!))
        val (pkg2, state2) = read(out)
        val again = paras(state2)[0]
        val a = again.text.indexOf(OBJ_CHAR)
        val b = again.text.lastIndexOf(OBJ_CHAR)
        assertEquals(30f, DocxImageEdits.rotation(again, a)!!, 0.01f)
        assertEquals(0f, DocxImageEdits.rotation(again, b)!!, 0.01f)
        assertNotNull(pkg2.image("rIdImg"))
    }

    @Test
    fun floatingAnchorRotationSurvivesSaveAndReopen() {
        val anchor = inline
            .replace("<wp:inline ", "<wp:anchor ")
            .replace("</wp:inline>", "</wp:anchor>")
        val bytes = docx("<w:p>$anchor</w:p>")
        val (pkg, state) = read(bytes)
        val p = paras(state)[0]
        val rotated = DocxImageEdits.rotate(p, 0, 120f)!!
        assertEquals(120f, DocxImageEdits.rotation(rotated, 0)!!, 0.01f)
        assertEquals(72f to 36f, DocxImageEdits.size(rotated, 0))

        val out = save(bytes, pkg, DocState(replacePara(state.blocks, rotated)!!))
        val (pkg2, state2) = read(out)
        val again = paras(state2)[0]
        assertEquals(120f, DocxImageEdits.rotation(again, 0)!!, 0.01f)
        assertEquals(72f to 36f, DocxImageEdits.size(again, 0))
        assertNotNull(pkg2.image("rIdImg"))
    }

    @Test
    fun legacyVmlRotationSurvivesSaveAndReopen() {
        val bytes = docx("<w:p>$vml</w:p>")
        val (pkg, state) = read(bytes)
        val p = paras(state)[0]
        val rotated = DocxImageEdits.rotate(p, 0, 45f)!!
        assertEquals(45f, DocxImageEdits.rotation(rotated, 0)!!, 0.01f)
        assertTrue(rotated.fmts[0].obj!!.contains("rotation:45"))
        assertEquals(72f to 36f, DocxImageEdits.size(rotated, 0))

        val out = save(bytes, pkg, DocState(replacePara(state.blocks, rotated)!!))
        val again = paras(read(out).second)[0]
        assertEquals(45f, DocxImageEdits.rotation(again, 0)!!, 0.01f)
        assertEquals(72f to 36f, DocxImageEdits.size(again, 0))
    }

    @Test
    fun rotatingWithoutATransformIsRejectedWithoutChangingTheRun() {
        val p = DocPara(1, null, "<w:p>", "", "", "\uFFFC", listOf(CharFmt("", null, "<w:r><w:t>x</w:t></w:r>")), true)
        assertNull(DocxImageEdits.rotate(p, 0, 45f))
        assertNull(DocxImageEdits.rotate(p, 1, 45f))

        // A real picture whose transform is absent must be preserved, not rewritten blindly.
        val noXfrm = inline.replace(Regex("<a:xfrm>.*?</a:xfrm>"), "")
        val picture = DocPara(2, null, "<w:p>", "", "", "\uFFFC", listOf(CharFmt("", null, noXfrm)), true)
        assertTrue(DocxImageEdits.isImage(picture, 0))
        assertNull(DocxImageEdits.rotate(picture, 0, 45f))
    }

    @Test
    fun rotatingToTheCurrentAngleIsANoOp() {
        val (_, state) = read(docx("<w:p>$inline</w:p>"))
        val p = paras(state)[0]
        assertSame(p, DocxImageEdits.rotate(p, 0, 0f))
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
    fun readingModeNeverAllowsPictureEditing() {
        assertFalse(DocxImageEdits.canEditImages(editMode = false, writable = true))
        assertFalse(DocxImageEdits.canEditImages(editMode = true, writable = false))
        assertTrue(DocxImageEdits.canEditImages(editMode = true, writable = true))
    }

    @Test
    fun everyCornerDragKeepsAspectRatio() {
        val drags = listOf(
            DocxImageResize.Corner.TOP_START to (-100f to -50f),
            DocxImageResize.Corner.TOP_END to (100f to -50f),
            DocxImageResize.Corner.BOTTOM_START to (-100f to 50f),
            DocxImageResize.Corner.BOTTOM_END to (100f to 50f)
        )
        for ((corner, d) in drags) {
            val s = DocxImageResize.drag(200f, 100f, d.first, d.second, corner, 1000f, 1000f)
            assertEquals("$corner width", 300f, s.widthDp, 0.01f)
            assertEquals("$corner height", 150f, s.heightDp, 0.01f)
            assertEquals("$corner ratio", 2f, s.widthDp / s.heightDp, 0.001f)
        }
    }

    @Test
    fun dragUsesTheDominantAxisAndTheInitialSize() {
        val vertical = DocxImageResize.drag(
            200f, 100f, 10f, 50f, DocxImageResize.Corner.BOTTOM_END, 1000f, 1000f
        )
        assertEquals(300f, vertical.widthDp, 0.01f)
        assertEquals(150f, vertical.heightDp, 0.01f)

        val tiny = DocxImageResize.drag(
            200f, 100f, 2f, 0f, DocxImageResize.Corner.BOTTOM_END, 1000f, 1000f
        )
        assertEquals(202f, tiny.widthDp, 0.01f)
        assertEquals(101f, tiny.heightDp, 0.01f)
    }

    @Test
    fun dragClampsToMinimumAndBothPageLimits() {
        val small = DocxImageResize.drag(
            200f, 100f, -190f, 0f, DocxImageResize.Corner.BOTTOM_END, 1000f, 1000f
        )
        assertEquals(DocxImageResize.MIN_DP, small.widthDp, 0.01f)
        assertEquals(DocxImageResize.MIN_DP / 2f, small.heightDp, 0.01f)

        // The height limit wins even when the width has room.
        val capped = DocxImageResize.drag(
            200f, 100f, 100f, 50f, DocxImageResize.Corner.BOTTOM_END, 300f, 100f
        )
        assertEquals(200f, capped.widthDp, 0.01f)
        assertEquals(100f, capped.heightDp, 0.01f)

        val invalid = DocxImageResize.drag(
            0f, 0f, 10f, 10f, DocxImageResize.Corner.BOTTOM_END, 1000f, 1000f
        )
        assertEquals(DocxImageResize.MIN_DP, invalid.widthDp, 0.01f)
        assertEquals(DocxImageResize.MIN_DP, invalid.heightDp, 0.01f)
    }

    @Test
    fun rotatedCornerDragFollowsThePictureAxes() {
        // A picture turned 90° clockwise: its local top-left corner sits at the visual top-right.
        val grown = DocxImageResize.dragRotated(
            200f, 100f, 10f, 0f, DocxImageResize.Corner.TOP_START, 90f, 1000f, 1000f
        )
        assertEquals(208f, grown.widthDp, 0.01f)
        assertEquals(104f, grown.heightDp, 0.01f)

        // At 45° a sideways drag is mostly perpendicular to that corner's diagonal: a small shrink.
        val tilted = DocxImageResize.dragRotated(
            200f, 100f, 10f, 0f, DocxImageResize.Corner.TOP_START, 45f, 1000f, 1000f
        )
        assertEquals(194.34f, tilted.widthDp, 0.05f)
        assertEquals(97.17f, tilted.heightDp, 0.05f)

        // Unrotated drags keep the old dominant-axis behaviour exactly.
        val flat = DocxImageResize.dragRotated(
            200f, 100f, 10f, 50f, DocxImageResize.Corner.BOTTOM_END, 0f, 1000f, 1000f
        )
        assertEquals(300f, flat.widthDp, 0.01f)
        assertEquals(150f, flat.heightDp, 0.01f)
    }

    @Test
    fun leftHandlePreviewKeepsTheOppositeEdgeStill() {
        val start = DocxImageResize.Corner.BOTTOM_START
        assertEquals(50f, DocxImageResize.previewOffset(200f, 100f, 150f, 75f, start).first, 0.01f)
        assertEquals(
            25f,
            DocxImageResize.previewOffset(200f, 100f, 150f, 75f, start, centerAligned = true).first,
            0.01f
        )
        assertEquals(
            0f,
            DocxImageResize.previewOffset(200f, 100f, 150f, 75f, start, endAligned = true).first,
            0.01f
        )
        assertEquals(0f, DocxImageResize.previewOffset(200f, 100f, 150f, 75f, start).second, 0.01f)
    }

    @Test
    fun rotatedPreviewKeepsTheOppositeCornerFixed() {
        // 200x100 -> 150x75, left-bottom handle, picture already turned 90° clockwise.
        val offset = DocxImageResize.previewOffset(
            startWidthDp = 200f,
            startHeightDp = 100f,
            widthDp = 150f,
            heightDp = 75f,
            corner = DocxImageResize.Corner.BOTTOM_START,
            rotationDeg = 90f
        )
        assertEquals(37.5f, offset.first, 0.01f)
        assertEquals(37.5f, offset.second, 0.01f)
    }

    @Test
    fun rotationAnglesNormalizeAndAccumulateWithoutWrapJumps() {
        assertEquals(0f, DocxImageRotation.normalize(360f), 0.001f)
        assertEquals(350f, DocxImageRotation.normalize(-10f), 0.001f)
        assertEquals(47f, DocxImageRotation.normalize(407f), 0.001f)
        assertEquals(0f, DocxImageRotation.normalize(Float.NaN), 0.001f)

        assertEquals(20f, DocxImageRotation.delta(170f, -170f), 0.001f)
        assertEquals(-20f, DocxImageRotation.delta(-170f, 170f), 0.001f)
        assertEquals(180f, DocxImageRotation.delta(0f, 180f), 0.001f)

        val session = DocxImageRotation.Session(startDegrees = 350f, pointerAngle = 10f)
        assertEquals(355f, session.moveTo(15f), 0.001f)
        assertEquals(5f, session.moveTo(25f), 0.001f)
        assertEquals(355f, session.moveTo(15f), 0.001f)
    }

    @Test
    fun rotationHandleAndRotatedBoundsFollowThePicture() {
        val image = DocxImageOverlay.Bounds(100f, 100f, 300f, 200f)
        val unrotated = DocxImageOverlay.rotationHandle(image, 0f, size = 40f, gap = 10f)
        assertEquals(180f, unrotated.left, 0.01f)
        assertEquals(50f, unrotated.top, 0.01f)

        val turned = DocxImageOverlay.rotationHandle(image, 90f, size = 40f, gap = 10f)
        assertEquals(260f, turned.left, 0.01f)
        assertEquals(130f, turned.top, 0.01f)

        val visual = DocxImageOverlay.visualBounds(image, 90f)
        assertEquals(100f, visual.width, 0.01f)
        assertEquals(200f, visual.height, 0.01f)
        assertEquals(200f, (visual.left + visual.right) / 2f, 0.01f)
        assertEquals(150f, (visual.top + visual.bottom) / 2f, 0.01f)
    }

    @Test
    fun smartToolbarPrefersBelowThenAboveThenClamps() {
        val viewport = DocxImageOverlay.Bounds(0f, 0f, 1000f, 1000f)
        val image = DocxImageOverlay.Bounds(400f, 400f, 600f, 500f)
        val below = DocxImageOverlay.toolbar(image, 300f, 50f, viewport)!!
        assertEquals(350f, below.left, 0.01f)
        assertEquals(508f, below.top, 0.01f)
        assertFalse(below.above)

        // The editor reserves room for the corner handles that overhang the picture box.
        val reserved = DocxImageOverlay.toolbar(image, 300f, 50f, viewport, belowReserve = 20f)!!
        assertEquals(528f, reserved.top, 0.01f)

        val nearBottom = DocxImageOverlay.Bounds(400f, 900f, 600f, 1000f)
        val above = DocxImageOverlay.toolbar(nearBottom, 300f, 50f, viewport)!!
        assertTrue(above.top + 50f <= nearBottom.top)
        assertTrue(above.above)

        // A rotation grip sitting where the toolbar would go pushes it to the other side.
        val withGrip = DocxImageOverlay.toolbar(
            image, 300f, 50f, viewport,
            handle = DocxImageOverlay.Bounds(450f, 510f, 550f, 560f)
        )!!
        assertTrue(withGrip.above)

        val nearLeft = DocxImageOverlay.Bounds(-100f, 400f, 100f, 500f)
        val clamped = DocxImageOverlay.toolbar(nearLeft, 300f, 50f, viewport)!!
        assertEquals(0f, clamped.left, 0.01f)

        assertNull(DocxImageOverlay.toolbar(DocxImageOverlay.Bounds(0f, 2000f, 100f, 2100f), 300f, 50f, viewport))
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
