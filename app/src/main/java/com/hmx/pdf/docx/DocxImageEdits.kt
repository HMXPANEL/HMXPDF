package com.hmx.pdf.docx

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Pure image-run operations on [DocPara]s. A picture is a whole run kept in [CharFmt.obj] and
 * stands as one [OBJ_CHAR] in the paragraph text (see [DocxModel]). These functions edit only that
 * run and the paragraph around it. Relationships, media and content types are never touched:
 * removing one picture cannot break another picture that still points at the same media file.
 */
object DocxImageEdits {

    /**
     * The single mode gate for picture editing. A writable package is not enough: reading mode
     * must never select a picture, change the image clipboard, or mutate the document.
     */
    fun canEditImages(editMode: Boolean, writable: Boolean): Boolean = editMode && writable

    fun isImage(f: CharFmt?): Boolean = f?.obj?.let { DocxObjects.info(it).imageRid } != null

    fun isImage(p: DocPara, run: Int): Boolean = isImage(p.fmts.getOrNull(run))

    fun imageRun(p: DocPara, run: Int): CharFmt? = p.fmts.getOrNull(run)?.takeIf(::isImage)

    /** Width and height in points, or null when the drawing gives no usable size. */
    fun size(p: DocPara, run: Int): Pair<Float, Float>? {
        val info = imageRun(p, run)?.obj?.let { DocxObjects.info(it) } ?: return null
        if (info.widthPt <= 0f || info.heightPt <= 0f) return null
        return info.widthPt to info.heightPt
    }

    /** Removes the picture run at [run]; the paragraph itself stays, as Word leaves it. */
    fun remove(p: DocPara, run: Int): DocPara? {
        if (!isImage(p, run)) return null
        val text = p.text.removeRange(run, run + 1)
        val fmts = p.fmts.toMutableList().also { it.removeAt(run) }
        val marks = p.marks.map { if (it > run) it - 1 else it }
        return p.edited(text = text, fmts = fmts, marks = marks)
    }

    /** Inserts [f] as a picture at [at] (clamped to the paragraph). */
    fun insert(p: DocPara, at: Int, f: CharFmt): DocPara? {
        if (!isImage(f)) return null
        val i = at.coerceIn(0, p.text.length)
        val text = p.text.substring(0, i) + OBJ_CHAR + p.text.substring(i)
        val fmts = p.fmts.toMutableList().also { it.add(i, f) }
        val marks = p.marks.map { if (it >= i) it + 1 else it }
        return p.edited(text = text, fmts = fmts, marks = marks)
    }

    /**
     * Writes a new size into the picture's OOXML. Both `wp:extent` and the picture transform's
     * `a:ext` are updated; legacy VML pictures get their CSS width/height. Null when the run has
     * no size this editor can safely rewrite.
     */
    fun resize(
        p: DocPara,
        run: Int,
        widthPt: Float,
        heightPt: Float,
        maxWidthPt: Float = 2000f,
        maxHeightPt: Float = 2000f
    ): DocPara? {
        val f = imageRun(p, run) ?: return null
        if (!widthPt.isFinite() || !heightPt.isFinite() || widthPt <= 0f || heightPt <= 0f) return null
        val (w, h) = fit(widthPt, heightPt, maxWidthPt, maxHeightPt)
        val obj = rewriteSize(f.obj ?: return null, w, h) ?: return null
        if (obj == f.obj) return p
        val fmts = p.fmts.toMutableList().also { it[run] = f.copy(obj = obj) }
        return p.edited(fmts = fmts)
    }

    /** Clockwise rotation in degrees, or null when the run is not a picture. */
    fun rotation(p: DocPara, run: Int): Float? {
        val info = imageRun(p, run)?.obj?.let { DocxObjects.info(it) } ?: return null
        return DocxImageRotation.normalize(info.rotationDeg)
    }

