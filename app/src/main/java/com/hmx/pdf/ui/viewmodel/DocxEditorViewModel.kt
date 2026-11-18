package com.hmx.pdf.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmx.pdf.data.repository.AutosaveCoordinator
import com.hmx.pdf.data.repository.EditorCheckpointStore
import com.hmx.pdf.data.repository.EditorTempFiles
import com.hmx.pdf.data.repository.LocalDocumentMirror
import com.hmx.pdf.data.repository.RecentFile
import com.hmx.pdf.data.repository.RecentFilesManager
import com.hmx.pdf.data.repository.SaveRecovery
import com.hmx.pdf.docx.DocBlock
import com.hmx.pdf.docx.CommentInfo
import com.hmx.pdf.docx.CharFmt
import com.hmx.pdf.docx.DocIds
import com.hmx.pdf.docx.DocxPageLayout
import com.hmx.pdf.docx.OBJ_CHAR
import com.hmx.pdf.docx.PageSetup
import com.hmx.pdf.docx.DocPara
import com.hmx.pdf.docx.DocState
import com.hmx.pdf.docx.DocTable
import com.hmx.pdf.docx.DocxNote
import com.hmx.pdf.docx.DocxNotes
import com.hmx.pdf.docx.DocxTables
import com.hmx.pdf.docx.DocxComments
import com.hmx.pdf.docx.DocxEdits
import com.hmx.pdf.docx.DocxPackage
import com.hmx.pdf.docx.DocxReader
import com.hmx.pdf.docx.DocxRevisions
import com.hmx.pdf.docx.DocxWriter
import com.hmx.pdf.docx.RevMeta
import com.hmx.pdf.docx.findPara
import com.hmx.pdf.docx.forEachPara
import com.hmx.pdf.docx.mapContainerOf
import com.hmx.pdf.docx.replacePara
import com.hmx.pdf.spell.AndroidSpellEngine
import com.hmx.pdf.spell.SpellCache
import com.hmx.pdf.spell.SpellCheck
import com.hmx.pdf.spell.SpellEngine
import com.hmx.pdf.spell.SpellIssue
import com.hmx.pdf.reading.AndroidTtsEngine
import com.hmx.pdf.reading.ReadingProjection
import com.hmx.pdf.reading.ReadingSession
import com.hmx.pdf.reading.TtsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The editor's own identity on comments it creates (no user account exists to attribute). */
private const val COMMENT_AUTHOR = "HMXPDF"
private const val COMMENT_INITIALS = "H"

/**
 * The Word editor's document: loads a .docx into blocks, keeps undo/redo as whole-document
 * snapshots (cheap: blocks are immutable and shared), and saves by patching the pristine local
 * mirror with [DocxWriter], so only what was edited is rewritten.
 *
 * The document lives in snapshot state rather than a flow: text fields read it in the same frame
 * they write it, which an asynchronously collected flow can't guarantee (the IME would see stale
 * text and the caret would jump).
 */
class DocxEditorViewModel : ViewModel() {

    data class Meta(
        val fileName: String = "",
        /** Local mirror of the file as opened (never modified). */
        val fileUri: Uri? = null,
        /** The uri the user opened — the "Save" target. */
        val sourceUri: Uri? = null,
        val isLoading: Boolean = true,
        val error: String? = null,
        val saving: Boolean = false
    )

    var meta by mutableStateOf(Meta())
        private set
    var pkg by mutableStateOf<DocxPackage?>(null)
        private set
    var doc by mutableStateOf<DocState?>(null)
        private set
    /** Footnote/endnote session state. Copy-on-write, so an undo snapshot holds it exactly. */
    var notes by mutableStateOf<DocxNotes?>(null)
        private set
    private var packageSavedNotes: DocxNotes? = null

    // ── spell checking (Phase 4.3) ─────────────────────────────────────────────

    /**
     * Spell suggestions are annotations, not document state: they live beside [doc], never in it,
     * so checking cannot change a run's rPr or make the document dirty. Application happens only
     * through [applySpellFix] when the user taps a suggestion.
     */
    enum class SpellStatus { IDLE, RUNNING, READY, UNAVAILABLE }

    var spellStatus by mutableStateOf(SpellStatus.IDLE)
        private set
    var spellIssues by mutableStateOf<List<SpellIssue>>(emptyList())
        private set

    /** Paragraph id → ranges to mark, ready for the reading/edit projection. */
    var spellRanges by mutableStateOf<Map<Long, List<IntRange>>>(emptyMap())
        private set
    var spellLanguage by mutableStateOf<String?>(null)
        private set

    /** Why no service is usable (offline policy, disabled, absent); diagnostics for the adapter. */
    var spellMessage by mutableStateOf<String?>(null)
        private set

    private val spellCache = SpellCache()
    private var spellJob: Job? = null

    /** Test seam: a fake engine avoids the Android text-service session in JVM tests. */
    internal var injectedSpellEngine: SpellEngine? = null
    private var deviceSpell: SpellEngine? = null
    private var spellProbed = false
    private var saved: DocState? = null
    /** The document as opened: the local mirror already holds exactly this. */
    private var original: DocState? = null
    private val undoStack = ArrayList<SessionSnap>()
    private val redoStack = ArrayList<SessionSnap>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    /** Bumped when undo/redo replace the document, so an open text field re-reads its paragraph. */
    var restoreEpoch by mutableStateOf(0)
        private set

    /**
     * True when closing would lose work. Body edits change [doc]; package-side edits live *beside*
     * it (`wantSettings`, `hfChanged`) and never touch `doc`, so dirty compares them against the
     * last save/load markers ([packageSavedSettings], [packageSavedHf]) too.
     *
     * Settings compare the *effective* part ([effectiveSettings]): the raw [wantSettings] is null
     * until the toggle is used, while every real Word document ships a `word/settings.xml` — so
     * comparing the raw override against the loaded part marked the whole document dirty the
     * moment it opened (false Save dot + Back prompt with zero edits).
     */
    val dirty: Boolean get() = doc != null && doc !== saved ||
        effectiveSettings() != packageSavedSettings ||
        hfChanged.any { (path, st) -> st !== packageSavedHf[path] } ||
        notes !== packageSavedNotes
    // The test-mirror half sits alone: unparenthesized, the elvis binds to the getter's left side
    // (`(mirror ?: uri) == "file"`) and any forced Boolean would compare against "file" as false.
    val editable: Boolean get() = pkg?.writable == true && (testFileMirror ?: (meta.fileUri?.scheme == "file"))

    /**
     * The session mode: documents open in View Mode (read-only); Edit is the explicit header
     * toggle. The VM owns it so every mutating method below can guard on one shared flag — the
     * screen hides its editing controls from this same value. (Backing state stays private:
     * a `private set` on a property named `editMode` would generate a JVM setter clashing with
     * [setEditMode].)
     */
    private var mode by mutableStateOf(false)
    val editMode: Boolean get() = mode

    /** Enters edit mode (writable documents only); leaving it is always allowed. */
    fun setEditMode(on: Boolean) {
        if (on && !editable) return
        if (!on) lastKey = null
        mode = on
    }

    /** Test seam: plain JVM unit tests build no android.net.Uri, so they force the mirror half of [editable]. */
    internal var testFileMirror: Boolean? = null

    /** Test seam: installs a parsed package/state without [load]'s picker and mirror pipeline. */
    internal fun primeForTest(parsed: Pair<DocxPackage, DocState>) {
        pkg = parsed.first; doc = parsed.second; saved = parsed.second; original = parsed.second
        testFileMirror = true
        resetPageLayoutExtras()
        resetAutosave()
    }

    /**
     * Track Changes. Off by default every session (nothing is captured until asked, matching the
     * editor's other opt-in behavior). Enabling needs Edit Mode; disabling — which the pending
     * revisions all survive — is always allowed.
     */
    var tracking by mutableStateOf(false)
        private set

    @JvmName("trackChanges") // clashes with the property's generated JVM setter otherwise
    fun setTracking(on: Boolean) {
        if (on && (!editMode || !editable)) return
        if (!on) lastCapture = null
        tracking = on
    }

    private var started = false
    /** The page last read in this document (from Recents), to reopen at. */
    var resumePage = 0
        private set
    private var lastKey: String? = null
    private var lastAt = 0L

