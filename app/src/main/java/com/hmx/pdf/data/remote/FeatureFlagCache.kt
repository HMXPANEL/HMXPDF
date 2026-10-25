package com.hmx.pdf.data.remote

import com.hmx.pdf.data.storage.StringKeyValueStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val KEY_SNAPSHOT = "snapshot_v1"
private const val KEY_RETRY_NOT_BEFORE = "retry_not_before"
private const val KEY_CONSECUTIVE_FAILURES = "consecutive_failures"
private const val CACHE_SCHEMA_VERSION = 1
private const val MAX_CACHED_FLAGS = 100

private val CacheJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

@Serializable
private data class CachedFlagDto(
    val enabled: Boolean,
    val version: Int,
    val source: String,
)

@Serializable
private data class CachedSnapshotDto(
    @SerialName("schema_version") val schemaVersion: Int = CACHE_SCHEMA_VERSION,
    val revision: String,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("fetched_at") val fetchedAtMillis: Long,
    @SerialName("max_age_seconds") val maxAgeSeconds: Int,
    @SerialName("kill_switch") val killSwitch: Boolean,
    val flags: Map<String, CachedFlagDto>,
)

/**
 * Last-known-good config plus the bounded retry deadline. No identifier is stored here.
 * Corrupt cache data reads as null, which makes every flag OFF.
 */
class FeatureFlagCache(
    private val store: StringKeyValueStore,
) {
    fun load(): ConfigSnapshot? = runCatching {
        val raw = store.getString(KEY_SNAPSHOT) ?: return null
        val dto = CacheJson.decodeFromString<CachedSnapshotDto>(raw)
        if (dto.schemaVersion != CACHE_SCHEMA_VERSION) return null
        if (dto.revision.isBlank() || dto.revision.length > 128) return null
        if (dto.generatedAt.isBlank() || dto.generatedAt.length > 64) return null
        if (dto.fetchedAtMillis <= 0) return null
        if (dto.maxAgeSeconds !in 1..CONFIG_MAX_AGE_SECONDS_LIMIT) return null
        if (dto.flags.size > MAX_CACHED_FLAGS) return null

        val flags = dto.flags.mapNotNull { (key, flag) ->
            if (key !in REQUESTED_FLAG_KEYS) return@mapNotNull null
            if (flag.version !in 0..CONFIG_MAX_VERSION_CODE) return@mapNotNull null
            if (flag.source.isBlank() || flag.source.length > 64) return@mapNotNull null
            key to RemoteFlag(flag.enabled, flag.version, flag.source)
        }.toMap()

        ConfigSnapshot(
            revision = dto.revision,
            generatedAt = dto.generatedAt,
            fetchedAtMillis = dto.fetchedAtMillis,
            maxAgeSeconds = dto.maxAgeSeconds,
            killSwitch = dto.killSwitch,
            flags = flags,
        )
    }.getOrNull()

    fun save(snapshot: ConfigSnapshot) {
        val dto = CachedSnapshotDto(
            revision = snapshot.revision,
            generatedAt = snapshot.generatedAt,
            fetchedAtMillis = snapshot.fetchedAtMillis,
            maxAgeSeconds = snapshot.maxAgeSeconds,
            killSwitch = snapshot.killSwitch,
            flags = snapshot.flags.mapValues { (_, flag) ->
                CachedFlagDto(flag.enabled, flag.version, flag.source)
            },
        )
        store.putString(KEY_SNAPSHOT, CacheJson.encodeToString(dto))
    }

    fun clear() {
        store.remove(KEY_SNAPSHOT)
        store.remove(KEY_RETRY_NOT_BEFORE)
        store.remove(KEY_CONSECUTIVE_FAILURES)
    }

    fun retryNotBeforeMillis(): Long =
        runCatching { store.getString(KEY_RETRY_NOT_BEFORE)?.toLongOrNull() ?: 0L }.getOrDefault(0L)

    fun consecutiveFailures(): Int =
        runCatching {
            store.getString(KEY_CONSECUTIVE_FAILURES)?.toIntOrNull()?.coerceIn(0, 10) ?: 0
        }.getOrDefault(0)

    fun recordSuccess() {
        store.remove(KEY_RETRY_NOT_BEFORE)
        store.remove(KEY_CONSECUTIVE_FAILURES)
    }

    /** 429: honour Retry-After, bounded to a sane range so a hostile header cannot pin us. */
    fun recordRateLimited(nowMillis: Long, retryAfterSeconds: Long?) {
        val delaySeconds = (retryAfterSeconds ?: 60L).coerceIn(30L, 86_400L)
        store.putString(KEY_CONSECUTIVE_FAILURES, "0")
        store.putString(KEY_RETRY_NOT_BEFORE, (nowMillis + delaySeconds * 1_000L).toString())
    }

    /** 5xx: exponential backoff from 30 s, capped at 15 min and at 10 recorded failures. */
    fun recordServerError(nowMillis: Long) {
        val failures = (consecutiveFailures() + 1).coerceAtMost(10)
        val delayMillis = (30_000L shl (failures - 1)).coerceAtMost(900_000L)
        store.putString(KEY_CONSECUTIVE_FAILURES, failures.toString())
        store.putString(KEY_RETRY_NOT_BEFORE, (nowMillis + delayMillis).toString())
    }

    fun recordNonRetryableFailure() {
        store.remove(KEY_RETRY_NOT_BEFORE)
        store.remove(KEY_CONSECUTIVE_FAILURES)
    }
}
