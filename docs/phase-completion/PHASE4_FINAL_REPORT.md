# Phase 4 final report (autonomous master-flow convention)

> **2026-11-17 completion: Phases 4.3, 4.4, 4.5 and 4.6 are complete on
> `release/hmxpdf-2.1.1-build`.** Final code/test head
> `275c54ab328977a65f968cd4b94a53984e36131f`; run 44124495265 (build, release, release-guard all
> success; 375 JVM tests, 0 failed, 42 pre-existing skips). Phase 4.4 was first verified by run
> 44057796676 on `890d076`; 4.3 and 4.5 by run 44120403325 on `4ded969`, then re-verified with the
> 4.6 suite. Device smoke for 4.6 is **not executed** (no device in CI) and is listed as a limit,
> not as a pass. Per instruction, `main` was not pushed (it remains at `ca0caa3`).

> **2026-11-17 correction: Phase 4.1 was reopened and is now complete.** Its first green
> builds were valid build evidence, but a source audit found a checkpoint publication crash
> window, recovered packages using the wrong source mirror on later saves, non-immutable
> spreadsheet saves, unbounded generations and unswept crash temps. Repairs
> `aab606f` → `e562454` → `4c406e4` → `0d72242` closed those paths; final run 44044415038 is
> success. Device-only scenarios (real force-stop/reboot, revoked URI, real ENOSPC) remain
> unverified and are listed as limits in the 4.1 section.

Scope basis: `docs/fresh-audit/HMXPDF_MASTER_IMPLEMENTATION_ROADMAP.md` (Phase 4.0–4.6) and
`docs/fresh-audit/HMXPDF_FEATURE_GAP_ANALYSIS.md` — the five Phase 4 planning documents named in
the original work order do not exist in the repository (`docs/phase4/` absent); no scope was
invented beyond these two approved documents and the FA defect register
(`docs/fresh-audit/HMXPDF_BUGS_AND_TECHNICAL_DEBT.md`, FA-01…FA-24).

## Sub-phase status

| Sub-phase | Scope (roadmap) | Status |
|---|---|---|
| 4.0 Foundation | Save coordinator, package-safe splicing, package-inclusive session state, shared fixtures, resolve skipped recovery tests | **IMPLEMENTED AND CI VERIFIED** (below) |
| 4.1 Autosave & recovery | Autosave, recovery checkpoints, save reliability, disk-full handling, retry, process-death recovery | **COMPLETE** (CI verified below; device scenarios listed as limits) |
| 4.2 Advanced tables | Structural row/col ops, merge/split, borders, shading, cell margins, row heights | **COMPLETE** (CI verified below; complex-grid limits documented) |
| 4.4 Footnotes/endnotes | Note part structure and editing | **COMPLETE** (CI verified below; external-Office/numbering limits documented) |
| 4.3 Spell checking | Local engine, suggestions, safe cross-run replacement | **COMPLETE** (CI verified below; hardware spell service unverified) |
| 4.5 Read Aloud | Android TTS, playback controls | **COMPLETE** (CI verified below; hardware TTS/focus unverified) |
| 4.6 Integration & regression | Cross-feature regression, release verification | **COMPLETE (JVM/CI)** (below; device smoke is explicitly unverified) |

