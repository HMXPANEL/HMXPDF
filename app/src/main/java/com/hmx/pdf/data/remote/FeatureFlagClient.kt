package com.hmx.pdf.data.remote

import com.hmx.pdf.data.privacy.ConsentState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

private val RequestJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

private val Iso8601Pattern =
    Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?(?:Z|[+-]\\d{2}:\\d{2})$")
private val RevisionPattern = Regex("^[A-Za-z0-9._:-]{1,128}$")
private val MAX_FLAGS_IN_RESPONSE = 100

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

object ConfigContract {
    fun buildRequest(appVersionCode: Int): String {
        require(appVersionCode in 0..CONFIG_MAX_VERSION_CODE) { "invalid app version code" }
        return RequestJson.encodeToString(
            ConfigRequestPayload(
                keys = REQUESTED_FLAG_KEYS,
                appVersionCode = appVersionCode,
            )
        )
    }

    /**
     * Strict enough to reject malformed/oversized responses, but ignores unknown fields and
     * unknown flags as required. A malformed requested flag invalidates the whole response.
     */
    fun parseResponse(body: String, fetchedAtMillis: Long): ConfigSnapshot? {
        if (fetchedAtMillis <= 0) return null
        if (body.toByteArray(Charsets.UTF_8).size > CONFIG_MAX_BODY_BYTES) return null

        val root = runCatching {
            RequestJson.parseToJsonElement(body).jsonObject
        }.getOrNull() ?: return null

        val revision = root.string("revision")
            ?.takeIf { RevisionPattern.matches(it) }
            ?: return null
        val generatedAt = root.string("generated_at")
            ?.takeIf { Iso8601Pattern.matches(it) }
            ?: return null
        val maxAgeSeconds = root.int("max_age_seconds")
            ?.takeIf { it in 1..CONFIG_MAX_AGE_SECONDS_LIMIT }
            ?: return null
        val killSwitch = root.boolean("kill_switch") ?: return null
        val flagsElement = root["flags"] as? JsonObject ?: return null
        if (flagsElement.size > MAX_FLAGS_IN_RESPONSE) return null

        val flags = LinkedHashMap<String, RemoteFlag>()
        for (key in REQUESTED_FLAG_KEYS) {
            val flagElement = flagsElement[key] ?: continue
            val flag = flagElement as? JsonObject ?: return null
            val enabled = flag.boolean("enabled") ?: return null
            val version = flag.int("version")
                ?.takeIf { it in 0..CONFIG_MAX_VERSION_CODE }
                ?: return null
            val source = flag.string("source")
                ?.takeIf { it.isNotBlank() && it.length <= 64 }
                ?: return null
            val config = flag["config"]
            if (config != null && config !is JsonObject) return null
            flags[key] = RemoteFlag(enabled, version, source)
        }

        return ConfigSnapshot(
            revision = revision,
            generatedAt = generatedAt,
            fetchedAtMillis = fetchedAtMillis,
            maxAgeSeconds = maxAgeSeconds,
            killSwitch = killSwitch,
            flags = flags,
        )
    }
}

/**
 * Remote availability only. There is deliberately no method that writes consent, and the
 * install-ID provider is not a dependency of this class.
 */
class FeatureFlagClient(
    private val configEndpoint: String,
    private val publishableKey: String,
    private val appVersionCode: Int,
    private val cache: FeatureFlagCache,
    private val transport: ConfigTransport,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun effectiveFeatures(consent: ConsentState): EffectiveFeatures {
        val snapshot = cache.load() ?: return EffectiveFeatures.OFF
        return snapshot.effectiveFeatures(consent, clock())
    }

    fun snapshot(): ConfigSnapshot? = cache.load()

    fun refresh(): ConfigRefreshResult {
        if (configEndpoint.isBlank() || publishableKey.isBlank()) {
            return ConfigRefreshResult(ConfigRefreshOutcome.NOT_CONFIGURED)
        }
        if (!configEndpoint.startsWith("https://", ignoreCase = true)) {
            return ConfigRefreshResult(ConfigRefreshOutcome.NOT_CONFIGURED)
        }

        val now = clock()
        if (now < cache.retryNotBeforeMillis()) {
            return ConfigRefreshResult(ConfigRefreshOutcome.BACKOFF)
        }

        val request = runCatching { ConfigContract.buildRequest(appVersionCode) }
            .getOrElse { return ConfigRefreshResult(ConfigRefreshOutcome.HTTP_ERROR) }
        val response = runCatching { transport.post(configEndpoint, publishableKey, request) }
            .getOrElse { return ConfigRefreshResult(ConfigRefreshOutcome.NETWORK_ERROR) }

        return when (response.status) {
            200 -> {
                val snapshot = ConfigContract.parseResponse(response.body, now)
                    ?: return ConfigRefreshResult(ConfigRefreshOutcome.INVALID_RESPONSE)
                cache.save(snapshot)
                cache.recordSuccess()
                ConfigRefreshResult(ConfigRefreshOutcome.UPDATED, snapshot)
            }
            429 -> {
                cache.recordRateLimited(now, response.retryAfterSeconds)
                ConfigRefreshResult(ConfigRefreshOutcome.RATE_LIMITED)
            }
            in 500..599 -> {
                cache.recordServerError(now)
                ConfigRefreshResult(ConfigRefreshOutcome.SERVER_ERROR)
            }
            401, 403 -> {
                cache.recordNonRetryableFailure()
                ConfigRefreshResult(ConfigRefreshOutcome.UNAUTHORIZED)
            }
            else -> {
                cache.recordNonRetryableFailure()
                ConfigRefreshResult(ConfigRefreshOutcome.HTTP_ERROR)
            }
        }
    }
}
