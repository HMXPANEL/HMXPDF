package com.hmx.pdf.docx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Anchor/wrap parsing and the geometry that keeps a turned picture inside its reserved slot. */
class DocxPictureLayoutTest {

    private val anchor = """
        <w:r><w:rPr/><w:drawing>
        <wp:anchor xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
          xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
          behindDoc="1" allowOverlap="0" layoutInCell="0">
          <wp:extent cx="914400" cy="457200"/>
          <wp:effectExtent l="12700" t="25400" r="38100" b="50800"/>
          <wp:wrapSquare wrapText="bothSides"/>
          <wp:positionH relativeFrom="margin"><wp:align>center</wp:align></wp:positionH>
          <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
            <a:blip r:embed="rIdImg"/>
          </a:graphicData></a:graphic>
        </wp:anchor></w:drawing></w:r>
    """.trimIndent()

    @Test
    fun parsesAnchoredSquareWrapAndItsFlags() {
        val info = DocxObjects.info(anchor)
        assertEquals("rIdImg", info.imageRid)
        assertEquals(72f, info.widthPt, 0.01f)
        assertEquals(36f, info.heightPt, 0.01f)
        assertEquals("center", info.hAlign)
        assertFalse(info.layout.inline)
        assertEquals(DocxWrapType.SQUARE, info.layout.wrap)
        assertEquals(DocxPictureFlow.FLOATING_BLOCK, info.layout.flow)
        assertTrue(info.layout.approximate)
        assertTrue(info.layout.behindDoc)
        assertFalse(info.layout.allowOverlap)
        assertFalse(info.layout.layoutInCell)
        assertEquals(1f, info.layout.effect.leftPt, 0.01f)
        assertEquals(2f, info.layout.effect.topPt, 0.01f)
        assertEquals(3f, info.layout.effect.rightPt, 0.01f)
        assertEquals(4f, info.layout.effect.bottomPt, 0.01f)
    }

    @Test
    fun topAndBottomWrapIsLaidOutAsABlockNotApproximated() {
        val info = DocxObjects.info(anchor.replace("<wp:wrapSquare wrapText=\"bothSides\"/>", "<wp:wrapTopAndBottom/>"))
        assertEquals(DocxWrapType.TOP_AND_BOTTOM, info.layout.wrap)
        assertEquals(DocxPictureFlow.BLOCK, info.layout.flow)
        assertFalse(info.layout.approximate)
    }

    @Test
    fun inlineDrawingStaysInline() {
        val inline = anchor
            .replace("<wp:anchor", "<wp:inline")
            .replace("</wp:anchor>", "</wp:inline>")
            .replace(" behindDoc=\"1\" allowOverlap=\"0\" layoutInCell=\"0\"", "")
        val info = DocxObjects.info(inline)
        assertTrue(info.layout.inline)
        assertEquals(DocxPictureFlow.INLINE, info.layout.flow)
        assertFalse(info.layout.approximate)
    }

    @Test
    fun parsesLegacyVmlPositionWrapAndRotation() {
        val vml = """
            <w:r><w:rPr/><w:pict>
              <v:shape xmlns:v="urn:schemas-microsoft-com:vml"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                style="position:absolute;width:72pt;height:36pt;mso-wrap-style:tight;mso-position-horizontal:right;rotation:45">
                <v:imagedata r:id="rIdVml"/>
              </v:shape>
            </w:pict></w:r>
        """.trimIndent()
        val info = DocxObjects.info(vml)
        assertEquals("rIdVml", info.imageRid)
        assertEquals(72f, info.widthPt, 0.01f)
        assertEquals(36f, info.heightPt, 0.01f)
        assertEquals(45f, info.rotationDeg, 0.01f)
        assertEquals("right", info.hAlign)
        assertFalse(info.layout.inline)
        assertEquals(DocxWrapType.TIGHT, info.layout.wrap)
        assertTrue(info.layout.approximate)
    }

    @Test
    fun rotationReservesTheAxisAlignedBoxAndCentresThePictureInIt() {
        val upright = DocxImageResize.slot(100f, 50f)
        assertEquals(100f, upright.widthDp, 0.001f)
        assertEquals(50f, upright.heightDp, 0.001f)
        assertEquals(0f, upright.drawLeftDp, 0.001f)
        assertEquals(0f, upright.drawTopDp, 0.001f)

        val quarter = DocxImageResize.slot(100f, 50f, rotationDeg = 90f)
        assertEquals(50f, quarter.widthDp, 0.001f)
        assertEquals(100f, quarter.heightDp, 0.001f)
        assertEquals(-25f, quarter.drawLeftDp, 0.001f)
        assertEquals(25f, quarter.drawTopDp, 0.001f)
    }

    @Test
    fun previewSlotContainsTheDraggedBoxAtItsOffset() {
        val preview = DocxImageResize.slot(
            startWidthDp = 100f,
            startHeightDp = 50f,
            shownWidthDp = 150f,
            shownHeightDp = 75f,
            previewX = -50f,
            previewY = -25f
        )
        assertEquals(150f, preview.widthDp, 0.001f)
        assertEquals(75f, preview.heightDp, 0.001f)
        assertEquals(0f, preview.drawLeftDp, 0.001f)
        assertEquals(0f, preview.drawTopDp, 0.001f)
        assertTrue(preview.drawLeftDp >= 0f && preview.drawTopDp >= 0f)
        assertTrue(preview.drawLeftDp + 150f <= preview.widthDp)
        assertTrue(preview.drawTopDp + 75f <= preview.heightDp)
    }
}
