package com.hmx.pdf.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The editor checkpoint store (Phase 4.1): commit/find/clear on the File-rooted core — identity
 * by URI (never display name), validate-before-commit with an atomic rename (the prior good
 * checkpoint survives every failure, disk-full included), generation increments, and refusal of
 * checkpoints that no longer match their recorded bytes.
 */
class EditorCheckpointStoreTest {

    private val CT_MAIN = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"

    @Test
    fun saveCleanupCannotDeleteANewerCheckpoint() {
        val r = root()
        val first = EditorCheckpointStore.commit(r, "content://doc/1", "d.docx", "docx", complete("v1"))!!
        val second = EditorCheckpointStore.commit(r, "content://doc/1", "d.docx", "docx", complete("v2"))!!
        assertFalse(EditorCheckpointStore.clearIfCurrent(r, first))
        assertEquals(second.file, EditorCheckpointStore.find(r, "content://doc/1")!!.file)
        assertTrue(EditorCheckpointStore.clearIfCurrent(r, second))
        assertNull(EditorCheckpointStore.find(r, "content://doc/1"))
    }

    /** A minimal valid DOCX package (what validateOfficePackage requires). */
    private fun docx(text: String): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml"))
            z.write((("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"$CT_MAIN\"/></Types>")).toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write((("<?xml version=\"1.0\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>")).toByteArray())
            z.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun root(): File =
        java.nio.file.Files.createTempDirectory("checkpoint-root").toFile().apply { deleteOnExit() }

    private fun complete(text: String): File =
        File.createTempFile("complete", ".docx").apply { writeBytes(docx(text)); deleteOnExit() }

    // ── commit / find round trip ───────────────────────────────────────────────

    @Test
    fun commitIsDiscoverableWithFullMetadata() {
        val r = root()
        val ck = EditorCheckpointStore.commit(r, "content://doc/1", "report.docx", "docx", complete("v1"), "hash-0")!!
        assertEquals("report.docx", ck.displayName)
        assertEquals("docx", ck.kind)
        assertEquals("content://doc/1", ck.originalUri)
        assertEquals("hash-0", ck.originalHash)
        assertEquals(1, ck.generation)
        assertEquals(ck.checkpointHash, EditorCheckpointStore.sha256(ck.file))
        assertTrue(ck.createdAt > 0)
        assertTrue(ck.sessionId.isNotBlank())
        // find() by identity returns the same checkpoint.
        assertEquals(ck.checkpointHash, EditorCheckpointStore.find(r, "content://doc/1")!!.checkpointHash)
    }

    @Test
    fun displayNamesDoNotIdentifyDocuments() {
        val r = root()
        EditorCheckpointStore.commit(r, "content://doc/1", "same name.docx", "docx", complete("a"), null)
        EditorCheckpointStore.commit(r, "content://doc/2", "same name.docx", "docx", complete("b"), null)
        assertNotEquals(
            EditorCheckpointStore.find(r, "content://doc/1")!!.checkpointHash,
            EditorCheckpointStore.find(r, "content://doc/2")!!.checkpointHash
        )
    }

    @Test
    fun aNewerCommitSupersedesAndIncrementsTheGeneration() {
        val r = root()
        EditorCheckpointStore.commit(r, "content://doc/1", "d.docx", "docx", complete("v1"), "hash-0")
        val second = EditorCheckpointStore.commit(r, "content://doc/1", "d.docx", "docx", complete("v2"), "hash-0")!!
        assertEquals(2, second.generation)
        assertEquals("hash-0", second.originalHash) // sticky: describes the opened destination
        assertEquals(second.checkpointHash, EditorCheckpointStore.find(r, "content://doc/1")!!.checkpointHash)
    }

    // ── failure leaves the last known good checkpoint intact ──────────────────

    @Test
    fun aFailedCommitKeepsThePriorCheckpoint() {
        val r = root()
        val first = EditorCheckpointStore.commit(r, "content://doc/1", "d.docx", "docx", complete("v1"), "hash-0")!!
        // An invalid "package" cannot commit: the previous checkpoint stays discoverable, with
        // its own generation and hash (the write-interruption boundary the roadmap asks for).
        val bad = File.createTempFile("bad", ".docx").apply { writeBytes("not a zip".toByteArray()); deleteOnExit() }
        assertNull(EditorCheckpointStore.commit(r, "content://doc/1", "d.docx", "docx", bad, "hash-0"))
        val found = EditorCheckpointStore.find(r, "content://doc/1")!!
        assertEquals(first.checkpointHash, found.checkpointHash)
        assertEquals(1, found.generation)
    }

    @Test
    fun aCorruptedCheckpointIsRefusedWithoutDeletingRecoveryData() {
        val r = root()
        val ck = EditorCheckpointStore.commit(r, "content://doc/1", "d.docx", "docx", complete("v1"), null)!!
        ck.file.writeBytes(ck.file.readBytes().copyOfRange(0, 10)) // truncate: hash no longer matches
        assertNull(EditorCheckpointStore.find(r, "content://doc/1"))
        assertTrue(ck.directory.exists()) // retain evidence and other generations
    }

    @Test
    fun interruptionAfterPackageWriteKeepsThePreviouslyPublishedGeneration() {
        val r = root()
        val first = EditorCheckpointStore.commit(r, "content://doc/1", "d.docx", "docx", complete("v1"), "original")!!
        val firstBytes = first.file.readBytes()
        val failed = EditorCheckpointStore.commit(r, "content://doc/1", "d.docx", "docx", complete("v2"), "original") {
            throw java.io.IOException("Injected failure before metadata publication")
        }
        assertNull(failed)
        val recovered = EditorCheckpointStore.find(r, "content://doc/1")!!
        assertEquals(first.file, recovered.file)
        assertTrue(firstBytes.contentEquals(recovered.file.readBytes()))
        assertEquals(first.generation, recovered.generation)
        val next = EditorCheckpointStore.commit(r, "content://doc/1", "d.docx", "docx", complete("v3"), "original")!!
        assertEquals(first.generation + 1, next.generation)
        assertEquals(next.file, EditorCheckpointStore.find(r, "content://doc/1")!!.file)
    }

    @Test
    fun storageIsBoundedToOneGenerationPlusMetadata() {
        val r = root()
        val first = EditorCheckpointStore.commit(r, "content://doc/1", "d.docx", "docx", complete("v1"))!!
        val dir = first.directory
        // Leftovers a crash can produce: an unpublished generation and a temp copy.
        val orphan = File(dir, "checkpoint-${java.util.UUID.randomUUID()}.docx").apply { writeBytes(byteArrayOf(1)) }
        val temp = File(dir, "${java.util.UUID.randomUUID()}.tmp").apply { writeBytes(byteArrayOf(1)) }
        val second = EditorCheckpointStore.commit(r, "content://doc/1", "d.docx", "docx", complete("v2"))!!
        assertFalse(first.file.exists())
        assertFalse(orphan.exists())
        assertFalse(temp.exists())
        assertEquals(second.file, EditorCheckpointStore.find(r, "content://doc/1")!!.file)
        assertEquals(setOf(second.file.name, "meta.properties"), dir.list()!!.toSet())
    }

    @Test
    fun aFailedPublicationLeavesNoGenerationBehind() {
        val r = root()
        val first = EditorCheckpointStore.commit(r, "content://doc/1", "d.docx", "docx", complete("v1"))!!
        val before = first.directory.list()!!.toSet()
        val failed = EditorCheckpointStore.commit(r, "content://doc/1", "d.docx", "docx", complete("v2")) {
            throw java.io.IOException("Injected failure after package rename")
        }
        assertNull(failed)
        assertEquals(before, first.directory.list()!!.toSet())
        assertEquals(first.file, EditorCheckpointStore.find(r, "content://doc/1")!!.file)
    }

    @Test
    fun aCheckpointCopiedUnderAnotherIdentityIsRefused() {
        val r = root()
        val ck = EditorCheckpointStore.commit(r, "content://doc/1", "d.docx", "docx", complete("v1"))!!
        val other = File(r, EditorCheckpointStore.keyFor("content://doc/2")).apply { mkdirs() }
        ck.directory.listFiles()!!.forEach { it.copyTo(File(other, it.name), overwrite = true) }
        // The metadata still names doc/1, so it must not surface as doc/2's recovery copy.
        assertNull(EditorCheckpointStore.find(r, "content://doc/2"))
    }

    @Test
    fun clearRemovesEverythingForTheIdentity() {
        val r = root()
        val ck = EditorCheckpointStore.commit(r, "content://doc/1", "d.docx", "docx", complete("v1"), null)!!
        EditorCheckpointStore.clear(r, "content://doc/1")
        assertNull(EditorCheckpointStore.find(r, "content://doc/1"))
        assertFalse(ck.directory.exists())
    }

    @Test
    fun unknownIdentityHasNoCheckpoint() {
        val r = root()
        assertNull(EditorCheckpointStore.find(r, "content://never-opened"))
    }

    // ── context overloads resolve to the no-backup root ────────────────────────

    @Test
    fun contextOverloadsKeyTheSameIdentityTheSameWay() {
        // The Context half must key the same identity the same way (find and clear coordinate
        // through it); keys are sha-256 hex, so equal identities collide and others do not.
        val key = EditorCheckpointStore.keyFor("content://doc/1")
        assertEquals(key, EditorCheckpointStore.keyFor("content://doc/1"))
        assertNotEquals(key, EditorCheckpointStore.keyFor("content://doc/2"))
        assertEquals(64, key.length)
    }
}