    /**
     * Writes a clockwise rotation into the picture's own transform. DrawingML uses
     * `a:xfrm/@rot` in 1/60000 degree; legacy VML uses the shape's CSS `rotation` in degrees.
     * The extent is deliberately untouched: OOXML rotation turns the picture about its center
     * without changing the inline layout box. Null when this drawing has no transform we can
     * safely rewrite.
     */
    fun rotate(p: DocPara, run: Int, degrees: Float): DocPara? {
        val f = imageRun(p, run) ?: return null
        if (!degrees.isFinite()) return null
        val target = DocxImageRotation.normalize(degrees)
        val current = rotation(p, run) ?: return null
        if (kotlin.math.abs(DocxImageRotation.delta(current, target)) < 0.0001f) return p
        val obj = rewriteRotation(f.obj ?: return null, target) ?: return null
        if (obj == f.obj) return p
        val fmts = p.fmts.toMutableList().also { it[run] = f.copy(obj = obj) }
        return p.edited(fmts = fmts)
    }

    /** The largest picture file a low-RAM phone should pull into a document. */
    const val MAX_IMAGE_BYTES = 25 * 1024 * 1024

    /** Reads a picked picture, refusing oversized or empty streams instead of filling the heap. */
    fun readImage(input: java.io.InputStream, maxBytes: Int = MAX_IMAGE_BYTES): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            total += n
            if (total > maxBytes) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    /** An image format this editor can safely add to a DOCX package. */
    data class ImageFormat(val extension: String, val contentType: String)

    /** Detects a supported format from its magic bytes; null for anything Word may not render. */
    fun formatOf(bytes: ByteArray): ImageFormat? {
        fun at(i: Int): Int = bytes.getOrNull(i)?.toInt()?.and(0xFF) ?: -1
        fun ascii(from: Int, text: String): Boolean =
            text.indices.all { at(from + it) == text[it].code }
        return when {
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 &&
                at(4) == 0x0D && at(5) == 0x0A && at(6) == 0x1A && at(7) == 0x0A ->
                ImageFormat("png", "image/png")
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF ->
                ImageFormat("jpg", "image/jpeg")
            ascii(0, "GIF87a") || ascii(0, "GIF89a") ->
                ImageFormat("gif", "image/gif")
            at(0) == 0x42 && at(1) == 0x4D ->
                ImageFormat("bmp", "image/bmp")
            else -> null
        }
    }

    /**
     * Replaces the picture's relationship and, when a size is supplied, its extent. Everything
     * else — paragraph position, anchor, wrap, alignment and rotation — stays in the run XML.
     */
    fun replace(
        p: DocPara,
        run: Int,
        rid: String,
        widthPt: Float? = null,
        heightPt: Float? = null,
        maxWidthPt: Float = 2000f,
        maxHeightPt: Float = 2000f
    ): DocPara? {
        val f = imageRun(p, run) ?: return null
        var obj = withImageRid(f.obj ?: return null, rid) ?: return null
        if (widthPt != null && heightPt != null) {
            if (!widthPt.isFinite() || !heightPt.isFinite() || widthPt <= 0f || heightPt <= 0f) return null
            val (w, h) = fit(widthPt, heightPt, maxWidthPt, maxHeightPt)
            obj = rewriteSize(obj, w, h) ?: return null
        }
        if (obj == f.obj) return p
        val fmts = p.fmts.toMutableList().also { it[run] = f.copy(obj = obj) }
        return p.edited(fmts = fmts)
    }

    /** Points a DrawingML or VML picture at [rid] without touching any other attribute. */
    fun withImageRid(obj: String, rid: String): String? {
        if (rid.isBlank()) return null
        val root = XmlScan.scan(obj)
        val edits = ArrayList<Triple<Int, Int, String>>()
        root.descendant("blip")?.let { n ->
            val tag = obj.substring(n.start, n.startTagEnd)
            val name = when {
                XmlScan.attr(tag, "embed") != null -> "embed"
                XmlScan.attr(tag, "link") != null -> "link"
                else -> null
            }
            if (name != null) edits.add(Triple(n.start, n.startTagEnd, setLocalAttr(tag, name, rid)))
        }
        root.descendant("imagedata")?.let { n ->
            val tag = obj.substring(n.start, n.startTagEnd)
            if (XmlScan.attr(tag, "id") != null) {
                edits.add(Triple(n.start, n.startTagEnd, setLocalAttr(tag, "id", rid)))
            }
        }
        if (edits.isEmpty()) return null
        return applyEdits(obj, edits)
    }

