package com.hmx.pdf.docx

import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Pure image-run operations on [DocPara]s. A picture is a whole run kept in [CharFmt.obj] and
 * stands as one [OBJ_CHAR] in the paragraph text (see [DocxModel]). These functions edit only that
 * run and the paragraph around it. Relationships, media and content types are never touched:
 * removing one picture cannot break another picture that still points at the same media file.
 */
object DocxImageEdits {

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
