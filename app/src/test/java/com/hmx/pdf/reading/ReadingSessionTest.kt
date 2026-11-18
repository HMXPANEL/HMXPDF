package com.hmx.pdf.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4.5 state machine with a fake engine: play/pause/resume, chunk completion, paragraph
 * navigation, stale callbacks, errors and the offline-unavailable path. The Android binding is a
 * thin adapter over this contract.
 */
class ReadingSessionTest {

    private class FakeEngine(override var available: Boolean = true) : TtsEngine {
        override var language: String? = "en-US"
        override var rate = 1f
        private var listener: TtsEngine.Listener? = null
        val spoken = ArrayList<Pair<String, String>>()
        var stops = 0
        var closed = false

        override fun setListener(listener: TtsEngine.Listener?) {
            this.listener = listener
        }

        override fun speak(text: String, utteranceId: String) {
            spoken.add(text to utteranceId)
        }

        override fun stop() {
            stops++
        }

        override fun close() {
            closed = true
        }

        fun done(id: String) {
            listener?.onDone(id)
        }

        fun error(id: String) {
            listener?.onError(id)
        }
    }

    /** Paragraph 11 needs two chunks (330 > 320); 22 and 33 have one each. */
    private fun script() = ReadingProjection.chunks(
        listOf(
            ReadingSegment(11, "A".repeat(330)),
            ReadingSegment(22, "Second paragraph."),
            ReadingSegment(33, "Third paragraph.")
        )
    )

    private fun session(engine: FakeEngine = FakeEngine()): Pair<ReadingSession, FakeEngine> {
        val s = ReadingSession(engine)
        s.load(script())
        return s to engine
    }

    @Test
    fun playPauseResumeKeepsTheChunk() {
        val (s, engine) = session()
        assertEquals(ReadingSession.State.IDLE, s.state)
        assertEquals(4, s.chunkCount)

        s.play()
        assertEquals(ReadingSession.State.PLAYING, s.state)
        assertEquals(11L, s.currentParaId)
        assertEquals("A".repeat(320), engine.spoken.single().first)

        s.pause()
        assertEquals(ReadingSession.State.PAUSED, s.state)
        assertEquals(11L, s.currentParaId)
        assertEquals(0, s.chunkIndex)
        assertEquals(2, engine.stops)

        s.resume()
        assertEquals(ReadingSession.State.PLAYING, s.state)
        assertEquals(2, engine.spoken.size)
        assertEquals("A".repeat(320), engine.spoken.last().first)

        // A callback for the paused utterance is stale and must not advance the resumed one.
        engine.done(engine.spoken.first().second)
        assertEquals(ReadingSession.State.PLAYING, s.state)
        assertEquals("A".repeat(320), s.currentText)
    }

    @Test
    fun completionAdvancesChunkByChunkThenReturnsIdle() {
        val (s, engine) = session()
        s.play()

        engine.done(engine.spoken.last().second)
        assertEquals(1, s.chunkIndex)
        assertEquals(11L, s.currentParaId)
        assertEquals("A".repeat(10), s.currentText)

        engine.done(engine.spoken.last().second)
        assertEquals(2, s.chunkIndex)
        assertEquals(22L, s.currentParaId)

        engine.done(engine.spoken.last().second)
        assertEquals(3, s.chunkIndex)
        assertEquals(33L, s.currentParaId)

        engine.done(engine.spoken.last().second)
        assertEquals(ReadingSession.State.IDLE, s.state)
        assertEquals(0, s.chunkIndex)
        assertEquals(4, engine.spoken.size)
    }

