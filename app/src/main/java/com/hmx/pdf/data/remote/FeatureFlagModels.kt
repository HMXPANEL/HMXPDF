package com.hmx.pdf.data.remote

import com.hmx.pdf.data.privacy.ConsentState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val DIAGNOSTICS_FLAG = "diagnostics.enabled"
const val PROBLEM_REPORTS_FLAG = "problem_reports.enabled"

/** Only these keys are requested. Unknown response flags are never consulted. */
val REQUESTED_FLAG_KEYS: List<String> = listOf(DIAGNOSTICS_FLAG, PROBLEM_REPORTS_FLAG)

const val CONFIG_MAX_BODY_BYTES = 64 * 1024
const val CONFIG_CONNECT_TIMEOUT_MS = 5_000
const val CONFIG_READ_TIMEOUT_MS = 5_000
const val CONFIG_DEFAULT_MAX_AGE_SECONDS = 60
const val CONFIG_MAX_AGE_SECONDS_LIMIT = 86_400
const val CONFIG_MAX_STALE_MILLIS = 24L * 60L * 60L * 1_000L
const val CONFIG_MAX_CLOCK_SKEW_MILLIS = 5L * 60L * 1_000L
const val CONFIG_MAX_VERSION_CODE = 2_100_000_000

const val FLAG_KILL_SWITCH_SOURCE = "flag_kill_switch"
const val GLOBAL_KILL_SWITCH_SOURCE = "global_kill_switch"

/** Exact backend request: keys plus the installed version code. No client identifier. */
@Serializable
data class ConfigRequestPayload(
    val keys: List<String>,
    @SerialName("app_version_code") val appVersionCode: Int,
)

data class RemoteFlag(
    val enabled: Boolean,
    val version: Int,
    val source: String,
)

data class ConfigSnapshot(
    val revision: String,
    val generatedAt: String,
    val fetchedAtMillis: Long,
    val maxAgeSeconds: Int,
    val killSwitch: Boolean,
    val flags: Map<String, RemoteFlag>,
) {
    fun isFresh(nowMillis: Long): Boolean =
        isWithin(nowMillis, maxAgeSeconds.toLong() * 1_000L)

    fun isWithinMaxStale(nowMillis: Long): Boolean =
        isWithin(nowMillis, CONFIG_MAX_STALE_MILLIS)

    private fun isWithin(nowMillis: Long, windowMillis: Long): Boolean {
        if (fetchedAtMillis <= 0) return false
        if (nowMillis < fetchedAtMillis - CONFIG_MAX_CLOCK_SKEW_MILLIS) return false
        val age = (nowMillis - fetchedAtMillis).coerceAtLeast(0L)
        return age <= windowMillis
    }

    /**
     * Effective availability is local consent AND a valid remote flag. A stale snapshot,
     * global kill switch, or per-flag kill-switch source forces the relevant feature OFF.
     * This method cannot write consent.
     */
    fun effectiveFeatures(consent: ConsentState, nowMillis: Long): EffectiveFeatures {
        if (killSwitch || !isWithinMaxStale(nowMillis)) return EffectiveFeatures.OFF

        fun remoteOn(key: String): Boolean {
            val flag = flags[key] ?: return false
            if (!flag.enabled) return false
            return flag.source != FLAG_KILL_SWITCH_SOURCE &&
                flag.source != GLOBAL_KILL_SWITCH_SOURCE
        }

        return EffectiveFeatures(
            diagnosticReports = consent.diagnosticReportsEnabled &&
                remoteOn(DIAGNOSTICS_FLAG),
            problemReports = consent.problemReportsEnabled &&
                remoteOn(PROBLEM_REPORTS_FLAG),
        )
    }
}

data class EffectiveFeatures(
    val diagnosticReports: Boolean,
    val problemReports: Boolean,
) {
    companion object {
        val OFF = EffectiveFeatures(diagnosticReports = false, problemReports = false)
    }
}

enum class ConfigRefreshOutcome {
    UPDATED,
    NOT_CONFIGURED,
    BACKOFF,
    NETWORK_ERROR,
    RATE_LIMITED,
    SERVER_ERROR,
    UNAUTHORIZED,
    HTTP_ERROR,
    INVALID_RESPONSE,
}

data class ConfigRefreshResult(
    val outcome: ConfigRefreshOutcome,
    val snapshot: ConfigSnapshot? = null,
)
