package com.hmx.pdf.spell

import com.hmx.pdf.docx.DocPara
import com.hmx.pdf.docx.DocState
import com.hmx.pdf.docx.forEachPara
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Phase 4.3 — spell checking.
 *
 * The whole pipeline is deliberately offline: [SpellEngine] is implemented by the installed
 * Android text service (an on-device Binder service), and this file never touches the network.
 * Suggestions are only *annotations*: they live in the ViewModel, never in [DocState]/rPr, so
 * checking a document cannot change its formatting or mark it dirty. A replacement happens only
 * when the user taps a suggestion, and then it goes through the normal paragraph kernel (and the
 * Track Changes capture) like any other edit.
 */
interface SpellEngine {
    /** False when no suitable installed service exists (or it was refused by the offline policy). */
    val available: Boolean

    /** The language the service checks, for display (BCP-47); null when unknown. */
    val language: String?

    /**
     * Suggestions per word. A missing word means "not checked"; an empty list means "no
     * suggestions" (normally a correctly spelled word). Implementations must not use the network.
     */
    suspend fun suggest(words: List<String>): Map<String, List<String>>

    /** Releases any service session. Safe to call more than once. */
    fun close() {}
}

/** One checkable word and its offsets into [DocPara.text]. */
data class SpellToken(val word: String, val range: IntRange)

/**
 * A misspelling found by a scan. [para] is the exact immutable paragraph the range belongs to:
 * a later edit replaces that object, so an old issue can never be applied to different text.
 */
data class SpellIssue(
    val para: DocPara,
    val range: IntRange,
    val word: String,
    val suggestions: List<String>
) {
    val paraId: Long get() = para.id
}

object SpellTokens {

    /**
     * Words in [text], cross-run by construction (the caller passes the paragraph's flat text).
     * Skips object characters (pictures, note marks, comment anchors), digits and digit-adjacent
     * fragments, URLs, e-mail addresses, file paths and one-letter tokens. Hyphens and apostrophes
     * inside a word are kept, so "well-known" and "doesn't" stay one checkable token.
     */
    fun of(text: String): List<SpellToken> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<SpellToken>()
        var i = 0
        while (i < text.length) {
            if (!text[i].isLetter()) { i++; continue }
            // The whitespace-delimited chunk decides whether this is prose at all.
            var cs = i
            while (cs > 0 && !text[cs - 1].isWhitespace()) cs--
            var ce = i
            while (ce < text.length && !text[ce].isWhitespace()) ce++
            if (looksLikeReference(text.substring(cs, ce))) { i = ce; continue }

            var end = i + 1
            while (end < text.length) {
                val c = text[end]
                if (c.isLetter()) { end++; continue }
                val joiner = c == '\'' || c == '\u2019' || c == '-'
                if (joiner && end + 1 < text.length && text[end + 1].isLetter()) { end += 2; continue }
                break
            }
            val word = text.substring(i, end)
            val prev = text.getOrNull(i - 1)
            val next = text.getOrNull(end)
            val letters = word.count { it.isLetter() }
            val digitAdjacent = prev?.isDigit() == true || next?.isDigit() == true
            if (letters >= 2 && !digitAdjacent && word.length <= 64) out.add(SpellToken(word, i until end))
            i = end
        }
        return out
    }

    /** URLs, e-mail addresses and paths are not prose, so their "words" are not checked. */
    fun looksLikeReference(chunk: String): Boolean {
        if (chunk.isEmpty()) return false
        if (chunk.contains("://") || chunk.contains("www.") || chunk.contains('@')) return true
        if (chunk.length > 1 && (chunk.startsWith("/") || chunk.startsWith("\\\\"))) return true
        if (chunk.contains('\\') && !chunk.contains(' ')) return true
        return false
    }
}

/** Small LRU of checked words, so re-opening the panel does not re-ask the service. */
class SpellCache(private val maxEntries: Int = 2048) {
    private val entries = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: String): List<String>? = entries[key]

    @Synchronized
    fun put(key: String, value: List<String>) {
        entries[key] = value
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size
}

object SpellCheck {

    /** Words per service call: bounds one Binder transaction and lets cancellation land soon. */
    const val CHUNK = 64

    /**
     * Scans every editable paragraph of [state] (body and table cells; [forEachPara] walks both)
     * and returns issues in document order. Non-editable paragraphs — raw/imported content the
     * writer must leave alone — are not checked, and neither are header/footer parts in this pass.
     *
     * Cancellation is cooperative between chunks: the caller's job is checked before every service
     * call, so closing the panel or editing the document stops the scan promptly.
     */
    suspend fun collect(state: DocState, engine: SpellEngine, cache: SpellCache): List<SpellIssue> {
        if (!engine.available) return emptyList()
        val prefix = (engine.language ?: "") + "|"
        val paragraphs = ArrayList<DocPara>()
        forEachPara(state.blocks) { p -> if (p.editable) { paragraphs.add(p); } }
        if (paragraphs.isEmpty()) return emptyList()

        val tokensByPara = paragraphs.map { it to SpellTokens.of(it.text) }
        val pending = LinkedHashSet<String>()
        for ((_, tokens) in tokensByPara) {
            for (t in tokens) if (cache.get(prefix + t.word) == null) pending.add(t.word)
        }
        for (chunk in pending.toList().chunked(CHUNK)) {
            currentCoroutineContext().ensureActive()
            // Cancellation must propagate: swallowing it here would let a cancelled scan finish and
            // publish suggestions the caller already abandoned.
            val answer = try {
                engine.suggest(chunk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyMap()
            }
            for (word in chunk) {
                // A missing answer is cached as "no suggestions" to avoid asking again this session.
                val suggestions = answer[word] ?: emptyList()
                cache.put(prefix + word, suggestions)
            }
        }

        val issues = ArrayList<SpellIssue>()
        for ((para, tokens) in tokensByPara) {
            for (t in tokens) {
                val suggestions = cache.get(prefix + t.word) ?: continue
                if (suggestions.isNotEmpty()) issues.add(SpellIssue(para, t.range, t.word, suggestions))
            }
        }
        return issues
    }
}
