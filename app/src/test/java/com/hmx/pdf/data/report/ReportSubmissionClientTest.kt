package com.hmx.pdf.data.report

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportSubmissionClientTest {

    private class FakeTransport(
        private val response: () -> ReportHttpResponse,
    ) : ReportTransport {
        var calls = 0
        var lastBody: String? = null

        override fun post(
            endpoint: String,
            publishableKey: String,
            body: String,
        ): ReportHttpResponse {
            calls++
            lastBody = body
            return response()
        }
    }

    private fun client(
        transport: ReportTransport,
        endpoint: String = "https://reports.example.test/report",
        key: String = "publishable-key",
    ) = ReportSubmissionClient(
        endpoint = endpoint,
        publishableKey = key,
        transport = transport,
        clock = { TEST_NOW },
    )

    @Test
    fun acceptsTheBackendAcknowledgementShape() {
        val transport = FakeTransport {
            ReportHttpResponse(
                201,
                """{"report_id":"$TEST_SERVER_ID","status":"received","received_at":"2026-10-25T12:00:00Z"}""",
            )
        }

        val result = client(transport).submit(testProblem())

        assertEquals(ReportSubmitOutcome.SUBMITTED, result.outcome)
        assertEquals(TEST_SERVER_ID, result.acknowledgement?.reportId)
        assertEquals("received", result.acknowledgement?.status)
        assertEquals(false, result.acknowledgement?.duplicate)
        assertEquals(1, transport.calls)
    }

    @Test
    fun duplicateAcknowledgementIsStillASubmission() {
        val transport = FakeTransport {
            ReportHttpResponse(
                200,
                """{"report_id":"$TEST_SERVER_ID","status":"received","received_at":"2026-10-25T12:00:00Z","duplicate":true}""",
            )
        }

        val result = client(transport).submit(testProblem())

        assertEquals(ReportSubmitOutcome.SUBMITTED, result.outcome)
        assertEquals(true, result.acknowledgement?.duplicate)
    }

    @Test
    fun retrySendsTheSameClientReportId() {
        val transport = FakeTransport {
            ReportHttpResponse(
                201,
                """{"report_id":"$TEST_SERVER_ID","status":"received","received_at":"2026-10-25T12:00:00Z"}""",
            )
        }
        val submission = client(transport)
        val payload = testProblem()

        submission.submit(payload)
        val firstBody = transport.lastBody
        submission.submit(payload)

        assertEquals(2, transport.calls)
        assertEquals(firstBody, transport.lastBody)
        assertTrue(firstBody!!.contains(TEST_CLIENT_ID))
    }

    @Test
    fun mapsTransportFailuresWithoutClaimingSuccess() {
        assertEquals(
            ReportSubmitOutcome.TIMEOUT,
            client(FakeTransport { throw SocketTimeoutException() }).submit(testProblem()).outcome,
        )
        assertEquals(
            ReportSubmitOutcome.OFFLINE,
            client(FakeTransport { throw IOException() }).submit(testProblem()).outcome,
        )
    }

    @Test
    fun mapsHttpStatusesToNonSuccessOutcomes() {
        assertEquals(
            ReportSubmitOutcome.REJECTED,
            client(FakeTransport { ReportHttpResponse(400, """{"error":"invalid_field"}""") })
                .submit(testProblem()).outcome,
        )
        assertEquals(
            ReportSubmitOutcome.RATE_LIMITED,
            client(FakeTransport { ReportHttpResponse(429, "", retryAfterSeconds = 3600L) })
                .submit(testProblem()).outcome,
        )
        assertEquals(
            ReportSubmitOutcome.SERVER_ERROR,
            client(FakeTransport { ReportHttpResponse(503, """{"error":"service_unavailable"}""") })
                .submit(testProblem()).outcome,
        )
    }

    @Test
    fun malformedSuccessBodyIsNeverReportedAsSubmitted() {
        val transport = FakeTransport { ReportHttpResponse(200, """{"status":"received"}""") }

        val result = client(transport).submit(testProblem())

        assertEquals(ReportSubmitOutcome.INVALID_RESPONSE, result.outcome)
        assertNull(result.acknowledgement)
    }

    @Test
    fun unconfiguredEndpointMakesNoRequest() {
        val transport = FakeTransport { error("must not be called") }

        val result = client(transport, endpoint = "").submit(testProblem())

        assertEquals(ReportSubmitOutcome.NOT_CONFIGURED, result.outcome)
        assertEquals(0, transport.calls)
    }

    @Test
    fun rejectsAnInvalidPayloadBeforeAnyRequest() {
        val transport = FakeTransport { error("must not be called") }
        val invalid = testProblem().copy(clientReportId = "not-a-uuid")

        val result = client(transport).submit(invalid)

        assertEquals(ReportSubmitOutcome.REJECTED, result.outcome)
        assertEquals(0, transport.calls)
    }
}
