# HMXPDF repository identity

Audit date: 2026-11-16. Evidence: local filesystem and source inspection. No network requests, credentials, builds, or Git mutations were used.

## Discovered location

`pwd` returned `/storage/emulated/0/AIProjects/Hmxpdf`. The harness path is `/mnt/sdcard/AIProjects/Hmxpdf`. Both candidate paths returned filesystem identifier `217:3255917`; they represent the same directory, not two copies.

The working directory, immediate parent, and both `/mnt/sdcard/AIProjects` and `/storage/emulated/0/AIProjects` were listed. A search for project settings through five directory levels found one identifiable HMXPDF candidate. `ClearPDF-main` was empty. Other discovered Gradle roots identified themselves as HmxCoderAI, HMXArena, hbe, or unrelated projects. The earlier expected `HMXPDF-phase3-testing` directory was absent.

## Identity results

| Check | Result |
|---|---|
| Source identity | HMX PDF Android application |
| Root project | `HMX-PDF`, `settings.gradle.kts:32` |
| Application ID / namespace | `com.hmx.pdf`, `app/build.gradle.kts:59–69` |
| Version | 2.1.1, versionCode 6 |
| Organization links | README links to HMXPANEL/HMXPDF; Settings uses that organization at `app/src/main/java/com/hmx/pdf/ui/screen/SettingsScreen.kt:159` |
| Git metadata | No `.git` file or directory in the project |
| Git command | `git rev-parse --show-toplevel` failed: not a git repository |
| Remote / repository owner | Unverified; README URLs are not Git remote evidence |
| Active branch | Unavailable; cannot verify `release/hmxpdf-2.1.1-build` |
| HEAD / latest commits | Unavailable |
| Tracked, staged, untracked changes | Cannot determine without Git metadata |
| Download provenance | Unverified; source-only tree consistent with ZIP extraction |

Historical documents name `bharti1233/Hmxpdf`, while current product links name HMXPANEL/HMXPDF and some distribution metadata still names Chethan616/ClearPDF. These are distinct claims, not evidence of the current archive's origin. No historical commit is assigned to this tree.

## Structural evidence

Five module directories exist: `app`, `pdf-core`, `ocr-core`, `backdrop`, `office_engine`. Four are always included; the fifth is conditional on a task name containing `bundle` (`settings.gradle.kts:33–40`). Gradle wrapper, catalog, manifests, resources, DOCX engine, XLSX engine, Compose editors, tests, and `.github/workflows/android.yml` exist.

The source inventory before report creation was 543 files: 358 Kotlin files, 49 XML files, seven Gradle Kotlin scripts, 39 Markdown files, two vendored JavaScript files, one HTML asset, fonts, images, OCR model, wrapper JAR and other project metadata. No Java, C/C++, checked-in `.so`, APK, or AAB files were found.

## Preservation baseline

Before creating reports, SHA-256 was calculated over all 543 files in sorted relative-path order. For each file, the digest input was UTF-8 relative path, a NUL byte, then the binary SHA-256 of file contents. Baseline:

`3624efb3638c6f2dd616a8db7faa5d5573ff3cf8e37c7f4bb5ca37d5ce68fd24`

This is an audit content fingerprint, **not a Git commit**. After report creation, the same calculation excluding `docs/fresh-audit/` returned the identical fingerprint for all 543 original files. The only additions are the 14 Markdown files in this directory. Existing documentation is preserved. `Word_16.0.20618.20008.apks` and `docs/word-competitive-analysis/` were not present in the enumerated tree; neither was created, moved, or deleted.

## Phase and experimental provenance

Phase 1, 1.1, 2, 3 and Phase-3 button-audit documents exist. Their corresponding source was inspected independently. No Phase-4 directory or dedicated autosave/spelling/speech/note-editing implementation was found. The experimental identifiers `pgGuides`, `applyScope`, `PgGuide`, `scopeToast`, `docx_scope_selected`, and `docx_guides_done` were absent from searched Kotlin/XML sources. This does not verify deletion of a Git branch or prove equivalence to any historical checkout. Existing Phase-3 Page Setup functionality is present and must not be confused with the rejected experimental UI.
