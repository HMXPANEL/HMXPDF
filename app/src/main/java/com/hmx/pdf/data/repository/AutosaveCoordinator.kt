package com.hmx.pdf.data.repository

/**
 * The autosave scheduling state machine (Phase 4.1): checkpoints run after a short idle debounce
 * ([idleDebounceMs] after the last edit), but never later than [maxDirtyMs] after the first
 * unsaved edit since the last checkpoint — continuous typing still checkpoints on a bounded
 * interval. One checkpoint write runs at a time ([beginWrite] refuses concurrent starts); edits
 * made during a write coalesce into exactly one follow-up due immediately after it ends — an
 * in-flight write is never cancelled merely because typing continues.
 *
 * Pure and clock-injected so scheduling is deterministically testable; the ViewModel supplies
 * `System.currentTimeMillis()` and runs the actual delay.
 */
internal class AutosaveCoordinator(
    private val idleDebounceMs: Long = DEFAULT_IDLE_DEBOUNCE_MS,
    private val maxDirtyMs: Long = DEFAULT_MAX_DIRTY_MS
) {

    private var firstDirtyAt: Long? = null
    private var lastEditAt: Long = 0
    private var writeInFlight = false
    private var editsDuringWrite = false

    /**
     * An edit happened at [nowMs] ([dirty] false resets tracking — the document is clean).
     * Returns milliseconds until the next checkpoint is due, 0 = due now, -1 = nothing due.
     */
    fun touch(nowMs: Long, dirty: Boolean): Long {
        if (!dirty) {
            firstDirtyAt = null
            return NONE
        }
        if (firstDirtyAt == null) firstDirtyAt = nowMs
        lastEditAt = nowMs
        if (writeInFlight) {
            // The running checkpoint is already stale; endWrite schedules the follow-up.
            editsDuringWrite = true
            return NONE
        }
        return dueIn(nowMs)
    }

    /** Starts the single allowed checkpoint write. False when one is already running. */
    fun beginWrite(): Boolean {
        if (writeInFlight) return false
        writeInFlight = true
        editsDuringWrite = false
        return true
    }

    /**
     * The write finished at [nowMs]. Returns 0 when edits happened during it (one coalesced
     * follow-up, capped from now — the newest state gets its own bounded window), -1 otherwise
     * (the next edit schedules the next checkpoint).
     */
    fun endWrite(nowMs: Long, succeeded: Boolean = true): Long {
        writeInFlight = false
        val hadNewer = editsDuringWrite
        editsDuringWrite = false
        if (!succeeded) {
            firstDirtyAt = nowMs
            lastEditAt = nowMs
            return maxDirtyMs
        }
        return if (hadNewer) {
            firstDirtyAt = nowMs
            lastEditAt = nowMs
            0L
        } else {
            firstDirtyAt = null
            NONE
        }
    }

    /** The document was saved to its destination: checkpoint tracking restarts from clean. */
    fun onSaved() {
        firstDirtyAt = null
        lastEditAt = 0
        editsDuringWrite = false
    }

    private fun dueIn(nowMs: Long): Long {
        val first = firstDirtyAt ?: return NONE
        val idleDue = lastEditAt + idleDebounceMs
        val maxDue = first + maxDirtyMs
        return (minOf(idleDue, maxDue) - nowMs).coerceAtLeast(0L)
    }

    companion object {
        const val NONE = -1L
        /** Roughly 2 seconds after the last edit (roadmap default; profiling may tune it). */
        const val DEFAULT_IDLE_DEBOUNCE_MS = 2_000L
        /** At latest 15 seconds after the first unsaved edit since the last checkpoint. */
        const val DEFAULT_MAX_DIRTY_MS = 15_000L
    }
}