    /**
     * The new picture's display size: the largest box with [imageWidth] × [imageHeight]'s aspect
     * ratio that fits inside the old picture box. The old box is kept when the ratios match.
     */
    fun fitReplacement(
        boxWidthPt: Float,
        boxHeightPt: Float,
        imageWidth: Int,
        imageHeight: Int
    ): Pair<Float, Float> {
        if (!boxWidthPt.isFinite() || !boxHeightPt.isFinite() || boxWidthPt <= 0f || boxHeightPt <= 0f ||
            imageWidth <= 0 || imageHeight <= 0
        ) return (boxWidthPt.takeIf { it.isFinite() && it > 0f } ?: 1f) to
            (boxHeightPt.takeIf { it.isFinite() && it > 0f } ?: 1f)
        val scale = min(boxWidthPt / imageWidth, boxHeightPt / imageHeight)
        return (imageWidth * scale).coerceAtLeast(1f) to (imageHeight * scale).coerceAtLeast(1f)
    }

    /** Scales [widthPt] × [heightPt] down until it fits both limits, keeping the aspect ratio. */
    fun fit(widthPt: Float, heightPt: Float, maxWidthPt: Float, maxHeightPt: Float = 2000f): Pair<Float, Float> {
        if (!widthPt.isFinite() || !heightPt.isFinite()) return 1f to 1f
        var w = widthPt.coerceAtLeast(1f)
        var h = heightPt.coerceAtLeast(1f)
        val mw = maxWidthPt.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: w
        val mh = maxHeightPt.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: h
        val k = min(1f, min(mw / w, mh / h))
        w *= k
        h *= k
        return w.coerceAtLeast(1f) to h.coerceAtLeast(1f)
    }

    /**
     * A pasted run needs its own drawing id: Word expects `wp:docPr/@id` to be unique in the
     * document. VML's shape id is made unique too when present.
     */
    fun withDrawingId(obj: String, id: Int): String {
        val root = XmlScan.scan(obj)
        val edits = ArrayList<Triple<Int, Int, String>>()
        root.descendant("docPr")?.let { n ->
            var tag = obj.substring(n.start, n.startTagEnd)
            tag = setAttr(tag, "id", id.toString())
            tag = setAttr(tag, "name", "Picture $id")
            edits.add(Triple(n.start, n.startTagEnd, tag))
        }
        root.descendant("shape")?.let { n ->
            if (n.attr(obj, "id") != null) {
                val tag = setAttr(obj.substring(n.start, n.startTagEnd), "id", "hmxImage$id")
                edits.add(Triple(n.start, n.startTagEnd, tag))
            }
        }
        return applyEdits(obj, edits)
    }

    // ── OOXML size rewriting ───────────────────────────────────────────────────

    private fun rewriteSize(obj: String, wPt: Float, hPt: Float): String? {
        val root = XmlScan.scan(obj)
        val cx = (wPt.toDouble() * 12700.0).roundToLong().coerceAtLeast(1L).toString()
        val cy = (hPt.toDouble() * 12700.0).roundToLong().coerceAtLeast(1L).toString()
        val edits = ArrayList<Triple<Int, Int, String>>()
        var resized = false

        root.descendant("extent")?.let { n ->
            var tag = obj.substring(n.start, n.startTagEnd)
            tag = setAttr(tag, "cx", cx)
            tag = setAttr(tag, "cy", cy)
            edits.add(Triple(n.start, n.startTagEnd, tag))
            resized = true
        }
        // The picture transform repeats the size for DrawingML. Word keeps the two in sync.
        root.descendant("xfrm")?.child("ext")?.let { n ->
            var tag = obj.substring(n.start, n.startTagEnd)
            tag = setAttr(tag, "cx", cx)
            tag = setAttr(tag, "cy", cy)
            edits.add(Triple(n.start, n.startTagEnd, tag))
        }
        // Legacy VML picture: the size is in the shape's CSS style.
        root.descendant("imagedata")?.let { img ->
            val shape = generateSequence(img.parent) { it.parent }
                .firstOrNull { it.attr(obj, "style") != null }
            if (shape != null) {
                val style = shape.attr(obj, "style") ?: ""
                val newStyle = setStyleSize(style, wPt, hPt)
                val tag = setAttr(
                    obj.substring(shape.start, shape.startTagEnd),
                    "style",
                    XmlScan.escape(newStyle)
                )
                edits.add(Triple(shape.start, shape.startTagEnd, tag))
                resized = true
            }
        }
        if (!resized || edits.isEmpty()) return null
        return applyEdits(obj, edits)
    }

