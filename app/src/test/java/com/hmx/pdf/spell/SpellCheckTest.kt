package com.hmx.pdf.spell

import com.hmx.pdf.docx.DocxFixtures
import com.hmx.pdf.docx.CharFmt
import com.hmx.pdf.docx.DocIds
import com.hmx.pdf.docx.DocPara
import com.hmx.pdf.docx.DocState
import com.hmx.pdf.docx.OBJ_CHAR
import com.hmx.pdf.docx.findPara
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4.3 core: token rules (cross-run, URLs, numbers, punctuation), the bounded cache, and the
 * scan contract (editable paragraphs only, chunked service calls, cancellation-friendly caching).
 */
class SpellCheckTest {

    private class FakeEngine(
        val dictionary: Set<String>,
        override val available: Boolean = true
    ) : SpellEngine {
        override var language: String? = "en-US"
        val calls = ArrayList<List<String>>()
        override suspend fun suggest(words: List<String>): Map<String, List<String>> {
            calls.add(words)
            return words.associateWith { w ->
                if (w.lowercase() in dictionary) emptyList() else listOf(w.reversed(), "fixed")
            }
        }
    }

    private fun doc(vararg paras: String): DocState {
        val body = paras.joinToString("") { "<w:p><w:r><w:t xml:space=\"preserve\">$it</w:t></w:r></w:p>" }
        return DocxFixtures.read(DocxFixtures.docx(body)).second
    }

    @Test
    fun tokenizerHandlesPunctuationNumbersUrlsAndJoins() {
        val text = "Visit https://example.com or mail a@b.com; 3rd abc123 don't well-known."
        val words = SpellTokens.of(text).map { it.word }
        assertEquals(listOf("Visit", "or", "mail", "don't", "well-known"), words)

        val apostrophe = SpellTokens.of(text).first { it.word == "don't" }
        assertEquals(text.substring(apostrophe.range), "don't")
    }

    @Test
    fun objectCharactersAndSingleLettersAreNotWords() {
        val text = "a ${OBJ_CHAR}bc ok"
        val words = SpellTokens.of(text).map { it.word }
        assertEquals(listOf("bc", "ok"), words)
    }

    @Test
    fun cacheIsBoundedAndEvictsTheOldestEntry() {
        val cache = SpellCache(maxEntries = 2)
        cache.put("a", emptyList())
        cache.put("b", listOf("x"))
        assertNotNull(cache.get("a"))
        cache.put("c", emptyList())
        assertNull(cache.get("b"))
        assertNotNull(cache.get("a"))
        assertEquals(2, cache.size())
    }

    @Test
    fun misspellingsCarryTheExactParagraphAndAreNotRepeatedAfterCaching() = runBlocking {
        val state = doc("hello wrld", "ok")
        val engine = FakeEngine(setOf("hello", "ok"))
        val cache = SpellCache()
        val issues = SpellCheck.collect(state, engine, cache)
        assertEquals(1, issues.size)
        assertTrue(issues.single().word == "wrld")
        assertEquals("wrld", issues.single().para.text.substring(issues.single().range))
        assertTrue(issues.single().suggestions.isNotEmpty())
        val para = findPara(state.blocks, issues.single().paraId)
        assertTrue(issues.single().para === para)

        val again = SpellCheck.collect(state, engine, cache)
        assertEquals(1, again.size)
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun wordsSplitByFormattingRunsAreCheckedAsOneToken() = runBlocking {
        val body = "<w:p><w:r><w:t>hel</w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>lo wrld</w:t></w:r></w:p>"
        val state = DocxFixtures.read(DocxFixtures.docx(body)).second
        val engine = FakeEngine(setOf("hello"))
        val issues = SpellCheck.collect(state, engine, SpellCache())
        assertEquals(listOf("wrld"), issues.map { it.word })
        assertEquals("wrld", issues.single().para.text.substring(issues.single().range))
    }

    @Test
    fun nonEditableParagraphsAreNotChecked() = runBlocking {
        val editablePara = doc("helo").blocks.filterIsInstance<DocPara>().first()
        // Imported field/raw content the writer must reproduce verbatim: readable, not ours to edit.
        val locked = DocPara(
            DocIds.next(), null, "<w:p>", "", "", "wrld",
            List(4) { CharFmt.Plain }, editable = false
        )
        val state = DocState(listOf(editablePara, locked))
        val engine = FakeEngine(setOf("hello"))
        val issues = SpellCheck.collect(state, engine, SpellCache())
        assertEquals(listOf("helo"), issues.map { it.word })
    }

    @Test
    fun longParagraphsAreChunkedAndCancelFriendly() = runBlocking {
        val words = (1..200).joinToString(" ") { i ->
            "word" + ('a' + (i / 676) % 26) + ('a' + (i / 26) % 26) + ('a' + i % 26)
        }
        val state = doc(words)
        val engine = FakeEngine(emptySet())
        val issues = SpellCheck.collect(state, engine, SpellCache())
        assertEquals(200, issues.size)
        assertTrue(engine.calls.all { it.size <= SpellCheck.CHUNK })
        assertTrue(engine.calls.size >= 2)
        assertEquals(engine.calls.sumOf { it.size }, issues.size)
    }

    @Test
    fun anInFlightScanIsCancelledCooperatively() = runBlocking {
        val state = doc("helo wrld")
        var started = false
        val engine = object : SpellEngine {
            override val available: Boolean = true
            override val language: String? = "en-US"
            override suspend fun suggest(words: List<String>): Map<String, List<String>> {
                started = true
                kotlinx.coroutines.awaitCancellation()
            }
        }
        var issues: List<SpellIssue>? = null
        val job = launch {
            issues = SpellCheck.collect(state, engine, SpellCache())
        }
        kotlinx.coroutines.yield()
        assertTrue(started)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertNull(issues)
    }

    @Test
    fun unavailableEngineScansNothing() = runBlocking {
        val engine = FakeEngine(emptySet(), available = false)
        assertTrue(SpellCheck.collect(doc("wrld"), engine, SpellCache()).isEmpty())
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun referenceLikeChunksAreSkipped() {
        assertTrue(SpellTokens.looksLikeReference("https://a.example/b"))
        assertTrue(SpellTokens.looksLikeReference("name@example.com"))
        assertTrue(SpellTokens.looksLikeReference("/sdcard/file.docx"))
        assertFalse(SpellTokens.looksLikeReference("ordinary,word."))
    }
}
