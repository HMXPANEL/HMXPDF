package com.hmx.pdf.data.repository

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/**
 * Durable editor checkpoints for unsaved edits (Phase 4.1): one complete, validated package per
 * document identity under [Context.getNoBackupFilesDir], so a process death between saves still
 * recovers the last committed checkpoint.
 *
 * Identity is the sha-256 of the source URI string — never the display name (two documents may
 * share a name; a URI does not change because the user renamed it). One checkpoint is kept per
 * document: a commit writes a new immutable generation, validates it, then publishes its filename
 * through the metadata rename. Until publication, the previous metadata and package remain intact.
 * An interrupted generation is never substituted for the last committed one.
 *
 * [originalHash] is the hash of the destination as it was when the document was opened; recovery
 * compares it against the current original to label conflicts ("the file changed since this
 * checkpoint"). The journal at [SaveRecovery] remains the owner of manual-save crash safety;
 * this store only covers edits that were never saved. The [Context]-bound halves delegate to the
 * File-rooted core, which JVM tests exercise directly.
 */
internal object EditorCheckpointStore {

    /** Bump when the metadata layout changes; readers refuse unknown versions. */
    const val VERSION = 1
    private const val ROOT = "editor-checkpoints"
    private const val META = "meta.properties"
    private val LEGACY_FILE = Regex("""checkpoint\.(docx|xlsx)""")
    private val GENERATION_FILE = Regex("""checkpoint-[0-9a-fA-F-]{36}\.(docx|xlsx)""")
    private val TEMP_FILE = Regex("""[0-9a-fA-F-]{36}\.tmp""")
    private val TEMP_META = Regex("""[0-9a-fA-F-]{36}\.meta\.tmp""")

    /** One recoverable checkpoint: the committed package plus its identity metadata. */
    data class Checkpoint(
        val docKey: String,
        val directory: File,
        val file: File,
        val kind: String,
        val displayName: String,
        val originalUri: String,
        val originalHash: String,
        val checkpointHash: String,
        val generation: Long,
        val createdAt: Long,
        val sessionId: String
    )

    /** Stable per-document key: sha-256 of the identity string, hex-encoded. */
    fun keyFor(identity: String): String =
        MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ── Context API ────────────────────────────────────────────────────────────

    /** Commits [complete] as the newest checkpoint of [sourceUri]; null when the commit failed. */
    fun commit(
        context: Context,
        sourceUri: String?,
        displayName: String,
        kind: String,
        complete: File,
        originalHash: String?
    ): Checkpoint? = sourceUri?.takeIf { it.isNotBlank() }?.let {
        commit(File(context.noBackupFilesDir, ROOT), it, displayName, kind, complete, originalHash)
    }

    /** The committed checkpoint of [sourceUri], when one exists and still validates. */
    fun find(context: Context, sourceUri: String): Checkpoint? =
        find(File(context.noBackupFilesDir, ROOT), sourceUri)

    fun clear(context: Context, sourceUri: String) =
        clear(File(context.noBackupFilesDir, ROOT), sourceUri)

    fun clearIfCurrent(context: Context, checkpoint: Checkpoint): Boolean =
        clearIfCurrent(File(context.noBackupFilesDir, ROOT), checkpoint)

    /** A save may only retire the generation it observed, never a newer recovery copy. */
    @Synchronized
    internal fun clearIfCurrent(root: File, checkpoint: Checkpoint): Boolean {
        val current = find(root, checkpoint.originalUri) ?: return false
        if (current.sessionId != checkpoint.sessionId) return false
        clear(root, checkpoint.originalUri)
        return true
    }

    // ── File-rooted core (JVM-testable) ───────────────────────────────────────