    // ── OOXML rotation rewriting ───────────────────────────────────────────────

    private fun rewriteRotation(obj: String, degrees: Float): String? {
        val root = XmlScan.scan(obj)
        val edits = ArrayList<Triple<Int, Int, String>>()
        var rotated = false

        // DrawingML: the picture transform carries the angle in 1/60000 degree.
        root.descendant("xfrm")?.let { n ->
            val tag = setAttr(obj.substring(n.start, n.startTagEnd), "rot", ooxmlRot(degrees))
            edits.add(Triple(n.start, n.startTagEnd, tag))
            rotated = true
        }
        // Legacy VML: the shape's CSS style carries clockwise degrees.
        if (!rotated) {
            root.descendant("imagedata")?.let { img ->
                val shape = generateSequence(img.parent) { it.parent }
                    .firstOrNull { it.attr(obj, "style") != null }
                if (shape != null) {
                    val style = shape.attr(obj, "style") ?: ""
                    val newStyle = setStyleRotation(style, degrees)
                    val tag = setAttr(
                        obj.substring(shape.start, shape.startTagEnd),
                        "style",
                        XmlScan.escape(newStyle)
                    )
                    edits.add(Triple(shape.start, shape.startTagEnd, tag))
                    rotated = true
                }
            }
        }
        if (!rotated || edits.isEmpty()) return null
        return applyEdits(obj, edits)
    }

    private fun ooxmlRot(degrees: Float): String {
        val units = (degrees.toDouble() * 60000.0).roundToLong() % 21600000L
        return (if (units < 0L) units + 21600000L else units).toString()
    }

    /** Replaces an attribute by local name (`r:embed` matches `embed`), adding it when absent. */
    private fun setLocalAttr(tag: String, name: String, value: String): String {
        val re = Regex("""(\s(?:[A-Za-z0-9_]+:)?${Regex.escape(name)}\s*=\s*)(["'])[^"']*\2""")
        if (re.containsMatchIn(tag)) {
            return re.replace(tag) { m -> m.groupValues[1] + m.groupValues[2] + value + m.groupValues[2] }
        }
        val close = if (tag.endsWith("/>")) tag.length - 2 else tag.length - 1
        if (close < 0) return tag
        return tag.substring(0, close).trimEnd() + " $name=\"$value\"" + tag.substring(close)
    }

    /** Replaces an attribute in one start tag, adding it when absent. */
    private fun setAttr(tag: String, name: String, value: String): String {
        // Require whitespace before the name so r:id is never mistaken for id.
        val re = Regex("""(\s${Regex.escape(name)}\s*=\s*)(["'])[^"']*\2""")
        if (re.containsMatchIn(tag)) {
            return re.replace(tag) { m -> m.groupValues[1] + m.groupValues[2] + value + m.groupValues[2] }
        }
        val close = if (tag.endsWith("/>")) tag.length - 2 else tag.length - 1
        if (close < 0) return tag
        return tag.substring(0, close).trimEnd() + " $name=\"$value\"" + tag.substring(close)
    }

    private fun setStyleSize(style: String, wPt: Float, hPt: Float): String {
        var s = style
        fun set(key: String, v: Float) {
            val re = Regex("""(^|;)\s*${Regex.escape(key)}\s*:\s*[^;]*""")
            val value = pt(v)
            s = if (re.containsMatchIn(s)) re.replace(s) { m -> "${m.groupValues[1]}$key:${value}pt" }
            else s.trimEnd(';') + ";$key:${value}pt"
        }
        set("width", wPt)
        set("height", hPt)
        return s
    }

