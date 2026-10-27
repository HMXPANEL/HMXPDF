package com.hmx.pdf.ui.screen

import androidx.compose.ui.text.AnnotatedString
import com.hmx.pdf.docx.CharFmt
import com.hmx.pdf.docx.DocPara
import com.hmx.pdf.docx.DocxImageResize
import com.hmx.pdf.docx.DocxPageKey
import com.hmx.pdf.docx.DocxStyles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Inline picture slots and the measured page starts that reflow the editor after an image edit. */
class DocxPictureTextTest {

    @Test
    fun slotsReserveThePicturesSizeWithoutChangingTheText() {
        val text = AnnotatedString("A\u200BB")
        val out = withPictureSlots(
            text,
            mapOf(1 to DocxImageResize.Slot(50f, 40f, 0f, 0f)),
            fontScale = 2f
        )
        assertEquals(text.text, out.text)
        val span = out.spanStyles.single { it.start == 1 }
        assertEquals(20f, span.item.fontSize.value, 0.001f)
        assertEquals(25f, span.item.letterSpacing.value, 0.001f)
    }

    @Test
    fun missingOrOutOfRangeSlotsLeaveTheTextUntouched() {
        val text = AnnotatedString("A\u200BB")
        assertEquals(text, withPictureSlots(text, emptyMap(), 1f))
        val out = withPictureSlots(text, mapOf(9 to DocxImageResize.Slot(50f, 40f, 0f, 0f)), 1f)
        assertEquals(text.text, out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun measuredOverflowStartsANewEditorPage() {
        val styles = DocxStyles("", "", mutableMapOf(), null)
        val blocks = listOf(para(1, "one"), para(2, "two"), para(3, "three"))

        assertEquals(1, flatten(blocks, styles, splitParas = false).pages)

        val measured = flatten(
            blocks,
            styles,
            splitParas = false,
            measured = setOf(DocxPageKey.Para(2))
        )
        assertEquals(2, measured.pages)
        assertTrue(measured.items.any { it is DocItem.Gap && it.page == 1 })
    }

    private fun para(id: Long, text: String) = DocPara(
        id,
        null,
        "<w:p>",
        "",
        "",
        text,
        List(text.length) { CharFmt.Plain },
        editable = true
    )
}
