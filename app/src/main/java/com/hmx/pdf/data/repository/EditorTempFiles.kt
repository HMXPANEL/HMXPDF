package com.hmx.pdf.data.repository

import android.content.Context
import java.io.File

/**
 * Bounded cache for editor intermediates (Phase 4.1). A save writes the patched package under
 * `cacheDir/{docs,sheets}/` and deletes it in a `finally`; a crash between those steps leaves an
 * orphan. Recovery mirrors (`recovered-*`) live directly in `cacheDir` and stay needed for the
 * rest of the session, so only stale ones are removed.
 *
 * Ages are deliberately generous: deleting a file that is still being read would break an
 * in-flight save, share or PDF conversion. A live save is always much younger than these bounds.
 */
internal object EditorTempFiles {

    internal const val PATCHED_MAX_AGE_MS = 60L * 60L * 1000L
    internal const val RECOVERED_MAX_AGE_MS = 24L * 60L * 60L * 1000L

    fun cleanup(context: Context, now: Long = System.currentTimeMillis()) {
        cleanup(context.cacheDir, now)
    }

    internal fun cleanup(cacheDir: File, now: Long) {
        removeOlder(File(cacheDir, "docs"), now, PATCHED_MAX_AGE_MS)
        removeOlder(File(cacheDir, "sheets"), now, PATCHED_MAX_AGE_MS)
        for (file in cacheDir.listFiles() ?: return) {
            if (file.isFile && file.name.startsWith("recovered-") &&
                age(file, now) > RECOVERED_MAX_AGE_MS
            ) {
                file.delete()
            }
        }
    }

    private fun removeOlder(dir: File, now: Long, maxAgeMs: Long) {
        for (file in dir.listFiles() ?: return) {
            if (file.isFile && age(file, now) > maxAgeMs) file.delete()
        }
    }

    private fun age(file: File, now: Long): Long = now - file.lastModified()
}