    private fun setStyleRotation(style: String, degrees: Float): String {
        val re = Regex("""(^|;)\s*rotation\s*:\s*[^;]*""")
        val value = pt(degrees)
        return if (re.containsMatchIn(style)) re.replace(style) { m -> "${m.groupValues[1]}rotation:$value" }
        else style.trimEnd(';') + ";rotation:$value"
    }

    private fun pt(v: Float): String =
        String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    /** Applies non-overlapping replacements from the end so earlier offsets stay valid. */
    private fun applyEdits(obj: String, edits: List<Triple<Int, Int, String>>): String {
        if (edits.isEmpty()) return obj
        val sb = StringBuilder(obj)
        var lastStart = Int.MAX_VALUE
        for ((start, end, text) in edits.sortedByDescending { it.first }) {
            if (end > lastStart) continue
            sb.replace(start, end, text)
            lastStart = start
        }
        return sb.toString()
    }
}

/**
 * Pure resize-handle geometry, in display dp, shared by the editor and its tests. The drag is
 * always measured from the size at gesture start, so a long drag never accumulates per-frame
 * rounding. Corner handles keep the picture's aspect ratio; edge handles change one axis. For
 * corners the dominant axis decides the scale, which makes a mostly-vertical drag on a wide
 * picture behave like Word instead of doing nothing.
 */
object DocxImageResize {

    /** The smallest picture width, or uniform-scale width, the handles will produce. */
    const val MIN_DP = 48f

    /**
     * A resize handle. Corners change both axes and keep the aspect ratio; the four edge
     * midpoints change only their own axis. [horizontal]/[vertical] say which axes the handle
     * drives, so the same drag and preview math serves all eight.
     */
    enum class Corner(
        val startsLeft: Boolean,
        val startsTop: Boolean,
        val horizontal: Boolean = true,
        val vertical: Boolean = true
    ) {
        TOP_START(true, true),
        TOP_END(false, true),
        BOTTOM_START(true, false),
        BOTTOM_END(false, false),
        TOP(true, true, horizontal = false),
        BOTTOM(true, false, horizontal = false),
        LEFT(true, true, vertical = false),
        RIGHT(false, true, vertical = false)
    }

    data class Size(val widthDp: Float, val heightDp: Float)

    /** A live resize preview: the size the drag would commit and the corner that drives it. */
    data class Preview(val size: Size, val corner: Corner)

    /**
     * The space a picture takes in its paragraph: the union of the committed and preview visual
     * boxes, plus where the unrotated picture is drawn inside it. The union is what keeps a rotated
     * or dragged picture from spilling over the text around it; the draw offset keeps the preview
     * anchored to the opposite corner without moving the slot itself.
     */
    data class Slot(
        val widthDp: Float,
        val heightDp: Float,
        val drawLeftDp: Float,
        val drawTopDp: Float
    )

    fun slot(
        startWidthDp: Float,
        startHeightDp: Float,
        shownWidthDp: Float = startWidthDp,
        shownHeightDp: Float = startHeightDp,
        rotationDeg: Float = 0f,
        previewX: Float = 0f,
        previewY: Float = 0f
    ): Slot {
        if (!startWidthDp.isFinite() || !startHeightDp.isFinite() || startWidthDp <= 0f || startHeightDp <= 0f) {
            val w = shownWidthDp.takeIf { it.isFinite() && it > 0f } ?: 1f
            val h = shownHeightDp.takeIf { it.isFinite() && it > 0f } ?: 1f
            return Slot(w, h, 0f, 0f)
        }
        val w = shownWidthDp.takeIf { it.isFinite() && it > 0f } ?: startWidthDp
        val h = shownHeightDp.takeIf { it.isFinite() && it > 0f } ?: startHeightDp
        val (startW, startH) = DocxImageRotation.bounds(startWidthDp, startHeightDp, rotationDeg)
        val (shownW, shownH) = DocxImageRotation.bounds(w, h, rotationDeg)
        val startLeft = (startWidthDp - startW) / 2f
        val startTop = (startHeightDp - startH) / 2f
        val previewLeft = previewX + (w - shownW) / 2f
        val previewTop = previewY + (h - shownH) / 2f
        val left = min(startLeft, previewLeft)
        val top = min(startTop, previewTop)
        val right = max(startLeft + startW, previewLeft + shownW)
        val bottom = max(startTop + startH, previewTop + shownH)
        return Slot(
            widthDp = max(right - left, 1f),
            heightDp = max(bottom - top, 1f),
            drawLeftDp = previewX - left,
            drawTopDp = previewY - top
        )
    }

