package com.hmx.pdf.docx

/** What an object run is, for drawing it: a picture, a text box's content, or a page break. */
class ObjInfo(
    /** Relationship id of the picture. */
    val imageRid: String?,
    /** Size in points (0 when unknown). */
    val widthPt: Float,
    val heightPt: Float,
    val textBox: List<DocBlock>,
    val pageBreak: Boolean,
    /** A floating picture's horizontal alignment ("left", "center", "right"), when it gives one. */
    val hAlign: String? = null,
    /** Clockwise rotation in degrees, normalized to [0, 360). */
    val rotationDeg: Float = 0f,
    /** Inline/anchor and wrap metadata, preserved even when the editor approximates it. */
    val layout: DocxPictureLayout = DocxPictureLayout()
)

object DocxObjects {
    private val cache = HashMap<String, ObjInfo>()

    fun info(obj: String): ObjInfo = synchronized(cache) { cache[obj] } ?: parse(obj).also {
        synchronized(cache) { if (cache.size > 2000) cache.clear(); cache[obj] = it }
    }

    fun isPageBreak(obj: String?): Boolean =
        obj != null && obj.contains("br") && (obj.contains("type=\"page\"") || obj.contains("type='page'"))

    private fun parse(obj: String): ObjInfo {
        val root = XmlScan.scan(obj)
        val blip = root.descendant("blip")
        var rid = blip?.attr(obj, "embed")
        var w = 0f
        var h = 0f
        var rotation = root.descendant("xfrm")?.attr(obj, "rot")?.toFloatOrNull()?.div(60000f) ?: 0f
        root.descendant("extent")?.let { e ->
            w = (e.attr(obj, "cx")?.toFloatOrNull() ?: 0f) / 12700f
            h = (e.attr(obj, "cy")?.toFloatOrNull() ?: 0f) / 12700f
        }
        var vmlStyle: String? = null
        if (rid == null) {
            // Legacy VML picture: <v:imagedata r:id> inside a shape sized by its CSS style.
            root.descendant("imagedata")?.let { img ->
                rid = img.attr(obj, "id")
                val style = generateSequence(img.parent) { it.parent }.firstNotNullOfOrNull { it.attr(obj, "style") } ?: ""
                vmlStyle = style
                w = cssPt(style, "width")
                h = cssPt(style, "height")
                rotation = cssRotation(style)
            }
        }
        val txbx = root.descendant("txbxContent")
        val text = if (txbx != null) DocxReader.Ctx(obj).container(txbx.children, readOnly = true) else emptyList()

        val anchor = root.descendant("anchor")
        val style = vmlStyle ?: ""
        val vmlFloating = style.isNotEmpty() && (
            cssValue(style, "position")?.equals("absolute", ignoreCase = true) == true ||
                cssValue(style, "mso-position-horizontal") != null ||
                cssValue(style, "mso-position-vertical") != null
            )
        var hAlign = root.descendant("positionH")?.child("align")?.let { XmlScan.unescape(it.inner(obj)).trim() }
        if (hAlign == null) hAlign = cssValue(style, "mso-position-horizontal")
        val wrap = when {
            anchor?.child("wrapTopAndBottom") != null -> DocxWrapType.TOP_AND_BOTTOM
            anchor?.child("wrapSquare") != null -> DocxWrapType.SQUARE
            anchor?.child("wrapTight") != null -> DocxWrapType.TIGHT
            anchor?.child("wrapThrough") != null -> DocxWrapType.THROUGH
            anchor?.child("wrapNone") != null -> DocxWrapType.NONE
            vmlFloating -> when (cssValue(style, "mso-wrap-style")?.lowercase()) {
                "tight" -> DocxWrapType.TIGHT
                "through" -> DocxWrapType.THROUGH
                "top-and-bottom" -> DocxWrapType.TOP_AND_BOTTOM
                "none" -> DocxWrapType.NONE
                else -> DocxWrapType.SQUARE
            }
            else -> DocxWrapType.NONE
        }
        val effect = root.descendant("effectExtent")?.let { e ->
            DocxEffectExtent(
                leftPt = emuPt(e.attr(obj, "l")),
                topPt = emuPt(e.attr(obj, "t")),
                rightPt = emuPt(e.attr(obj, "r")),
                bottomPt = emuPt(e.attr(obj, "b"))
            )
        } ?: DocxEffectExtent()
        val layout = DocxPictureLayout(
            inline = anchor == null && !vmlFloating,
            wrap = wrap,
            behindDoc = anchor?.attr(obj, "behindDoc") == "1" || anchor?.attr(obj, "behindDoc") == "true",
            allowOverlap = anchor?.attr(obj, "allowOverlap") != "0",
            layoutInCell = anchor?.attr(obj, "layoutInCell") != "0",
            effect = effect
        )
        return ObjInfo(rid, w, h, text, isPageBreak(obj), hAlign, DocxImageRotation.normalize(rotation), layout)
    }

    private fun emuPt(v: String?): Float = (v?.toFloatOrNull() ?: 0f) / 12700f

    private fun cssValue(style: String, key: String): String? =
        Regex("(?:^|;)\\s*${Regex.escape(key)}\\s*:\\s*([^;]+)").find(style)?.groupValues?.get(1)?.trim()

    private fun cssPt(style: String, key: String): Float {
        val m = Regex("(?:^|;)\\s*$key\\s*:\\s*([0-9.]+)\\s*(pt|px|in|cm|mm)?").find(style) ?: return 0f
        val v = m.groupValues[1].toFloatOrNull() ?: return 0f
        return when (m.groupValues[2]) {
            "px" -> v * 0.75f
            "in" -> v * 72f
            "cm" -> v * 28.3465f
            "mm" -> v * 2.83465f
            else -> v
        }
    }

    /** VML stores rotation in the shape's CSS style, in clockwise degrees. */
    private fun cssRotation(style: String): Float {
        val m = Regex("(?:^|;)\\s*rotation\\s*:\\s*(-?[0-9.]+)").find(style) ?: return 0f
        return m.groupValues[1].toFloatOrNull() ?: 0f
    }
}