(Execution order follows the roadmap's dependency graph: 4.1 → 4.2 → 4.4 → 4.3 → 4.5 → 4.6.)

---

## Phase 4.0 — Foundation: COMPLETE (CI verified)

Published head: `9463b5314af6e33e539dc9635c359d57f187183e` (normal pushes only).
Final CI run: https://github.com/bharti1233/Hmxpdf/actions/runs/43943430785 — completed/success
(unit tests 0 failures, lint, debug APK 170.70 MB, signed release APK 134.59 MB, AAB 51.17 MB,
release-guard). Artifacts correspond to exactly this commit (verified against the remote head).

### Defects fixed (FA register)

- **FA-03 — header/footer edits no longer drop the part's root namespaces.** The VM captures each
  loaded part's original root start tag (`hfEnvelopes`, captured on first parse) and
  `DocxWriter.Extras.hfEnvelopes` carries it to the writer; `DocxPageLayout.hfXml` re-emits the
  original envelope verbatim (foreign namespaces like `r`/`a`/`mc:Ignorable` survive). Brand-new
  parts keep the minimal `w`-only root.
- **FA-04 — one immutable save capture.** `DocxEditorViewModel.saveCapture()` snapshots
  (package, body, package-level edits) on the owner thread; `buildPatched` and the acknowledgement
  (`recordSaved(capture)`) consume exactly that generation. Edits made during a slow write stay
  dirty instead of being marked saved. The share/export path (`currentFile`) uses the same capture.
- **FA-05 — package-inclusive undo.** Undo/redo steps are `SessionSnap` (body + header/footer part
  contents + relationship claims + envelopes + settings splice). Removing a header/footer then
  undoing restores the section reference and its part/relationship claims as one unit — no
  dangling reference can reach the next save.
- **FA-06 — session relationship lookup.** `hfPathOf` consults the session's claims before the
  loaded rels, so a part created this session resolves (its rel id cannot exist in the loaded
  rels string).
- **FA-22 — OOXML `CT_OnOff` truthiness and inclusive section lookup.** `titlePg` /
  `hasEvenOdd` honor `w:val="false"|"0"|"off"` (a bare element or true/absent val is ON);
  `sectionAt` treats a `sectPr`-carrying paragraph as inside the section it terminates.
- **Skipped recovery tests resolved.** The empty `@Ignore`d `SaveRecoverySameUriTest` (2 empty
  methods) is deleted; `SaveRecovery`'s pure core (journal round trip, destination-comparison
  table, restore decision table, pending auto-discard rules) is now JVM-tested directly —
  `SaveRecoveryCoreTest` (6 tests). Context-bound halves delegate to the core; nothing was
  disabled to go green.
- **Shared test fixtures.** `DocxFixtures` builds valid in-memory DOCX packages (custom body,
  rels, content-type overrides, extra parts) used by the integrity and session tests.

### New/changed files

Main: `SaveRecovery.kt`, `DocxPageLayout.kt`, `DocxWriter.kt` (`Extras.hfEnvelopes` wiring),
`DocxEditorViewModel.kt`.
Tests: `DocxFixtures.kt`, `DocxHeaderFooterIntegrityTest.kt` (4), `DocxSessionStateTest.kt` (6),
`SaveRecoveryCoreTest.kt` (6) — 16 new tests replacing 2 empty ignored stubs.

### CI history for this sub-phase (all repair root causes fixed, none skipped-by)

