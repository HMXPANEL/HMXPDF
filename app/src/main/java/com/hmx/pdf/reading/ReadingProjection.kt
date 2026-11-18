package com.hmx.pdf.reading

import com.hmx.pdf.docx.DocBlock
import com.hmx.pdf.docx.DocPara
import com.hmx.pdf.docx.OBJ_CHAR
import com.hmx.pdf.docx.forEachPara

/** One readable paragraph, in document order. [paraId] is the stable id the highlight uses. */
data class ReadingSegment(val paraId: Long, val text: String)

/**
 * Phase 4.5 — the reading projection.
 *
 * This is a *projection*, not an edit: the document is never mutated and no run formatting is
 * consulted. Paragraphs are read through [forEachPara], so body text, table cells and list items
 * come out in reading order. Declared review policy: pending deletions (`w:del`) are not read,
 * pending insertions are; object runs (pictures, note marks, comment anchors, page breaks) are
 * silent. Blank paragraphs are skipped, and invisible markers/fields are already excluded from
 * [DocPara.text] by the reader.
 */
object ReadingProjection {

    /** Speech is cut at sentence-ish boundaries so pause/next reacts quickly and no engine gets a book. */
    const val MAX_CHUNK = 320

    fun segments(blocks: List<DocBlock>, includeDeleted: Boolean = false): List<ReadingSegment> {
        val out = ArrayList<ReadingSegment>()
        forEachPara(blocks) { p ->
            val text = readableText(p, includeDeleted)
            if (text.isNotBlank()) out.add(ReadingSegment(p.id, text))
        }
        return out
    }

    /** Visible, speakable text of one paragraph (object characters and policy-excluded revisions removed). */
    fun readableText(p: DocPara, includeDeleted: Boolean = false): String {
        val sb = StringBuilder(p.text.length)
        for (i in p.text.indices) {
            val c = p.text[i]
            if (c == OBJ_CHAR) continue
            if (!includeDeleted && p.fmts.getOrNull(i)?.rev?.ins == false) continue
            sb.append(c)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /** Splits segments into chunks of at most [MAX_CHUNK] characters (sentence-first, then word). */
    fun chunks(segments: List<ReadingSegment>): List<ReadingChunkRef> {
        val out = ArrayList<ReadingChunkRef>()
        for ((index, segment) in segments.withIndex()) {
            var at = 0
            while (at < segment.text.length) {
                val end = chunkEnd(segment.text, at)
                out.add(ReadingChunkRef(index, segment.paraId, at, segment.text.substring(at, end)))
                at = end
            }
        }
        return out
    }

    private fun chunkEnd(text: String, start: Int): Int {
        val hard = (start + MAX_CHUNK).coerceAtMost(text.length)
        if (hard == text.length) return hard
        // Prefer the last sentence end in the window; otherwise the last space.
        var best = -1
        for (i in start until hard) {
            val c = text[i]
            val followedBySpace = i + 1 < text.length && text[i + 1].isWhitespace()
            if ((c == '.' || c == '!' || c == '?' || c == ';') && followedBySpace) best = i + 1
        }
        if (best > start) return best
        for (i in hard downTo start + 1) if (text[i - 1].isWhitespace()) return i
        return hard
    }
}

/** A chunk bound to the paragraph it belongs to (stable for the highlight, independent of offsets). */
data class ReadingChunkRef(val segment: Int, val paraId: Long, val offset: Int, val text: String)
