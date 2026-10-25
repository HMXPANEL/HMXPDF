package com.hmx.pdf.data.report

import android.content.Context
import com.hmx.pdf.data.repository.PdfServiceLocator
import java.io.File

/**
 * The only diagnostic checks this app actually performs. They touch app-private storage and the
 * PDF engine; they never read a document, a filename, a path, a log, or device identifiers.
 */
object ReportSelfChecks {
    fun run(context: Context): Map<SelfCheckKey, SelfCheckValue> {
        val app = context.applicationContext
        return linkedMapOf(
            SelfCheckKey.STORAGE_WRITE to check {
                writeProbe(File(app.noBackupFilesDir, "self-check-storage"))
            },
            SelfCheckKey.CACHE_WRITE to check {
                writeProbe(File(app.cacheDir, "self-check-cache"))
            },
            SelfCheckKey.PDF_ENGINE_INIT to check {
                // Constructing the lazy engine is the real initialization path the app uses.
                PdfServiceLocator.pdfViewer
                true
            },
            SelfCheckKey.REPORT_QUEUE_INIT to check {
                val queueDir = File(app.noBackupFilesDir, "reports")
                if (!queueDir.isDirectory && !queueDir.mkdirs() && !queueDir.isDirectory) {
                    return@check false
                }
                ReportQueueFactory.create(app).list()
                true
            },
        )
    }

    private inline fun check(block: () -> Boolean): SelfCheckValue =
        runCatching { if (block()) SelfCheckValue.OK else SelfCheckValue.FAILED }
            .getOrDefault(SelfCheckValue.FAILED)

    private fun writeProbe(file: File): Boolean {
        val directory = file.parentFile ?: return false
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return false
        return runCatching {
            file.writeText("hmx-self-check", Charsets.UTF_8)
            val matches = file.readText(Charsets.UTF_8) == "hmx-self-check"
            matches
        }.getOrDefault(false).also {
            runCatching { file.delete() }
        }
    }
}