| Run | Commit | Result | Root cause of failure |
|---|---|---|---|
| 43938424486 | `c8816c2` | failure | 2 compile errors (`begin(root,…)` referenced `context`; `currentFile`'s `buildPatched` call) → fixed `632d9d5` |
| 43939305850 | `632d9d5` | failure | test compile: `kotlin.io.createTempDir` deprecated at ERROR level → fixed `bf9dabd` |
| 43941290865 | `bf9dabd` | failure | genuine test failure: wrong expectation vs. the established `sections()` model (terminal body-level entry) → fixed `9463b53` (test-only; production behavior verified correct) |
| 43943430785 | `9463b53` | **success** | — |

Test outcome on the green run: the same suite as the failed run (290 executed, 42 skipped — the
pre-existing `@Ignore`d Android-Context-only classes, untouched) with 0 failures.

### Post-build source audit (STEP H)

- Built commit = published remote head (`9463b53`) — verified via `git ls-remote`.
- Combined diff touches exactly the 9 intended files; no unrelated changes.
- No TODO/FIXME/placeholder code; no secrets (the single "passwords" grep hit is a doc comment
  explaining the journal never stores them).
- Invariants held: `DocxWriter` remains the sole DOCX writer (envelope support extends the
  existing `Extras` seam, no parallel writer); byte-preserving patch architecture untouched;
  unknown parts/relations preserved; no new dependencies.
- Test compilation note: Kotlin's `createTempDir` deprecation is `DeprecationLevel.ERROR`
  (a compile error, not a suppressible warning); regular warnings remain non-fatal
  (pre-existing `w:` debt in main sources is untouched and not hidden).

### Not verified / remaining limits

- No on-device/instrumented run: the Context-bound halves of `SaveRecovery` (URI hashing, SAF
  copies) remain covered only by inspection — their decision inputs are JVM-tested.
- 42 tests remain skipped (pre-existing `@Ignore`d OfficeEngineDownloadTest classes requiring an
  Android Context); enabling them needs Robolectric or an androidTest source set (a separate,
  deliberate change, not part of 4.0 scope).
- External Word/LibreOffice compatibility of envelope-preserving HF parts is untested.

---

## Phase 4.1 — Autosave and document recovery: COMPLETE (CI verified)

Published head: `0d72242dede86ddaec1c7e56d6cab015220d02ad`.
Verification run: https://github.com/bharti1233/Hmxpdf/actions/runs/44044415038 — `build`,
`release` and `release-guard` all completed/success. Artifacts: debug APK 170,830,525 bytes
(artifact 14311642159), signed release APK 134,671,621 bytes (14310593891), AAB 51,197,800 bytes
(14310374040). The workflow checked out `0d72242…`, matching the published development branch head.

Phase 4.1 was reopened after its first green build: a source audit found interruption and
concurrency defects the original tests did not cover. This completion claim covers the repaired
protocol below; the earlier `e36abbb` verdict is superseded.

### What was implemented

- **`EditorCheckpointStore`** (`data/repository`): one complete, validated package per document
  identity — sha-256 of the source URI, never the display name — under `noBackupFilesDir`.
  Commit protocol: copy to a new immutable generation → validate (`SaveRecovery.validateOfficePackage`)
  → fsync → rename the package → write and fsync metadata → rename metadata (the single publication
  point). Until that publication the previous package and metadata are untouched; a failure or
  cancellation deletes only the new generation and temps. After publication superseded generations
  and interrupted temps are pruned, so storage is bounded to one package plus metadata per document.
  `find` validates identity, recorded hash and package structure, refuses orphans, and retires only
  the observed session via `clearIfCurrent` (a newer checkpoint is never deleted by an older save).
- **`AutosaveCoordinator`**: deterministic scheduling core — ~2 s idle debounce, 15 s bounded
  maximum dirty interval, one write at a time, edits during a write coalesce into one follow-up.
  A failed write returns a 15 s retry delay and stays dirty without needing another keystroke.
- **DOCX editor wiring** (`DocxEditorViewModel`): body/undo/redo/settings changes feed the
  coordinator; checkpoint writes use the 4.0 `SaveCapture` discipline extended with source file,
  display name and identity (one captured generation, unique temp output). A successful save marks
  only the captured generation saved, retires only the checkpoint it observed, and keeps newer
  edits scheduled. Recovery copies the checkpoint into a matching local source package so later
  saves patch recovered content, restarts undo, marks the document dirty and labels stale originals
  via the mirror hash.
- **Spreadsheet wiring** (`SpreadsheetViewModel`): an immutable `SheetCapture` (source mirror,
  display name, op list, identity) now feeds checkpoint writes, manual save, PDF export and share.
  A manual save acknowledges only an exact op-log match; edits made during the save remain dirty and
  scheduled. Cleanup is generation-aware, and patched temp files are deleted after save/export.
  Recovery restarts the op log with `savedAt = -1` (dirty until a real save); legacy .xls is
  read-only and never checkpoints.
- **Temp-file bounds** (`EditorTempFiles`): at editor load, crash-orphaned patched files older than
  1 hour and recovery mirrors older than 24 hours are removed. Only editor-owned names are touched;
  fresh files that may belong to an in-flight save/share/conversion are preserved.
- **UI**: recovery prompts reuse the existing dialogs (`GlassDialog`, `UnsavedChangesDialog`), with
  strings in all 7 locales.
- **WorkManager cleanup**: intentionally not added (optional/deferred in the roadmap); bounded
  per-document storage plus the age sweep replace it.

### Tests

- `AutosaveCoordinatorTest` (8, fake clock): idle debounce, bounded interval, clean no-schedule,
  single-write exclusion, coalesced follow-up, no-op end, save reset, failed-write retry without a
  new keystroke.
- `EditorCheckpointStoreTest` (12): metadata round trip, display-name non-identity, generation
  increments with sticky originalHash, failed commit keeps the prior checkpoint, corrupted
  checkpoint refused without deleting evidence, interrupted publication keeps the prior generation,
  bounded generation storage, failed publication leaves no generation, identity mismatch refused,
  clear, unknown identity, key stability.
- `EditorTempFilesTest` (1): only stale editor intermediates are removed; fresh and unrelated cache
  files survive.
- The full JVM test tasks (`testPlayDebugUnitTest`, `:pdf-core:test`, `:ocr-core:test`) passed in the
  verification run. 42 pre-existing skips remain (`OfficeEngineDownloadTest` needs an Android
  context); no test was disabled or skipped to obtain this result.

### CI history for this sub-phase

| Run | Commit | Result | Note |
|---|---|---|---|
| 43946472274 | `f58a6ec` | failure | public property exposed internal `Checkpoint` type → fixed `67e249a` |
| 43947101199 | `67e249a` | failure | test-only `listFiles()` NPE on a deleted directory → fixed `df0848c` |
| 43949208216 | `df0848c` | success | DOCX editor verification |
| 43951540104 | `e36abbb` | success | spreadsheet wiring (later superseded by the audit) |
| 43992838288 | `aab606f` | success | checkpoint publication + recovery-source repair |
| 43993526471 | `e562454` | success | autosave retry + detached follow-up |
| 43994949152 | `4c406e4` | success | generation-aware cleanup + source capture |
| 44043818080 | `a4d7145` | failure | compile: `cacheDir` is already a `File` → fixed `0d72242` |
| 44044415038 | `0d72242` | **success** | final 4.1 verification (build, release, release-guard) |

### Not verified / remaining limits

- No device/emulator run: real force-stop process death, device reboot, a URI revoked mid-session,
  and actual ENOSPC on a full device are **not** executed by CI. The store's publication and
  failure boundaries are covered by injected-failure JVM tests; device behaviour remains
  unverified.
- The 2 s/15 s constants are roadmap defaults, unprofiled on device.
- ENOSPC handling is reasoned and injection-tested at the checkpoint/store boundary; the SAF write
  path is protected by `SaveRecovery` (prepare/backup/journal before touching the destination), but
  a real full-disk device run was not performed.
- The spreadsheet capture snapshots the op list, which is self-contained for values/formatting, but
  the writer still reads the session's style table; concurrent style derivation during a save is
  not stress-tested.
- No WorkManager-based opportunistic cleanup (optional per roadmap).

---

## Phase 4.2 — Advanced tables: COMPLETE (CI verified)

Published head: `b9bb233348b87bdc024b86b80957e91ef90b7b89`.
Verification run: https://github.com/bharti1233/Hmxpdf/actions/runs/44050687595 — `build`,
`release` and `release-guard` all completed/success. Artifacts: debug APK 171,061,101 bytes
(artifact 14314525137), signed release APK 134,738,175 bytes (14313319179), AAB 51,242,635 bytes
(14313488573). The workflow checked out `b9bb233…`, matching the published development branch head.
Per instruction, `main` was not pushed; this sub-phase was published only to
`release/hmxpdf-2.1.1-build`.

### What was implemented

- **`DocxTables`** (`docx`): an immutable table kernel over the existing `DocTable`/`DocRow`/
  `DocCell` model. It provides logical grid positions (`positions`, `columnCount`, `cellAt`,
  `locate`), table creation at a body index (never inside a cell, never after the body's `sectPr`,
  always followed by a paragraph so a body cannot end on a table), row/column insert and delete,
  rectangular merge/split with validated `gridSpan`/`vMerge`, shading, cell and table borders, cell
  margins, cell width, row height and table alignment. Merges append covered cells' content to the
  anchor in row-major order instead of dropping it; splits re-expand the region into single cells.
- **Property splices** (`DocxProps`): `TBLPR_ORDER`/`TRPR_ORDER`/`TCPR_ORDER` and
  `setTbl`/`setTr`/`setTc` insert or replace one element at its schema position while preserving
  unknown sibling elements and attributes. No table element is rebuilt wholesale, so unrelated
  XML is not rewritten.
- **View-model commands** (`DocxEditorViewModel`): `insertTable`, row/column insert/delete,
  `mergeTableCells`, `splitTableCell`, shading, borders, margins, width, row height and alignment.
  All are gated on `editMode && editable && !tracking`; structural edits are refused while Track
  Changes is on rather than writing a table with unrepresentable revision semantics. Edits go
  through the existing `commit` path, so they are a single undo step and flow into the 4.1
  autosave checkpoints.
- **UI** (`DocxEditorScreen`): a `TABLE` panel opened from the toolbar, showing the focused cell's
  row/column position, a create-table stepper (1–20 rows × 1–10 columns), structural buttons,
  merge/split, shading swatches, border toggles, margin/width presets, row-height and alignment
  controls. Buttons are disabled when the mode, tracking, grid or table bounds make the operation
  invalid. 19 new `docx_table_*` strings across all 7 locales.
- **Grid safety**: kernels refuse tables whose children are not plain rows/cells, irregular row
  widths, `gridBefore`/`gridAfter`, `hMerge`, and revision-bearing containers
  (`ins`/`del`/`cellIns`/`cellDel`/`cellMerge`/`*PrChange`). Tag detection matches whole element
  names, so `insideH`/`insideV` borders are not mistaken for `<w:ins>`.

### Tests

- `DocxTablesTest` (7): creation grid + trailing paragraph, row/column ops preserving rectangularity
  and content, 2×2 merge content conservation with `vMerge` restart/continue then split
  restoration, revision refusal without `insideH`/`insideV` false positives, irregular-grid
  refusal, property-splice ordering with unknown siblings preserved, and a real
  writer → reader round trip of a merged/shaded table.
- `DocxTablesViewModelTest` (5): Edit Mode gate, Track Changes refusal and recovery after turning it
  off, undo/redo through the existing session stacks, merge/split through the VM, and
  focus-inside-a-cell → table/cell targeting.
- The full JVM test tasks (`testPlayDebugUnitTest`, `:pdf-core:test`, `:ocr-core:test`) passed in the
  verification run (324 completed, 0 failed; 42 pre-existing `OfficeEngineDownloadTest` skips
  remain unchanged). No test was disabled or skipped to obtain this result.

### CI history for this sub-phase

| Run | Commit | Result | Note |
|---|---|---|---|
| 44048852341 | `e77d229` | failure | resource compile: unescaped apostrophe in `docx_table_complex` → fixed `b6eb4b5` |
| 44049471861 | `b6eb4b5` | failure | VM merge test asserted a 3-cell continuation row instead of one spanning cell → fixed `b9bb233` |
| 44050687595 | `b9bb233` | **success** | final 4.2 verification (build, release, release-guard) |

### Not verified / remaining limits

- Structural row/column and merge/split operations are limited to simple rectangular grids: tables
  with `gridBefore`/`gridAfter`, `hMerge`, irregular rows, non-row/cell children, nested raw table
  XML or structural revision markers are refused with no edit (and the panel explains the refusal).
  Merged tables can be split back, and cell-level formatting works on any resolved grid, but
  further structural changes inside a merged grid are a second-pass item per the roadmap.
- Deletion operations remove whole rows/columns only; there is no arbitrary multi-cell selection
  model yet. The panel acts on the focused cell's table/row/column.
- Row height exposes automatic and one fixed preset; `w:hRule="exact"` is not surfaced.
- Width/margin presets are fixed twip values, not a free-form unit editor.
- No external Microsoft Word/LibreOffice open test and no device/emulator run are performed by CI;
  schema validity is covered by the reader round trip, the schema-ordered splices and the existing
  writer invariants. Real Word rendering fidelity of created widths remains unverified.

---

## Phase 4.4 — Footnotes and endnotes: COMPLETE (CI verified)

First verified head: `890d076da308951b6add5756a8a8bb69026b7dc7`.
Verification run: https://github.com/bharti1233/Hmxpdf/actions/runs/44057796676 — `build`,
`release` and `release-guard` all completed/success. Artifacts: debug APK 171,158,101 bytes
(14317291747), signed release APK set 134,780,851 bytes (14317706240), AAB 51,268,289 bytes
(14317486868). The workflow checked out `890d076…`, matching the published branch head. The phase
was re-verified in the combined final gate (44124495265 on `275c54a`).

### What was implemented

- **`DocxNotes`** (`docx`): immutable snapshots of every footnote/endnote (kind, id, separator
  flag, paragraph blocks, original element span, added/edited/removed flags). Parts are rebuilt by
  splicing only touched notes; reserved separators `-1`/`0` are never allocated, edited or removed.
  Ids are allocated independently per part (`nextId`), and `add`/`edit`/`remove` return null rather
  than producing a partial state.
- **Reader/package**: `word/footnotes.xml` and `word/endnotes.xml` resolve through relationships
  (fallback to the conventional paths); `DocxPackage.notes` carries the parsed snapshot.
- **Writer**: `DocxWriter.Extras.notes` rewrites an existing part in place or creates the part plus
  its relationship and content-type override. `DocxWriter` remains the only DOCX writer; untouched
  notes and unrelated parts stay byte-preserved.
- **ViewModel**: `insertNote` adds the reference and the note element in one undo step;
  `deleteNote` removes both atomically and refuses when a reference lives in read-only/raw content
  (an orphan reference is worse than a no-op); `noteTyped` edits note paragraphs through the same
  paragraph kernel (the note number object run is preserved); note operations are refused while
  Track Changes is on. The notes snapshot participates in dirty tracking, checkpoints, save capture
  and recovery.
- **UI**: Notes panel (7 locales) lists notes, inserts footnotes/endnotes at the focused paragraph,
  edits their text and removes them.

### Tests

`DocxNotesTest` (9): separator preservation, per-part id allocation, atomic reference+note add,
delete refusal for `-1`, edit/add writer round trips for existing and created parts (relationship
and content-type entries), comments/notes isolation, section-properties preservation and the
untouched-part byte policy. `DocxNotesViewModelTest` (6): Edit Mode/Track Changes gates, atomic
undo/redo, save capture and reference removal. Total added for 4.4: 15 JVM tests; the final suite
is 375 executed / 0 failed / 42 pre-existing skips.

### CI history for this sub-phase

| Run | Commit | Result | Root cause of failure |
|---|---|---|---|
| 44055858012 | `5958c0c` | failure | notes snapshot handling around a nullable `base` → fixed `5435915` |
| 44056482426 | `5435915` | failure | test-only Edit Mode gate expectation → fixed `890d076` |
| 44057796676 | `890d076` | **success** | 4.4 verification (build, release, release-guard) |

### Not verified / remaining limits

- A note reference is appended at the end of the focused paragraph (no caret range in this
  editor), matching the comments precedent.
- Note numbering definitions (`numbering.xml`) and section-level note settings are preserved but
  not edited; only verified scope is supported.
- Typing over a reference can orphan a note; removal of a reference in raw/read-only content
  refuses instead of guessing.
- No external Microsoft Word/LibreOffice reopen and no device run are performed by CI.

---

## Phase 4.3 — Spell checking: COMPLETE (CI verified)

Published head: `4ded969c6f81e02e52a43247e27fd9d572a97162` (4.3 implementation plus the reading
stack). Verification runs: https://github.com/bharti1233/Hmxpdf/actions/runs/44120403325 (4.3/4.5
gate on `4ded969`) and https://github.com/bharti1233/Hmxpdf/actions/runs/44124495265 (final gate on
`275c54a`). Artifacts at the final gate: debug APK 171,426,738 bytes (14346744273), signed release
APK set 134,876,344 bytes (14346409191), AAB 51,319,340 bytes (14346728913).

