package com.hmx.pdf.ui.viewmodel

import com.hmx.pdf.docx.CharFmt
import com.hmx.pdf.docx.DocPara
import com.hmx.pdf.docx.DocxComments
import com.hmx.pdf.docx.DocxFixtures
import com.hmx.pdf.docx.OBJ_CHAR
import com.hmx.pdf.spell.SpellCache
import com.hmx.pdf.spell.SpellCheck
import com.hmx.pdf.spell.SpellEngine
import com.hmx.pdf.spell.SpellIssue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4.3 at the ViewModel: suggestions are annotations only (never dirty, never change rPr),
 * application is an explicit edit, a stale suggestion refuses, and the tracked/replacement
 * pipelines plus undo behave exactly like any other paragraph edit.
 */
class DocxSpellViewModelTest {

    private class FakeEngine(val dictionary: Set<String>) : SpellEngine {
        override val available: Boolean = true
        override val language: String? = "en-US"
        override suspend fun suggest(words: List<String>): Map<String, List<String>> =
            words.associateWith { w -> if (w.lowercase() in dictionary) emptyList() else listOf("hello") }
    }

    private fun vm(body: String = "<w:p><w:r><w:t xml:space=\"preserve\">helo world</w:t></w:r></w:p>"): DocxEditorViewModel {
        val (pkg, state) = DocxFixtures.read(DocxFixtures.docx(body))
        return DocxEditorViewModel().apply {
            primeForTest(pkg to state)
            setEditMode(true)
        }
    }

    private fun scan(view: DocxEditorViewModel, engine: SpellEngine = FakeEngine(setOf("world"))): List<SpellIssue> =
        runBlocking { SpellCheck.collect(view.doc!!, engine, SpellCache()) }

    private fun paragraph(view: DocxEditorViewModel): DocPara =
        view.doc!!.blocks.filterIsInstance<DocPara>().first()

    @Test
    fun suggestionsAreAnnotationsAndOnlyAnExplicitFixEditsTheDocument() {
        val view = vm()
        val issues = scan(view)
        assertEquals(1, issues.size)
        view.installSpellResults(issues)

        // Annotating changed nothing.
        assertEquals("helo world", paragraph(view).text)
        assertFalse(view.dirty)
        assertEquals(listOf(0..3), view.spellRanges[issues.single().paraId])

        assertTrue(view.applySpellFix(issues.single(), "hello"))
        assertEquals("hello world", paragraph(view).text)
        assertTrue(view.dirty)
        // The paragraph's remaining annotations were dropped with the edit.
        assertTrue(view.spellRanges[issues.single().paraId].isNullOrEmpty())
    }

    @Test
    fun staleSuggestionRefusesAfterItsParagraphChanged() {
        val view = vm()
        val issues = scan(view)
        view.installSpellResults(issues)
        val p = paragraph(view)

        view.typed(p.id, "helo world again", CharFmt.Plain)
        assertFalse(view.applySpellFix(issues.single(), "hello"))
        assertTrue(paragraph(view).text.startsWith("helo world again"))
    }

    @Test
    fun replacementKeepsRunFormattingAndCommentAnchors() {
        val view = vm(
            "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>helo</w:t></w:r>" +
                "<w:r><w:t xml:space=\"preserve\"> world</w:t></w:r></w:p>"
        )
        val anchored = DocxComments.anchorPara(paragraph(view), "0")
        view.replace(anchored)
        val issues = scan(view)
        view.installSpellResults(issues)

        assertTrue(view.applySpellFix(issues.single(), "hello"))
        val p = paragraph(view)
        assertTrue(p.text.startsWith("hello world"))
        assertTrue(p.text.contains(OBJ_CHAR))
        assertTrue(p.fmts[0].rPr.contains("b"))
        assertEquals(CharFmt.Plain, p.fmts[5])
        assertTrue(p.fmts.any { it.obj?.contains("commentReference") == true })
    }

    @Test
    fun trackedReplacementUsesTheRevisionPipeline() {
        val view = vm()
        val issues = scan(view)
        view.installSpellResults(issues)
        view.setTracking(true)

        assertTrue(view.applySpellFix(issues.single(), "hello"))
        val p = paragraph(view)
        assertTrue(p.text.contains("hello"))
        assertTrue(p.text.contains("helo"))
        assertTrue(p.fmts.any { it.rev?.ins == true })
        assertTrue(p.fmts.any { it.rev?.ins == false })
    }

    @Test
    fun viewModeRefusesAReplacement() {
        val view = vm()
        val issues = scan(view)
        view.installSpellResults(issues)
        view.setEditMode(false)
        assertFalse(view.applySpellFix(issues.single(), "hello"))
        assertEquals("helo world", paragraph(view).text)
    }

    @Test
    fun undoRestoresTheWordAndRedoAppliesItAgain() {
        val view = vm()
        val issues = scan(view)
        view.installSpellResults(issues)
        assertTrue(view.applySpellFix(issues.single(), "hello"))

        view.undo()
        assertEquals("helo world", paragraph(view).text)
        view.redo()
        assertEquals("hello world", paragraph(view).text)
    }

    @Test
    fun ignoringOneSuggestionLeavesTheOthers() {
        val view = vm("<w:p><w:r><w:t xml:space=\"preserve\">helo wrld</w:t></w:r></w:p>")
        val issues = scan(view)
        assertEquals(2, issues.size)
        view.installSpellResults(issues)

        view.ignoreSpellIssue(issues[0])
        assertEquals(1, view.spellIssues.size)
        assertEquals(issues[1].word, view.spellIssues.single().word)
        assertEquals(listOf(issues[1].range), view.spellRanges[issues[1].paraId])
        assertEquals("helo wrld", paragraph(view).text)
        assertFalse(view.dirty)
    }
}
