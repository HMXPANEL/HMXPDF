package com.hmx.pdf.data.report

import android.content.Context
import java.io.File

object ReportQueueFactory {
    fun create(context: Context): ReportQueueStore =
        ReportQueueStore(File(context.applicationContext.noBackupFilesDir, "reports"))
}

/**
 * Dormant Phase 3.2 foundation. No production code calls [schedule]; Phase 3.4 wires it to an
 * explicit user Send action. The unique name is the only piece needed to keep retries stable.
 */
interface ReportUploadScheduler {
    fun schedule(clientReportId: String)
}

object ReportWorkName {
    fun uniqueName(clientReportId: String): String {
        val id = com.hmx.pdf.data.normalizeUuid(clientReportId)
            ?: throw IllegalArgumentException("invalid client report id")
        return "report-$id"
    }
}