    fun load(context: Context, uri: Uri, nameHint: String? = null) {
        if (started) return
        started = true
        viewModelScope.launch {
            // One recents read feeds both the display name fallback and the resume page. The old
            // path decoded the same JSON twice on first open. The permission binder calls go to
            // IO with the rest of the open work instead of running before the first frame.
            val (name, page) = withContext(Dispatchers.IO) {
                runCatching { EditorTempFiles.cleanup(context) }
                runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                val recent = runCatching {
                    RecentFilesManager.getRecents(context).firstOrNull { it.uriString == uri.toString() }
                }.getOrNull()
                val resolved = nameHint?.takeIf { it.isNotBlank() }
                    ?: runCatching { queryName(context, uri) }.getOrNull()
                    ?: recent?.name
                    ?: uri.lastPathSegment ?: "Document.docx"
                resolved to (recent?.currentPage ?: 0)
            }
            resumePage = page
            val loaded = withContext(Dispatchers.IO) {
                // This route only opens .docx, so do not let a display name like "report.docx (1)"
                // become a bogus mirror extension.
                val readable = runCatching { LocalDocumentMirror.localFile(context, uri, "docx") }.getOrDefault(uri)
                val local = if (readable.scheme == "file") readable
                    else runCatching { mirrorToCache(context, readable, name) }.getOrDefault(readable)
                val parsed = runCatching { context.contentResolver.openInputStream(local)?.use { DocxReader.read(it) } }.getOrNull()
                parsed to local
            }
            val (parsed, local) = loaded
            if (parsed == null) {
                meta = Meta(fileName = name, fileUri = local, sourceUri = uri, isLoading = false, error = "Couldn't read this document.")
                return@launch
            }
            pkg = parsed.first
            doc = parsed.second
            saved = parsed.second
            original = parsed.second
            meta = Meta(fileName = name, fileUri = local, sourceUri = uri, isLoading = false)
            resetPageLayoutExtras()
            appContext = context.applicationContext
            originalHash = withContext(Dispatchers.IO) {
                val mirror = local.takeIf { it.scheme == "file" }?.path?.let(::File)
                if (mirror?.isFile == true) runCatching { EditorCheckpointStore.sha256(mirror) }.getOrNull() else null
            }
            // A previous session may have died with unsaved edits for this document: offer the
            // last committed checkpoint (recovery happens on this launch or a later one).
            val found = withContext(Dispatchers.IO) {
                runCatching { EditorCheckpointStore.find(context, uri.toString()) }.getOrNull()
            }
            pendingCheckpoint = found
            pendingCheckpointStale = found != null && found.originalHash.isNotEmpty() &&
                originalHash != null && found.originalHash != originalHash
            withContext(Dispatchers.IO) {
                runCatching {
                    RecentFilesManager.addRecent(context, RecentFile(name = name, uriString = uri.toString(), timestamp = System.currentTimeMillis()))
                }
            }
        }
    }

    // ── edit log ───────────────────────────────────────────────────────────────

    /**
     * One undo step: the body snapshot plus the package-level session state (header/footer part
     * contents, their relationship claims, root envelopes and the settings splice) as it was
     * *before* the change (FA-05). Restoring the body alone could bring a section reference back
     * while its part/relationship claim was already purged — a dangling reference on the next save.
     */
    private class SessionSnap(
        val body: DocState,
        val hf: Map<String, DocState>,
        val relIds: Map<String, String>,
        val envelopes: Map<String, String>,
        val settings: String?,
        val settingsChanged: Boolean,
        val notes: DocxNotes?
    )

    private fun snap(): SessionSnap = SessionSnap(
        body = doc ?: DocState(emptyList()),
        hf = hfChanged.toMap(), relIds = hfRelIds.toMap(), envelopes = hfEnvelopes.toMap(),
        settings = wantSettings, settingsChanged = settingsChanged,
        notes = notes
    )

    private fun restore(s: SessionSnap) {
        doc = s.body
        hfChanged.clear(); hfChanged.putAll(s.hf)
        hfRelIds.clear(); hfRelIds.putAll(s.relIds)
        hfEnvelopes.clear(); hfEnvelopes.putAll(s.envelopes)
        wantSettings = s.settings; settingsChanged = s.settingsChanged
        notes = s.notes
        invalidateReading()
    }

    /** Pushes the pre-change snapshot and starts a new step (typing coalesces per [key]). */
    private fun step(key: String?) {
        val now = System.currentTimeMillis()
        val coalesce = key != null && key == lastKey && now - lastAt < 1500
        if (!coalesce) {
            undoStack.add(snap())
            if (undoStack.size > 200) undoStack.removeAt(0)
        }
        lastKey = key
        lastAt = now
        redoStack.clear()
        canUndo = undoStack.isNotEmpty()
        canRedo = false
    }

    /**
     * Replaces the document. Typing into one paragraph coalesces into one undo step (per [key],
     * until a pause); everything else is its own step.
     */
    private fun commit(next: DocState, key: String? = null) {
        val cur = doc ?: return
        if (next === cur) return
        step(key)
        doc = next
        scheduleCheckpoint()
        invalidateReading()
    }

    /** One step that changes a note part only (typing in a note body). */
    private fun commitNotes(next: DocxNotes, key: String? = null) {
        if (next === notes) return
        step(key)
        notes = next
        scheduleCheckpoint()
    }

    /**
     * One atomic step for a note reference and its part element: both are captured in the same
     * snapshot, so undo/redo can never expose a reference without its note (or the reverse).
     */
    private fun commitNotePair(nextDoc: DocState, nextNotes: DocxNotes) {
        if (nextDoc === doc && nextNotes === notes) return
        step(null)
        doc = nextDoc
        notes = nextNotes
        scheduleCheckpoint()
        invalidateReading()
    }

    /** Edits stop speech so a highlight can never follow text that changed under it. */
    private fun invalidateReading() {
        if (readingSession == null) return
        readingAutoPlay = false
        readingSession?.stop()
        scriptDoc = null
        mirrorReading()
    }

    /** Ends typing coalescing, so the next keystroke starts a new undo step. */
    fun breakUndoGroup() { lastKey = null }

    fun undo() {
        if (!editMode) return
        val prev = undoStack.removeLastOrNull() ?: return
        doc?.let { redoStack.add(snap()) }
        restore(prev)
        lastKey = null
        canUndo = undoStack.isNotEmpty(); canRedo = true
        restoreEpoch++
        scheduleCheckpoint()
    }

    fun redo() {
        if (!editMode) return
        val next = redoStack.removeLastOrNull() ?: return
        doc?.let { undoStack.add(snap()) }
        restore(next)
        lastKey = null
        canUndo = true; canRedo = redoStack.isNotEmpty()
        restoreEpoch++
        scheduleCheckpoint()
    }

    // ── autosave checkpoints (Phase 4.1) ───────────────────────────────────────

    /**
     * The application context (set once per load), which lets checkpoint writes run without an
     * Activity. Null in JVM tests, which is also what disables autosave scheduling there — the
     * coordinator and store carry their own deterministic tests.
     */
    private var appContext: Context? = null
    /** The opened destination's hash (its local mirror): recovery's stale-original label. */
    private var originalHash: String? = null
    private val autosave = AutosaveCoordinator()
    private var autosaveJob: Job? = null
    private var checkpointJob: Job? = null
    /** The session fingerprint the last committed checkpoint captured (reference-compared). */
    private var lastCheckpointFp: SessionSnap? = null

    /** A checkpoint found for this document at load (or offered for later): recover/discard/later. */
    internal var pendingCheckpoint by mutableStateOf<EditorCheckpointStore.Checkpoint?>(null)
        private set
    /** True when the destination changed since the pending checkpoint was taken. */
    var pendingCheckpointStale by mutableStateOf(false)
        private set

    private fun resetAutosave() {
        autosave.onSaved(); autosaveJob?.cancel(); checkpointJob?.cancel()
        autosaveJob = null; checkpointJob = null; lastCheckpointFp = null
        pendingCheckpoint = null; pendingCheckpointStale = false
    }