### What was implemented

- **`AndroidSpellEngine`** (`spell`): adapter over the installed Android `SpellCheckerSession`
  (local Binder service). The offline policy refuses a service package that requests `INTERNET`
  (API 31+ reads `currentSpellCheckerInfo`, older releases read the secure `spell_checker`
  component); with no acceptable service the panel reports unavailable. Requests are bounded
  (64 words, 5 suggestions, timeout) and cancellation cancels the session call.
- **`SpellCheck`**: tokenizer over each editable paragraph's *flat* text, so words split across
  runs are checkable. Skips object runs, single letters, numbers/digit-adjacent fragments, URLs,
  e-mail addresses and paths; chunks the pending words; a bounded LRU (`SpellCache`) keeps results
  for the session. Cancellation is rethrown rather than swallowed, so a cancelled scan cannot
  publish suggestions.
- **ViewModel**: suggestions are annotations beside `DocState` — never in rPr, the dirty baseline
  or a checkpoint. `SpellIssue` holds the exact `DocPara` instance; `applySpellFix` refuses when
  that instance or the text/range changed (stale suggestion), and otherwise edits through
  `DocxEdits` with the normal Track Changes capture (one undo step). Ignoring a suggestion is not
  an edit.
- **UI**: Spell panel (7 locales) with running/unavailable/no-issues states, language, re-check,
  word and suggestion chips, view-mode/Edit-Mode guidance; annotations are display-only spans
  drawn by `DocxLook.annotate`.

