# HMXPDF autonomous development master log

## Phase 4.6 integration and final verification — 2026-11-17 (CI run 44124495265)

Cross-feature integration is covered by `DocxPhase4IntegrationTest`: in one session it inserts a
table and a row, adds a footnote (reference plus part element), applies an explicit spell
suggestion, takes the single save capture, writes through `DocxWriter`, and reads the file back —
table structure, both notes (the fixture's id 3 and the new one), the corrected paragraph and an
unrelated `word/customXml` part all survive. Undo then unwinds the four operations newest-first and
redo restores the same combined state, so undo and the checkpoint generation move together. A
read-aloud pass over the edited document speaks every chunk and leaves the `DocState` instance and
the dirty flag untouched. The final gate on `275c54a` is run 44124495265: build, release and
release-guard all succeeded; debug APK 171,426,738 B, signed release APK set 134,876,344 B
(universal APK 86,075,076 B), AAB 51,319,340 B. Device-only items (API 23/current, low RAM,
no-GMS, offline voice installation, audio focus, force-stop recovery and external
Word/LibreOffice reopen) remain unexecuted and are recorded in
`docs/HMXPDF_FINAL_PROJECT_VERIFICATION_REPORT.md`. The documentation head `690ad4c` was itself
gated in run 44131731875 (build, release, release-guard all success); later documentation-only
commits change no code. Per instruction, `main` is not pushed.

## Phase 4.3 implementation — 2026-11-17 (CI run 44120403325; final 44124495265)

Spell checking runs entirely offline through `AndroidSpellEngine`, a `SpellCheckerSession` adapter
over the installed Android text service; it never opens a network connection. The adapter refuses
a service whose package requests `INTERNET` (API 31+ reads the active service's package, older
releases read the secure `spell_checker` component) and the panel reports an unavailable state
otherwise. `SpellCheck` tokenizes each editable paragraph's flat text, which makes words split
across runs checkable; it skips object runs, digits/digit-adjacent fragments, URLs, e-mail
addresses and paths, and asks the service in bounded 64-word chunks with cooperative cancellation.
A bounded LRU (`SpellCache`) keeps results for the session. Suggestions are annotations held in the
ViewModel beside the document — never in `DocState`, rPr or the dirty baseline — and are drawn as
display-only ranges behind the text. The view model publishes issues with the exact paragraph
instance they were found in; a replacement is only applied from an explicit tap, refuses when the
paragraph instance or text changed (stale suggestion), and otherwise goes through `DocxEdits`
with the same Track Changes capture as every other edit (single undo step). The new Spell panel
(7 locales) lists words and suggestions, exposes running/unavailable/no-issues states and a
re-check action. JVM tests cover token rules, cross-run words, bounded cache, chunking, cooperative
cancellation, editable-only scanning, stale refusal, formatting/comment preservation, tracked
replacement, view-mode gating and ignore. No local Android build. Verified in run 44120403325 on `4ded969` and
re-verified with the 4.6 suite in run 44124495265 on `275c54a`: build, release and release-guard
all succeeded (375 JVM tests, 0 failed, 42 pre-existing skips). Per instruction, `main` is not
pushed.

## Phase 4.4 implementation — 2026-11-17 (CI run 44057796676; re-verified 44124495265)

Footnotes and endnotes: `DocxNotes` parses `word/footnotes.xml` / `word/endnotes.xml` into
immutable note snapshots (kind, id, separator flag, paragraph blocks, original element span) and
builds the part back by splicing only added/edited/removed notes; reserved separators (-1/0) are
never allocated or touched, ids are independent per part. `DocxReader` resolves both parts through
relationships (fallback to word/*notes.xml), `DocxPackage` carries the parsed state, and
`DocxWriter.Extras.notes` writes an existing part in place or creates the part plus its
relationship and content-type override. The view model inserts a note reference and its note
element in one undo step, deletes both atomically (refusing when a reference cannot be removed
from read-only/raw content), edits note paragraphs through the body's paragraph kernel, refuses
note operations while Track Changes is on, and includes the notes snapshot in dirty, checkpoints,
save capture and recovery. The Notes panel (7 locales) lists notes, inserts footnotes/endnotes at
the focused paragraph, edits their paragraph text and removes them. JVM tests cover separator
preservation, id allocation, comment/note isolation, writer round trips (existing and created
parts, relationship and content-type entries), VM gating, undo/redo and the save capture. No local
Android build. First verified in run 44057796676 on `890d076`; re-verified together with 4.3/4.5/4.6
in run 44124495265 on `275c54a` (all jobs success, 375 JVM tests, 0 failed, 42 pre-existing skips).
Per instruction, `main` is not pushed.

## Phase 4.5 implementation — 2026-11-17 (CI run 44120403325; final 44124495265)

Read Aloud is a read-only projection. `ReadingProjection` walks the document through `forEachPara`
(body text, table cells and list items in reading order), strips object runs, excludes pending
deletions (`w:del`) while including pending insertions, collapses whitespace, skips blank
paragraphs, and cuts the script into ≤320-character chunks at sentence boundaries first and word
boundaries second. `ReadingSession` is a JVM-testable state machine over `TtsEngine`: play, pause,
resume, stop, chunk completion, previous/next paragraph, stale-callback rejection, error→paused,
rate clamping and the unavailable state; pause is stop plus the retained chunk index because the
Android API has no real pause. `AndroidTtsEngine` selects only a voice whose
`isNetworkConnectionRequired` is false (a cloud voice is never selected or requested), uses
transient audio focus, pauses on focus loss, moves TTS init/utterance callbacks onto the main
thread, and releases focus only for the utterance that owns it so a flushed chunk cannot abandon
the next chunk's focus. The Read panel (7 locales) shows status, voice, play/pause/resume, stop,
previous/next, rate chips and the current chunk. Reading never edits, accepts a revision, dirties
the file or marks a checkpoint: the highlight is a display-only `readingId`, and every edit, undo,
document load or editor close calls `stopReading`, so a stale paragraph id can never be highlighted.
JVM tests cover projection order/policy/chunk bounds and the full session state machine with a fake
engine. No local Android build. Verified in run 44120403325 on `4ded969`; final gate with 4.6 in
run 44124495265 on `275c54a` (all jobs success, 375 JVM tests, 0 failed, 42 pre-existing skips). Per
instruction, `main` is not pushed.

## Phase 4.2 complete — 2026-11-17 (CI run 44050687595)

Advanced tables: immutable `DocxTables` kernels (grid validation, table creation, row/column
insert and delete, rectangular merge/split, shading, borders, margins, width, row height and
alignment), schema-ordered `tblPr`/`trPr`/`tcPr` property splices in `DocxProps`, view-model
commands gated on `editMode && editable && !tracking`, and a table panel localized in all seven
locales. The kernels refuse `DocRaw` children, gridBefore/gridAfter, hMerge, irregular grids and
revision-bearing tables; merges preserve covered content on the anchor instead of dropping it.
JVM tests cover grid invariants, content conservation, writer→reader round trip, revision refusal
(without false positives from `insideH`/`insideV`), property ordering and the view-model gates,
undo/redo and focus targeting. Published head `b9bb233`; run 44050687595 success (324 tests, 0
failed, 42 pre-existing skips; debug APK 171,061,101 B, release APK 134,738,175 B, AAB 51,242,635
B). Repair history: `e77d229` → resource-apostrophe failure, `b6eb4b5` → VM merge-assertion
failure, `b9bb233` green. Per instruction, `main` was not pushed.

## Phase 4.1 complete — 2026-11-17

Final head `0d72242`; CI run 44044415038 success (build, release, release-guard), checked-out
commit equals the published branch head. Debug APK 170,830,525 B, signed release APK
134,671,621 B, AAB 51,197,800 B. The reopened audit repaired publication crash windows, wrong
recovery source mirrors, live-state spreadsheet saves, unbounded generations, unconditional
checkpoint deletion and unswept crash temps; regression tests cover each boundary. Device-only
process-death/reboot/revoked-URI/real-ENOSPC scenarios remain unverified and are documented as
limits. `main` is fast-forwarded to the same verified head. Next: Phase 4.2 (advanced tables).

## Phase 4.1 audit round 2 — 2026-11-17 (superseded by the completion entry above)

Source audit after the synchronization found and repaired: spreadsheet saves captured live state
on the IO thread and acknowledged edits made during the save (now an immutable `SheetCapture`
covers source, name, ops and identity; only an exact op match becomes the clean baseline); a
spreadsheet save retired whatever checkpoint was newest (now `clearIfCurrent` only); interrupted
or failed checkpoint publications left orphan generation packages and temps (now removed, and a
successful commit prunes to one generation plus metadata); and crash-orphaned editor cache files
were never swept (now age-bounded in `EditorTempFiles`, wired at both editor loads). Regression
tests cover orphan cleanup, bounded storage, identity mismatch, and temp aging. No local Android
build; CI verification is pending. Phase 4.1 is still open.

## Main synchronization — 2026-11-17

GitHub default branch is `release/hmxpdf-2.1.1-build`; it carries the development work.
Before further Phase 4 work, `main` was synchronized by fast-forward from `de40322` to the
verified development head `4c406e4` (0 commits unique to main, 37 unique to the development
branch). `backup/main-before-phase4-sync-2026-11-17` was created and verified at the original
main commit before any change. No force-push, reset, clean or deletion was used. All Phase 1–4.0
final commits and the Phase 4.1 recovery repair are reachable from main; their CI runs are green.
Phase 4.1 remains open — the synchronized baseline includes in-progress work that is documented
as open, not as complete. Full evidence: `docs/MAIN_BRANCH_SYNCHRONIZATION_REPORT.md`.

## Recovery audit update — 2026-11-17

Resumed after server restart cancelled the CI monitor. Run 43993526471 for `e562454`
was still running on resumption (release and release-guard subsequently succeeded; build
pending). Additional repairs capture the DOCX source mirror and output name with document
state, use unique output files, retire checkpoints only by matching generation, keep newer
edits scheduled after save, compare spreadsheet checkpoint operation lists rather than their
length, and cancel pending debounce when the document becomes clean. A regression test
covers stale save cleanup attempting to delete a newer checkpoint. Spreadsheet immutable
save capture and concurrent save acknowledgement remain under audit; no completion claimed.

Phase 4.1 is REOPENED; earlier completion statements below are superseded. Green CI did not
prove interruption safety. Source audit found replacement of package bytes before metadata
publication could invalidate the previous checkpoint, and recovery kept using the original
package as the subsequent writer source. Repairs now use immutable generation filenames with
a single metadata publication point, sync metadata after writing, retain unreadable recovery
data, and copy recovered packages into matching editor source mirrors. An injected failure
before metadata publication verifies preservation of the previously committed generation.

Validation so far: `git diff --check` passed; no local Gradle/APK builds. New CI verification
is pending. Phase 4.2 must wait for the reopened foundation gate. Remaining audit items include
save/checkpoint races, newer edits during save, spreadsheet op identity versus list length,
retry scheduling, startup recovery with inaccessible originals, and storage bounds. Earlier
claims that all these scenarios were covered by the original JVM tests were too broad.

Recovery point for autonomous phase-by-phase work. Update after every meaningful operation.

## Environment and identity

- Local workspace (no `.git`): `/mnt/sdcard/AIProjects/Hmxpdf` (= `/storage/emulated/0/AIProjects/Hmxpdf`)
- Working clone: `/tmp/opencode/hmxpdf-phase1-publish`
- Remote: https://github.com/bharti1233/Hmxpdf.git
- Actual default branch (discovered via `git ls-remote --symref`): `release/hmxpdf-2.1.1-build`
- CI: `.github/workflows/android.yml` — on push to the default branch: unit tests → lint → `assemblePlayDebug` → signed `assemblePlayRelease` + `bundlePlayRelease` (release environment secrets) → `release-guard`.

## Phase 1 — DOCX Editing Foundation: COMPLETE (CI verified)

- Pre-publication baseline preserved: branch `backup/pre-phase1-publication` @ `55007c32663c0c3e84bc76ec1abbed07e3318bc8`.
- Publication commit: `c031c732c6d9a8dff64f4fd8b2349ef5039e5ace` (normal push to the default branch).
  - 13 modified source/test files: DocxModel/Reader/Writer/Edits/Comments/Revisions, DocxEditorViewModel, DocxEditorScreen, plus 5 test classes.
  - Plus docs: `docs/fresh-audit/` (14), `docs/phase1-completion/` (8), and the pre-Phase-1 audit trail.
- Token note: the token supplied with an `app.` prefix was rejected (401); the same token without the prefix authenticated (scopes `repo`, `workflow`). Masked in all logs. Recommendation: rotate this token after use since it was pasted in plain text.
- CI run 43934592604 (https://github.com/bharti1233/Hmxpdf/actions/runs/43934592604): **completed/success**.
  - Jobs: build (tests+lint+debug APK) ✅, release (signed APK 134.59 MB + AAB 51.17 MB, signature verified) ✅, release-guard ✅.
  - Debug APK artifact: `hmx-pdf-debug-apk` 170.70 MB.
  - Test counts not published by workflow; step success recorded only.
- Post-build audit: modified-file diff reviewed; no secrets, no dead code introduced; `DocxWriter` remains the sole DOCX writer; architecture invariants held.
- Reports: `docs/phase1-completion/*` (8 files) and `docs/phase-completion/PHASE1_FINAL_REPORT.md`.
- Not verified: on-device/instrumentation behavior; external Word/LibreOffice compatibility.

## Phase 2 — Track Changes: COMPLETE (CI verified)

- Re-verified against source at `c031c73` (not trusting the phase reports): reader parses `w:ins`/`w:del`/`w:delText` into per-char `Rev` flags; writer emits `w:ins`/`w:del` wrappers + `w:delText`; `DocxRevisions.accept/reject/acceptAll/rejectAll` present; VM `tracking`/`pendingRevisions`/`resolveRevision`/`resolveAllRevisions` present; `DocPanel.REVISIONS` panel with Track toggle and accept/reject-all actions present.
- Tests: `DocxRevisionsTest` (24 `@Test`) + `DocxTrackingStateTest` (9 `@Test`) — green in run 43934592604 (head `c031c73`).
- Debug + signed release APK/AAB artifacts for the same commit verified.
- Report: `docs/phase-completion/PHASE2_FINAL_REPORT.md`.
- Not verified: on-device panel review; external Word/LibreOffice handling of emitted wrappers.

## Phase 3 — Page Layout and Page Setup: COMPLETE (CI verified)

- Re-verified against source: `DocxPageLayout` (sections, orientation/sizes/margins/gutter, titlePg, section-break insert/type, HF references, even/odd settings splice), VM ops (`setSectionSetup`, `commitSect` rebase, HF editing with stable-id cache), `DocPanel.LAYOUT` panel UI, `DocxPageLayoutTest` (18 round-trip tests) — green in run 43934592604.
- Rejected experimental direct-manipulation UI was not reintroduced.
- Report: `docs/phase-completion/PHASE3_FINAL_REPORT.md`.

## Phase 4 — Advanced Document Editing: 4.0 COMPLETE (CI verified)

Planning basis: `docs/fresh-audit/HMXPDF_MASTER_IMPLEMENTATION_ROADMAP.md` + `HMXPDF_FEATURE_GAP_ANALYSIS.md` (+ FA register `HMXPDF_BUGS_AND_TECHNICAL_DEBT.md`). The five named `docs/phase4/*.md` planning docs do not exist; scope limited to the approved docs — no invention.

### Phase 4.0 — Foundation: COMPLETE

Commits (normal pushes): `c8816c2` (implementation) → repairs `632d9d5` (2 compile errors: `SaveRecovery.begin(root,…)` context seam, `currentFile` capture), `bf9dabd` (`createTempDir` deprecated-at-ERROR in test code; unused import), `9463b53` (test-only: `sections()` terminal-entry model). Final: run 43943430785 — **success** (tests 0 failures / 42 pre-existing skips, lint, debug APK 170.70 MB, signed release APK 134.59 MB + AAB 51.17 MB, release-guard).

- FA-03: HF envelope preservation (`hfEnvelopes` VM capture → `Extras` → `hfXml` re-emits original root start tag).
- FA-04: `saveCapture()` immutable generation; writer + `recordSaved(capture)` consume exactly it; share/export path too.
- FA-05: `SessionSnap` package-inclusive undo/redo (HF parts, rel claims, envelopes, settings).
- FA-06: `hfPathOf` resolves session-created parts first.
- FA-22: `CT_OnOff` val-aware `titlePg`/`hasEvenOdd`; inclusive `sectionAt`.
- Recovery: `SaveRecovery` pure core JVM-tested (`SaveRecoveryCoreTest`, 6 tests) replacing the empty `@Ignore`d `SaveRecoverySameUriTest`.
- Shared `DocxFixtures`; 16 new tests total. Post-build audit clean (built commit = remote head; diff scope exact; no secrets/placeholders; sole-writer invariant held).

Report: `docs/phase-completion/PHASE4_FINAL_REPORT.md`.

## Phase 4.1 — Autosave and Recovery: COMPLETE (CI verified)

Commits: `f58a6ec` (DOCX editor: store + coordinator + wiring + recovery UI) → repair `67e249a` (internal visibility of `pendingCheckpoint`) → repair `df0848c` (test-only `listFiles` NPE) → `e36abbb` (spreadsheet wiring). Final runs: 43949208216 (DOCX) and 43951540104 (full) — **both success** (305 tests / 0 failures / 42 pre-existing skips, lint, debug APK 170.82 MB, signed release APK 134.65 MB + AAB 51.20 MB, release-guard).

- `EditorCheckpointStore`: one validated complete package per document identity (sha-256 of the source URI) under noBackupFilesDir; validate→atomic-rename keeps the last known-good checkpoint through disk-full/interruption/death; generation + sticky originalHash metadata; corrupt leftovers refused+cleaned.
- `AutosaveCoordinator`: 2 s idle debounce, 15 s bounded dirty interval, one write at a time, edits-during-write coalesce into one immediate follow-up.
- Both editors wired: checkpoint writes ride the save-capture/op-log discipline; manual save clears the store; load offers recover/discard/later; recovery never produces a false clean state; stale originals labeled. Recovery UI in the established dialog patterns, 7 locales.
- Tests: AutosaveCoordinatorTest (7) + EditorCheckpointStoreTest (8).
- Limits: on-device force-stop/restart scenarios remain device-verified work; 2 s/15 s unprofiled; WorkManager cleanup not added (roadmap-optional).

Report: `docs/phase-completion/PHASE4_FINAL_REPORT.md`.

## Phase 4.2 — Advanced Tables: NEXT

Next steps (STEP A): read roadmap §4.2 + gap-analysis "Advanced DOCX tables"; audit the existing table model (reader cell blocks, `mapContainerOf`, writer `table`), reject/protect revision-bearing structural edits; implement row/col ops, merge/split, borders/shading/margins/row heights with tests; publish; monitor CI.

## Open items / risks

- Exact unit-test counts invisible to CI reporting (no JUnit XML artifact) — consider adding a test-report upload step in a later phase commit.
- External Office compatibility fixtures still unrun.
- Device verification requires manual install of the CI APK artifacts.