    /** Dirty state changed: (re)schedule the debounced checkpoint per the coordinator. */
    private fun scheduleCheckpoint() {
        val context = appContext ?: return
        val delayMs = autosave.touch(System.currentTimeMillis(), dirty)
        if (!dirty) { autosaveJob?.cancel(); autosaveJob = null }
        if (delayMs == AutosaveCoordinator.NONE) return
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(delayMs)
            writeCheckpoint(context)
        }
    }

    /**
     * One checkpoint write of one captured generation (the same [SaveCapture] discipline as
     * [save]): writes a complete package through the sole writer into the checkpoint store.
     * Edits during the write coalesce into one follow-up ([AutosaveCoordinator.endWrite]); the
     * running write is never cancelled by continued typing.
     */
    private fun writeCheckpoint(context: Context) {
        if (!dirty) return
        if (checkpointJob?.isActive == true) return
        val cap = saveCapture() ?: return
        val fp = snap()
        if (unchangedSinceCheckpoint(fp)) { lastCheckpointFp = fp; return }
        if (!autosave.beginWrite()) return
        checkpointJob = viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val patched = buildPatched(context, cap) ?: return@runCatching false
                    try {
                        EditorCheckpointStore.commit(
                            context = context,
                            sourceUri = cap.identity,
                            displayName = cap.fileName,
                            kind = "docx",
                            complete = patched,
                            originalHash = originalHash
                        ) != null
                    } finally {
                        patched.delete()
                    }
                }.getOrDefault(false)
            }
            if (ok) lastCheckpointFp = fp
            val followUp = autosave.endWrite(System.currentTimeMillis(), succeeded = ok)
            if (followUp != AutosaveCoordinator.NONE) {
                autosaveJob?.cancel()
                autosaveJob = viewModelScope.launch { delay(followUp); writeCheckpoint(context) }
            }
        }
    }

    /** Reference comparison against the checkpointed generation: cheap necessity check. */
    private fun unchangedSinceCheckpoint(fp: SessionSnap): Boolean {
        val last = lastCheckpointFp ?: return false
        return fp.body === last.body && fp.settings === last.settings &&
            fp.settingsChanged == last.settingsChanged && fp.notes === last.notes &&
            fp.hf.size == last.hf.size && fp.relIds.size == last.relIds.size &&
            fp.hf.all { (k, v) -> last.hf[k] === v } &&
            fp.relIds.all { (k, v) -> last.relIds[k] === v }
    }

    /** Uses the last committed checkpoint as the current document (recovery offer → Recover). */
    fun recoverPendingCheckpoint() {
        val ck = pendingCheckpoint ?: return
        val context = appContext ?: return
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val source = File.createTempFile("recovered-", ".docx", context.cacheDir)
                    try {
                        ck.file.copyTo(source, overwrite = true)
                        val document = source.inputStream().use { DocxReader.read(it) }
                            ?: error("Could not read checkpoint")
                        document to source
                    } catch (error: Exception) {
                        source.delete()
                        throw error
                    }
                }.getOrNull()
            }
            pendingCheckpoint = null
            val (document, source) = parsed ?: return@launch
            val (p, d) = document
            meta = meta.copy(fileUri = Uri.fromFile(source))
            pkg = p
            doc = d
            // The destination never received these edits: dirty against the opened original, so
            // a save is still required — never a false clean state. Undo restarts (roadmap).
            saved = original
            undoStack.clear(); redoStack.clear()
            canUndo = false; canRedo = false
            resetPageLayoutExtras()
            restoreEpoch++
            lastCheckpointFp = null
            autosave.onSaved()
        }
    }

    /** Deletes the pending checkpoint (recovery offer → Discard). */
    fun discardPendingCheckpoint() {
        val ck = pendingCheckpoint ?: return
        pendingCheckpoint = null
        val context = appContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { EditorCheckpointStore.clear(context, ck.originalUri) }
        }
    }

    /** Keeps the checkpoint on disk; the next open of this document offers it again. */
    fun checkpointLater() { pendingCheckpoint = null }

    fun para(id: Long): DocPara? = doc?.let { findPara(it.blocks, id) }

    // ── tracked-change capture ─────────────────────────────────────────────────

    /** One row of the Revisions panel: a pending insertion or deletion. */
    data class RevisionInfo(
        val paraId: Long,
        /** The revision id (`w:id`). An edit that replaced text carries both its shapes under it. */
        val id: Int,
        val ins: Boolean,
        /** The revised text, abbreviated. */
        val excerpt: String,
        val author: String,
        val date: String
    )

    /** Every pending tracked change, in document order, tables and previews included. */
    fun pendingRevisions(): List<RevisionInfo> {
        val out = ArrayList<RevisionInfo>()
        doc?.blocks?.let { blocks ->
            forEachPara(blocks) { p ->
                for (r in DocxRevisions.pending(p)) out.add(
                    RevisionInfo(p.id, r.id, r.ins, DocxRevisions.excerpt(p, r.id), p.revs[r.id]?.author ?: "", p.revs[r.id]?.date ?: "")
                )
            }
        }
        return out
    }

    /**
     * The revision a capture lands under — null with tracking off (plain editing path). Continuous
     * typing coalesces into one revision, exactly like its undo step (same key, same window);
     * every other action passes a fresh key.
     */
    private fun capture(key: String): Pair<Int, RevMeta>? {
        if (!tracking) return null
        val now = System.currentTimeMillis()
        lastCapture?.let { last -> if (last.first == key && now - lastCaptureAt < 1500) {
            lastCaptureAt = now
            return last.second to last.third
        } }
        val meta = RevMeta(COMMENT_AUTHOR, docDate())
        val c = Triple(key, DocxRevisions.nextId(), meta)
        lastCapture = c
        lastCaptureAt = now
        return c.second to c.third
    }

    private var lastCapture: Triple<String, Int, RevMeta>? = null
    private var lastCaptureAt = 0L
    private var captureOps = 0

    /** The unique key of a one-shot action (each replace is its own revision). */
    private fun freshKey(): String = "op${captureOps++}"

    /** `w:date` in the machine format comments use: fixed US locale, no java.time (min SDK 23). */
    private fun docDate(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()) + "Z"

    /**
     * Typing: the field's text changed. Returns the committed paragraph's caret when tracking
     * captured the edit (retained deleted text re-enters the paragraph, so the field must
     * re-read its buffer), or -1 — today's plain path, no re-read.
     */
    fun typed(id: Long, newText: String, typing: com.hmx.pdf.docx.CharFmt): Int {
        if (!editMode) return -1
        val d = doc ?: return -1
        val p = findPara(d.blocks, id) ?: return -1
        val np = if (tracking) {
            val (revId, meta) = capture("type$id")!!
            DocxEdits.typed(p, newText, typing, revId, meta)
        } else DocxEdits.typed(p, newText, typing)
        if (np === p) return -1
        commit(DocState(replacePara(d.blocks, np) ?: return -1), "type$id")
        if (!tracking) return -1
        val (pre, suf) = DocxEdits.diff(p.text, newText)
        val retained = (pre until p.text.length - suf).count { p.fmts[it].rev == null }
        return pre + retained + (newText.length - suf - pre)
    }

    fun replace(para: DocPara, grouped: Boolean = false) {
        if (!editMode) return
        val d = doc ?: return
        replacePara(d.blocks, para)?.let { commit(DocState(it), if (grouped) "fmt${para.id}" else null) }
    }

    /** Enter: splits paragraph [id] at [at] and returns the new paragraph's id. */
    fun split(id: Long, text: String, at: Int, typing: com.hmx.pdf.docx.CharFmt): Long? {
        if (!editMode) return null
        val d = doc ?: return null
        val p0 = findPara(d.blocks, id) ?: return null
        val np = if (tracking) {
            val (revId, meta) = capture("type$id")!!
            DocxEdits.typed(p0, text, typing, revId, meta)
        } else DocxEdits.typed(p0, text, typing)
        val (a, b) = DocxEdits.split(np, at.coerceIn(0, np.text.length), pkg!!.styles)
        val blocks = mapContainerOf(d.blocks, id) { list ->
            val out = ArrayList<DocBlock>(list.size + 1)
            for (x in list) if (x is DocPara && x.id == id) { out.add(a); out.add(b) } else out.add(x)
            out
        } ?: return null
        lastKey = null
        commit(DocState(blocks))
        return b.id
    }

    /**
     * Backspace at the start of paragraph [id]: joins it to the paragraph before it. Returns that
     * paragraph's id and where the caret goes, or null when there is nothing to join with.
     */
    fun mergeWithPrevious(id: Long): Pair<Long, Int>? {
        if (!editMode) return null
        val d = doc ?: return null
        var result: Pair<Long, Int>? = null
        val blocks = mapContainerOf(d.blocks, id) { list ->
            val i = list.indexOfFirst { it is DocPara && it.id == id }
            val prev = list.getOrNull(i - 1)
            val cur = list[i] as DocPara
            if (prev is DocPara && prev.editable) {
                val merged = DocxEdits.merge(prev, cur)
                result = prev.id to prev.text.length
                list.toMutableList().also { it[i - 1] = merged; it.removeAt(i) }
            } else if (prev != null && cur.isEmpty && i < list.size - 1) {
                // An empty paragraph after a table or picture: just remove it.
                result = -1L to 0
                list.toMutableList().also { it.removeAt(i) }
            } else list
        } ?: return null
        if (result != null) { lastKey = null; commit(DocState(blocks)) }
        return result
    }

    /** Paragraph-level change applied to every paragraph id in [ids]. */
    fun mapParas(ids: Collection<Long>, f: (DocPara) -> DocPara) {
        if (!editMode) return
        var blocks = doc?.blocks ?: return
        for (id in ids) {
            val p = findPara(blocks, id) ?: continue
            if (!p.editable) continue
            val np = f(p)
            if (np !== p) blocks = replacePara(blocks, np) ?: blocks
        }
        if (blocks !== doc?.blocks) { lastKey = null; commit(DocState(blocks)) }
    }

    /** Replaces every occurrence of the query — one undo step ("Replace all"). */
    fun replaceAll(query: String, replacement: String, matchCase: Boolean) {
        val d = doc ?: return
        val ids = ArrayList<Long>()
        val hits = HashMap<Long, List<IntRange>>()
        forEachPara(d.blocks) { p ->
            val ms = DocxEdits.matchesIn(p.text, query, !matchCase)
            if (ms.isNotEmpty()) { ids.add(p.id); hits[p.id] = ms }
        }
        if (ids.isEmpty()) return
        val (revId, meta) = if (tracking) capture("replace-all${freshKey()}")!! else null to null
        mapParas(ids) { p -> hits[p.id]?.let { DocxEdits.replaceAllIn(p, it, replacement, revId, meta) } ?: p }
    }

    /** Replaces one match on its paragraph — one undo step. The range must come from a live match. */
    fun replaceOne(id: Long, range: IntRange, replacement: String, searchedState: DocState? = doc) {
        if (!editMode || !editable || searchedState !== doc) return
        val current = para(id) ?: return
        if (range.first < 0 || range.last < range.first || range.last >= current.text.length) return
        val expected = current.text.substring(range)
        val (revId, meta) = if (tracking) capture(freshKey())!! else null to null
        mapParas(listOf(id)) { cur -> DocxEdits.replaceRange(cur, range.first, range.last + 1, replacement, revId, meta, expected) }
    }

    // ── comments ───────────────────────────────────────────────────────────────

    /**
     * Adds a comment anchored to [paraId]'s start — one undo step. [paraId] null (or a paragraph
     * that has since gone) falls back to the first editable paragraph, so "Add" works even with no
     * caret session: the anchor survives focus being consumed by the field that requested it.
     */
    fun addComment(paraId: Long?, text: String): Boolean {
        if (!editMode || !editable || text.isBlank()) return false
        val cm = pkg?.comments ?: return false
        val blocks = doc?.blocks ?: return false
        val target = paraId?.let { findPara(blocks, it) }?.takeIf { p -> p.editable }
            ?: firstEditable(blocks)
            ?: return false
        // w:date is a machine format: fixed US locale, no java.time (min SDK 23).
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()) + "Z"
        val id = cm.add(COMMENT_AUTHOR, COMMENT_INITIALS, date, text)
        mapParas(listOf(target.id)) { DocxComments.anchorPara(it, id) }
        return true
    }

    /** Deletes a comment everywhere: part element, range markers, reference runs — one undo step. */
    fun deleteComment(id: String) {
        if (!editMode || !editable) return
        val cm = pkg?.comments ?: return
        cm.remove(id)
        val all = ArrayList<Long>()
        doc?.blocks?.let { blocks -> forEachPara(blocks) { p -> all.add(p.id) } }
        mapParas(all) { DocxComments.stripPara(it, id) }
    }

    // ── footnotes / endnotes (Phase 4.4) ───────────────────────────────────────

    /**
     * Note edits land in a second package part and in the body in the same step, so Track Changes
     * (which can only wrap body runs) refuses them rather than writing half a change.
     */
    private fun canEditNotes(): Boolean = editMode && editable && !tracking
    /** Content notes in document order (separators and removed entries excluded). */
    fun notesList(kind: DocxNotes.Kind? = null): List<DocxNote> {
        val n = notes ?: return emptyList()
        return if (kind == null) n.visible(DocxNotes.Kind.FOOTNOTE) + n.visible(DocxNotes.Kind.ENDNOTE)
        else n.visible(kind)
    }

    /** The note's first paragraph without its number mark, for the list row. */
    fun noteFirstText(note: DocxNote): String =
        note.blocks.filterIsInstance<DocPara>().firstOrNull()?.text?.filter { it != OBJ_CHAR } ?: ""

    /** The paragraphs of one note, for the same text-field editor the header/footer parts use. */
    fun noteParas(kind: DocxNotes.Kind, id: Int): List<DocPara> {
        val note = notes?.notes(kind)?.firstOrNull { it.id == id && !it.removed } ?: return emptyList()
        val out = ArrayList<DocPara>()
        forEachPara(note.blocks) { out.add(it) }
        return out
    }

    /**
     * Adds a note and its body reference in one undo step. The reference is appended to the focused
     * paragraph (the VM has no caret range, the comments precedent); with no focus the first
     * editable paragraph takes it. Returns the new note id, or null when refused.
     */
    fun insertNote(kind: DocxNotes.Kind, paraId: Long? = null, text: String = ""): Int? {
        if (!canEditNotes()) return null
        val cur = notes ?: return null
        val d = doc ?: return null
        val target = paraId?.let { findPara(d.blocks, it) }?.takeIf { it.editable }
            ?: firstEditable(d.blocks) ?: return null
        val (nextNotes, id) = DocxNotes.add(cur, kind, text)
        val blocks = replacePara(d.blocks, DocxNotes.anchorPara(target, kind, id)) ?: return null
        commitNotePair(DocState(blocks), nextNotes)
        return id
    }

    /**
     * Deletes a note and every editable reference to it in one step. A reference the editor can't
     * remove (raw/read-only content) makes the whole deletion refuse: an orphan reference is worse
     * than a polite no-op.
     */
    fun deleteNote(kind: DocxNotes.Kind, id: Int): Boolean {
        if (!canEditNotes()) return false
        val cur = notes ?: return false
        val d = doc ?: return false
        val nextNotes = DocxNotes.remove(cur, kind, id) ?: return false
        val stripped = DocxNotes.stripReferences(d.blocks, kind, id) ?: d.blocks
        if (DocxNotes.hasReference(stripped, kind, id)) return false
        commitNotePair(if (stripped === d.blocks) d else DocState(stripped), nextNotes)
        return true
    }

    /** Types into one note paragraph, through the same paragraph kernel as the body. */
    fun noteTyped(kind: DocxNotes.Kind, id: Int, paraId: Long, newText: String) {
        if (!canEditNotes()) return
        val cur = notes ?: return
        val note = cur.notes(kind).firstOrNull { it.id == id && !it.removed } ?: return
        val p = findPara(note.blocks, paraId) ?: return
        if (!p.editable || p.text == newText) return
        // The note's number mark is an object run in the paragraph text: typing must keep it.
        val marks = p.fmts.filter { it.obj != null }
        val np = if (marks.isEmpty()) {
            DocxEdits.typed(p, newText, DocxEdits.fmtAt(p, p.text.length))
        } else {
            val base = DocxEdits.typed(
                p.edited(text = "", fmts = emptyList()), newText, DocxEdits.fmtAt(p, p.text.length)
            )
            base.edited(
                text = OBJ_CHAR.toString().repeat(marks.size) + base.text,
                fmts = marks + base.fmts
            )
        }
        val blocks = replacePara(note.blocks, np) ?: return
        val next = DocxNotes.edit(cur, kind, id, blocks) ?: return
        commitNotes(next, "note${kind.name}$id$paraId")
    }

    // ── spell checking (Phase 4.3) ─────────────────────────────────────────────

    /**
     * Scans the current document with the installed on-device service. The run is cancelled by a
     * newer scan or a reload; results are discarded when the document changed meanwhile, so an
     * annotation never points at text from an older generation.
     */
    fun checkSpelling() {
        val snapshot = doc ?: return
        spellJob?.cancel()
        val engine = spellEngine()
        if (engine == null || !engine.available) {
            spellIssues = emptyList()
            spellRanges = emptyMap()
            spellLanguage = engine?.language
            spellMessage = (engine as? AndroidSpellEngine)?.reason
            spellStatus = SpellStatus.UNAVAILABLE
            return
        }
        spellLanguage = engine.language
        spellMessage = null
        spellStatus = SpellStatus.RUNNING
        spellJob = viewModelScope.launch {
            val found = try {
                SpellCheck.collect(snapshot, engine, spellCache)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (doc !== snapshot) return@launch
            installSpellResults(found)
        }
    }

    /** Test seam and the single place issues/ranges are published together. */
    internal fun installSpellResults(issues: List<SpellIssue>) {
        spellIssues = issues
        spellRanges = issues.groupBy { it.paraId }.mapValues { (_, list) -> list.map { it.range } }
        spellStatus = SpellStatus.READY
    }

    /**
     * Applies one user-approved suggestion. The issue must belong to the paragraph instance it was
     * found in and the live text must still match: after any edit to that paragraph the old
     * suggestion is stale and refuses instead of replacing the wrong characters. Track Changes uses
     * the same capture as every other edit.
     */
    fun applySpellFix(issue: SpellIssue, replacement: String): Boolean {
        if (!editMode || !editable) return false
        val d = doc ?: return false
        val current = findPara(d.blocks, issue.paraId) ?: return false
        if (current !== issue.para) return false
        if (!current.editable) return false
        if (issue.range.first < 0 || issue.range.last < issue.range.first) return false
        if (issue.range.last >= current.text.length) return false
        if (current.text.substring(issue.range) != issue.word) return false
        if (issue.word == replacement) return false
        val (revId, meta) = if (tracking) capture(freshKey())!! else null to null
        var changed = false
        mapParas(listOf(issue.paraId)) { cur ->
            val np = DocxEdits.replaceRange(
                cur, issue.range.first, issue.range.last + 1, replacement, revId, meta, issue.word
            )
            if (np !== cur) changed = true
            np
        }
        // Ranges in that paragraph shifted; drop its remaining annotations rather than misplace them.
        if (changed) dropSpellIssues { it.paraId == issue.paraId }
        return changed
    }

    /** Hides one suggestion without editing the document (the user's "ignore"). */
    fun ignoreSpellIssue(issue: SpellIssue) {
        dropSpellIssues { it.paraId == issue.paraId && it.range == issue.range && it.word == issue.word }
    }

    private fun dropSpellIssues(predicate: (SpellIssue) -> Boolean) {
        val next = spellIssues.filterNot(predicate)
        spellIssues = next
        spellRanges = next.groupBy { it.paraId }.mapValues { (_, list) -> list.map { it.range } }
    }

    /** On device the engine is created once, on the main thread (the service session needs a Looper). */
    private fun spellEngine(): SpellEngine? {
        injectedSpellEngine?.let { return it }
        val context = appContext ?: return null
        if (!spellProbed) {
            spellProbed = true
            deviceSpell = runCatching { AndroidSpellEngine(context) }.getOrNull()
        }
        return deviceSpell
    }

    // ── read aloud (Phase 4.5) ─────────────────────────────────────────────────

    /**
     * Reading is strictly read-only: it never commits, never changes rPr and never marks the
     * document dirty. [scriptDoc] is the generation the current script was built from; any edit
     * calls [stopReading] and invalidates it, so a highlight can never follow edited text.
     */
    private var readingSession: ReadingSession? = null
    private var readingEngine: TtsEngine? = null
    private var scriptDoc: DocState? = null

    var readingState by mutableStateOf(ReadingSession.State.IDLE)
        private set
    var readingParaId by mutableStateOf<Long?>(null)
        private set
    /** The chunk being spoken (or paused on), for the panel's "now reading" line. */
    var readingText by mutableStateOf<String?>(null)
        private set
    var readingLanguage by mutableStateOf<String?>(null)
        private set
    var readingAvailable by mutableStateOf(true)
        private set
    /** True while the on-device voice is still initialising; the panel shows a short status line. */
    var readingPreparing by mutableStateOf(false)
        private set
    var readingSpeed by mutableStateOf(1f)
        private set
    /** A play request that arrived before the engine finished initialising. */
    private var readingAutoPlay = false

    /** The toolbar/panel action: play when idle, pause when speaking, resume when paused. */
    fun readAloudToggle() {
        val session = readingSession() ?: return
        syncReadingScript(session)
        if (session.state == ReadingSession.State.PLAYING) session.pause()
        else {
            // The voice initialises asynchronously; remember the request and start when ready.
            if (session.state == ReadingSession.State.UNAVAILABLE &&
                readingEngine?.available != true
            ) readingAutoPlay = true
            session.play()
        }
    }

    fun readAloudNextParagraph() {
        val session = readingSession ?: return
        syncReadingScript(session)
        session.nextParagraph()
    }

    fun readAloudPreviousParagraph() {
        val session = readingSession ?: return
        syncReadingScript(session)
        session.previousParagraph()
    }

    /** Explicit stop: also the path navigation away, an edit and unload take. */
    fun stopReading() {
        val session = readingSession ?: return
        readingAutoPlay = false
        session.stop()
        scriptDoc = null
        mirrorReading()
    }

    fun setReadingRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 2f)
        readingSpeed = clamped
        readingSession?.setRate(clamped)
    }

    private fun readingSession(): ReadingSession? {
        val existing = readingSession
        if (existing != null) return existing
        val context = appContext ?: return null
        val engine = AndroidTtsEngine(
            context = context,
            onFocusLost = {
                // Losing audio focus pauses speech; the saved chunk index resumes it later.
                readingSession?.pause()
            },
            onReady = {
                readingSession?.refreshAvailability()
                readingPreparing = false
                val session = readingSession
                val wanted = readingAutoPlay
                readingAutoPlay = false
                if (wanted && session != null && readingEngine?.available == true) {
                    if (doc != null) syncReadingScript(session)
                    session.play()
                }
                mirrorReading()
            }
        )
        val session = ReadingSession(engine).also { it.onChange = { mirrorReading() } }
        engine.rate = readingSpeed
        readingEngine = engine
        readingSession = session
        readingPreparing = !engine.available
        mirrorReading()
        return session
    }

    private fun syncReadingScript(session: ReadingSession) {
        val d = doc ?: return
        if (d === scriptDoc) return
        scriptDoc = d
        session.load(ReadingProjection.chunks(ReadingProjection.segments(d.blocks)))
    }

    private fun mirrorReading() {
        val session = readingSession ?: return
        readingState = session.state
        readingParaId = if (session.state == ReadingSession.State.PLAYING ||
            session.state == ReadingSession.State.PAUSED
        ) session.currentParaId else null
        readingText = if (session.state == ReadingSession.State.PLAYING ||
            session.state == ReadingSession.State.PAUSED
        ) session.currentText else null
        readingLanguage = readingEngine?.language
        readingAvailable = session.state != ReadingSession.State.UNAVAILABLE &&
            (readingEngine?.available == true)
    }

    // ── tracked-change resolution ──────────────────────────────────────────────

    /** Resolves the one pending revision of [info] — one undo step, other revisions untouched. */
    fun resolveRevision(info: RevisionInfo, accept: Boolean) {
        if (!editMode || !editable) return
        mapParas(listOf(info.paraId)) { p -> if (accept) DocxRevisions.accept(p, info.id) else DocxRevisions.reject(p, info.id) }
    }

    /** Accepts or rejects every pending revision at once — a single commit, one undo step. */
    fun resolveAllRevisions(accept: Boolean) {
        if (!editMode || !editable) return
        val all = ArrayList<Long>()
        doc?.blocks?.let { blocks -> forEachPara(blocks) { p -> all.add(p.id) } }
        mapParas(all) { p -> if (accept) DocxRevisions.acceptAll(p) else DocxRevisions.rejectAll(p) }
    }

    /** The first editable paragraph (the breaks and page-setup fallback target), or null. */
    fun firstEditableId(): Long? = doc?.blocks?.let { firstEditable(it)?.id }

    /** True when [id] addresses a top-level paragraph — the only place Word hosts a section break. */
    fun isBodyLevel(id: Long): Boolean = doc?.blocks?.any { it is DocPara && it.id == id } == true

    /** The first top-level editable paragraph — the section-break fallback target (table paras never). */
    fun firstBodyLevelId(): Long? = doc?.blocks?.firstOrNull { it is DocPara && it.editable }?.id

    /** The first editable paragraph (the add-comment anchor fallback), or null. */
    private fun firstEditable(blocks: List<com.hmx.pdf.docx.DocBlock>): DocPara? {
        var found: DocPara? = null
        forEachPara(blocks) { p -> if (found == null && p.editable) found = p }
        return found
    }

    /** The comments anchored in the document right now — additions undone show their absence. */
    fun comments(): List<CommentInfo> {
        val cm = pkg?.comments ?: return emptyList()
        val d = doc ?: return emptyList()
        val anchored = HashSet<String>()
        forEachPara(d.blocks) { p ->
            Regex("""<w:commentRangeStart[^>]*w:id="(\d+)""").findAll(p.extras).forEach { anchored.add(it.groupValues[1]) }
        }
        forEachPara(d.blocks) { p ->
            for (marker in p.markers) {
                val node = com.hmx.pdf.docx.XmlScan.scan(marker.xml).children.firstOrNull() ?: continue
                if (node.local == "commentRangeStart") node.attr(marker.xml, "id")?.let { anchored.add(it) }
            }
        }
        return cm.items.filter { it.id in anchored }
    }

    // ── page layout (P3) ────────────────────────────────────────────────────────

    /**
     * The trailing section's page setup as the document blocks hold it right now (not the mirror's
     * `pkg.page`), so preview and PDF follow the undo stack, never a stale value.
     */
    fun livePage(): PageSetup {
        val d = doc ?: return pkg?.page ?: PageSetup()
        val last = DocxPageLayout.sections(d).lastOrNull() ?: return pkg?.page ?: PageSetup()
        return DocxPageLayout.pageSetupOf(last.sectPr)
    }

    /** Every section in current document order, keyed for the page-setup panel's picker. */
    fun sectionsOf(): List<DocxPageLayout.Section> {
        val d = doc ?: return emptyList()
        return DocxPageLayout.sections(d)
    }

    /**
     * Applies one section-kernel edit against the section's CURRENT terminator: the captured
     * [section] object may predate other panel edits this session (page setup then titlePg,
     * header then footer), and rebuilding on its stale string would silently drop those. The
     * [rebase] transform builds content from the fresh sectPr; a rebase that changes nothing
     * (double-taps, stale captures) commits nothing — no phantom undo step, no re-serialized
     * para. A captured synthetic section that has since materialized is gone: refuse instead of
     * double-splicing a second trailing sectPr (Word repair bait).
     */
    private fun commitSect(section: DocxPageLayout.Section, key: String? = null, rebase: (String) -> String) {
        if (!editMode || !editable) return
        val d = doc ?: return
        val fresh = DocxPageLayout.sections(d).firstOrNull {
            it.paraId == section.paraId && it.rawId == section.rawId
        } ?: (if (section.paraId == null && section.rawId == null) return else section)
        val content = rebase(fresh.sectPr)
        if (content == fresh.sectPr) return
        val next = DocxPageLayout.withSect(d, fresh, content)
        if (next.blocks !== d.blocks) commit(next, key)
    }

    /** One section's page setup, orientation included. [key] coalesces field-by-field undo steps. */
    fun setSectionSetup(section: DocxPageLayout.Section, s: PageSetup, key: String? = null) {
        if (s == DocxPageLayout.pageSetupOf(section.sectPr)) return
        if (!editMode || !editable) return
        commitSect(section, key) { fresh -> DocxPageLayout.withPageSetup(fresh, s) }
    }

    fun setFirstPageDifferent(section: DocxPageLayout.Section, on: Boolean) {
        if (on == DocxPageLayout.titlePg(section.sectPr)) return
        commitSect(section) { fresh -> DocxPageLayout.withTitlePg(fresh, on) }
    }

    /** Inserts a section break paragraph before [beforeId] ("nextPage"/"continuous"/"evenPage"/"oddPage"). */
    fun insertSectionBreak(beforeId: Long, type: String?): Boolean {
        if (!editMode || !editable) return false
        val d = doc ?: return false
        val source = DocxPageLayout.sectionAt(d, beforeId)
        val next = DocxPageLayout.insertSectionBreak(d, beforeId, source, type) ?: return false
        lastKey = null
        commit(next)
        return true
    }

    /** Inserts a page break into [id] at [caret] (a structural object, not text: untracked). */
    fun insertPageBreak(id: Long, caret: Int): Boolean {
        if (!editMode || !editable) return false
        val d = doc ?: return false
        val p = findPara(d.blocks, id) ?: return false
        var at = caret.coerceIn(0, p.text.length)
        while (at > 0 && p.text[at - 1] == OBJ_CHAR) at--
        val np = DocxEdits.typed(p, p.text.substring(0, at) + OBJ_CHAR + p.text.substring(at), hnBreakFmt())
        if (np === p) return false
        lastKey = null
        commit(DocState(replacePara(d.blocks, np) ?: return false))
        return true
    }

    private fun hnBreakFmt() = CharFmt(rPr = "", link = null, obj = DocxPageLayout.PAGE_BREAK_OBJ)

    // ── tables (Phase 4.2) ─────────────────────────────────────────────────────

    /** The table, row and cell that own the focused paragraph, if it sits in a cell. */
    fun tableLocation(paraId: Long?): DocxTables.Location? {
        val d = doc ?: return null
        return paraId?.let { DocxTables.locate(d.blocks, it) }
    }

    /**
     * Structural table edits are never tracked: the editor has no flattened revision semantics
     * for grid changes, so Track Changes refuses them instead of writing an invalid table.
     */
    private fun canEditTable(): Boolean = editMode && editable && !tracking

    private fun containsPara(blocks: List<DocBlock>, id: Long): Boolean {
        for (b in blocks) {
            if (b is DocPara && b.id == id) return true
            if (b is DocTable) for (row in b.rows) for (cell in row.cells) {
                if (containsPara(cell.blocks, id)) return true
            }
        }
        return false
    }

    /** Body position for a new table: after the focused paragraph, never nested inside a cell. */
    private fun insertIndexFor(blocks: List<DocBlock>, focusId: Long?): Int {
        if (focusId == null) return blocks.size
        for ((i, b) in blocks.withIndex()) {
            if (b is DocPara && b.id == focusId) return i + 1
            if (b is DocTable && containsPara(listOf(b), focusId)) return i + 1
        }
        return blocks.size
    }

    fun insertTable(rows: Int, cols: Int, focusId: Long? = null): Boolean {
        if (!canEditTable()) return false
        val d = doc ?: return false
        val blocks = DocxTables.create(d.blocks, insertIndexFor(d.blocks, focusId), rows, cols) ?: return false
        lastKey = null
        commit(DocState(blocks))
        return true
    }

    /**
     * Applies one table kernel to the addressed table. The kernel is immutable and refuses unsafe
     * grids, so a refused edit leaves the document (and its dirty state) untouched.
     */
    private fun updateTable(tableId: Long, f: (DocTable) -> DocTable?): Boolean {
        if (!canEditTable()) return false
        val d = doc ?: return false
        val blocks = DocxTables.mapTable(d.blocks, tableId, f) ?: return false
        if (blocks === d.blocks) return false
        lastKey = null
        commit(DocState(blocks))
        return true
    }

    fun insertTableRow(tableId: Long, at: Int): Boolean =
        updateTable(tableId) { DocxTables.insertRow(it, at) }

    fun deleteTableRow(tableId: Long, at: Int): Boolean =
        updateTable(tableId) { DocxTables.deleteRow(it, at) }

    fun insertTableColumn(tableId: Long, at: Int): Boolean =
        updateTable(tableId) { DocxTables.insertColumn(it, at) }

    fun deleteTableColumn(tableId: Long, at: Int): Boolean =
        updateTable(tableId) { DocxTables.deleteColumn(it, at) }

    fun mergeTableCells(tableId: Long, cellId: Long, rowSpan: Int, colSpan: Int): Boolean =
        updateTable(tableId) { DocxTables.mergeRange(it, cellId, rowSpan, colSpan) }

    fun splitTableCell(tableId: Long, cellId: Long): Boolean =
        updateTable(tableId) { DocxTables.split(it, cellId) }

    fun shadeTableCell(tableId: Long, cellId: Long, fill: String?): Boolean =
        updateTable(tableId) { DocxTables.shade(it, cellId, fill) }

    fun setTableCellBorders(tableId: Long, cellId: Long, on: Boolean): Boolean =
        updateTable(tableId) { DocxTables.cellBorders(it, cellId, on) }

    fun setTableCellMargins(tableId: Long, cellId: Long, twips: Int): Boolean =
        updateTable(tableId) { DocxTables.cellMargins(it, cellId, twips) }

    fun setTableCellWidth(tableId: Long, cellId: Long, twips: Int): Boolean =
        updateTable(tableId) { DocxTables.cellWidth(it, cellId, twips) }

    fun setTableRowHeight(tableId: Long, rowIndex: Int, twips: Int?): Boolean =
        updateTable(tableId) { DocxTables.rowHeight(it, rowIndex, twips) }

    fun setTableBorders(tableId: Long, on: Boolean): Boolean =
        updateTable(tableId) { DocxTables.tableBorders(it, on) }

    fun setTableAlignment(tableId: Long, jc: String?): Boolean =
        updateTable(tableId) { DocxTables.tableAlignment(it, jc) }

    /** Toggles the settings-level odd/even headers flag (settings.xml is spliced at save). */
    fun setEvenOddHeaders(on: Boolean) {
        if (!editMode || !editable) return
        val current = wantSettings ?: pkg?.settings
        if (on == DocxPageLayout.hasEvenOdd(current)) return
        if (on) {
            settingsChanged = true
            wantSettings = DocxPageLayout.withEvenOdd(current, true)
        } else if (pkg?.settings == null) {
            // No settings part ever: Word's "off" here is back to the loaded package state itself —
            // not an empty <w:settings> shell written just to hold a removed flag.
            wantSettings = null
            settingsChanged = false
        } else {
            settingsChanged = true
            wantSettings = DocxPageLayout.withEvenOdd(current, false)
        }
        lastKey = null
        scheduleCheckpoint()
    }

    fun evenOddHeaders(): Boolean = DocxPageLayout.hasEvenOdd(wantSettings ?: pkg?.settings)

    // ── headers & footers (P3): part contents live here, references live in sections ──

    private val hfChanged = HashMap<String, DocState>()
    private val hfRelIds = HashMap<String, String>()
    /**
     * `path → the part's original root start tag` (FA-03): captured from the loaded part the
     * first time it is parsed, so an edit re-serializes under a root that still declares the
     * foreign namespaces (`r`, `a`, `wp`, `mc:Ignorable`, …) its preserved children rely on.
     * Brand-new parts have no entry and get the minimal `w`-only root.
     */
    private val hfEnvelopes = HashMap<String, String>()
    private var settingsChanged = false
    /** The spliced settings.xml once the even/odd toggle is on (DocxPackage has no copy()). */
    private var wantSettings: String? = null

    /**
     * The settings part the session would save: the pending edit when one exists, else the
     * loaded part. [dirty] and [recordSaved] must both speak this value — the raw override is
     * null for "untouched", which is not the same as "the document has no settings".
     */
    private fun effectiveSettings(): String? = wantSettings ?: pkg?.settings

    /** What the last save made durable (or the load put in `pkg`): the dirty baseline for package edits. */
    private var packageSavedSettings: String? = null
    private val packageSavedHf = HashMap<String, DocState>()

    /** Saved-parse cache per part path: paragraph ids stay stable across recompositions. */
    private val hfCache = HashMap<String, DocState>()

    private fun hfWalk(state: DocState): List<DocPara> {
        val out = ArrayList<DocPara>()
        forEachPara(state.blocks) { out.add(it) }
        return out
    }

    /** The paragraphs of one header/footer part: the live edit when present, else the cached parse. */
    fun hfParas(path: String): List<DocPara> {
        hfChanged[path]?.let { return hfWalk(it) }
        val saved = hfCache.getOrPut(path) { parseHf(path) }
        return hfWalk(saved)
    }

    /** Parses the loaded part (empty when absent) and captures its root envelope for later saves. */
    private fun parseHf(path: String): DocState {
        val xml = pkg?.hfParts?.get(path)
        if (xml != null && path !in hfEnvelopes) {
            val root = com.hmx.pdf.docx.XmlScan.scan(xml).children.firstOrNull()
            if (root != null) hfEnvelopes[path] = root.startTag(xml)
        }
        return DocxPageLayout.hfState(xml)
    }

    /** The field buffer of one header/footer paragraph. */
    fun hfText(path: String, paraId: Long): String = hfParas(path).firstOrNull { it.id == paraId }?.text ?: ""

    /** Edits one header/footer paragraph's text (package state; no undo step — comments precedent). */
    fun hfTyped(path: String, paraId: Long, newText: String) {
        if (!editMode || !editable) return
        val cur = hfParas(path)
        val idx = cur.indexOfFirst { it.id == paraId }
        if (idx < 0 || cur[idx].text == newText) return
        val p = cur[idx]
        // Headers keep their paragraph's own last ink while typing (styles/fonts preserved).
        val np = DocxEdits.typed(p, newText, DocxEdits.fmtAt(p, p.text.length))
        val base = hfChanged[path] ?: hfCache.getOrPut(path) { parseHf(path) }
        val next = DocState(replacePara(base.blocks, np) ?: return)
        hfChanged[path] = next
        scheduleCheckpoint()
    }

    /** Creates a brand-new header/footer part and references it on [section]; returns its path. */
    fun hfCreate(section: DocxPageLayout.Section, header: Boolean, variant: String): String? {
        if (!editMode || !editable) return null
        val p = pkg ?: return null
        if (DocxPageLayout.reference(section.sectPr, header, variant) != null) return null
        val path = DocxPageLayout.freePart(p.hfParts.keys + hfChanged.keys, !header)
        if (path in hfChanged) return null
        val para = DocPara(
            id = DocIds.next(), origin = null, openTag = "<w:p>", pPr = "", extras = "",
            text = "", fmts = emptyList(), editable = true
        )
        hfChanged[path] = DocState(listOf(para))
        val id = freeRelId()
        hfRelIds[path] = id
        commitSect(section) { fresh -> DocxPageLayout.withReference(fresh, header, variant, id) }
        scheduleCheckpoint()
        return path
    }

    /** Removes this section's reference to a header/footer variant; other variants/sections untouched. */
    fun hfRemove(section: DocxPageLayout.Section, header: Boolean, variant: String) {
        if (!editMode || !editable) return
        val rid = DocxPageLayout.reference(section.sectPr, header, variant) ?: return
        commitSect(section) { fresh -> DocxPageLayout.withReference(fresh, header, variant, null) }
        // A part no section references any more stops being carried: purge its session text/rel
        // claim so the save writes neither the part nor its relationship (writer skips unlisted paths).
        val d = doc ?: return
        if (DocxPageLayout.sections(d).any { it.sectPr.contains("r:id=\"$rid\"") }) return
        val path = hfRelIds.entries.firstOrNull { it.value == rid }?.key ?: hfPathOf(rid)
        if (path != null) { hfChanged.remove(path); hfRelIds.remove(path) }
        scheduleCheckpoint()
    }

    private fun freeRelId(): String {
        // Session-claimed ids are invisible to the loaded rels string; counting both is what keeps
        // a second hfCreate in one session from reusing the first create's id (duplicate Id = repair).
        val fromRels = Regex("Id=\"(rId\\d+)\"").findAll(pkg?.relsXml ?: "").mapNotNull { m ->
            m.groupValues[1].removePrefix("rId").toIntOrNull()
        }
        val fromSession = hfRelIds.values.mapNotNull { it.removePrefix("rId").toIntOrNull() }
        return "rId${((fromRels + fromSession).maxOrNull() ?: 0) + 1}"
    }

    /**
     * The header/footer part path a section reference points at ([rid]). Session claims are
     * consulted first (FA-06): a part created this session has a relationship id the loaded
     * rels string has never seen, and re-resolving it through the loaded rels returned null —
     * the panel's second open of a new header fell through to "cannot resolve".
     */
    fun hfPathOf(rid: String): String? {
        hfRelIds.entries.firstOrNull { it.value == rid }?.let { return it.key }
        val p = pkg ?: return null
        return p.rels[rid]?.takeIf { it in p.hfParts }
    }

    /** Package-level layout state belongs to one document: clearing at every (re)load. */
    private fun resetPageLayoutExtras() {
        hfChanged.clear(); hfRelIds.clear(); hfEnvelopes.clear(); hfCache.clear(); settingsChanged = false; wantSettings = null
        // The loaded settings/hfs ARE the saved baseline: edits that land back on the loaded value
        // (a toggle on then off) compare equal here and stop counting as dirty.
        packageSavedSettings = pkg?.settings
        packageSavedHf.clear()
        notes = pkg?.notes
        packageSavedNotes = pkg?.notes
        // Annotations belong to the previous document/generation; the cache keeps checked words.
        spellJob?.cancel(); spellJob = null
        spellIssues = emptyList(); spellRanges = emptyMap()
        spellStatus = SpellStatus.IDLE; spellLanguage = null; spellMessage = null
        readingAutoPlay = false; readingSession?.stop(); scriptDoc = null
        mirrorReading()
    }

    /** The package half of save: header/footer parts, notes and settings ride with the body patch. */
    private fun extras(): DocxWriter.Extras {
        val n = notes?.takeIf { it !== packageSavedNotes }
        val noteParts = LinkedHashMap<String, String>()
        if (n != null) for (kind in DocxNotes.Kind.values()) {
            val xml = n.document(kind) ?: continue
            val path = n.pathOf(kind) ?: DocxNotes.defaultPath(kind, pkg?.mainPath?.substringBeforeLast('/', "") ?: "")
            noteParts[path] = xml
        }
        return DocxWriter.Extras(
            hfSave = hfChanged.toMap(),
            hfRelIds = hfRelIds.toMap(),
            settings = if (settingsChanged) wantSettings else null,
            hfEnvelopes = hfEnvelopes.toMap(),
            notes = noteParts
        )
    }

    /**
     * Everything one save serializes, captured once on the owner thread (FA-04): the package, the
     * body and the package-level edits as they were when the save was requested. The IO coroutine
     * and [recordSaved] consume exactly this — re-reading the live fields from the write thread let
     * edits made during a slow save change the output or be acknowledged as saved when they weren't.
     */
    internal data class SaveCapture(
        val pkg: DocxPackage,
        val doc: DocState,
        val extras: DocxWriter.Extras,
        val source: File? = null,
        val fileName: String = "document.docx",
        val identity: String? = null,
        /** The notes snapshot this capture wrote (null when no note changed). */
        val notes: DocxNotes? = null
    )

    internal fun saveCapture(): SaveCapture? {
        val p = pkg ?: return null
        val d = doc ?: return null
        return SaveCapture(p, d, extras(),
            meta.fileUri?.takeIf { it.scheme == "file" }?.path?.let(::File), meta.fileName,
            meta.sourceUri?.toString(),
            notes?.takeIf { it !== packageSavedNotes })
    }

    /** The save succeeded: body and package snapshots become the dirty baseline (also a test seam). */
    internal fun recordSaved(capture: SaveCapture) {
        saved = capture.doc
        // The effective part, not the raw override: a save with no settings edit still makes the
        // loaded settings part the baseline (a null override compared against it would keep the
        // document dirty forever after saving).
        packageSavedSettings = capture.extras.settings ?: capture.pkg.settings
        packageSavedHf.clear()
        packageSavedHf.putAll(capture.extras.hfSave)
        capture.notes?.let { packageSavedNotes = it }
        lastCheckpointFp = null
        autosave.onSaved()
        autosaveJob?.cancel(); autosaveJob = null
        // A slow manual save can acknowledge an older generation. Keep protecting newer edits.
        if (dirty) scheduleCheckpoint()
    }

    /** Legacy test seam: record the current session state as saved. */
    internal fun recordSaved(snapshot: DocState) {
        val p = pkg ?: return
        recordSaved(SaveCapture(p, snapshot, extras(), notes = notes?.takeIf { it !== packageSavedNotes }))
    }

    /** Test seam: the same payload [save] hands the writer, so JVM tests cover what a save writes. */
    internal fun writerExtras() = extras()

    private fun buildPatched(context: Context, capture: SaveCapture): File? {
        val src = capture.source ?: return null
        val dir = File(context.cacheDir, "docs").apply { mkdirs() }
        val out = File.createTempFile("edited-", ".docx", dir)
        return runCatching { DocxWriter.write(capture.pkg, capture.doc, src, out, capture.extras); out }
            .getOrElse { out.delete(); null }
    }

    sealed class SaveResult {
        data object Saved : SaveResult()
        data object NeedsSaveAs : SaveResult()
        data class Failed(val message: String) : SaveResult()
    }

    private sealed class WriteResult {
        data object Success : WriteResult()
        data class Failure(val message: String, val canRetryWithSaveAs: Boolean = true) : WriteResult()
    }

    fun save(context: Context, target: Uri?, onDone: (SaveResult) -> Unit) {
        if (meta.saving) return onDone(SaveResult.Failed("A save is already in progress."))
        if (!editable) return onDone(SaveResult.Failed("This document is read-only and can't be saved."))
        val dest = target ?: meta.sourceUri ?: return onDone(SaveResult.NeedsSaveAs)
        // One immutable generation for the whole save (FA-04): body, package and package-level
        // edits are captured here on the owner thread; the writer and the acknowledgement below
        // speak exactly this capture, never the live session state.
        val capture = saveCapture() ?: return onDone(SaveResult.Failed("Couldn't write the document."))
        meta = meta.copy(saving = true)
        val identity = meta.sourceUri?.toString()
        viewModelScope.launch {
            val checkpointBeforeSave = withContext(Dispatchers.IO) {
                identity?.let { EditorCheckpointStore.find(context, it) }
            }
            val result = withContext(Dispatchers.IO) {
                val patched = buildPatched(context, capture) ?: return@withContext SaveResult.Failed("Couldn't write the document.")
                try {
                    when (val written = writeWithRecovery(
                        context = context,
                        uri = dest,
                        file = patched,
                        displayName = capture.fileName,
                        requireBackup = target == null
                    )) {
                        WriteResult.Success -> SaveResult.Saved
                        is WriteResult.Failure ->
                            if (target == null && written.canRetryWithSaveAs) SaveResult.NeedsSaveAs
                            else SaveResult.Failed(written.message)
                    }
                } finally {
                    patched.delete()
                }
            }
            meta = meta.copy(saving = false)
            if (result is SaveResult.Saved) {
                recordSaved(capture)
                if (!dirty && checkpointBeforeSave != null) {
                    withContext(Dispatchers.IO) {
                        EditorCheckpointStore.clearIfCurrent(context, checkpointBeforeSave)
                    }
                }
            }
            onDone(result)
        }
    }

    /**
     * Prepare a durable recovery entry (backup + validated replacement), then write the replacement
     * to the SAF destination and read it back. SAF cannot promise an atomic replace; the journal is
     * what makes an interrupted write recoverable. A destination that changed after the backup is
     * never overwritten.
     */
    private fun writeWithRecovery(
        context: Context,
        uri: Uri,
        file: File,
        displayName: String,
        requireBackup: Boolean
    ): WriteResult {
        if (file.length() <= 0L) {
            return WriteResult.Failure("Couldn't prepare the document.", canRetryWithSaveAs = false)
        }
        val entry = runCatching {
            SaveRecovery.begin(context, uri, displayName, "docx", file, requireBackup)
        }.getOrElse {
            return WriteResult.Failure(
                it.message?.takeIf { message -> message.isNotBlank() }
                    ?: "Couldn't prepare a safe copy; the original was not changed.",
                canRetryWithSaveAs = false
            )
        }
        return try {
            SaveRecovery.markWriting(entry)
            if (SaveRecovery.destinationChangedSinceBackup(context, entry)) {
                // Keep the prepared copy: the destination belongs to someone else now, but the
                // user's edits are still the only recoverable copy of this save.
                SaveRecovery.markFailed(entry)
                return WriteResult.Failure(
                    "The destination changed while the document was being prepared. Nothing was written; " +
                        "a recovery copy was kept.",
                    canRetryWithSaveAs = true
                )
            }
            val prepared = entry.prepared
                ?: throw IllegalStateException("The prepared document is missing")
            val out = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: runCatching { context.contentResolver.openOutputStream(uri, "w") }.getOrNull()
                ?: throw IllegalStateException("Couldn't open that location for writing")
            out.use { output ->
                prepared.inputStream().use { input -> input.copyTo(output) }
            }
            if (SaveRecovery.destinationHash(context, entry) != entry.preparedHash) {
                throw IllegalStateException("The destination did not receive the whole document")
            }
            SaveRecovery.markVerified(entry)
            WriteResult.Success
        } catch (error: Throwable) {
            SaveRecovery.markFailed(entry)
            when (SaveRecovery.restoreIfSafe(context, entry)) {
                SaveRecovery.RestoreResult.SUCCEEDED -> {
                    SaveRecovery.markVerified(entry)
                    WriteResult.Success
                }
                SaveRecovery.RestoreResult.UNCHANGED,
                SaveRecovery.RestoreResult.RESTORED -> {
                    SaveRecovery.discard(entry)
                    WriteResult.Failure(
                        "Couldn't save to that location. The original document is unchanged.",
                        canRetryWithSaveAs = true
                    )
                }
                SaveRecovery.RestoreResult.UNSAFE,
                SaveRecovery.RestoreResult.FAILED -> WriteResult.Failure(
                    "Couldn't save, and the original couldn't be restored safely. " +
                        "A recovery copy was kept; HMX PDF will offer it on the next launch.",
                    canRetryWithSaveAs = false
                )
            }
        }
    }

    fun suggestedSaveAsName(): String {
        val n = meta.fileName.ifBlank { "Document.docx" }
        return n.substringBeforeLast('.') + " (edited).docx"
    }

    /** The edited document (or the original when unedited) as a file uri. Null if an edited copy failed. */
    fun currentFile(context: Context, onDone: (Uri?) -> Unit) {
        val uri = meta.fileUri ?: return onDone(null)
        val hasEdits = doc !== original || settingsChanged || hfChanged.isNotEmpty()
        if (!hasEdits) return onDone(officeShareSource(false, uri) { null })
        viewModelScope.launch {
            // The share/export copy is one write of one generation: captured on the owner
            // thread, exactly like save() (FA-04).
            val cap = saveCapture()
            val out = withContext(Dispatchers.IO) {
                if (cap == null) null else buildPatched(context, cap)?.let { Uri.fromFile(it) }
            }
            onDone(officeShareSource(true, uri) { out })
        }
    }

    /**
     * The document as a PDF, for the PDF button and Print, best source first: the Office engine
     * (LibreOffice, Word's own layout rules) when it is installed; otherwise the editor's own pages
     * drawn straight into a PDF ([DocxPdfWriter]), exactly as they read here, unsaved edits
     * included; and only if that fails, the old web-preview converter.
     */
    fun exportToPdf(context: Context, onDone: (Uri?) -> Unit) {
        // The fallback renderer follows the live page setup (blocks), not a possibly-stale mirror.
        pkg?.page = livePage()
        val p = pkg
        val d = doc
        val name = meta.fileName.ifBlank { "Document.docx" }
        currentFile(context) { src ->
            viewModelScope.launch {
                val out = withContext(Dispatchers.Default) {
                    if (src != null) {
                        runCatching { com.hmx.pdf.office.OfficeEngine.tryConvert(context, src, name.lowercase()) }
                            .getOrNull()?.let { return@withContext Uri.fromFile(it) }
                    }
                    if (p != null && d != null) {
                        val dir = File(context.cacheDir, "converted_pdfs/${System.currentTimeMillis()}")
                        val pdf = File(dir, name.substringBeforeLast('.').ifBlank { "Document" }.replace('/', '_') + ".pdf")
                        if (com.hmx.pdf.ui.screen.DocxPdfWriter.write(context, p, d.blocks, pdf)) return@withContext Uri.fromFile(pdf)
                    }
                    if (src == null) null
                    else runCatching { com.hmx.pdf.utils.UniversalDocumentConverter.convertToPdf(context, src) }.getOrNull()
                }
                onDone(out)
            }
        }
    }

    private fun mirrorToCache(context: Context, uri: Uri, name: String): Uri {
        val dir = File(context.cacheDir, "docs").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "document.docx" }
        val file = File(dir, "${System.currentTimeMillis()}_$safe")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { input.copyTo(it) }
        } ?: return uri
        if (file.length() == 0L) { file.delete(); return uri }
        return Uri.fromFile(file)
    }

    private fun queryName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i != -1 && c.moveToFirst()) c.getString(i) else null
        }

    override fun onCleared() {
        spellJob?.cancel()
        deviceSpell?.close()
        deviceSpell = null
        readingAutoPlay = false
        readingPreparing = false
        readingSession?.release()
        readingSession = null
        readingEngine = null
        super.onCleared()
    }
}