### Tests

`SpellCheckTest` (10): token rules (punctuation/numbers/URLs/apostrophes/hyphens), cross-run words,
object-character exclusion, bounded cache, 64-word chunking, editable-only scanning, cooperative
cancellation, long paragraphs and range accuracy. `DocxSpellViewModelTest` (7): annotations do not
dirty, explicit replacement, stale refusal, formatting/comment-anchor preservation, Track Changes,
undo/redo, view-mode gating and ignore. Total added for 4.3: 17 JVM tests.

### CI history for this sub-phase

| Run | Commit | Result | Root cause of failure |
|---|---|---|---|
| 44058006069 | `ee293ce` | failure | `getSuggestions` was passed `String[]`; the API takes `TextInfo[]` |
| 44115043278 | `ecd9588` | failure | two-arg array call resolved to the single-`TextInfo` overload (needs `sequentialWords`); reading highlight used an out-of-scope `accent` |
| 44115880504 | `1908079` | failure | `readingRate` property setter clashed with `setReadingRate(Float)` |
| 44116666002 | `6c5b9de` | failure | test compile: package-qualified `launch` had no `CoroutineScope` receiver |
| 44118641946 | `4c6c143` | failure | two real test defects: the session did not clamp rate; `runCatching` swallowed `CancellationException` → fixed `4ded969` |
| 44120403325 | `4ded969` | **success** | 4.3 + 4.5 gate (build, release, release-guard) |
| 44124495265 | `275c54a` | **success** | final combined gate with 4.6 |

