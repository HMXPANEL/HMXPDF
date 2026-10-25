package com.hmx.pdf.data.report

import com.hmx.pdf.data.normalizeUuid
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val REPORT_CONNECT_TIMEOUT_MS = 10_000
const val REPORT_READ_TIMEOUT_MS = 10_000
const val REPORT_MAX_RESPONSE_BYTES = 8 * 1024

data class ReportHttpResponse(
    val status: Int,
    val body: String,
    val retryAfterSeconds: Long? = null,
)

interface ReportTransport {
    fun post(endpoint: String, publishableKey: String, body: String): ReportHttpResponse
}

/**
 * HTTPS-only POST with a bounded body and no redirects. Request and response content is never
 * logged: reports can contain user-authored text.
 */
class HttpUrlReportTransport(
    private val connectTimeoutMillis: Int = REPORT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMillis: Int = REPORT_READ_TIMEOUT_MS,
) : ReportTransport {
    override fun post(
        endpoint: String,
        publishableKey: String,
        body: String,
    ): ReportHttpResponse {
        val url = URI.create(endpoint).toURL()
        require(url.protocol.equals("https", ignoreCase = true)) {
            "report endpoint must use HTTPS"
        }

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            useCaches = false
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            setRequestProperty("content-type", "application/json; charset=utf-8")
            setRequestProperty("accept", "application/json")
            setRequestProperty("apikey", publishableKey)
        }

        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            require(bytes.size <= REPORT_MAX_BODY_BYTES) { "report body too large" }
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            val status = connection.responseCode
            val stream: InputStream? = if (status in 200..299) {
                runCatching { connection.inputStream }.getOrNull()
            } else {
                runCatching { connection.errorStream }.getOrNull()
            }
            val responseBody = stream?.use { readBounded(it) }?.toString(Charsets.UTF_8).orEmpty()
            return ReportHttpResponse(
                status = status,
                body = responseBody,
                retryAfterSeconds = connection.getHeaderField("retry-after")?.trim()?.toLongOrNull(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4 * 1024)
        while (out.size() <= REPORT_MAX_RESPONSE_BYTES) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

enum class ReportSubmitOutcome {
    SUBMITTED,
    REJECTED,
    RATE_LIMITED,
    TIMEOUT,
    OFFLINE,
    UNAUTHORIZED,
    SERVER_ERROR,
    INVALID_RESPONSE,
    NOT_CONFIGURED,
    CONSENT_OFF,
    UNAVAILABLE,
    LOCAL_ERROR,
}

data class ReportAcknowledgement(
    val reportId: String,
    val status: String,
    val receivedAt: String,
    val duplicate: Boolean = false,
)

data class ReportSubmitResult(
    val outcome: ReportSubmitOutcome,
    val acknowledgement: ReportAcknowledgement? = null,
    val errorCode: ReportErrorCode? = null,
    val httpStatus: Int? = null,
)

@Serializable
private data class ReportAckDto(
    @SerialName("report_id") val reportId: String? = null,
    val status: String? = null,
    @SerialName("received_at") val receivedAt: String? = null,
    val duplicate: Boolean = false,
)

private val AckJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Submits one already-validated payload. The caller owns the stable client report ID; retries
 * reuse it so the backend can deduplicate. This class never enqueues, schedules, or retries.
 */
class ReportSubmissionClient(
    private val endpoint: String,
    private val publishableKey: String,
    private val transport: ReportTransport = HttpUrlReportTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun submit(payload: ReportPayload): ReportSubmitResult {
        if (!isConfigured(endpoint, publishableKey)) {
            return ReportSubmitResult(ReportSubmitOutcome.NOT_CONFIGURED)
        }
        val validationError = payload.validationError(clock())
        if (validationError != null) {
            return ReportSubmitResult(
                outcome = ReportSubmitOutcome.REJECTED,
                errorCode = ReportErrorCode.REJECTED,
            )
        }

        val body = runCatching { ReportJson.encodeToString(payload) }
            .getOrElse {
                return ReportSubmitResult(
                    outcome = ReportSubmitOutcome.REJECTED,
                    errorCode = ReportErrorCode.REJECTED,
                )
            }
        if (body.toByteArray(Charsets.UTF_8).size > REPORT_MAX_BODY_BYTES) {
            return ReportSubmitResult(
                outcome = ReportSubmitOutcome.REJECTED,
                errorCode = ReportErrorCode.REJECTED,
            )
        }

        val response = try {
            transport.post(endpoint, publishableKey, body)
        } catch (timeout: SocketTimeoutException) {
            return ReportSubmitResult(
                outcome = ReportSubmitOutcome.TIMEOUT,
                errorCode = ReportErrorCode.TIMEOUT,
            )
        } catch (io: IOException) {
            return ReportSubmitResult(
                outcome = ReportSubmitOutcome.OFFLINE,
                errorCode = ReportErrorCode.OFFLINE,
            )
        } catch (invalid: IllegalArgumentException) {
            return ReportSubmitResult(ReportSubmitOutcome.NOT_CONFIGURED)
        } catch (error: Exception) {
            return ReportSubmitResult(
                outcome = ReportSubmitOutcome.OFFLINE,
                errorCode = ReportErrorCode.OFFLINE,
            )
        }

        return when (response.status) {
            200, 201 -> parseAcknowledgement(response.body)
            400, 413, 422 -> ReportSubmitResult(
                outcome = ReportSubmitOutcome.REJECTED,
                errorCode = ReportErrorCode.REJECTED,
                httpStatus = response.status,
            )
            401, 403 -> ReportSubmitResult(
                outcome = ReportSubmitOutcome.UNAUTHORIZED,
                errorCode = ReportErrorCode.SERVER_ERROR,
                httpStatus = response.status,
            )
            429 -> ReportSubmitResult(
                outcome = ReportSubmitOutcome.RATE_LIMITED,
                errorCode = ReportErrorCode.RATE_LIMITED,
                httpStatus = response.status,
            )
            in 500..599 -> ReportSubmitResult(
                outcome = ReportSubmitOutcome.SERVER_ERROR,
                errorCode = ReportErrorCode.SERVER_ERROR,
                httpStatus = response.status,
            )
            else -> ReportSubmitResult(
                outcome = ReportSubmitOutcome.SERVER_ERROR,
                errorCode = ReportErrorCode.SERVER_ERROR,
                httpStatus = response.status,
            )
        }
    }

    private fun parseAcknowledgement(body: String): ReportSubmitResult {
        if (body.toByteArray(Charsets.UTF_8).size > REPORT_MAX_RESPONSE_BYTES) {
            return invalidResponse()
        }
        val dto = runCatching { AckJson.decodeFromString<ReportAckDto>(body) }
            .getOrElse { return invalidResponse() }
        val reportId = dto.reportId?.let(::normalizeUuid) ?: return invalidResponse()
        val status = dto.status?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 }
            ?: return invalidResponse()
        val receivedAt = dto.receivedAt?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 }
            ?: return invalidResponse()
        return ReportSubmitResult(
            outcome = ReportSubmitOutcome.SUBMITTED,
            acknowledgement = ReportAcknowledgement(
                reportId = reportId,
                status = status,
                receivedAt = receivedAt,
                duplicate = dto.duplicate,
            ),
            httpStatus = 200,
        )
    }

    private fun invalidResponse() = ReportSubmitResult(
        outcome = ReportSubmitOutcome.INVALID_RESPONSE,
        errorCode = ReportErrorCode.INVALID_RESPONSE,
    )

    private fun isConfigured(endpoint: String, key: String): Boolean =
        endpoint.isNotBlank() &&
            key.isNotBlank() &&
            endpoint.startsWith("https://", ignoreCase = true)
}
