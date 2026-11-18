package com.hmx.pdf.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The autosave scheduler's contract (Phase 4.1), on a fake clock: an idle debounce after the last
 * edit, a bounded maximum dirty interval for continuous typing, one write at a time, and
 * coalescing of edits made during a write into exactly one immediate follow-up. `touch` returns
 * the delay the VM should schedule; only the cap can make it 0, because every touch resets the
 * idle window.
 */
class AutosaveCoordinatorTest {

    private val IDLE = AutosaveCoordinator.DEFAULT_IDLE_DEBOUNCE_MS
    private val MAX = AutosaveCoordinator.DEFAULT_MAX_DIRTY_MS

    @Test
    fun failedWriteRetriesWithoutAnotherKeystroke() {
        val c = AutosaveCoordinator()
        c.touch(0, dirty = true)
        assertTrue(c.beginWrite())
        assertEquals(MAX, c.endWrite(2_000, succeeded = false))
        assertTrue(c.beginWrite())
        assertEquals(AutosaveCoordinator.NONE, c.endWrite(17_000, succeeded = true))
    }

    @Test
    fun theFirstEditSchedulesTheIdleDebounce() {
        val c = AutosaveCoordinator()
        assertEquals(IDLE, c.touch(1_000, dirty = true))
        // A later touch (the VM rescheduling after more typing) returns the fresh idle window.
        assertEquals(IDLE, c.touch(3_000, dirty = true))
    }

    @Test
    fun continuousTypingCheckpointsAtTheBoundedInterval() {
        val c = AutosaveCoordinator()
        // A keystroke every 2s keeps resetting the idle window: every touch is the full debounce…
        for (t in 0..12 step 2) assertEquals(IDLE, c.touch(t * 1_000L, dirty = true))
        // …but the cap runs from the FIRST unsaved edit: due at exactly 15s.
        assertEquals(1_000, c.touch(14_000, dirty = true))
        assertEquals(0, c.touch(15_000, dirty = true))
    }

    @Test
    fun aCleanDocumentNeverSchedules() {
        val c = AutosaveCoordinator()
        assertEquals(AutosaveCoordinator.NONE, c.touch(0, dirty = false))
        assertEquals(IDLE, c.touch(0, dirty = true))
        assertEquals(AutosaveCoordinator.NONE, c.touch(5_000, dirty = false))
    }

    @Test
    fun onlyOneWriteRunsAtATime() {
        val c = AutosaveCoordinator()
        c.touch(0, dirty = true)
        assertTrue(c.beginWrite())
        assertFalse(c.beginWrite())
    }

    @Test
    fun editsDuringAWriteCoalesceIntoOneImmediateFollowUp() {
        val c = AutosaveCoordinator()
        c.touch(0, dirty = true)
        assertTrue(c.beginWrite())
        // Typing during the write: no new schedule (endWrite owns it)…
        assertEquals(AutosaveCoordinator.NONE, c.touch(500, dirty = true))
        assertEquals(AutosaveCoordinator.NONE, c.touch(900, dirty = true))
        // …and exactly one follow-up, due immediately, with a fresh bounded window.
        assertEquals(0, c.endWrite(1_000))
        assertEquals(IDLE, c.touch(1_000, dirty = true))
    }

    @Test
    fun aWriteWithoutNewerEditsLeavesNothingScheduled() {
        val c = AutosaveCoordinator()
        c.touch(0, dirty = true)
        assertTrue(c.beginWrite())
        assertEquals(AutosaveCoordinator.NONE, c.endWrite(2_000))
        // The next edit schedules the next checkpoint normally.
        assertEquals(IDLE, c.touch(2_000, dirty = true))
    }

    @Test
    fun savingResetsTrackingUntilTheNextEdit() {
        val c = AutosaveCoordinator()
        c.touch(0, dirty = true)
        c.onSaved()
        // After a save the next edit starts a FRESH window anchored at that edit: the cap is not
        // inherited from the pre-save first-dirty time.
        assertEquals(IDLE, c.touch(100_000, dirty = true))
        assertEquals(0, c.touch(100_000 + MAX, dirty = true))
    }
}
