package com.hmx.pdf.data.privacy

import com.hmx.pdf.data.InMemoryStringStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentStoreTest {

    @Test
    fun missingStateIsOff() {
        val state = ConsentStore(InMemoryStringStore()).read()
        assertEquals(ConsentState.OFF, state)
        assertFalse(state.diagnosticReportsEnabled)
        assertFalse(state.problemReportsEnabled)
        assertFalse(state.hasDecision)
        assertNull(state.updatedAtMillis)
    }

    @Test
    fun savedValuesAreIndependent() {
        val store = InMemoryStringStore()
        val consent = ConsentStore(store)
        consent.save(diagnosticReportsEnabled = true, problemReportsEnabled = false, nowMillis = 1234L)
        assertEquals(
            ConsentState(true, false, CONSENT_VERSION, 1234L),
            consent.read(),
        )

        consent.save(diagnosticReportsEnabled = false, problemReportsEnabled = true, nowMillis = 5678L)
        assertEquals(
            ConsentState(false, true, CONSENT_VERSION, 5678L),
            consent.read(),
        )
    }

    @Test
    fun explicitOffDecisionIsRecordedAndStillOff() {
        val consent = ConsentStore(InMemoryStringStore())
        consent.save(diagnosticReportsEnabled = false, problemReportsEnabled = false, nowMillis = 99L)
        val state = consent.read()
        assertEquals(ConsentState(false, false, CONSENT_VERSION, 99L), state)
        assertTrue(state.hasDecision)
    }

    @Test
    fun missingVersionFailsClosed() {
        val store = InMemoryStringStore(
            mapOf(
                "diagnostic_reports_enabled" to "true",
                "problem_reports_enabled" to "true",
                "consent_updated_at" to "1234",
            )
        )
        assertEquals(ConsentState.OFF, ConsentStore(store).read())
    }

    @Test
    fun unknownVersionFailsClosed() {
        val store = InMemoryStringStore(
            mapOf(
                "consent_version" to "0",
                "diagnostic_reports_enabled" to "true",
                "problem_reports_enabled" to "true",
                "consent_updated_at" to "1234",
            )
        )
        assertEquals(ConsentState.OFF, ConsentStore(store).read())
    }

    @Test
    fun malformedBooleanFailsClosed() {
        for (bad in listOf("yes", "1", "", "TRUE", "null")) {
            val store = InMemoryStringStore(
                mapOf(
                    "consent_version" to "1",
                    "diagnostic_reports_enabled" to bad,
                    "problem_reports_enabled" to "true",
                    "consent_updated_at" to "1234",
                )
            )
            assertEquals("bad value: $bad", ConsentState.OFF, ConsentStore(store).read())
        }
    }

    @Test
    fun malformedTimestampFailsClosed() {
        for (bad in listOf("", "abc", "0", "-5")) {
            val store = InMemoryStringStore(
                mapOf(
                    "consent_version" to "1",
                    "diagnostic_reports_enabled" to "true",
                    "problem_reports_enabled" to "true",
                    "consent_updated_at" to bad,
                )
            )
            assertEquals("bad timestamp: $bad", ConsentState.OFF, ConsentStore(store).read())
        }
    }

    @Test
    fun clearRemovesTheDecision() {
        val consent = ConsentStore(InMemoryStringStore())
        consent.save(true, true, 10L)
        consent.clear()
        assertEquals(ConsentState.OFF, consent.read())
    }
}
