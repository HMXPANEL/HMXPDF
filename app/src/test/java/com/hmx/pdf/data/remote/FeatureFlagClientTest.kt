package com.hmx.pdf.data.remote

import com.hmx.pdf.data.InMemoryStringStore
import com.hmx.pdf.data.privacy.ConsentState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagClientTest {

    private val now = 1_000_000L

    private fun snapshot(
        fetchedAt: Long = now,
        killSwitch: Boolean = false,
        diagnosticEnabled: Boolean = true,
        diagnosticSource: String = "rollout",
        problemEnabled: Boolean = true,
        problemSource: String = "rollout",
    ) = ConfigSnapshot(
        revision = "a".repeat(64),
        generatedAt = "2026-10-24T12:00:00Z",
        fetchedAtMillis = fetchedAt,
        maxAgeSeconds = 60,
        killSwitch = killSwitch,
        flags = mapOf(
            DIAGNOSTICS_FLAG to RemoteFlag(diagnosticEnabled, 3, diagnosticSource),
            PROBLEM_REPORTS_FLAG to RemoteFlag(problemEnabled, 4, problemSource),
        ),
    )

    private fun responseJson(
        killSwitch: Boolean = false,
        diagnosticEnabled: Boolean = true,
        diagnosticSource: String = "rollout",
        problemEnabled: Boolean = true,
        problemSource: String = "rollout",
        extraFlag: String = "",
    ): String = """
        {
          "revision": "${"a".repeat(64)}",
          "generated_at": "2026-10-24T12:00:00Z",
          "max_age_seconds": 60,
          "kill_switch": $killSwitch,
          "flags": {
            "diagnostics.enabled": {"enabled": $diagnosticEnabled, "version": 3, "source": "$diagnosticSource", "config": {}},
            "problem_reports.enabled": {"enabled": $problemEnabled, "version": 4, "source": "$problemSource", "config": {}}
            $extraFlag
          }
        }
    """.trimIndent()

    @Test
    fun requestContainsOnlyKeysAndVersionCode() {
        val body = ConfigContract.buildRequest(6)
        val json = Json.parseToJsonElement(body).jsonObject

        assertEquals(setOf("keys", "app_version_code"), json.keys)
        assertEquals(6, json["app_version_code"]!!.jsonPrimitive.int)
        assertEquals(
            REQUESTED_FLAG_KEYS,
            json["keys"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(body.contains("install", ignoreCase = true))
        assertFalse(body.contains("tester", ignoreCase = true))
        assertFalse(body.contains("device", ignoreCase = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun requestRejectsAnInvalidVersionCode() {
        ConfigContract.buildRequest(-1)
    }

    @Test
    fun validResponseParsesAndIgnoresUnknownFieldsAndFlags() {
        val parsed = ConfigContract.parseResponse(
            responseJson(
                extraFlag = ""","future.flag": {"enabled": true, "version": 1, "source": "rollout"}""",
            ).replace("\"flags\": {", "\"unknown_top_level\": 1, \"flags\": {"),
            now,
        )

        assertNotNull(parsed)
        assertEquals(setOf(DIAGNOSTICS_FLAG, PROBLEM_REPORTS_FLAG), parsed!!.flags.keys)
        assertTrue(parsed.flags.getValue(DIAGNOSTICS_FLAG).enabled)
        assertEquals(60, parsed.maxAgeSeconds)
        assertEquals(now, parsed.fetchedAtMillis)
    }

    @Test
    fun malformedOrOversizedResponsesFailClosed() {
        val cases = listOf(
            "",
            "[]",
            "{",
            "x".repeat(CONFIG_MAX_BODY_BYTES + 1),
            responseJson().replace("\"max_age_seconds\": 60", "\"max_age_seconds\": 0"),
            responseJson().replace("\"kill_switch\": false,", ""),
            responseJson().replace("\"generated_at\": \"2026-10-24T12:00:00Z\"", "\"generated_at\": \"yesterday\""),
            responseJson().replace("\"enabled\": true, \"version\": 3", "\"enabled\": \"true\", \"version\": 3"),
            responseJson().replace("\"source\": \"rollout\"", "\"source\": \"\""),
        )
        for (body in cases) {
            assertNull(body.take(40), ConfigContract.parseResponse(body, now))
        }
    }

    @Test
    fun localConsentOffOverridesAnEnabledRemoteFlag() {
        val consent = ConsentState(diagnosticReportsEnabled = false, problemReportsEnabled = true)
        val features = snapshot().effectiveFeatures(consent, now)
        assertFalse(features.diagnosticReports)
        assertTrue(features.problemReports)
    }

    @Test
    fun globalKillSwitchForcesEverythingOff() {
        val consent = ConsentState(diagnosticReportsEnabled = true, problemReportsEnabled = true)
        val features = snapshot(killSwitch = true).effectiveFeatures(consent, now)
        assertEquals(EffectiveFeatures.OFF, features)
    }

    @Test
    fun perFlagKillSwitchSourceForcesThatFlagOff() {
        val consent = ConsentState(diagnosticReportsEnabled = true, problemReportsEnabled = true)
        val features = snapshot(diagnosticSource = FLAG_KILL_SWITCH_SOURCE)
            .effectiveFeatures(consent, now)
        assertFalse(features.diagnosticReports)
        assertTrue(features.problemReports)
    }

    @Test
    fun staleSnapshotFailsClosedButFreshCacheSurvivesBeyondFreshnessWindow() {
        val consent = ConsentState(diagnosticReportsEnabled = true, problemReportsEnabled = true)
        val cached = snapshot()

        assertFalse(cached.isFresh(now + 61_000L))
        assertTrue(cached.isWithinMaxStale(now + 61_000L))
        assertTrue(cached.effectiveFeatures(consent, now + 61_000L).diagnosticReports)

        val beyondMaxStale = now + CONFIG_MAX_STALE_MILLIS + 1L
        assertFalse(cached.isWithinMaxStale(beyondMaxStale))
        assertEquals(EffectiveFeatures.OFF, cached.effectiveFeatures(consent, beyondMaxStale))
    }

    @Test
    fun clockSkewIntoTheFutureIsRejected() {
        val consent = ConsentState(diagnosticReportsEnabled = true, problemReportsEnabled = true)
        val cached = snapshot()
        // A cached fetch timestamp far ahead of the device clock is not trustworthy.
        val farPast = now - CONFIG_MAX_CLOCK_SKEW_MILLIS - 1L
        assertEquals(EffectiveFeatures.OFF, cached.effectiveFeatures(consent, farPast))
    }

    @Test
    fun unknownFlagsNeverEnableAnything() {
        val consent = ConsentState(diagnosticReportsEnabled = true, problemReportsEnabled = true)
        val snapshot = ConfigSnapshot(
            revision = "a".repeat(64),
            generatedAt = "2026-10-24T12:00:00Z",
            fetchedAtMillis = now,
            maxAgeSeconds = 60,
            killSwitch = false,
            flags = mapOf("future.enabled" to RemoteFlag(true, 1, "rollout")),
        )
        assertEquals(EffectiveFeatures.OFF, snapshot.effectiveFeatures(consent, now))
    }

    @Test
    fun clientRefreshesAndPersistsAValidResponse() {
        val cache = FeatureFlagCache(InMemoryStringStore())
        val transport = FakeTransport(ConfigHttpResponse(200, responseJson()))
        val client = FeatureFlagClient(
            configEndpoint = "https://example.test/functions/v1/config",
            publishableKey = "publishable",
            appVersionCode = 6,
            cache = cache,
            transport = transport,
            clock = { now },
        )

        val result = client.refresh()
        assertEquals(ConfigRefreshOutcome.UPDATED, result.outcome)
        assertNotNull(result.snapshot)
        assertEquals(1, transport.calls)
        assertFalse(transport.lastBody!!.contains("install", ignoreCase = true))
        assertEquals(
            EffectiveFeatures(true, true),
            client.effectiveFeatures(ConsentState(true, true)),
        )
    }

    @Test
    fun unconfiguredClientPerformsNoRequest() {
        val transport = FakeTransport(ConfigHttpResponse(200, responseJson()))
        val client = FeatureFlagClient(
            configEndpoint = "",
            publishableKey = "",
            appVersionCode = 6,
            cache = FeatureFlagCache(InMemoryStringStore()),
            transport = transport,
            clock = { now },
        )
        assertEquals(ConfigRefreshOutcome.NOT_CONFIGURED, client.refresh().outcome)
        assertEquals(0, transport.calls)
    }

    @Test
    fun cleartextEndpointIsRefusedWithoutARequest() {
        val transport = FakeTransport(ConfigHttpResponse(200, responseJson()))
        val client = FeatureFlagClient(
            configEndpoint = "http://example.test/functions/v1/config",
            publishableKey = "publishable",
            appVersionCode = 6,
            cache = FeatureFlagCache(InMemoryStringStore()),
            transport = transport,
            clock = { now },
        )
        assertEquals(ConfigRefreshOutcome.NOT_CONFIGURED, client.refresh().outcome)
        assertEquals(0, transport.calls)
    }

    @Test
    fun rateLimitSetsABoundedBackoffWithoutTouchingTheCache() {
        val cache = FeatureFlagCache(InMemoryStringStore())
        val transport = FakeTransport(ConfigHttpResponse(429, """{"error":"rate_limited"}""", 120))
        val client = FeatureFlagClient(
            "https://example.test/functions/v1/config",
            "publishable",
            6,
            cache,
            transport,
        ) { now }

        assertEquals(ConfigRefreshOutcome.RATE_LIMITED, client.refresh().outcome)
        assertEquals(now + 120_000L, cache.retryNotBeforeMillis())
        assertEquals(ConfigRefreshOutcome.BACKOFF, client.refresh().outcome)
        assertEquals(1, transport.calls)
        assertNull(cache.load())
    }

    @Test
    fun serverErrorsUseExponentialBoundedBackoff() {
        val cache = FeatureFlagCache(InMemoryStringStore())
        val transport = FakeTransport(ConfigHttpResponse(503, """{"error":"service_unavailable"}"""))
        val client = FeatureFlagClient(
            "https://example.test/functions/v1/config",
            "publishable",
            6,
            cache,
            transport,
        ) { now }

        assertEquals(ConfigRefreshOutcome.SERVER_ERROR, client.refresh().outcome)
        assertEquals(now + 30_000L, cache.retryNotBeforeMillis())
        assertEquals(1, cache.consecutiveFailures())
    }

    @Test
    fun authFailureAndNetworkFailureDoNotWriteACache() {
        val cache = FeatureFlagCache(InMemoryStringStore())
        val unauthorized = FeatureFlagClient(
            "https://example.test/functions/v1/config",
            "publishable",
            6,
            cache,
            FakeTransport(ConfigHttpResponse(401, "")),
        ) { now }
        assertEquals(ConfigRefreshOutcome.UNAUTHORIZED, unauthorized.refresh().outcome)
        assertNull(cache.load())

        val network = FeatureFlagClient(
            "https://example.test/functions/v1/config",
            "publishable",
            6,
            cache,
            FakeTransport(error = java.io.IOException("offline")),
        ) { now }
        assertEquals(ConfigRefreshOutcome.NETWORK_ERROR, network.refresh().outcome)
        assertNull(cache.load())
    }

    @Test
    fun invalidResponseLeavesThePreviousCacheIntact() {
        val cache = FeatureFlagCache(InMemoryStringStore())
        cache.save(snapshot())
        val client = FeatureFlagClient(
            "https://example.test/functions/v1/config",
            "publishable",
            6,
            cache,
            FakeTransport(ConfigHttpResponse(200, """{"revision":"x"}""")),
        ) { now }

        assertEquals(ConfigRefreshOutcome.INVALID_RESPONSE, client.refresh().outcome)
        assertNotNull(cache.load())
    }

    private class FakeTransport(
        var response: ConfigHttpResponse = ConfigHttpResponse(200, ""),
        var error: Exception? = null,
    ) : ConfigTransport {
        var calls = 0
        var lastBody: String? = null

        override fun post(endpoint: String, publishableKey: String, body: String): ConfigHttpResponse {
            calls++
            lastBody = body
            error?.let { throw it }
            return response
        }
    }
}
