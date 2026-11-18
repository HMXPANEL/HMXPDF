package com.hmx.pdf.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Crash-orphaned editor intermediates are removed by age; fresh ones are never touched. */
class EditorTempFilesTest {

    private fun cache(): File =
        java.nio.file.Files.createTempDirectory("editor-cache").toFile().apply { deleteOnExit() }

    private fun file(dir: File, name: String, modifiedAt: Long): File {
        dir.mkdirs()
        return File(dir, name).apply { writeBytes(byteArrayOf(1)); setLastModified(modifiedAt) }
    }

    @Test
    fun removesOnlyIntermediatesOlderThanTheirBound() {
        val root = cache()
        val docs = File(root, "docs").apply { mkdirs() }
        val sheets = File(root, "sheets").apply { mkdirs() }
        val now = 10_000_000_000L
        // One hour + 1 ms old: orphaned by a crashed save.
        val oldPatched = file(docs, "edited-old.docx", now - EditorTempFiles.PATCHED_MAX_AGE_MS - 1)
        val oldSheets = file(sheets, "edited-old.xlsx", now - EditorTempFiles.PATCHED_MAX_AGE_MS - 1)
        // Recent: may belong to an in-flight save or share.
        val freshPatched = file(docs, "edited-new.docx", now - 1_000)
        val freshSheets = file(sheets, "edited-new.xlsx", now - 1_000)
        // Recovery mirrors have the longer bound.
        val oldMirror = file(root, "recovered-old.docx", now - EditorTempFiles.RECOVERED_MAX_AGE_MS - 1)
        val liveMirror = file(root, "recovered-live.docx", now - EditorTempFiles.RECOVERED_MAX_AGE_MS + 1)
        val unrelated = file(root, "user-download.docx", now - EditorTempFiles.RECOVERED_MAX_AGE_MS - 1)

        EditorTempFiles.cleanup(root, now)

        assertFalse(oldPatched.exists())
        assertFalse(oldSheets.exists())
        assertFalse(oldMirror.exists())
        assertTrue(freshPatched.exists())
        assertTrue(freshSheets.exists())
        assertTrue(liveMirror.exists())
        // Only the editor's own intermediate names may be touched.
        assertTrue(unrelated.exists())
    }
}
