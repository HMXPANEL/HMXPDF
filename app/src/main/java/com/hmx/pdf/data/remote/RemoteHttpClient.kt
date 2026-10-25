package com.hmx.pdf.data.remote

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

data class ConfigHttpResponse(
    val status: Int,
    val body: String,
    val retryAfterSeconds: Long? = null,
)

interface ConfigTransport {
    fun post(endpoint: String, publishableKey: String, body: String): ConfigHttpResponse
}

/**
 * Minimal HTTPS-only JSON POST using HttpURLConnection. Redirects are not followed, the body
 * is read with a hard cap, and no request/response content is logged.
 */
class HttpUrlConfigTransport(
    private val connectTimeoutMillis: Int = CONFIG_CONNECT_TIMEOUT_MS,
    private val readTimeoutMillis: Int = CONFIG_READ_TIMEOUT_MS,
) : ConfigTransport {
    override fun post(
        endpoint: String,
        publishableKey: String,
        body: String,
    ): ConfigHttpResponse {
        val url = URI.create(endpoint).toURL()
        require(url.protocol.equals("https", ignoreCase = true)) {
            "config endpoint must use HTTPS"
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
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            val status = connection.responseCode
            val stream: InputStream? = if (status in 200..299) {
                runCatching { connection.inputStream }.getOrNull()
            } else {
                runCatching { connection.errorStream }.getOrNull()
            }
            val responseBody = stream?.use { readBounded(it) }?.toString(Charsets.UTF_8).orEmpty()
            return ConfigHttpResponse(
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
        val buffer = ByteArray(8 * 1024)
        while (out.size() <= CONFIG_MAX_BODY_BYTES) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