    @Test
    fun paragraphNavigationSkipsRemainingChunks() {
        val (s, engine) = session()
        s.play()

        s.nextParagraph()
        assertEquals(2, s.chunkIndex)
        assertEquals(22L, s.currentParaId)
        assertEquals("Second paragraph.", engine.spoken.last().first)

        s.nextParagraph()
        assertEquals(3, s.chunkIndex)
        assertEquals(33L, s.currentParaId)

        // "Next" in the last paragraph has nowhere to go and must not restart it.
        val count = engine.spoken.size
        s.nextParagraph()
        assertEquals(3, s.chunkIndex)
        assertEquals(count, engine.spoken.size)

        s.previousParagraph()
        assertEquals(2, s.chunkIndex)
        assertEquals(22L, s.currentParaId)

        // Previous from the middle of the first paragraph rewinds to its first chunk.
        s.previousParagraph()
        s.previousParagraph()
        assertEquals(0, s.chunkIndex)
        assertEquals(11L, s.currentParaId)
    }

    @Test
    fun paragraphNavigationWhilePausedOnlyMovesTheHighlight() {
        val (s, engine) = session()
        s.play()
        s.pause()
        val count = engine.spoken.size

        s.nextParagraph()
        assertEquals(ReadingSession.State.PAUSED, s.state)
        assertEquals(2, s.chunkIndex)
        assertEquals(22L, s.currentParaId)
        assertEquals(count, engine.spoken.size)

        s.previousParagraph()
        assertEquals(0, s.chunkIndex)
        assertEquals(11L, s.currentParaId)
    }

    @Test
    fun stopResetsAndIgnoresTheOldUtterance() {
        val (s, engine) = session()
        s.play()
        val old = engine.spoken.last().second

        s.stop()
        assertEquals(ReadingSession.State.IDLE, s.state)
        assertEquals(0, s.chunkIndex)
        assertEquals(11L, s.currentParaId)

        engine.done(old)
        assertEquals(ReadingSession.State.IDLE, s.state)
        assertEquals(1, engine.spoken.size)
    }

    @Test
    fun loadStopsRunningSpeech() {
        val (s, engine) = session()
        s.play()
        val old = engine.spoken.last().second

        s.load(script())
        assertEquals(ReadingSession.State.IDLE, s.state)
        assertEquals(0, s.chunkIndex)
        assertEquals(2, engine.stops)

        engine.done(old)
        assertEquals(ReadingSession.State.IDLE, s.state)
    }

    @Test
    fun errorPausesAtTheCurrentChunkAndIgnoresStaleErrors() {
        val (s, engine) = session()
        s.play()
        val id = engine.spoken.last().second

        engine.error(id)
        assertEquals(ReadingSession.State.PAUSED, s.state)
        assertEquals(0, s.chunkIndex)
        assertEquals(11L, s.currentParaId)
        assertEquals(2, engine.stops)

        engine.error("999")
        assertEquals(2, engine.stops)
        assertEquals(ReadingSession.State.PAUSED, s.state)
    }

    @Test
    fun unavailableEngineNeverSpeaksAndRecoversWhenReady() {
        val engine = FakeEngine(available = false)
        val s = ReadingSession(engine)
        s.load(script())
        assertEquals(ReadingSession.State.UNAVAILABLE, s.state)

        s.play()
        assertEquals(ReadingSession.State.UNAVAILABLE, s.state)
        assertTrue(engine.spoken.isEmpty())

        engine.available = true
        s.refreshAvailability()
        assertEquals(ReadingSession.State.IDLE, s.state)

        s.play()
        assertEquals(ReadingSession.State.PLAYING, s.state)
        assertEquals(1, engine.spoken.size)
    }

    @Test
    fun rateIsClamped() {
        val (s, engine) = session()
        s.setRate(3f)
        assertEquals(2f, engine.rate, 0.001f)
        s.setRate(0.1f)
        assertEquals(0.5f, engine.rate, 0.001f)
    }

    @Test
    fun releaseClosesTheEngineAndClearsTheScript() {
        val (s, engine) = session()
        s.play()

        s.release()

        assertTrue(engine.closed)
        assertEquals(ReadingSession.State.IDLE, s.state)
        assertEquals(0, s.chunkCount)
        s.play()
        assertTrue(engine.spoken.size == 1)
    }
}
