package com.hmx.pdf.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The pure core of the save-recovery journal (the [Context]-bound halves delegate to it), run as
 * real JVM tests. This resolves the previously `@Ignore`d, empty recovery tests: the journal
 * round trip, the interrupted-write state machine and the restore decision table are all covered
 * here without an Android device (FA-18's test resolution, Phase 4.0).
 */
class SaveRecoveryCoreTest {

    private val CT_MAIN = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"

    /** A minimal valid DOCX package (what validateOfficePackage requires). */
    private fun docx(text: String): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml"))
            z.write(("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"$CT_MAIN\"/></Types>").toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write(("<?xml version=\"1.0\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                "<w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>").toByteArray())
            z.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun newRoot(): File =
        java.nio.file.Files.createTempDirectory("recovery-root").toFile().apply { deleteOnExit() }

    private fun prepared(text: String): File =
        File.createTempFile("prepared", ".docx").apply { writeBytes(docx(text)); deleteOnExit() }

    // ── begin: validated copies + readable journal ─────────────────────────────

    @Test
    fun beginPreparesAValidatedCopyAndAJournaledEntry() {
        val root = newRoot()
        val entry = SaveRecovery.begin(root, null, "doc.docx", "docx", prepared("v1"), requireBackup = false)
        assertEquals(SaveRecovery.State.PREPARED, entry.state)
        assertEquals("doc.docx", entry.displayName)
        assertTrue(entry.backup == null) // a Save As target without a readable original
        val preparedCopy = entry.prepared!!
        assertTrue(preparedCopy.isFile && preparedCopy.length() > 0L)
        assertNotEquals("", entry.preparedHash)
        // The journal is on disk and the entry is still pending (nothing consumed it).
        val pending = SaveRecovery.pending(root) { null }
        assertEquals(1, pending.size)
        assertEquals(entry.preparedHash, pending[0].preparedHash)
    }

    @Test
    fun beginRejectsAnInvalidPackage() {
        val root = newRoot()
        val bad = File.createTempFile("bad", ".docx").apply { writeBytes("not a zip".toByteArray()); deleteOnExit() }
        try {
            SaveRecovery.begin(root, null, "doc.docx", "docx", bad, requireBackup = false)
            throw AssertionError("begin must refuse an invalid package")
        } catch (expected: IOException) { /* refused */ }
        // Nothing was left behind.
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test
    fun journalStateTransitionsAreVisibleOnDisk() {
        val root = newRoot()
        val entry = SaveRecovery.begin(root, null, "doc.docx", "docx", prepared("v1"), requireBackup = false)
        SaveRecovery.markWriting(entry)
        assertEquals(SaveRecovery.State.WRITING, SaveRecovery.pending(root) { null }.first().state)
        SaveRecovery.markFailed(entry)
        assertEquals(SaveRecovery.State.FAILED, SaveRecovery.pending(root) { null }.first().state)
    }

    // ── destination comparison (the previously empty first ignored test) ──────

    @Test
    fun destinationChangedSinceBackupFollowsTheComparisonTable() {
        fun entryWithBackup(originalHash: String): SaveRecovery.Entry = SaveRecovery.Entry(
            directory = newRoot(), destination = null, displayName = "d", kind = "docx",
            backup = File.createTempFile("backup", ".docx").apply { deleteOnExit() },
            prepared = null, originalHash = originalHash, preparedHash = "", state = SaveRecovery.State.PREPARED, createdAt = 0L
        )
        assertFalse(SaveRecovery.destinationChangedSinceBackup(entryWithBackup("h"), "h"))
        assertTrue(SaveRecovery.destinationChangedSinceBackup(entryWithBackup("h"), "different"))
        assertTrue(SaveRecovery.destinationChangedSinceBackup(entryWithBackup("h"), null))
        // No original to compare (Save As target): never "changed".
        val noBackup = SaveRecovery.Entry(
            directory = newRoot(), destination = null, displayName = "d", kind = "docx",
            backup = null, prepared = null, originalHash = "", preparedHash = "",
            state = SaveRecovery.State.PREPARED, createdAt = 0L
        )
        assertFalse(SaveRecovery.destinationChangedSinceBackup(noBackup, null))
    }

    // ── restore decision table (the previously empty second ignored test) ─────

    @Test
    fun restoreStepFollowsTheSafetyTable() {
        // The write actually completed: nothing to restore.
        assertEquals(
            SaveRecovery.RestoreResult.SUCCEEDED,
            SaveRecovery.restoreStep(true, "prepared", "original", hasBackup = true, currentHash = "prepared", destinationValid = true)
        )
        // The destination is still the original we backed up: the write never landed.
        assertEquals(
            SaveRecovery.RestoreResult.UNCHANGED,
            SaveRecovery.restoreStep(true, "prepared", "original", hasBackup = true, currentHash = "original", destinationValid = true)
        )
        // No backup possible: restoring over an unreadable destination is unsafe.
        assertEquals(
            SaveRecovery.RestoreResult.UNSAFE,
            SaveRecovery.restoreStep(true, "prepared", "original", hasBackup = false, currentHash = "other", destinationValid = true)
        )
        // The previously ignored case: the destination is a VALID but DIFFERENT package —
        // it may be someone else's independent change, so the backup is never copied over it.
        assertEquals(
            SaveRecovery.RestoreResult.UNSAFE,
            SaveRecovery.restoreStep(true, "prepared", "original", hasBackup = true, currentHash = "other", destinationValid = true)
        )
        // An invalid (half-written) destination is ours to fix: put the original back.
        assertEquals(
            SaveRecovery.RestoreResult.RESTORED,
            SaveRecovery.restoreStep(true, "prepared", "original", hasBackup = true, currentHash = "other", destinationValid = false)
        )
        // An unreadable destination is never overwritten.
        assertEquals(
            SaveRecovery.RestoreResult.UNSAFE,
            SaveRecovery.restoreStep(true, "prepared", "original", hasBackup = true, currentHash = null, destinationValid = false)
        )
        // No destination at all: nothing to restore onto.
        assertEquals(
            SaveRecovery.RestoreResult.UNSAFE,
            SaveRecovery.restoreStep(false, "prepared", "original", hasBackup = true, currentHash = "other", destinationValid = false)
        )
    }

    // ── pending: automatic discard only where no data can be lost ──────────────

    /**
     * A Save-As-style entry (no destination URI in the JVM test) stays pending until consumed:
     * its prepared copy is valid and nothing may be discarded that could still be recovered.
     * (The destination-hash auto-discard branches need a real destination URI — covered by
     * instrumented runs; on the JVM only their decision inputs are reachable, and those are
     * covered by [destinationChangedSinceBackupFollowsTheComparisonTable].)
     */
    @Test
    fun pendingKeepsASaveAsEntryWithAValidRecoveryCopy() {
        val root = newRoot()
        SaveRecovery.begin(root, null, "doc.docx", "docx", prepared("v1"), requireBackup = false)
        assertEquals(1, SaveRecovery.pending(root) { null }.size)
        // An unrelated destination hash does not discard it either: the copy is still the only
        // recoverable version of the document.
        assertEquals(1, SaveRecovery.pending(root) { "unrelated" }.size)
    }
}
