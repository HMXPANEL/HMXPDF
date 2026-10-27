package com.hmx.pdf.docx

/** How a floating picture asks text to wrap around it. */
enum class DocxWrapType { NONE, SQUARE, TIGHT, THROUGH, TOP_AND_BOTTOM }

/**
 * How the editor can lay a picture out. Inline pictures sit in the paragraph's text line; a
 * top-and-bottom floating picture becomes a block. Every other floating mode is preserved in the
 * XML but approximated as a block, because text cannot yet flow beside a picture.
 */
enum class DocxPictureFlow { INLINE, BLOCK, FLOATING_BLOCK }

/** DrawingML effect extents (shadow, glow) in points, kept so the XML round-trips unchanged. */
data class DocxEffectExtent(
    val leftPt: Float = 0f,
    val topPt: Float = 0f,
    val rightPt: Float = 0f,
    val bottomPt: Float = 0f
)

/** Anchor and wrap metadata of one picture, parsed from its verbatim run. */
data class DocxPictureLayout(
    val inline: Boolean = true,
    val wrap: DocxWrapType = DocxWrapType.NONE,
    val behindDoc: Boolean = false,
    val allowOverlap: Boolean = true,
    val layoutInCell: Boolean = true,
    val effect: DocxEffectExtent = DocxEffectExtent()
) {
    val flow: DocxPictureFlow
        get() = when {
            inline -> DocxPictureFlow.INLINE
            wrap == DocxWrapType.TOP_AND_BOTTOM -> DocxPictureFlow.BLOCK
            else -> DocxPictureFlow.FLOATING_BLOCK
        }

    /** True when the file asks for a wrap this editor only approximates as a block. */
    val approximate: Boolean get() = flow == DocxPictureFlow.FLOATING_BLOCK
}

/** A page break found by the measured layout, so pictures can reflow the editor's pages. */
sealed interface DocxPageKey {
    data class Para(val id: Long) : DocxPageKey
    data class Row(val tableId: Long, val rowIndex: Int) : DocxPageKey
}
