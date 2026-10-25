package com.hmx.pdf.data.remote

import com.hmx.pdf.data.InMemoryStringStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagCacheTest {

    private fun snapshot(
        fetchedAt: Long = 1_000L,
        flags: Map<String, RemoteFlag> = mapOf(
            DIAGNOSTICS_FLAG to RemoteFlag(true, 1, "rollout"),
            PROBLEM_REPORTS_FLAG to RemoteFlag(false, 2, "disabled"),
        ),
    ) = ConfigSnapshot(
        revision = "b".repeat(64),
        generatedAt = "2026-10-24T12:00:00Z",
        fetchedAtMillis = fetchedAt,
        maxAgeSeconds = 60,
        killSwitch = false,
        flags = flags,
    )

    @Test
    fun snapshotRoundTrips() {
        val cache = FeatureFlagCache(InMemoryStringStore())
        val original = snapshot()
        cache.save(original)
        assertEquals(original, cache.load())
    }

    @Test
    fun corruptOrUnknownCacheFailsClosed() {
        for (bad in listOf("", "not json", "[]", """{"schema_version":2}""")) {
            val store = InMemoryStringStore(mapOf("snapshot_v1" to bad))
            assertNull(bad, FeatureFlagCache(store).load())
        }
    }

    @Test
    fun unknownFlagsAreDroppedFromTheCache() {
        val cache = FeatureFlagCache(InMemoryStringStore())
        cache.save(
            snapshot(
                flags = mapOf(
                    DIAGNOSTICS_FLAG to RemoteFlag(true, 1, "rollout"),
                    "future.flag" to RemoteFlag(true, 9, "rollout"),
                )
            )
        )
        assertEquals(setOf(DIAGNOSTICS_FLAG), cache.load()!!.flags.keys)
    }

    @Test
    fun rateLimitBackoffIsBounded() {
        val cache = FeatureFlagCache(InMemoryStringStore())
        cache.recordRateLimited(10_000L, 120L)
        assertEquals(130_000L, cache.retryNotBeforeMillis())

        cache.recordRateLimited(10_000L, 1L)
        assertEquals(40_000L, cache.retryNotBeforeMillis())

        cache.recordRateLimited(10_000L, 999_999L)
        assertEquals(10_000L + 86_400_000L, cache.retryNotBeforeMillis())
    }

    @Test
    fun serverBackoffGrowsAndIsCapped() {
        val cache = FeatureFlagCache(InMemoryStringStore())
        cache.recordServerError(1_000L)
        assertEquals(31_000L, cache.retryNotBeforeMillis())
        assertEquals(1, cache.consecutiveFailures())

        cache.recordServerError(31_000L)
        assertEquals(91_000L, cache.retryNotBeforeMillis())
        assertEquals(2, cache.consecutiveFailures())

        repeat(20) { cache.recordServerError(1_000L) }
        assertEquals(10, cache.consecutiveFailures())
        assertTrue(cache.retryNotBeforeMillis() <= 1_000L + 900_000L)
    }

    @Test
    fun successAndNonRetryableFailureResetBackoff() {
        val cache = FeatureFlagCache(InMemoryStringStore())
        cache.recordServerError(1_000L)
        cache.recordSuccess()
        assertEquals(0L, cache.retryNotBeforeMillis())
        assertEquals(0, cache.consecutiveFailures())

        cache.recordServerError(1_000L)
        cache.recordNonRetryableFailure()
        assertEquals(0L, cache.retryNotBeforeMillis())
        assertEquals(0, cache.consecutiveFailures())
    }

    @Test
    fun clearRemovesSnapshotAndBackoff() {
        val cache = FeatureFlagCache(InMemoryStringStore())
        cache.save(snapshot())
        cache.recordServerError(1_000L)
        cache.clear()
        assertNull(cache.load())
        assertEquals(0L, cache.retryNotBeforeMillis())
        assertEquals(0, cache.consecutiveFailures())
        assertFalse(cache.load() != null)
    }
}