### Not verified / remaining limits

- Only the installed on-device service is used; there is no bundled dictionary. Dictionary
  distribution remains deferred per the roadmap (license/version/APK/memory/maintenance analysis
  not done).
- Suggestion quality, mixed-language behavior and availability depend on the device's service.
  No hardware run is performed by CI.
- The service package allow/refuse policy is inspection- and unit-level; an actual service with a
  network permission was not exercised on a device.
- Very long documents are chunked and cancellable, but latency was not profiled on hardware.

---

## Phase 4.5 — Read Aloud: COMPLETE (CI verified)

Published head: `4ded969c6f81e02e52a43247e27fd9d572a97162`; final gate `275c54a` (run 44124495265,
artifacts listed under 4.3). 4.5 was verified by run 44120403325 and re-verified in the final
combined suite.

### What was implemented

- **`ReadingProjection`**: document-order projection through `forEachPara` (body, table cells,
  list items), object runs stripped, pending deletions excluded while pending insertions are read,
  whitespace collapsed, blank paragraphs skipped, and ≤320-character chunks cut at sentence
  boundaries first, then word boundaries. It never mutates `DocState` and consults no formatting.
- **`ReadingSession`**: pure `TtsEngine` state machine — play/pause/resume/stop, chunk completion,
  previous/next paragraph, stale-callback rejection, error→paused, rate clamping and the
  unavailable state. Pause is stop plus the retained chunk index (Android has no real pause);
  resume re-speaks that chunk.
