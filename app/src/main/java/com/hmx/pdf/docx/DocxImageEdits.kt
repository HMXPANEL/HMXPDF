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
 * Pure corner-drag geometry, in display dp, shared by the editor and its tests. The drag is always
 * measured from the size at gesture start, so a long drag never accumulates per-frame rounding.
 * Corner drags keep the picture's aspect ratio; the dominant axis decides the scale, which makes a
 * mostly-vertical drag on a wide picture behave like Word instead of doing nothing.
 */
object DocxImageResize {

    /** The smallest picture the corner handles will produce. */
    const val MIN_DP = 48f

    enum class Corner(val startsLeft: Boolean, val startsTop: Boolean) {
        TOP_START(true, true),
        TOP_END(false, true),
        BOTTOM_START(true, false),
        BOTTOM_END(false, false)
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
     * Corner drag for a picture drawn at [rotationDeg]. [dxDp] and [dyDp] are pointer movement in
     * screen axes; they are mapped into the picture's own axes and projected onto the dragged
     * diagonal, so the opposite corner stays fixed and the picture grows outward at any angle.
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
        val signX = if (corner.startsLeft) -1f else 1f
        val signY = if (corner.startsTop) -1f else 1f
        val sx = widthDp / startWidthDp
        val sy = heightDp / startHeightDp
        // Opposite corner relative to the centre, in the picture's own axes.
        val qx = -signX * startWidthDp / 2f
        val qy = -signY * startHeightDp / 2f
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

/**
 * Angle math for free rotation, in degrees, clockwise on screen (y grows down). Every gesture is
 * accumulated from shortest signed deltas, so crossing the ±180° seam never flips the picture.
 */
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

    /** Pointer angle around a centre; 0° points right, 90° points down. */
    fun pointerAngle(centerX: Float, centerY: Float, x: Float, y: Float): Float =
        Math.toDegrees(kotlin.math.atan2(y - centerY, x - centerX).toDouble()).toFloat()

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

    /** Mutable gesture accumulator: one instance per press, never stored in the document. */
    class Session(startDegrees: Float, pointerAngle: Float) {
        private var last = pointerAngle
        var degrees: Float = DocxImageRotation.normalize(startDegrees)
            private set

        fun moveTo(pointerAngle: Float): Float {
            degrees = DocxImageRotation.normalize(
                degrees + DocxImageRotation.delta(last, pointerAngle)
            )
            last = pointerAngle
            return degrees
        }
    }
}

/**
 * Placement geometry for the floating picture chrome, in one coordinate space (the editor root).
 * The toolbar prefers the space below the picture; above is only used when the rotation grip can
 * keep its own reserved band. Everything is clamped to the visible viewport.
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
        belowReserve: Float = 0f,
        handleReserve: Float = 52f,
        handle: Bounds? = null
    ): Placement? {
        if (toolbarWidth <= 0f || toolbarHeight <= 0f || viewport.width <= 0f || viewport.height <= 0f) return null
        if (!image.intersects(viewport)) return null
        // Corner handles reach past the picture box; keep the toolbar clear of them.
        val below = image.bottom + gap + belowReserve
        val above = image.top - gap - toolbarHeight - handleReserve
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
        fun overlaps(p: Placement): Boolean = handle != null &&
            p.left < handle.right && p.left + toolbarWidth > handle.left &&
            p.top < handle.bottom && p.top + toolbarHeight > handle.top
        val belowPlacement = at(below)
        val abovePlacement = at(above)
        val belowOk = fitsBelow && !overlaps(belowPlacement)
        val aboveOk = fitsAbove && !overlaps(abovePlacement)
        return when {
            belowOk -> belowPlacement
            aboveOk -> abovePlacement
            fitsBelow -> belowPlacement
            fitsAbove -> abovePlacement
            (viewport.bottom - image.bottom) >= (image.top - viewport.top) -> belowPlacement
            else -> abovePlacement
        }
    }

    /**
     * The rotation grip's top-left, attached to the picture's top-centre and carried around by
     * [degrees]. The caller clamps it to the viewport so it can never be clipped.
     */
    fun rotationHandle(image: Bounds, degrees: Float, size: Float, gap: Float = 10f): Placement {
        val (x, y) = DocxImageRotation.rotate(0f, -(image.height / 2f + gap + size / 2f), degrees)
        val cx = (image.left + image.right) / 2f
        val cy = (image.top + image.bottom) / 2f
        return Placement(cx + x - size / 2f, cy + y - size / 2f, false)
    }
}