    fun drag(
        startWidthDp: Float,
        startHeightDp: Float,
        dxDp: Float,
        dyDp: Float,
        corner: Corner,
        maxWidthDp: Float,
        maxHeightDp: Float,
        minDp: Float = MIN_DP
    ): Size {
        if (startWidthDp <= 0f || startHeightDp <= 0f || !startWidthDp.isFinite() || !startHeightDp.isFinite()) {
            return Size(minDp, minDp)
        }
        if (!corner.horizontal || !corner.vertical) {
            return dragEdge(startWidthDp, startHeightDp, dxDp, dyDp, corner, maxWidthDp, maxHeightDp, minDp)
        }
        val signX = if (corner.startsLeft) -1f else 1f
        val signY = if (corner.startsTop) -1f else 1f
        val sx = (startWidthDp + signX * dxDp) / startWidthDp
        val sy = (startHeightDp + signY * dyDp) / startHeightDp
        var scale = if (kotlin.math.abs(dxDp) >= kotlin.math.abs(dyDp)) sx else sy
        if (!scale.isFinite()) scale = 1f
        val minScale = minDp / startWidthDp
        val maxScale = minOf(
            if (maxWidthDp.isFinite()) maxWidthDp / startWidthDp else Float.MAX_VALUE,
            if (maxHeightDp.isFinite()) maxHeightDp / startHeightDp else Float.MAX_VALUE
        )
        scale = scale.coerceIn(minScale, maxOf(minScale, maxScale))
        return Size(startWidthDp * scale, startHeightDp * scale)
    }

    /**
     * An edge drag changes only its own dimension; the opposite edge stays fixed. The minimum
     * height follows the same effective floor as a corner drag (the height at which a uniformly
     * scaled picture is [minDp] wide), so a small picture is never forced to jump larger.
     */
    private fun dragEdge(
        startWidthDp: Float,
        startHeightDp: Float,
        dxDp: Float,
        dyDp: Float,
        corner: Corner,
        maxWidthDp: Float,
        maxHeightDp: Float,
        minDp: Float
    ): Size {
        val signX = if (corner.startsLeft) -1f else 1f
        val signY = if (corner.startsTop) -1f else 1f
        val minW = minOf(minDp, startWidthDp).coerceAtLeast(1f)
        val minH = minOf(minDp * startHeightDp / startWidthDp, startHeightDp).coerceAtLeast(1f)
        val maxW = maxOf(minW, startWidthDp, if (maxWidthDp.isFinite()) maxWidthDp else Float.MAX_VALUE)
        val maxH = maxOf(minH, startHeightDp, if (maxHeightDp.isFinite()) maxHeightDp else Float.MAX_VALUE)
        val w = if (corner.horizontal) (startWidthDp + signX * dxDp).coerceIn(minW, maxW) else startWidthDp
        val h = if (corner.vertical) (startHeightDp + signY * dyDp).coerceIn(minH, maxH) else startHeightDp
        return Size(w, h)
    }

