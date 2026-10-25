package com.hmx.pdf.data.report

import com.hmx.pdf.data.normalizeUuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

const val REPORT_SCHEMA_VERSION = 1
const val REPORT_MAX_BODY_BYTES = 16 * 1024
const val REPORT_MAX_DESCRIPTION_CHARS = 4_000
const val REPORT_MAX_ERROR_SUMMARY_CHARS = 200
const val REPORT_MAX_ERROR_DETAIL_CHARS = 4_000
const val REPORT_MAX_ATTACHED_ERROR_BYTES = 4_000
const val REPORT_MAX_SELF_CHECKS_BYTES = 2_000
const val REPORT_MAX_VERSION_CODE = 2_100_000_000
const val REPORT_FUTURE_TOLERANCE_MILLIS = 5L * 60L * 1_000L
const val REPORT_MAX_AGE_MILLIS = 30L * 24L * 60L * 60L * 1_000L

private val ControlChars = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
private val LocalePattern = Regex("^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$")
private val Iso8601Pattern =
    Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")

internal val ReportJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}

@Serializable
enum class ReportKind {
    @SerialName("diagnostic_test") DIAGNOSTIC_TEST,
    @SerialName("problem") PROBLEM,
}

@Serializable
enum class ProblemCategory {
    @SerialName("bug") BUG,
    @SerialName("crash") CRASH,
    @SerialName("performance") PERFORMANCE,
    @SerialName("other") OTHER,
}

@Serializable
enum class BuildFlavor {
    @SerialName("play") PLAY,
    @SerialName("foss") FOSS,
    @SerialName("fdroid") FDROID,
}

@Serializable
enum class BuildType {
    @SerialName("debug") DEBUG,
    @SerialName("release") RELEASE,
}

@Serializable
enum class SelfCheckKey {
    @SerialName("storage_write") STORAGE_WRITE,
    @SerialName("cache_write") CACHE_WRITE,
    @SerialName("pdf_engine_init") PDF_ENGINE_INIT,
    @SerialName("report_queue_init") REPORT_QUEUE_INIT,
}

@Serializable
enum class SelfCheckValue {
    @SerialName("ok") OK,
    @SerialName("failed") FAILED,
    @SerialName("skipped") SKIPPED,
}

val SELF_CHECK_ORDER: List<SelfCheckKey> = listOf(
    SelfCheckKey.STORAGE_WRITE,
    SelfCheckKey.CACHE_WRITE,
    SelfCheckKey.PDF_ENGINE_INIT,
    SelfCheckKey.REPORT_QUEUE_INIT,
)

@Serializable
data class ReportApp(
    @SerialName("version_code") val versionCode: Int,
    @SerialName("version_name") val versionName: String,
    val flavor: BuildFlavor,
    @SerialName("build_type") val buildType: BuildType,
    @SerialName("android_api") val androidApi: Int? = null,
    val locale: String? = null,
)

@Serializable
data class AttachedError(
    val summary: String,
    val detail: String? = null,
)

@Serializable
data class ReportPayload(
    @SerialName("schema_version") val schemaVersion: Int = REPORT_SCHEMA_VERSION,
    val kind: ReportKind,
    @SerialName("client_report_id") val clientReportId: String,
    @SerialName("install_id") val installId: String,
    @SerialName("client_created_at") val clientCreatedAt: String,
    val app: ReportApp,
    val category: ProblemCategory? = null,
    val description: String? = null,
    @SerialName("attached_error") val attachedError: AttachedError? = null,
    @SerialName("self_checks") val selfChecks: Map<SelfCheckKey, SelfCheckValue>? = null,
) {
    /** Returns a backend-compatible error code, or null when the payload is valid. */
    fun validationError(nowMillis: Long = System.currentTimeMillis()): String? {
        if (schemaVersion != REPORT_SCHEMA_VERSION) return "invalid_schema_version"
        if (normalizeUuid(clientReportId) != clientReportId) return "invalid_client_report_id"
        if (normalizeUuid(installId) != installId) return "invalid_install_id"

        val createdAt = ReportTimestamps.parseIso8601Utc(clientCreatedAt)
            ?: return "invalid_client_created_at"
        if (createdAt > nowMillis + REPORT_FUTURE_TOLERANCE_MILLIS) {
            return "invalid_client_created_at"
        }
        if (createdAt < nowMillis - REPORT_MAX_AGE_MILLIS) {
            return "invalid_client_created_at"
        }

        if (app.versionCode !in 0..REPORT_MAX_VERSION_CODE) return "invalid_app"
        if (!validText(app.versionName, 1, 64)) return "invalid_app"
        val androidApi = app.androidApi
        if (androidApi != null && androidApi !in 1..100) return "invalid_app"
        val locale = app.locale
        if (locale != null) {
            if (locale.length !in 1..16 || !LocalePattern.matches(locale)) {
                return "invalid_app"
            }
        }

        when (kind) {
            ReportKind.DIAGNOSTIC_TEST -> {
                if (description != null || category != null || attachedError != null) {
                    return "invalid_kind"
                }
                val checks = selfChecks ?: return "invalid_self_checks"
                if (checks.isEmpty()) return "invalid_self_checks"
                if (checks.keys.any { it !in SELF_CHECK_ORDER }) return "invalid_self_checks"
                val encodedChecks = ReportJson.encodeToString(checks).toByteArray(Charsets.UTF_8)
                if (encodedChecks.size > REPORT_MAX_SELF_CHECKS_BYTES) {
                    return "invalid_self_checks"
                }
            }
            ReportKind.PROBLEM -> {
                if (selfChecks != null) return "invalid_kind"
                val text = description ?: return "invalid_description"
                if (!validText(text, 1, REPORT_MAX_DESCRIPTION_CHARS)) {
                    return "invalid_description"
                }
                if (attachedError != null) {
                    val error = attachedError
                    if (!validText(error.summary, 1, REPORT_MAX_ERROR_SUMMARY_CHARS)) {
                        return "invalid_attached_error"
                    }
                    val detail = error.detail
                    if (detail != null &&
                        !validText(detail, 1, REPORT_MAX_ERROR_DETAIL_CHARS)
                    ) {
                        return "invalid_attached_error"
                    }
                    val encodedError = ReportJson.encodeToString(error).toByteArray(Charsets.UTF_8)
                    if (encodedError.size > REPORT_MAX_ATTACHED_ERROR_BYTES) {
                        return "invalid_attached_error"
                    }
                }
            }
        }

        if (ReportJson.encodeToString(this).toByteArray(Charsets.UTF_8).size >
            REPORT_MAX_BODY_BYTES
        ) {
            return "payload_too_large"
        }
        return null
    }

    fun requireValid(nowMillis: Long = System.currentTimeMillis()) {
        val error = validationError(nowMillis)
        if (error != null) throw ReportValidationException(error)
    }

    private fun validText(value: String, min: Int, max: Int): Boolean =
        value.length in min..max &&
            value.trim().isNotEmpty() &&
            !ControlChars.containsMatchIn(value)
}