- **`AndroidTtsEngine`**: offline policy (`isNetworkConnectionRequired == false`, never a cloud
  voice), transient audio focus, pause on focus loss, TTS init/utterance callbacks posted to the
  main thread, and focus release guarded by the owning utterance id so a flushed chunk cannot
  abandon the next chunk's focus.
- **ViewModel/UI**: read-only state (`readingState`, `readingParaId`, `readingText`, language,
  availability, rate) with auto-play after asynchronous engine init; edits, undo/redo, document
  load and editor close all call `stopReading`, so a stale paragraph can never be highlighted.
  Read panel (7 locales) exposes status, voice, play/pause/resume, stop, previous/next, rate chips
  and the current chunk; a toolbar action opens it and the paragraph being read is tinted.

### Tests

`ReadingProjectionTest` (8): document/table order, blank/whitespace handling, deleted-vs-inserted
policy, object stripping, chunk bound/coverage, sentence-then-word boundaries and a 120-paragraph
document. `ReadingSessionTest` (10): play/pause/resume, chunk completion, paragraph navigation
(playing and paused), stop/load stale-callback rejection, error→paused, unavailable→ready, rate
clamping and release. Total added for 4.5: 18 JVM tests.

### CI history

Shared with 4.3 above; the reading-specific compile repairs were the `accent` scope, the
`readingRate`/`setReadingRate` JVM clash, and the two real state-machine defects (rate clamp,
swallowed cancellation) fixed in `4ded969`.

### Not verified / remaining limits

- The projection covers body, tables and list content. Notes and header/footer parts are not read
  (the roadmap marks them optional); they would need a separate deterministic projection.
- No background playback service: speech is scoped to the editor and stops when the editor is
  disposed. Audio focus behavior with real calls/other players is untested.
- Resume returns to the start of the current chunk, not an exact word offset.
- Rate presets are fixed (0.75×/1×/1.25×/1.5×) and clamped to 0.5×–2×.
- Only offline voices are acceptable; if a device has none, the panel reports unavailable. No
  device/emulator TTS, voice-download, Bluetooth/headset or lifecycle test is performed by CI.

---

## Phase 4.6 — Integration and release verification: COMPLETE (JVM/CI); DEVICE SMOKE NOT RUN

