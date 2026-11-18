package com.hmx.pdf.reading

/**
 * The minimal speech engine the session drives. [AndroidTtsEngine] is the on-device implementation;
 * JVM tests use a fake. Implementations must not fetch audio from the network (the Android engine
 * selects a voice that does not require a network connection).
 */
interface TtsEngine {
    val available: Boolean
    val language: String?

    var rate: Float

    fun setListener(listener: Listener?)

    fun speak(text: String, utteranceId: String)

    /** Stops the current utterance. Android has no pause, so pause is stop + retained position. */
    fun stop()

    fun close()

    interface Listener {
        fun onDone(id: String)
        fun onError(id: String)
    }
}

/**
 * Phase 4.5 state machine, independent of Android so it is testable end to end.
 *
 * The script is a list of bounded chunks from [ReadingProjection]. Pause uses stop plus the chunk
 * index (the roadmap's "stop + saved chunk offset"); resume re-speaks that chunk from its start.
 * Completion advances chunk by chunk, while previous/next move by paragraph. An edit invalidates
 * the session: the ViewModel calls [stop], so a stale id can never be highlighted.
 */
class ReadingSession(private val engine: TtsEngine) {

    enum class State { IDLE, PLAYING, PAUSED, UNAVAILABLE }

    var state: State = State.IDLE
        private set

    private var chunks: List<ReadingChunkRef> = emptyList()
    private var index = 0
    private var utterance = 0

    /** Called after every observable change; the ViewModel mirrors state into Compose state. */
    var onChange: (() -> Unit)? = null

    private val listener = object : TtsEngine.Listener {
        override fun onDone(id: String) = this@ReadingSession.onDone(id)
        override fun onError(id: String) = this@ReadingSession.onError(id)
    }

    val currentParaId: Long? get() = chunks.getOrNull(index)?.paraId
    val currentText: String? get() = chunks.getOrNull(index)?.text
    val chunkCount: Int get() = chunks.size
    val chunkIndex: Int get() = index

    init {
        engine.setListener(listener)
    }

    /** Replaces the script; a running utterance stops so no old text is spoken. */
    fun load(next: List<ReadingChunkRef>) {
        engine.stop()
        chunks = next
        index = 0
        state = if (!engine.available) State.UNAVAILABLE else State.IDLE
        changed()
    }

    fun play() {
        if (!engine.available) { state = State.UNAVAILABLE; changed(); return }
        if (chunks.isEmpty()) return
        if (state == State.PLAYING) return
        if (index !in chunks.indices) index = 0
        speakCurrent()
    }

    fun pause() {
        if (state != State.PLAYING) return
        engine.stop()
        // The current chunk index is the saved offset: resume starts that chunk again.
        state = State.PAUSED
        changed()
    }

    fun resume() {
        if (state == State.UNAVAILABLE) return
        if (chunks.isEmpty()) return
        speakCurrent()
    }

    fun stop() {
        engine.stop()
        index = 0
        utterance++
        state = if (!engine.available) State.UNAVAILABLE else State.IDLE
        changed()
    }

    /** Moves to the next paragraph (skipping the rest of this paragraph's chunks). */
    fun nextParagraph() {
        if (chunks.isEmpty()) return
        val segment = chunks[index.coerceIn(0, chunks.lastIndex)].segment
        val at = chunks.indexOfFirst { it.segment > segment }
        // Already in the last paragraph: do not restart it, "next" has nowhere to go.
        if (at < 0) return
        moveTo(at)
    }

    fun previousParagraph() {
        if (chunks.isEmpty()) return
        val segment = chunks[index.coerceIn(0, chunks.lastIndex)].segment
        val at = chunks.indexOfLast { it.segment < segment }
        moveTo(if (at < 0) 0 else chunks.indexOfFirst { it.segment == chunks[at].segment })
    }

    fun setRate(rate: Float) {
        engine.rate = rate.coerceIn(0.5f, 2f)
        changed()
    }

    /** Availability can appear after the asynchronous engine initialises. */
    fun refreshAvailability() {
        if (!engine.available) state = State.UNAVAILABLE
        else if (state == State.UNAVAILABLE) state = State.IDLE
        changed()
    }

    fun release() {
        engine.setListener(null)
        engine.stop()
        engine.close()
        state = State.IDLE
        chunks = emptyList()
        index = 0
        changed()
    }

    private fun moveTo(at: Int) {
        index = at
        // Paragraph navigation while playing restarts at the new paragraph; while paused it just
        // moves the highlight and the saved position.
        if (state == State.PLAYING) speakCurrent() else changed()
    }

    private fun speakCurrent() {
        val chunk = chunks.getOrNull(index) ?: run {
            state = State.IDLE
            index = 0
            changed()
            return
        }
        if (!engine.available) { state = State.UNAVAILABLE; changed(); return }
        val id = (++utterance).toString()
        state = State.PLAYING
        changed()
        engine.speak(chunk.text, id)
    }

    private fun onDone(id: String) {
        if (id != utterance.toString() || state != State.PLAYING) return
        if (index + 1 < chunks.size) {
            index++
            speakCurrent()
        } else {
            state = State.IDLE
            index = 0
            changed()
        }
    }

    private fun onError(id: String) {
        if (id != utterance.toString()) return
        engine.stop()
        // Keep the position so the user can retry; the failure is not a silent success.
        state = State.PAUSED
        changed()
    }

    private fun changed() {
        onChange?.invoke()
    }
}