    /**
     * Drag for a picture drawn at [rotationDeg]. [dxDp] and [dyDp] are pointer movement in screen
     * axes; they are mapped into the picture's own axes and projected onto the dragged diagonal
     * (corners) or edge normal (edges), so the opposite corner or edge stays fixed at any angle.
     * Unrotated pictures keep the exact previous behaviour of [drag].
     */
    fun dragRotated(
        startWidthDp: Float,
        startHeightDp: Float,
        dxDp: Float,
        dyDp: Float,
        corner: Corner,
        rotationDeg: Float,
        maxWidthDp: Float,
        maxHeightDp: Float,
        minDp: Float = MIN_DP
    ): Size {
        val rotation = DocxImageRotation.normalize(rotationDeg)
        if (rotation == 0f) {
            return drag(startWidthDp, startHeightDp, dxDp, dyDp, corner, maxWidthDp, maxHeightDp, minDp)
        }
        if (startWidthDp <= 0f || startHeightDp <= 0f || !startWidthDp.isFinite() || !startHeightDp.isFinite()) {
            return Size(minDp, minDp)
        }
        val (localX, localY) = DocxImageRotation.rotate(dxDp, dyDp, -rotation)
        if (!corner.horizontal || !corner.vertical) {
            val minW = minOf(minDp, startWidthDp).coerceAtLeast(1f)
            val minH = minOf(minDp * startHeightDp / startWidthDp, startHeightDp).coerceAtLeast(1f)
            val maxW = maxOf(minW, startWidthDp, if (maxWidthDp.isFinite()) maxWidthDp else Float.MAX_VALUE)
            val maxH = maxOf(minH, startHeightDp, if (maxHeightDp.isFinite()) maxHeightDp else Float.MAX_VALUE)
            val w = if (corner.horizontal) {
                val qx = (if (corner.startsLeft) -1f else 1f) * startWidthDp / 2f
                (startWidthDp * (1f + localX / qx)).coerceIn(minW, maxW)
            } else startWidthDp
            val h = if (corner.vertical) {
                val qy = (if (corner.startsTop) -1f else 1f) * startHeightDp / 2f
                (startHeightDp * (1f + localY / qy)).coerceIn(minH, maxH)
            } else startHeightDp
            return Size(w, h)
        }
        val signX = if (corner.startsLeft) -1f else 1f
        val signY = if (corner.startsTop) -1f else 1f
        // Offset of the dragged corner from the centre, in the picture's own axes.
        val qx = signX * startWidthDp / 2f
        val qy = signY * startHeightDp / 2f
        val along = qx * qx + qy * qy
        val scale = if (along <= 0f) 1f else 1f + (localX * qx + localY * qy) / along
        val minScale = minDp / startWidthDp
        val maxScale = minOf(
            if (maxWidthDp.isFinite()) maxWidthDp / startWidthDp else Float.MAX_VALUE,
            if (maxHeightDp.isFinite()) maxHeightDp / startHeightDp else Float.MAX_VALUE
        )
        val clamped = scale.coerceIn(minScale, maxOf(minScale, maxScale))
        return Size(startWidthDp * clamped, startHeightDp * clamped)
    }

    /**
     * Preview shift that keeps the opposite corner still on screen. [rotationDeg] is the picture's
     * committed clockwise rotation; [dragRotated] already maps the pointer delta into the picture's
     * own axes. Inline pictures reflow to their paragraph alignment when the edit is committed, so
     * this is preview-only; the saved OOXML carries the new extent, not a position.
     */
    fun previewOffset(
        startWidthDp: Float,
        startHeightDp: Float,
        widthDp: Float,
        heightDp: Float,
        corner: Corner,
        rotationDeg: Float = 0f,
        centerAligned: Boolean = false,
        endAligned: Boolean = false
    ): Pair<Float, Float> {
        if (startWidthDp <= 0f || startHeightDp <= 0f || widthDp <= 0f || heightDp <= 0f) return 0f to 0f
        val rotation = DocxImageRotation.normalize(rotationDeg)
        if (rotation == 0f) {
            // Keep the unrotated preview exactly as it was before rotation existed.
            val x = if (corner.startsLeft) when {
                endAligned -> 0f
                centerAligned -> (startWidthDp - widthDp) / 2f
                else -> startWidthDp - widthDp
            } else 0f
            val y = if (corner.startsTop) startHeightDp - heightDp else 0f
            return x to y
        }
        val sx = widthDp / startWidthDp
        val sy = heightDp / startHeightDp
        // The opposite corner (corners) or opposite edge midpoint (edges), relative to the centre.
        val qx = when {
            !corner.horizontal -> 0f
            corner.startsLeft -> startWidthDp / 2f
            else -> -startWidthDp / 2f
        }
        val qy = when {
            !corner.vertical -> 0f
            corner.startsTop -> startHeightDp / 2f
            else -> -startHeightDp / 2f
        }
        val (dx, dy) = DocxImageRotation.rotate(qx - sx * qx, qy - sy * qy, rotation)
        val baseX = when {
            endAligned -> startWidthDp - widthDp
            centerAligned -> (startWidthDp - widthDp) / 2f
            else -> 0f
        }
        return dx - baseX - (widthDp - startWidthDp) / 2f to
            dy - (heightDp - startHeightDp) / 2f
    }
}

