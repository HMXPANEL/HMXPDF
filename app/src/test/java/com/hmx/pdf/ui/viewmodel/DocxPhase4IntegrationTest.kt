package com.hmx.pdf.ui.viewmodel

import com.hmx.pdf.docx.DocPara
import com.hmx.pdf.docx.DocTable
import com.hmx.pdf.docx.DocxFixtures
import com.hmx.pdf.docx.DocxNotes
import com.hmx.pdf.reading.ReadingProjection
import com.hmx.pdf.reading.ReadingSession
import com.hmx.pdf.reading.TtsEngine
import com.hmx.pdf.spell.SpellCache
import com.hmx.pdf.spell.SpellCheck
import com.hmx.pdf.spell.SpellEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4.6 — cross-feature integration under one session.
 *
 * Tables, notes and a spell replacement are applied in order, captured for save, written through
 * the single DOCX writer and read back. The undo stack is then unwound feature by feature, and a
 * read-aloud pass is proven not to mutate the document or its dirty state. This is the JVM half of
 * the roadmap's 4.6 acceptance; device smoke (API levels, low RAM, TTS voice, external Office) is
 * recorded as a limit in the phase report.
 */
class DocxPhase4IntegrationTest {

    private val rel = DocxFixtures.REL

    private fun footnotesPart(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:footnotes ${DocxFixtures.W_NS}>" +
            "<w:footnote w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:separator/></w:r></w:p></w:footnote>" +
            "<w:footnote w:type=\"continuationSeparator\" w:id=\"0\"><w:p><w:r><w:continuationSeparator/></w:r></w:p></w:footnote>" +
            "<w:footnote w:id=\"3\"><w:p><w:r><w:footnoteRef/></w:r>" +
            "<w:r><w:t xml:space=\"preserve\">existing note</w:t></w:r></w:p></w:footnote>" +
            "</w:footnotes>"

    private fun packageBytes(): ByteArray = DocxFixtures.docx(
        body = "<w:p><w:r><w:t xml:space=\"preserve\">Hello wrld. </w:t></w:r></w:p>",
        rels = "<Relationship Id=\"rIdFn\" Type=\"$rel/footnotes\" Target=\"footnotes.xml\"/>",
        contentTypes = "<Override PartName=\"/word/footnotes.xml\" " +
            "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml\"/>",
        parts = mapOf(
            "word/footnotes.xml" to footnotesPart(),
            "word/customXml/item1.xml" to "<keep>unknown-part</keep>"
        )
    )

    private class FakeEngine : SpellEngine {
        override val available: Boolean = true
        override val language: String? = "en-US"
        override suspend fun suggest(words: List<String>): Map<String, List<String>> =
            words.associateWith { w -> if (w == "Hello") emptyList() else listOf("world") }
    }

    private class FakeTts : TtsEngine {
        override val available: Boolean = true
        override var language: String? = "en-US"
        override var rate = 1f
        private var listener: TtsEngine.Listener? = null
        val spoken = ArrayList<String>()

        override fun setListener(listener: TtsEngine.Listener?) { this.listener = listener }
        override fun speak(text: String, utteranceId: String) { spoken.add(utteranceId) }
        override fun stop() {}
        override fun close() {}
        fun done(id: String) { listener?.onDone(id) }
    }

    private fun vm(bytes: ByteArray): DocxEditorViewModel {
        val (pkg, state) = DocxFixtures.read(bytes)
        return DocxEditorViewModel().apply {
            primeForTest(pkg to state)
            setEditMode(true)
        }
    }

    private fun bodyPara(view: DocxEditorViewModel): DocPara =
        view.doc!!.blocks.filterIsInstance<DocPara>().first { it.text.startsWith("Hello") }

    @Test
    fun tablesNotesAndSpellingSurviveOneCaptureAndUndoFeatureByFeature() {
        val bytes = packageBytes()
        val view = vm(bytes)
        val paraId = bodyPara(view).id

        // 1) Table structure, 2) a note with its reference, 3) an explicit spell replacement.
        assertTrue(view.insertTable(2, 2, focusId = paraId))
        val tableId = view.doc!!.blocks.filterIsInstance<DocTable>().single().id
        assertTrue(view.insertTableRow(tableId, 1))
        assertEquals(3, view.doc!!.blocks.filterIsInstance<DocTable>().single().rows.size)

        val noteId = view.insertNote(DocxNotes.Kind.FOOTNOTE, paraId, "integration note")
        assertNotNull(noteId)

        val issues = runBlocking { SpellCheck.collect(view.doc!!, FakeEngine(), SpellCache()) }
        view.installSpellResults(issues)
        val wrong = issues.single { it.word == "wrld" }
        assertTrue(view.applySpellFix(wrong, "world"))
        assertEquals("Hello world. ${com.hmx.pdf.docx.OBJ_CHAR}", bodyPara(view).text)

        // The one save capture carries body, table, note and package edits together.
        val capture = view.saveCapture()!!
        assertEquals(3, capture.doc.blocks.filterIsInstance<DocTable>().single().rows.size)
        assertTrue(capture.doc.blocks.filterIsInstance<DocPara>().any { it.text.startsWith("Hello world.") })
        assertNotNull(capture.notes)
        val visible = capture.notes!!.visible(DocxNotes.Kind.FOOTNOTE)
        assertEquals(setOf(3, noteId), visible.map { it.id }.toSet())
        assertTrue(capture.extras.notes.containsKey("word/footnotes.xml"))

        // Read Aloud is a projection: playing every chunk mutates neither the state nor its dirty flag.
        val beforeDoc = view.doc
        val beforeDirty = view.dirty
        val segments = ReadingProjection.segments(beforeDoc!!.blocks)
        assertTrue(segments.any { it.text.startsWith("Hello world.") })
        // Empty created cells are blank paragraphs and are skipped by the declared projection policy.
        assertTrue(segments.none { it.text.isBlank() })
        val tts = FakeTts()
        val session = ReadingSession(tts)
        session.load(ReadingProjection.chunks(segments))
        session.play()
        var guard = 0
        while (session.state == ReadingSession.State.PLAYING && guard++ < 500) {
            val id = tts.spoken.lastOrNull() ?: break
            tts.done(id)
        }
        assertEquals(ReadingSession.State.IDLE, session.state)
        assertSame(beforeDoc, view.doc)
        assertEquals(beforeDirty, view.dirty)
        assertEquals("Hello world. ${com.hmx.pdf.docx.OBJ_CHAR}", bodyPara(view).text)

        // Restartable (editor close/reopen): drop the live session but keep the captured generation.
        session.stop()

        // The writer is the single DOCX path; all three features and the unknown part survive round trip.
        val saved = DocxFixtures.save(bytes, capture.pkg, capture.doc, capture.extras)
        val (back, state) = DocxFixtures.read(saved)
        assertTrue(state.blocks.filterIsInstance<DocPara>().any { it.text.startsWith("Hello world.") })
        assertEquals(3, state.blocks.filterIsInstance<DocTable>().single().rows.size)
        assertTrue(back.notes.visible(DocxNotes.Kind.FOOTNOTE).any { it.id == 3 })
        assertTrue(
            back.notes.visible(DocxNotes.Kind.FOOTNOTE).any {
                it.blocks.filterIsInstance<DocPara>().any { p -> p.text.contains("integration note") }
            }
        )
        assertEquals("<keep>unknown-part</keep>", DocxFixtures.entry(saved, "word/customXml/item1.xml"))

        // Undo unwinds each feature as one step, newest first.
        view.undo() // spell replacement
        assertTrue(bodyPara(view).text.startsWith("Hello wrld."))
        view.undo() // note (reference + part element)
        assertEquals(1, view.notesList(DocxNotes.Kind.FOOTNOTE).size) // the fixture's original note 3
        assertEquals(3, view.notesList(DocxNotes.Kind.FOOTNOTE).single().id)
        view.undo() // row insert
        assertEquals(2, view.doc!!.blocks.filterIsInstance<DocTable>().single().rows.size)
        view.undo() // table creation
        assertTrue(view.doc!!.blocks.filterIsInstance<DocTable>().isEmpty())
        assertFalse(view.canUndo)

        // Redo restores the same combined state, ready to save again.
        repeat(4) { view.redo() }
        assertEquals(3, view.doc!!.blocks.filterIsInstance<DocTable>().single().rows.size)
        assertEquals(2, view.notesList(DocxNotes.Kind.FOOTNOTE).size)
        assertTrue(bodyPara(view).text.startsWith("Hello world."))
    }
}
