# Changelog

All notable changes to HMXPDF are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Entries reference the commits that introduced each change.

## 2.1.1 — 2026-11-18 (original release)

Application version: `2.1.1` (`versionCode` 6). Default branch:
`release/hmxpdf-2.1.1-build`.

### Added

- **Official website** (2026-11-18, `550cd8a`): static HMXPDF site under
  `hmxwebsite/`, deployed on Vercel with clean URLs, a strict Content Security
  Policy, and security headers. A follow-up (`28f6cfa`) documents the Git-driven
  deployment setup.
- **DOCX Read Aloud** (2026-11-17, `ecd9588`): Android text-to-speech playback
  with speed control and reading highlights, working offline.
- **DOCX offline spell check** (2026-11-17, `ee293ce`): local engine adapter,
  annotation model, suggestions panel, and safe cross-run replacement.
- **DOCX footnotes and endnotes** (2026-11-17, `5958c0c`): note-part model,
  writer support, editing panel, and tests.
- **DOCX advanced tables** (2026-11-17, `e77d229`): row and column operations,
  merge and split, borders, shading, cell margins, and row heights.
- **DOCX and XLSX autosave and recovery** (2026-11-16, `f58a6ec`, `e36abbb`):
  checkpoint scheduling, process-death recovery, and restore flows.
- **DOCX save foundation** (2026-11-16, `c8816c2`): save coordinator, immutable
  save capture, package-inclusive undo, and header/footer namespace preservation.
- **DOCX find and replace, and comments** (2026-10-30, `59e1071`).
- **DOCX track changes** (2026-10-31, `cc421ea`, `f1551ce`): revision model,
  recorded insertions and deletions, revision markers, and a review panel.
- **DOCX page setup and headers/footers** (2026-10-31, `fb920c3`): section
  orientation, sizes, margins, breaks, header and footer parts, and settings.

### Changed

- Spreadsheet saves are immutable and checkpoint retention is bounded; crash
  temporaries are swept (2026-11-17, `a4d7145`).
- DOCX saves capture the source document identity so recovery restores the
  matching package (2026-11-17).
- Website deployment is Git-driven: pushes to `release/hmxpdf-2.1.1-build`
  produce a production deployment on Vercel (2026-11-18).

### Fixed

- DOCX stale replacement safety and offset-aware comment and bookmark markers
  (2026-11-16, `c031c73`).
- DOCX false dirty state when opening a file — the effective settings part is
  compared (2026-11-02, `fefe9a6`).
- Page-break round-trip: the object carries its full run, and settings and
  header/footer state survive (2026-11-01).
- Autosave now retries failed checkpoints, retains newer generations, and detaches
  follow-up scheduling (2026-11-17).
- Recovery preserves published checkpoints and restores source-matching packages
  (2026-11-17).
- Build reliability: R8 dexing for Apache POI, AGP 9 ProGuard configuration, and
  the Gradle wrapper executable bit (2026-10-30).

### Documentation

- Repository documentation overhaul: new README, contribution guide, security
  policy, Contributor Covenant code of conduct, privacy notes, this changelog,
  issue and pull request templates, and audit reports under `docs/`.

### Security

- No security advisories have been published.
- GitHub private vulnerability reporting is enabled; see [SECURITY.md](SECURITY.md).

## Earlier history

The repository begins with `e488aa2` (2026-10-30), which migrated the HMXPDF
codebase with audited fixes and Office Engine reliability improvements. Earlier
development happened outside this repository; no tags or releases were carried
over. Inherited upstream attribution is recorded in [NOTICE](NOTICE),
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), and
[docs/LICENSING_AUDIT.md](docs/LICENSING_AUDIT.md).