Final code/test head: `275c54ab328977a65f968cd4b94a53984e36131f`.
Final gate: https://github.com/bharti1233/Hmxpdf/actions/runs/44124495265 — `build`, `release` and
`release-guard` all completed/success. The workflow checked out `275c54a…`, matching the published
development branch head at verification time.

### What was verified

- **Cross-feature integration** (`DocxPhase4IntegrationTest`, 1 test): one session inserts a 2×2
  table and a third row, adds a footnote (reference + note element), applies an explicit spell
  replacement, takes the single save capture, writes through `DocxWriter`, and reads the file
  back. Table structure, the fixture's original note id 3, the new note, the corrected paragraph
  text and an unrelated `word/customXml/item1.xml` part all survive. Undo unwinds the four
  features newest-first (spell → note → row → table) and redo restores the combined state, tying
  undo to checkpoint-generation semantics. A read-aloud pass speaks every chunk and leaves the
  `DocState` instance, its text and the dirty flag unchanged, proving the read-only contract at
  integration level.
- **Full gate**: 375 JVM tests executed (0 failed, 42 pre-existing `OfficeEngineDownloadTest`
  skips), Android lint with the existing baseline, debug APK, signed release APK set, signed AAB
  and the release-guard job.
- **Artifacts** (artifact IDs from run 44124495265):
  - debug APK: 171,426,738 bytes (14346744273)
  - release APK set: 134,876,344 bytes (14346409191). Universal APK 86,075,076 bytes,
    `sha256 3b7b9360ce255e082791b8e95f7963ba8f73cc99f6c34a066816801b5949d9b4`; arm64-v8a
    34,428,132 bytes, `sha256 e174ca32271dcfefa1a8d3ab5d2f0e868ad9157d0ce6fc7bd6a502b9671c1cff`;
    armeabi-v7a/x86/x86_64 APKs are included in the same set.
  - AAB: 51,319,340 bytes (14346728913),
    `sha256 8b4b7c008091803f63dd0983f3b0cc2024ecefde5cd45ce065166f87d1ba012c`.
  - Artifact zip digests: release APK set `5a2a662f3ada8c477b36459f8a9cdc753b04416198441db3d958bcbba987e4cc`,
    AAB `5d8a8466d7c12ecc8692d4d167f7c2782bd1c13763b10621134f56bee0238e75`.
- **Source audit**: no TODO/FIXME/placeholder code in the new modules; `DocxWriter` remains the
  sole DOCX writer (notes/tables/spelling all route through it); unknown parts and relationships
  are preserved; no new dependency was added; offline policies (spell service, TTS voice) hold in
  code and tests; the documentation commit after the gate touches only `docs/`; `main` was not
  pushed (`ca0caa35761298df5912c95e2c9c815c241601c1`).

### CI history for this sub-phase

| Run | Commit | Result | Root cause of failure |
|---|---|---|---|
| 44122819387 | `db6091c` | failure | test compile: `undo()`/`redo()` return `Unit`, not `Boolean` |
| 44124495265 | `275c54a` | **success** | final 4.3–4.6 gate (build, release, release-guard) |
| 44131731875 | `690ad4c` | **success** | documentation head re-gate (build, release, release-guard); later doc-only commits change no code |

### Not verified / remaining limits (device smoke explicitly not run)

- No device/emulator smoke: API 23, a current API level, low-RAM devices and a no-GMS device were
  not exercised. The roadmap's device matrix remains **unverified, not passed**.
- No external Microsoft Word/LibreOffice reopen of the generated DOCX after table/note/spelling
  changes; schema and round-trip behavior are covered by JVM tests and the writer/reader kernels.
- Real TTS voice availability, audio focus transitions, Bluetooth/headset routing and process
  lifecycle are not executed by CI; the fake-engine state machine is verified.
- A real on-device spell service (including the network-permission refusal path) is not executed;
  the adapter is inspection- and unit-level.
- Force-stop/reboot recovery, revoked SAF URI and real ENOSPC remain device-only items carried
  from 4.1; their decision boundaries are JVM-tested.
- Memory/storage bounds under sustained editing were not measured on hardware.
- No release was published to GitHub; artifacts are CI-only. Signing identity and application ID
  were not changed.

### Acceptance statement

There is no known unresolved release-blocking P1 in the JVM/CI scope. All gate components passed
on `275c54a`, and no test was skipped, ignored or disabled to obtain this result (the 42 skips are
the pre-existing Android-Context-only `OfficeEngineDownloadTest` classes). The device-smoke and
external-Office items above are **explicitly unverified** and must not be reported as passing.