/** Angle math for picture rotation, in degrees, clockwise on screen (y grows down). */
object DocxImageRotation {

    fun normalize(degrees: Float): Float {
        if (!degrees.isFinite()) return 0f
        val r = degrees % 360f
        return if (r < 0f) r + 360f else r
    }

    /** Shortest signed turn from [from] to [to], in (-180, 180]. */
    fun delta(from: Float, to: Float): Float {
        if (!from.isFinite() || !to.isFinite()) return 0f
        var d = (to - from) % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    fun rotate(x: Float, y: Float, degrees: Float): Pair<Float, Float> {
        val r = Math.toRadians(degrees.toDouble())
        val c = kotlin.math.cos(r).toFloat()
        val s = kotlin.math.sin(r).toFloat()
        return (x * c - y * s) to (x * s + y * c)
    }

    /** Axis-aligned size around a [width]×[height] rectangle turned [degrees]. */
    fun bounds(width: Float, height: Float, degrees: Float): Pair<Float, Float> {
        val r = Math.toRadians(degrees.toDouble())
        val c = kotlin.math.abs(kotlin.math.cos(r)).toFloat()
        val s = kotlin.math.abs(kotlin.math.sin(r)).toFloat()
        return (width * c + height * s) to (width * s + height * c)
    }
}

/**
 * Placement geometry for the floating picture chrome, in one coordinate space (the editor root).
 * The toolbar prefers the space below the picture, falls back above when there is no room, and is
 * always clamped to the visible viewport.
 */
object DocxImageOverlay {

    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        fun intersects(other: Bounds): Boolean =
            right > other.left && left < other.right && bottom > other.top && top < other.bottom
    }

    data class Placement(val left: Float, val top: Float, val above: Boolean)

    /** The axis-aligned box around the picture after [degrees], sharing its centre. */
    fun visualBounds(bounds: Bounds, degrees: Float): Bounds {
        val (w, h) = DocxImageRotation.bounds(
            bounds.width,
            bounds.height,
            DocxImageRotation.normalize(degrees)
        )
        val cx = (bounds.left + bounds.right) / 2f
        val cy = (bounds.top + bounds.bottom) / 2f
        return Bounds(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    /** Toolbar top-left, or null when the picture is off-screen. */
    fun toolbar(
        image: Bounds,
        toolbarWidth: Float,
        toolbarHeight: Float,
        viewport: Bounds,
        gap: Float = 8f,
        belowReserve: Float = 0f
    ): Placement? {
        if (toolbarWidth <= 0f || toolbarHeight <= 0f || viewport.width <= 0f || viewport.height <= 0f) return null
        if (!image.intersects(viewport)) return null
        val below = image.bottom + gap + belowReserve
        val above = image.top - gap - toolbarHeight
        val fitsBelow = below + toolbarHeight <= viewport.bottom
        val fitsAbove = above >= viewport.top
        val left = ((image.left + image.right) / 2f - toolbarWidth / 2f).coerceIn(
            viewport.left,
            (viewport.right - toolbarWidth).coerceAtLeast(viewport.left)
        )
        fun at(top: Float) = Placement(
            left,
            top.coerceIn(viewport.top, (viewport.bottom - toolbarHeight).coerceAtLeast(viewport.top)),
            top + toolbarHeight <= image.top
        )
        val belowPlacement = at(below)
        val abovePlacement = at(above)
        return when {
            fitsBelow -> belowPlacement
            fitsAbove -> abovePlacement
            (viewport.bottom - image.bottom) >= (image.top - viewport.top) -> belowPlacement
            else -> abovePlacement
        }
    }
}