    @Synchronized
    internal fun commit(
        root: File,
        identity: String,
        displayName: String,
        kind: String,
        complete: File,
        originalHash: String? = null,
        beforePublish: () -> Unit = {}
    ): Checkpoint? {
        require(kind == "docx" || kind == "xlsx") { "Unsupported document kind" }
        if (!complete.isFile || complete.length() <= 0L) return null
        val dir = File(root, keyFor(identity))
        val generationId = UUID.randomUUID().toString()
        val tmp = File(dir, "$generationId.tmp")
        val target = File(dir, "checkpoint-$generationId.$kind")
        val metaTmp = File(dir, "$generationId.meta.tmp")
        val meta = File(dir, META)
        // Set once the metadata rename makes `target` the published generation. Before that,
        // every exit path removes `target` so interrupted commits cannot accumulate packages.
        var published = false
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return null
            copyValidated(complete, tmp, kind) ?: return null
            val hash = sha256(tmp)
            val previous = readCheckpoint(dir, kind)
            // This immutable package is not discoverable until metadata names it.
            if (!tmp.renameTo(target)) return null
            // The metadata rename is the only publication point.
            val properties = Properties().apply {
                setProperty("version", VERSION.toString())
                setProperty("identity", identity)
                setProperty("displayName", displayName)
                setProperty("kind", kind)
                setProperty("file", target.name)
                setProperty("checkpointHash", hash)
                setProperty("generation", ((previous?.generation ?: 0L) + 1L).toString())
                setProperty("createdAt", System.currentTimeMillis().toString())
                setProperty("sessionId", UUID.randomUUID().toString())
                setProperty("originalHash", originalHash ?: previous?.originalHash ?: "")
            }
            FileOutputStream(metaTmp).use { out ->
                properties.store(out, "HMXPDF editor checkpoint")
                out.fd.sync()
            }
            beforePublish()
            if (!metaTmp.renameTo(meta)) return null
            published = true
            val checkpoint = readCheckpoint(dir, kind)
            // The store keeps exactly one generation per document: superseded packages are
            // removed only after the new one is published, so an interruption always leaves the
            // previous checkpoint intact.
            if (checkpoint != null) prune(dir, checkpoint.file.name)
            return checkpoint
        } catch (cancellation: java.util.concurrent.CancellationException) {
            throw cancellation
        } catch (expected: Exception) {
            // Disk-full, interruption, anything: remove our partials, leave the prior checkpoint.
            return null
        } finally {
            if (!published) target.delete()
            tmp.delete()
            metaTmp.delete()
        }
    }

    /**
     * Deletes names this store creates except [keepName] and the metadata. Superseded generations
     * and interrupted temps are bounded storage; nothing else in the directory is touched.
     */
    private fun prune(dir: File, keepName: String) {
        for (name in dir.list() ?: return) {
            if (name == META || name == keepName) continue
            if (!ownedName(name)) continue
            File(dir, name).delete()
        }
    }

    private fun ownedName(name: String): Boolean =
        LEGACY_FILE.matches(name) || GENERATION_FILE.matches(name) ||
            TEMP_FILE.matches(name) || TEMP_META.matches(name)

    /**
     * Copies [from] to [to] and validates the copy as a [kind] package *before* it can become a
     * checkpoint; returns the copy, or null (with [to] removed) when the package is invalid.
     */
    private fun copyValidated(from: File, to: File, kind: String): File? {
        try {
            from.inputStream().use { input ->
                FileOutputStream(to).use { out ->
                    input.copyTo(out)
                    out.fd.sync()
                }
            }
            return if (SaveRecovery.validateOfficePackage(to, kind)) to else { to.delete(); null }
        } catch (cancellation: java.util.concurrent.CancellationException) {
            to.delete()
            throw cancellation
        } catch (expected: Exception) {
            to.delete()
            return null
        }
    }

    @Synchronized
    internal fun find(root: File, identity: String): Checkpoint? {
        val dir = File(root, keyFor(identity))
        if (!dir.isDirectory) return null
        return runCatching {
            readCheckpoint(dir, "docx")?.takeIf { checkpoint ->
                checkpoint.originalUri == identity &&
                    sha256(checkpoint.file) == checkpoint.checkpointHash &&
                    SaveRecovery.validateOfficePackage(checkpoint.file, checkpoint.kind)
            }?.also { checkpoint -> prune(dir, checkpoint.file.name) }
        }.getOrNull()
    }

    private fun readCheckpoint(dir: File, kind: String): Checkpoint? {
        val meta = File(dir, META)
        if (!meta.isFile) return null
        val properties = Properties()
        try {
            meta.inputStream().use { properties.load(it) }
        } catch (expected: Exception) {
            return null
        }
        if (properties.getProperty("version")?.toIntOrNull() != VERSION) return null
        val recordedKind = properties.getProperty("kind") ?: kind
        if (recordedKind != "docx" && recordedKind != "xlsx") return null
        // Read older checkpoints too; new commits always name an immutable generation.
        val filename = properties.getProperty("file") ?: "checkpoint.$recordedKind"
        if (File(filename).name != filename || !filename.endsWith(".$recordedKind")) return null
        val file = File(dir, filename)
        if (!file.isFile) return null
        val identity = properties.getProperty("identity") ?: return null
        return Checkpoint(
            docKey = keyFor(identity),
            directory = dir,
            file = file,
            kind = recordedKind,
            displayName = properties.getProperty("displayName") ?: "Document",
            originalUri = identity,
            originalHash = properties.getProperty("originalHash") ?: "",
            checkpointHash = properties.getProperty("checkpointHash") ?: "",
            generation = properties.getProperty("generation")?.toLongOrNull() ?: 0L,
            createdAt = properties.getProperty("createdAt")?.toLongOrNull() ?: 0L,
            sessionId = properties.getProperty("sessionId") ?: ""
        )
    }

    @Synchronized
    internal fun clear(root: File, identity: String) {
        File(root, keyFor(identity)).deleteRecursively()
    }

    internal fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").run {
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    update(buffer, 0, read)
                }
            }
            digest().joinToString("") { "%02x".format(it) }
        }
}