class ReportValidationException(val code: String) : IllegalArgumentException(code)

object ReportTimestamps {
    private const val PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

    fun iso8601Utc(millis: Long): String = formatter().format(Date(millis))

    fun parseIso8601Utc(value: String): Long? {
        if (!Iso8601Pattern.matches(value)) return null
        return runCatching {
            val parsed = formatter().parse(value) ?: return null
            if (formatter().format(parsed) != value) return null
            parsed.time
        }.getOrNull()
    }

    fun nowIso8601(): String = iso8601Utc(System.currentTimeMillis())

    private fun formatter() = SimpleDateFormat(PATTERN, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
}

object ReportPayloadFactory {
    fun diagnostic(
        clientReportId: String,
        installId: String,
        clientCreatedAt: String,
        app: ReportApp,
        selfChecks: Map<SelfCheckKey, SelfCheckValue>,
        nowMillis: Long = System.currentTimeMillis(),
    ): ReportPayload {
        val payload = ReportPayload(
            kind = ReportKind.DIAGNOSTIC_TEST,
            clientReportId = requireUuid(clientReportId, "invalid_client_report_id"),
            installId = requireUuid(installId, "invalid_install_id"),
            clientCreatedAt = clientCreatedAt,
            app = app,
            selfChecks = SELF_CHECK_ORDER.mapNotNull { key ->
                selfChecks[key]?.let { key to it }
            }.toMap(),
        )
        payload.requireValid(nowMillis)
        return payload
    }

    fun problem(
        clientReportId: String,
        installId: String,
        clientCreatedAt: String,
        app: ReportApp,
        description: SanitizeResult,
        category: ProblemCategory? = null,
        attachedError: AttachedError? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): ReportPayload {
        if (!description.safeToSubmit) {
            throw ReportValidationException("unsafe_description")
        }
        val safeAttachedError = attachedError?.let { error ->
            val summary = ReportSanitizer.errorSummary(error.summary)
            val detail = error.detail?.let(ReportSanitizer::errorDetail)
            if (!summary.safeToSubmit || detail?.safeToSubmit == false) {
                throw ReportValidationException("unsafe_attached_error")
            }
            AttachedError(summary.text, detail?.text)
        }
        val payload = ReportPayload(
            kind = ReportKind.PROBLEM,
            clientReportId = requireUuid(clientReportId, "invalid_client_report_id"),
            installId = requireUuid(installId, "invalid_install_id"),
            clientCreatedAt = clientCreatedAt,
            app = app,
            category = category,
            description = description.text,
            attachedError = safeAttachedError,
        )
        payload.requireValid(nowMillis)
        return payload
    }

    private fun requireUuid(value: String, code: String): String =
        normalizeUuid(value) ?: throw ReportValidationException(code)
}

@Serializable
enum class ReportState {
    @SerialName("pending") PENDING,
    @SerialName("failed") FAILED,
    @SerialName("submitted") SUBMITTED,
    @SerialName("rejected") REJECTED,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
enum class ReportErrorCode {
    @SerialName("timeout") TIMEOUT,
    @SerialName("offline") OFFLINE,
    @SerialName("rate_limited") RATE_LIMITED,
    @SerialName("server_error") SERVER_ERROR,
    @SerialName("invalid_response") INVALID_RESPONSE,
    @SerialName("rejected") REJECTED,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
data class ReportEnvelope(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val payload: ReportPayload,
    val state: ReportState = ReportState.PENDING,
    @SerialName("server_id") val serverId: String? = null,
    val attempts: Int = 0,
    @SerialName("last_error") val lastError: ReportErrorCode? = null,
    @SerialName("created_at") val createdAtMillis: Long,
    @SerialName("updated_at") val updatedAtMillis: Long,
) {
    val isTerminal: Boolean
        get() = state == ReportState.SUBMITTED ||
            state == ReportState.REJECTED ||
            state == ReportState.CANCELLED
}
