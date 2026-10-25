package com.hmx.pdf.data.report

import com.hmx.pdf.data.normalizeUuid
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private val QueueJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

class ReportQueueException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class ReportQueueFullException :
    ReportQueueException("report_queue_full")

/**
 * One JSON envelope per report under a caller-supplied private directory. Android wiring uses
 * noBackupFilesDir, so neither payloads nor the install UUID reach cloud/device-transfer backup.
 * Enqueueing never uploads anything and corrupt files are ignored rather than crashing.
 */
class ReportQueueStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MAX_REPORTS = 50
        const val ENVELOPE_SCHEMA_VERSION = 1
    }

    fun enqueue(payload: ReportPayload): ReportEnvelope {
        payload.requireValid(clock())
        val file = fileFor(payload.clientReportId)
        read(file)?.let { return it }

        ensureDirectory()
        pruneTo(MAX_REPORTS - 1)
        if (jsonFiles().size >= MAX_REPORTS) throw ReportQueueFullException()

        val now = clock()
        val envelope = ReportEnvelope(
            schemaVersion = ENVELOPE_SCHEMA_VERSION,
            payload = payload,
            state = ReportState.PENDING,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        writeAtomic(file, QueueJson.encodeToString(envelope))
        return envelope
    }

    fun list(): List<ReportEnvelope> =
        jsonFiles()
            .mapNotNull(::read)
            .sortedByDescending { it.createdAtMillis }

    fun get(clientReportId: String): ReportEnvelope? =
        normalizeUuid(clientReportId)?.let { read(fileFor(it)) }

    /** Terminal states are immutable; invalid transitions return null instead of throwing. */
    fun transition(
        clientReportId: String,
        state: ReportState,
        serverId: String? = null,
        error: ReportErrorCode? = null,
    ): ReportEnvelope? {
        val current = get(clientReportId) ?: return null
        if (current.isTerminal) return null

        val normalizedServerId = when {
            serverId == null -> null
            else -> normalizeUuid(serverId) ?: return null
        }
        if (state == ReportState.SUBMITTED && normalizedServerId == null) return null
        if (state == ReportState.REJECTED && error == null) return null

        val updated = current.copy(
            state = state,
            serverId = if (state == ReportState.SUBMITTED) normalizedServerId else null,
            lastError = error,
            updatedAtMillis = maxOf(clock(), current.updatedAtMillis),
        )
        return runCatching {
            writeAtomic(fileFor(clientReportId), QueueJson.encodeToString(updated))
            updated
        }.getOrNull()
    }

    fun recordAttempt(clientReportId: String): ReportEnvelope? {
        val current = get(clientReportId) ?: return null
        if (current.isTerminal) return null
        val updated = current.copy(
            state = ReportState.PENDING,
            attempts = current.attempts + 1,
            lastError = null,
            updatedAtMillis = maxOf(clock(), current.updatedAtMillis),
        )
        return runCatching {
            writeAtomic(fileFor(clientReportId), QueueJson.encodeToString(updated))
            updated
        }.getOrNull()
    }

    fun delete(clientReportId: String): Boolean {
        val id = normalizeUuid(clientReportId) ?: return false
        return runCatching { fileFor(id).delete() }.getOrDefault(false)
    }

    private fun pruneTo(limit: Int) {
        val files = jsonFiles()
        if (files.size <= limit) return

        val valid = LinkedHashMap<File, ReportEnvelope>()
        for (file in files) {
            val envelope = read(file)
            if (envelope == null) {
                runCatching { file.delete() }
            } else {
                valid[file] = envelope
            }
        }

        var count = valid.size
        val terminalOldestFirst = valid.entries
            .filter { it.value.isTerminal }
            .sortedBy { it.value.updatedAtMillis }
        for (entry in terminalOldestFirst) {
            if (count <= limit) break
            if (runCatching { entry.key.delete() }.getOrDefault(false)) count--
        }
    }

    private fun ensureDirectory() {
        if (directory.isDirectory) return
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw ReportQueueException("queue_directory_unavailable")
        }
    }

    private fun jsonFiles(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.toList()
            .orEmpty()

    private fun fileFor(clientReportId: String): File {
        val id = normalizeUuid(clientReportId)
            ?: throw ReportQueueException("invalid_report_id")
        return File(directory, "$id.json")
    }

    private fun read(file: File): ReportEnvelope? = runCatching {
        val envelope = QueueJson.decodeFromString<ReportEnvelope>(file.readText())
        val serverId = envelope.serverId
        when {
            envelope.schemaVersion != ENVELOPE_SCHEMA_VERSION -> null
            envelope.payload.clientReportId != file.nameWithoutExtension -> null
            envelope.createdAtMillis <= 0 -> null
            envelope.updatedAtMillis < envelope.createdAtMillis -> null
            envelope.attempts < 0 -> null
            serverId != null && normalizeUuid(serverId) == null -> null
            else -> envelope
        }
    }.getOrNull()

    private fun writeAtomic(file: File, content: String) {
        ensureDirectory()
        val temp = File(directory, "${file.name}.tmp")
        try {
            temp.writeText(content, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } catch (error: Exception) {
            runCatching { temp.delete() }
            throw ReportQueueException("queue_write_failed", error)
        }
    }
}
