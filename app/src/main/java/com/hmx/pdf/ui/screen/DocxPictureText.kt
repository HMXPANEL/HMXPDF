package com.hmx.pdf.ui.screen

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.sp
import com.hmx.pdf.docx.DocxImageResize
import com.hmx.pdf.docx.ObjInfo

/** The size a picture is drawn at, and the caps its corner handles may reach. */
internal data class PictureBox(
    val widthDp: Float,
    val heightDp: Float,
    val maxWidthDp: Float,
    val maxHeightDp: Float
)

/**
 * The one sizing rule shared by the editor, its hit testing and the PDF exporter: keep the
 * picture's aspect ratio, cap it to the text column and the page, and never shrink below 12 dp.
 */
internal fun pictureBox(info: ObjInfo, scale: Float, maxWidth: Float, maxHeight: Float): PictureBox {
    val ratio = if (info.widthPt > 0f && info.heightPt > 0f) info.heightPt / info.widthPt else 0.6f
    val safeRatio = ratio.coerceIn(0.05f, 20f)
    val widthCap = maxWidth.takeIf { it.isFinite() }?.coerceAtLeast(12f) ?: 12f
    val heightCap = maxHeight.takeIf { it.isFinite() }?.coerceAtLeast(12f) ?: 12f
    val maxW = widthCap.coerceAtMost(heightCap / safeRatio).coerceAtLeast(12f)
    val want = if (info.widthPt > 0f) info.widthPt * scale else maxW
    val w = want.coerceAtMost(maxW).coerceAtLeast(12f)
    return PictureBox(w, w * safeRatio, maxW, heightCap)
}

/**
 * Reserves each inline picture's slot in the text. The picture is still one U+FFFC (shown as a
 * zero-width space), so caret, selection and search offsets stay one-to-one with the model; the
 * span makes that character as tall and as wide as the picture's visual box.
 */
internal fun withPictureSlots(
    annotated: AnnotatedString,
    slots: Map<Int, DocxImageResize.Slot>,
    fontScale: Float
): AnnotatedString {
    if (slots.isEmpty()) return annotated
    val fs = fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    val b = AnnotatedString.Builder(annotated)
    for ((index, slot) in slots) {
        if (index !in 0 until annotated.length) continue
        b.addStyle(
            SpanStyle(
                fontSize = (slot.heightDp / fs).coerceAtLeast(1f).sp,
                letterSpacing = (slot.widthDp / fs).sp
            ),
            index,
            index + 1
        )
    }
    return b.toAnnotatedString()
}

/**
 * Top-left of an inline picture slot in the text layout, in pixels. [getHorizontalPosition] with
 * the primary direction gives the caret at the object character; in an RTL line that caret is the
 * picture's right edge, so the slot starts one width to its left.
 */
internal fun inlineSlotOffset(
    layout: TextLayoutResult,
    index: Int,
    slot: DocxImageResize.Slot,
    rtl: Boolean,
    density: Float
): Pair<Float, Float> {
    val length = layout.layoutInput.text.length
    val safe = index.coerceIn(0, length)
    val line = layout.getLineForOffset(safe)
    val lineRtl = layout.getParagraphDirection(line) == ResolvedTextDirection.Rtl
    val x = layout.getHorizontalPosition(safe, usePrimaryDirection = true)
    val left = if (rtl || lineRtl) x - slot.widthDp * density else x
    return left to layout.getLineTop(line)
}
