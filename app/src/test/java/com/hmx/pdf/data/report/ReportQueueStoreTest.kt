package com.hmx.pdf.data.report

import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReportQueueStoreTest {

    private lateinit var directory: File
    private lateinit var queue: ReportQueueStore

    @Before
    fun setUp() {
        directory = File(
            System.getProperty("java.io.tmpdir"),
            "hmxpdf-report-queue-${System.nanoTime()}",
        )
        queue = ReportQueueStore(directory) { TEST_NOW }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun enqueueIsIdempotentAndKeepsTheClientIdStable() {
        val payload = testProblem()
        val first = queue.enqueue(payload)
        val second = queue.enqueue(payload)

        assertEquals(first, second)
        assertEquals(TEST_CLIENT_ID, first.payload.clientReportId)
        assertEquals(ReportState.PENDING, first.state)
        assertEquals(1, queue.list().size)
        assertEquals(1, directory.listFiles { file -> file.name.endsWith(".json") }!!.size)
    }

    @Test
    fun retriesKeepTheSamePayloadAndClientId() {
        val payload = testProblem()
        queue.enqueue(payload)

        val attempted = queue.recordAttempt(TEST_CLIENT_ID)!!
        assertEquals(1, attempted.attempts)
        assertEquals(TEST_CLIENT_ID, attempted.payload.clientReportId)
        assertEquals(payload, attempted.payload)

        val failed = queue.transition(
            TEST_CLIENT_ID,
            ReportState.FAILED,
            error = ReportErrorCode.OFFLINE,
        )!!
        assertEquals(ReportState.FAILED, failed.state)
        assertEquals(ReportErrorCode.OFFLINE, failed.lastError)

        val submitted = queue.transition(
            TEST_CLIENT_ID,
            ReportState.SUBMITTED,
            serverId = TEST_SERVER_ID,
        )!!
        assertEquals(ReportState.SUBMITTED, submitted.state)
        assertEquals(TEST_SERVER_ID, submitted.serverId)
        assertEquals(TEST_CLIENT_ID, submitted.payload.clientReportId)
    }

    @Test
    fun terminalStatesAreImmutable() {
        queue.enqueue(testProblem())
        queue.transition(TEST_CLIENT_ID, ReportState.SUBMITTED, serverId = TEST_SERVER_ID)

        assertNull(queue.transition(TEST_CLIENT_ID, ReportState.PENDING))
        assertNull(queue.recordAttempt(TEST_CLIENT_ID))
        assertEquals(
            ReportState.SUBMITTED,
            queue.get(TEST_CLIENT_ID)!!.state,
        )
    }

    @Test
    fun submittedRequiresAValidServerIdAndRejectedRequiresAnError() {
        queue.enqueue(testProblem())
        assertNull(queue.transition(TEST_CLIENT_ID, ReportState.SUBMITTED))
        assertNull(queue.transition(TEST_CLIENT_ID, ReportState.SUBMITTED, serverId = "nope"))
        assertNull(queue.transition(TEST_CLIENT_ID, ReportState.REJECTED))
        assertNotNull(
            queue.transition(TEST_CLIENT_ID, ReportState.REJECTED, error = ReportErrorCode.REJECTED)
        )
    }

    @Test
    fun queueIsBoundedAndPrunesOldestTerminalEntriesFirst() {
        repeat(ReportQueueStore.MAX_REPORTS) {
            queue.enqueue(testProblem(clientReportId = UUID.randomUUID().toString()))
        }
        assertEquals(ReportQueueStore.MAX_REPORTS, queue.list().size)

        val full = runCatching {
            queue.enqueue(testProblem(clientReportId = UUID.randomUUID().toString()))
        }
        assertTrue(full.exceptionOrNull() is ReportQueueFullException)

        val oldest = queue.list().last()
        queue.transition(oldest.payload.clientReportId, ReportState.SUBMITTED, serverId = TEST_SERVER_ID)

        val replacement = testProblem(clientReportId = UUID.randomUUID().toString())
        queue.enqueue(replacement)
        assertEquals(ReportQueueStore.MAX_REPORTS, queue.list().size)
        assertNull(queue.get(oldest.payload.clientReportId))
        assertNotNull(queue.get(replacement.clientReportId))
    }

    @Test
    fun corruptAndPartialFilesAreIgnoredSafely() {
        queue.enqueue(testProblem())
        File(directory, "not-json.json").writeText("{broken")
        File(directory, "partial.json.tmp").writeText("{}")

        assertEquals(1, queue.list().size)
        assertNull(queue.get("not-a-uuid"))
    }

    @Test
    fun corruptFilesAreRemovedWhenTheQueueNeedsSpace() {
        repeat(ReportQueueStore.MAX_REPORTS) {
            queue.enqueue(testProblem(clientReportId = UUID.randomUUID().toString()))
        }
        val corrupt = File(directory, "corrupt.json").apply { writeText("not json") }
        val terminal = queue.list().last()
        queue.transition(terminal.payload.clientReportId, ReportState.SUBMITTED, serverId = TEST_SERVER_ID)

        queue.enqueue(testProblem(clientReportId = UUID.randomUUID().toString()))
        assertFalse(corrupt.exists())
        assertEquals(ReportQueueStore.MAX_REPORTS, queue.list().size)
    }

    @Test
    fun deleteRemovesOnlyTheRequestedEnvelope() {
        queue.enqueue(testProblem(clientReportId = TEST_CLIENT_ID))
        val other = UUID.randomUUID().toString()
        queue.enqueue(testProblem(clientReportId = other))

        assertTrue(queue.delete(TEST_CLIENT_ID))
        assertNull(queue.get(TEST_CLIENT_ID))
        assertNotNull(queue.get(other))
        assertFalse(queue.delete("not-a-uuid"))
    }
}
