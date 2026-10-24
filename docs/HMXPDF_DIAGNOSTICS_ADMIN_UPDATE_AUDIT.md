# HMXPDF — Diagnostics, Admin Panel & Update System

## Phase 1 Audit Report

| Field | Value |
|---|---|
| Date | 2026-10-24 |
| Repository | `https://github.com/HMXPANEL/HMXPDF.git` |
| Default branch | `main` |
| Audit branch | `feature/hmxpdf-diagnostics-admin-updates` |
| Base commit | `912e9977916c4f365147a294e60c3cd95d96960e` |
| Phase | **Phase 1 — read-only audit only** |
| Implementation status | **Not started.** No app source, migration, Edge Function, admin UI, or updater changes were made. |
| Git status | No commits, no pushes, no repository-setting changes were made during this audit. |
| Report status | This file is an untracked deliverable on the feature branch. It was deliberately not committed, per Phase 0 item 9. |

**Evidence convention.** “Verified” means the fact was read from the repository at the base commit or observed through an executed command/GitHub API call. “Assumption” means a proposed design decision that has not been implemented or verified. No Gradle test, build, lint run, Supabase change, or device test was performed for this report; those are explicitly **NOT RUN**.

---

## 1. Executive summary

HMXPDF is a mature, offline-first Android document application with no existing backend, no admin web application, no crash/analytics SDK, no diagnostics pipeline, and no in-app updater. The requested five-phase system is therefore mostly greenfield work around an existing, working PDF/Office app.

The Android app already contains several patterns that can be reused without adding heavy dependencies:

- `WorkManager` 2.10.1 and a working `HttpURLConnection` download worker with Range resume, SHA-256 verification, foreground notification, and cancellation.
- A durable `noBackupFilesDir` journal with atomic-write and recovery behavior (`SaveRecovery`).
- SharedPreferences-based settings and version-aware onboarding state.
- A Compose/Material 3 “Liquid Glass” settings screen with a reusable sub-page pattern.
- Manual dependency location (`PdfServiceLocator`) and `kotlinx.serialization`.
- A CI release job that already builds signed Play APKs/AABs and verifies the signer against the configured keystore.

The main gaps are:

- **No Supabase project, schema, migrations, Edge Functions, Auth, or RLS** for HMXPDF.
- **No admin panel** or web frontend of any kind.
- **No `Application` subclass, crash handler, structured logging, BuildConfig fields, diagnostics consent UI, offline queue, or redaction layer.**
- **No update-check API, APK hosting policy, checksums in CI, GitHub Release publication, or in-app download/install flow.**
- **No Android instrumentation tests** and no local Android SDK/emulator. Backend integration testing cannot run locally without Docker/Supabase CLI.
- **No GitHub Releases or tags**, despite README/Obtainium links pointing at them.
- **No branch protection on `main`**, and the `release` environment exists but has no protection rules and no environment-scoped secrets.

**Recommended architecture:** a dedicated Supabase project whose `hmx` schema is not exposed through PostgREST; all reads/writes go through Edge Functions. Testers authenticate with per-install bearer tokens obtained by redeeming admin-created invite codes. Admins authenticate with Supabase Auth (password + TOTP MFA) and are checked server-side against an `admin_users` allowlist. The admin panel is a static Vite + TypeScript SPA that talks only to the `admin-api` Edge Function. Android reuses `WorkManager`/`HttpURLConnection`; self-update is enabled only for the `foss` channel, disabled for `play` (Play policy/Play App Signing) and `fdroid` (different signer).

**Phase 1 verdict:** the audit deliverable is complete and the repository state is safe. Phase 2 is **not** authorized and must not start until the user approves this report and resolves the remaining Supabase/MFA/F-Droid questions in §15.

> **Phase 2 status update (2026-10-24):** Phase 2 was subsequently authorized and implemented on `feature/hmxpdf-diagnostics-admin-updates`. The implementation exposes `hmx` in PostgREST's `db-schemas` only so service-role Edge Functions can reach it; `anon` and `authenticated` have no schema `USAGE` and no table grants, and a direct PostgREST request was verified to return `42501 permission denied for schema hmx`. See Appendix D and `docs/HMXPDF_PHASE2_BACKEND_REPORT.md`. The Phase 1 verdict above is preserved as the state at audit time.

### 1.1 Verification status of this final pass

| Class | Items |
|---|---|
| **Verified from the repository at `912e997`** | Remote/default branch/HEAD; clean tracked tree and the two untracked docs; Gradle versions/flavors/global release signing; manifest permissions and FileProvider roots; 9 logging sites; no app-level Room/DataStore (WorkManager brings Room transitively); no crash/analytics SDK; test inventory (29 app JVM, 4 `pdf-core`, 0 `ocr-core`, 0 instrumentation); CI jobs and missing `needs`; no releases/tags; branch protection absent; `release` environment unprotected with zero environment secrets; four repo-scoped signing secret names; F-Droid recipe staleness and duplicate `OfficeEngineFlavor`. |
| **Verified from the existing built APK** | `com.hmx.pdf`, versionCode 6 / versionName 2.1.1, minSdk 23, target/compile 36; signer SHA-256 `e30ab…`; v1/v2 true, v3/v3.1/v4 false; merged Play APK contains `INTERNET` and `READ_PHONE_STATE`; `REQUEST_INSTALL_PACKAGES` absent. |
| **Verified from GitHub API** | Two successful base-commit runs (`40066747132`, `40066747199`); repository secret names; environment metadata; secret scanning enabled, Dependabot updates disabled. |
| **Proposed, not implemented** | Supabase schema/RLS/Edge Functions; diagnostics consent/queue/redaction; Vercel admin SPA; TOTP admin flow; FOSS update checker/downloader/installer; CI release changes. No backend, admin, or Android change exists yet. |
| **Not verified / NOT RUN** | Any Gradle build/test/lint in this environment (no Android SDK/emulator); any Supabase migration or RLS test (no project/Docker/CLI); any Vercel deployment; any device install/update; Play App Signing status (no Play Console access); F-Droid build (blocked by duplicate class); runtime MFA behavior. These must be proven in Phases 2–5. |

---

## 2. Verified repository and branch state

### 2.1 Repository and branch

| Check | Result | Evidence |
|---|---|---|
| `origin` fetch URL | `https://github.com/HMXPANEL/HMXPDF.git` | `git remote -v` |
| `origin` push URL | `https://github.com/HMXPANEL/HMXPDF.git` | `git remote -v` |
| GitHub repository | `HMXPANEL/HMXPDF`, public | GitHub API |
| GitHub default branch | `main` | GitHub API `default_branch=main` |
| Local `main` | `912e9977916c4f365147a294e60c3cd95d96960e` | `git rev-parse main` |
| `origin/main` | `912e9977916c4f365147a294e60c3cd95d96960e` | `git rev-parse origin/main` |
| Local vs remote `main` | `0` ahead, `0` behind | `git rev-list --left-right --count main...origin/main` |
| Audit branch | `feature/hmxpdf-diagnostics-admin-updates` | `git branch --show-current` |
| Audit branch HEAD | `912e9977916c4f365147a294e60c3cd95d96960e` | `git rev-parse HEAD` |
| Working tree | Only `?? docs/HMXPDF_DIAGNOSTICS_ADMIN_UPDATE_AUDIT.md` and `?? docs/HMXPDF_FULL_SOURCE_AUDIT.md` | `git status --short` |
| New commits during audit | **None** | `git status`, `git log` |
| Pushes during audit | **None** | No `git push` executed |
| Repository settings changed | **None** | No settings API writes executed |

The pre-existing untracked file `docs/HMXPDF_FULL_SOURCE_AUDIT.md` was read for context but **not modified, moved, deleted, or committed**. It remains untracked.

### 2.2 GitHub state

| Check | Result |
|---|---|
| Releases | `0` |
| Tags | `0` |
| Latest release endpoint | `404 Not Found` |
| `main` branch protection | `404 Branch not protected` |
| Actions enabled | Yes; all actions allowed; SHA pinning not required |
| Repository secrets | 4 names only: `CLEARPDF_UPLOAD_KEY_ALIAS`, `CLEARPDF_UPLOAD_KEY_PASSWORD`, `CLEARPDF_UPLOAD_STORE_BASE64`, `CLEARPDF_UPLOAD_STORE_PASSWORD` |
| Environment `release` | Exists (created `2026-10-24T04:33:03Z`) |
| Environment protection rules | **None** |
| Environment deployment branch policy | **None** |
| Environment-scoped secrets | **0** (signing secrets are repository-scoped) |
| Secret scanning / push protection | Enabled |
| Dependabot security updates | Disabled |

**Implication:** the release workflow references `environment: release`, but that environment does not currently gate deployments or hold its own secrets. This is a release-security gap to address before any tag-based release. Changing it requires explicit authorization and is outside this audit.

### 2.3 CI status at the base commit

Two workflow runs exist for `912e997`:

| Run | Event | Branch | Result |
|---|---|---|---|
| `40066747132` | `push` | `main` | success |
| `40066747199` | `workflow_dispatch` | `main` | success (signed release artifact build) |

These prove the existing build/test/lint/release-artifact pipeline passed at the base commit. They do **not** prove the new diagnostics/admin/update work, which does not exist yet.

### 2.4 Commands executed for Phase 0/1

- `git remote -v`, `git status --short`, `git branch -vv`, `git rev-parse HEAD`, `git log --oneline -8`
- `git fetch origin --prune`, `git rev-list --left-right --count main...origin/main`
- `git switch -c feature/hmxpdf-diagnostics-admin-updates 912e9977916c4f365147a294e60c3cd95d96960e`
- GitHub API: repository metadata, default branch, releases, tags, branch protection, secrets names, environments, workflow runs
- `aapt2 dump badging` and `apksigner verify --print-certs` on the previously built signed Play universal APK
- Read-only `grep`/`find` audits of Gradle, manifest, Kotlin sources, resources, tests, CI, privacy/security docs, and F-Droid metadata
- Supabase MCP: list organizations and projects (no secrets printed)

No Gradle task, Supabase mutation, Docker command, emulator, or device command was run.

---

## 3. Existing architecture and integration points

### 3.1 Modules and source layout

| Module | Purpose | Relevant facts |
|---|---|---|
| `:app` | Android application | 314 Kotlin files across app/library source sets; Compose UI, PDF/Office/scan features, settings, WorkManager downloader. |
| `:pdf-core` | PDF engine | Kotlin/JVM library; 4 JVM tests. |
| `:ocr-core` | OCR core | Kotlin library; **no test source directory** (CI task passes as empty). |
| `:backdrop` | Glass/backdrop rendering | UI dependency. |
| `:office_engine` | Dynamic feature | Intentionally **not bundled**; see `app/build.gradle.kts:145-155`. |

Application package root: `com.hmx.pdf`. Notable subpackages:

- `data/model`, `data/repository` — models, preferences, recent files, save recovery, service locator.
- `domain/usecase` — PDF use cases.
- `office` — Office engine client/runtime/downloader.
- `ui/components`, `ui/navigation`, `ui/screen`, `ui/theme`, `ui/viewmodel`, `ui/utils` — Compose UI.
- `utils`, `utils/xlsx`, `docx`, `imageeditor` — document and image processing.
- `org.libreoffice.kit` — Office engine JNI wrapper.

### 3.2 Build configuration

Verified in `app/build.gradle.kts`, `gradle/libs.versions.toml`, and `gradle/wrapper/gradle-wrapper.properties`:

| Setting | Value |
|---|---|
| Android Gradle Plugin | `9.0.0` |
| Kotlin | `2.3.0` |
| Gradle wrapper | `9.3.0` |
| Java toolchain | 17 |
| `namespace` / `applicationId` | `com.hmx.pdf` |
| `compileSdk` | `36` |
| `buildToolsVersion` | `36.1.0` |
| `minSdk` | `23` (Android 6.0) |
| `targetSdk` | `36` |
| `versionCode` | `6` |
| `versionName` | `2.1.1` |
| Release minify/shrink | Enabled (`isMinifyEnabled = true`, `isShrinkResources = true`) |
| Lint | `checkReleaseBuilds = true`; baseline `app/lint-baseline.xml` |
| `BuildConfig` | **Not enabled**; no `BuildConfig` references in app source |
| `vcsInfo.include` | `false` for release |
| Dependencies info | Excluded from APK/AAB |
| Splits | ABI splits + universal APK for raw APK builds; disabled for F-Droid and bundles |
| `testOptions.unitTests.isReturnDefaultValues` | `true` |

### 3.3 Flavors, versioning, and signing

Product flavor dimension: `distribution`.

| Flavor | Purpose | Network/office behavior |
|---|---|---|
| `play` | Google Play | Optional Office engine via Play Feature Delivery; main manifest does not declare `INTERNET`, but merged dependencies contribute it (verified in the built APK). |
| `foss` | GitHub/F-Droid-style sideload | Declares `INTERNET` and `ACCESS_NETWORK_STATE`; optional Office engine download; URL-to-PDF. |
| `fdroid` | F-Droid free-software build | Declares `INTERNET`; no Play services/ML Kit; Tesseract OCR; no downloaded Office engine. |

F-Droid versioning: when `-PfdroidAbi=<abi>` is supplied, `versionCode = 10 * defaultConfig.versionCode + index + 1`, with ABI order `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` (`app/build.gradle.kts:27-31, 131-137`). The checked-in F-Droid recipe still points at `https://github.com/Chethan616/ClearPDF.git` (not `HMXPANEL/HMXPDF`), pins commit `e33ef206…`, and lists version codes `61–64`; it is stale. The `fdroid` source set also declares `OfficeEngineFlavor` in the same package as `main`, which is a source-level duplicate-class blocker. Neither the F-Droid build nor the duplicate-class failure was executed here; both are verified by source inspection only. See §12.

Signing:

- `key.properties` (gitignored) or environment variables `CLEARPDF_UPLOAD_STORE_FILE`, `CLEARPDF_UPLOAD_STORE_PASSWORD`, `CLEARPDF_UPLOAD_KEY_ALIAS`, `CLEARPDF_UPLOAD_KEY_PASSWORD`.
- Release artifact builds fail if signing is missing (`app/build.gradle.kts:33-56, 89-105`).
- Existing signed Play universal APK verified:
  - Package `com.hmx.pdf`
  - `versionCode=6`, `versionName=2.1.1`
  - `minSdk=23`, `targetSdk=36`, `compileSdk=36`
  - Signer DN `CN=Harsh maurya, OU=HMXPANEL, O=HMXPANEL, L=Mumbai, ST=Maharashtra, C=IN`
  - Signer certificate SHA-256 `e30ab37d83b52a0fd8a15da51a0d323107043daa9ac773fa44f17968348614d3`
- CI re-computes the keystore certificate SHA-256 and fails if any release APK signer differs (`.github/workflows/android.yml:154-182`).

**The production signing key must be preserved. No key regeneration is part of this project.**

### 3.4 Manifest and permissions

Verified in `app/src/main/AndroidManifest.xml`, `app/src/foss/AndroidManifest.xml`, and `app/src/fdroid/AndroidManifest.xml`:

- Main manifest declares `CAMERA`, legacy `READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`), legacy `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=28`), `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`.
- `INTERNET` and `ACCESS_NETWORK_STATE` are declared only in `foss` and `fdroid` source manifests. The comment says the Play build does not declare `INTERNET`, but the **merged Play APK does contain `INTERNET`** (contributed by dependencies) and also `READ_PHONE_STATE`, `WAKE_LOCK`, and `RECEIVE_BOOT_COMPLETED`.
- No `REQUEST_INSTALL_PACKAGES`.
- No `QUERY_ALL_PACKAGES`, location, SMS, contacts, or overlay permissions.
- `<application>` has **no `android:name`** attribute; there is no Application subclass.
- `FileProvider` authority `${applicationId}.provider`; `file_paths.xml` exposes:
  - `cache-path name="scan_images" path="scan_images/"`
  - `cache-path name="shared_exports" path="shared/"`
  - `external-files-path name="external_files" path="."`
- `DocumentActivity` handles external VIEW/SEND intents for PDF, Word, Excel, PowerPoint, text, and images. `MainActivity` is the launcher and shortcut entry.
- `OfficeEngineService` runs in `:office` process.
- `allowBackup="true"` with `dataExtractionRules` and `fullBackupContent` both pointing at `backup_rules.xml`. That file uses `<full-backup-content>`; Android 12+ expects `<data-extraction-rules>` for `dataExtractionRules`. It also excludes `cache/decrypted_pdfs/` and `cache/shared/` under `domain="file"` instead of `domain="cache"`, so those explicit exclusions do not match. Cache is normally excluded from Auto Backup anyway, but the rules are still wrong. These are existing configuration defects; new diagnostics data must not rely on this file for exclusion.

### 3.5 Logging, crash handling, and error handling

- No crash reporting SDK, analytics SDK, or telemetry.
- No `Application` subclass and no `Thread.setDefaultUncaughtExceptionHandler`.
- **9 logging call sites total** (verified by `grep` across `app/src`, `pdf-core/src`, `ocr-core/src`, `backdrop/src`):
  - `office/OfficeEngineClient.kt:111` — `Log.w` with throwable on conversion failure.
  - `ui/screen/DocxEditorScreen.kt:1544`, `:1570`, `:1593` — `Log.w` on image decode/load failures.
  - `ui/screen/DocxPdfWriter.kt:59` — `Log.w` on PDF draw failure.
  - `ui/screen/SignaturePadDialog.kt:593` — `printStackTrace()` (not `Log`).
  - `pdf-core/.../PdfSecurityService.kt:99`, `:157` — `Log.w` on stale decrypted-file cleanup failures.
  - `ocr-core/.../OcrServiceImpl.kt:27` — `Log.w` on OCR failure.
- Release ProGuard removes `Log.d/v/i` but keeps `Log.w` (`app/proguard-rules.pro`).
- Error handling is dominated by `runCatching`/`catch` at feature level:
  - 138 matches in `ui/viewmodel`
  - 79 in `ui/screen`
  - 18 in `office`
  - 46 in `utils`
- There is no central error taxonomy, no event IDs, and no safe place to attach reporting without touching many files.

**Integration point:** add a `HMXApplication` and a small `Diagnostics` facade. Instrument only a short list of high-value failure sites (viewer open/export, Office conversion, DOCX save, scan save, global uncaught exception). Reporting calls must be non-throwing and must not alter existing control flow.

### 3.6 Networking, background work, persistence, and DI

| Concern | Existing implementation |
|---|---|
| HTTP | `HttpURLConnection` only, in `OfficeEngineDownloadWorker` (`app/src/main/java/com/hmx/pdf/office/OfficeEngineDownloadWorker.kt`). Supports Range resume, HTTP 206/200, SHA-256, foreground notification, cancellation. |
| Background work | WorkManager `2.10.1` (`androidx.work:work-runtime-ktx`). |
| Persistence | SharedPreferences (`Preferences.kt`, `OnboardingManager.kt`, `LocalDocumentMirror.kt`, `PdfPageBookmarksManager.kt`) and app-private files. No app-level Room or DataStore. WorkManager pulls in Room transitively for its own work database (a ProGuard keep rule exists), but the app defines no entities/DAOs. |
| Durable journal | `SaveRecovery.kt` uses `noBackupFilesDir`, atomic replacement, and a properties journal. |
| DI | None. `PdfServiceLocator` object constructs services. |
| JSON | `kotlinx.serialization.json` 1.8.1. |
| Token/secret storage | No `EncryptedSharedPreferences`/security-crypto dependency; Android Keystore is available on minSdk 23 but not currently used. |

**Integration point:** reuse the `OfficeEngineDownloadWorker` patterns and `SaveRecovery` atomic-write discipline. Do not add OkHttp/Retrofit/Room/Hilt for this feature.

### 3.7 UI, settings, navigation, and design system

- Compose + Material 3 + `kyant.shapes` glass components.
- `SettingsScreen.kt` uses a single composable with an integer `page` state and `AnimatedContent`; sub-pages are internal, not nav routes. Existing pages: Index, Appearance, Language, Files, Personalization, About, Licenses, Office, Glass (`SettingsScreen.kt:655-668`).
- `SettingsSubPage.open` hides the app tab bar while a sub-page is open.
- `DocsNavGraph.kt` uses Navigation Compose string routes; settings is a single `ROUTE_SETTINGS` destination.
- Reusable glass UI: `SettingsSection`, `SettingsNavRow`, `SettingsDivider`, `SettingsSectionHeader`, `GlassScreenScaffold`, `GlassScreenHeaderRow`, `ToolPrimaryButton`, `GlassChoiceChip`, `AccentIconTile`.
- Version display reads `PackageManager` at runtime (`SettingsScreen.kt:215-217`), not a hard-coded string. The unused `settings_version` string still says `1.1.0` but is not used for display.
- About page currently has app name, version, “made by”, GitHub star button, and open-source notice. There is no diagnostics/support/update row.

**Integration point:** add a new Settings row/panel (“Diagnostics & Support”) using the existing glass components. A multi-step report form can be a dedicated nav route (`report_problem`) or a sub-page; either fits. Update UI can be a dialog plus a settings row.

### 3.8 Existing update/download/install functionality

- No in-app update checker.
- No APK download for the app itself.
- No `REQUEST_INSTALL_PACKAGES`.
- No package installer handoff.
- No `PackageManager.getPackageArchiveInfo` usage.
- Existing download code is for the optional Office engine archive only.
- No update strings in `strings.xml`.
- `OnboardingManager` tracks the last onboarded versionCode and the “What’s New” seen version; it is a useful pattern for version-aware UI but not an updater.

### 3.9 Tests and CI

| Area | State |
|---|---|
| App JVM tests | 29 files under `app/src/test/java`, JUnit 4 + `androidx.test:core`/`ext:junit`/`rules`. |
| `pdf-core` tests | 4 files. |
| `ocr-core` tests | No test source directory; CI task is effectively empty. |
| Android instrumentation tests | **None** (`androidTest` absent). |
| Emulator/device tests | None. |
| Backend tests | None (no backend). |
| Admin tests | None (no admin). |
| CI `build` job | `testPlayDebugUnitTest`, `:pdf-core:test`, `:ocr-core:test`, `lintPlayDebug`, `assemblePlayDebug`; uploads debug APK. |
| CI `release` job | Tag `v*` or manual dispatch; builds `assemblePlayRelease` + `bundlePlayRelease`; verifies APK signer and AAB signature; uploads artifacts. Does **not** create a GitHub Release, checksums, or FOSS APK. |
| CI `release-guard` job | Dry-runs release/aggregate tasks without signing and asserts the missing-signing guard fails. |
| Job dependencies | **None.** `release` does not `needs: build`. |
| FOSS/F-Droid CI | None. |
| Backend/admin CI | None. |

### 3.10 Backend and admin state

- **No Supabase configuration in the repository.** No `supabase/` directory, SQL, migrations, Edge Functions, `config.toml`, or client credentials.
- The connected Supabase account has one organization and one unrelated inactive project. It is not HMXPDF and must not be reused. No HMXPDF-specific project exists.
- **No web/admin project.** No `package.json`, Vite/Next config, `index.html` app shell, or TypeScript/React source. The only HTML is a bundled DOCX renderer asset.
- No authentication, roles, RLS policies, rate limits, retention jobs, or audit log exist anywhere.

### 3.11 Integration point map

| Integration point | File / location | Proposed use |
|---|---|---|
| Application startup | `app/src/main/AndroidManifest.xml` `<application>` | Add `android:name=".HMXApplication"`; install crash handler and WorkManager scheduling. |
| Build-time configuration | `app/build.gradle.kts` | Enable `buildConfig`; add public endpoint URLs, channel, signer fingerprint, feature flags. |
| Settings entry | `SettingsScreen.kt` root panels | Add “Diagnostics & Support” row; new page constant(s). |
| Report form | `DocsNavGraph.kt` + new screen | Add a focused route for manual report preview/approval. |
| Queue/worker | `OfficeEngineDownloadWorker.kt`, `SaveRecovery.kt` | Reuse patterns; new `DiagnosticsUploadWorker`. |
| Update download | `OfficeEngineDownloadWorker.kt` | Reuse Range/SHA-256/progress/cancel patterns; new `UpdateDownloadWorker`. |
| FileProvider | `file_paths.xml` | Add `cache-path` for `updates/`; only FOSS uses installer handoff. |
| Install permission | `app/src/foss/AndroidManifest.xml` | Add `REQUEST_INSTALL_PACKAGES` to FOSS only. |
| Backup exclusion | `backup_rules.xml` | Exclude diagnostics queue, update cache, and token preference file. |
| Release pipeline | `.github/workflows/android.yml` | Add FOSS APK, checksums, GitHub Release publication, job dependencies. |
| Admin hosting | new `admin/` | Static SPA, no server-side secrets. |
| Backend | new `supabase/` | Migrations, RLS, Edge Functions, tests. |

---

## 4. Existing code and components that can be reused

| Component | Location | Reuse | Caveats |
|---|---|---|---|
| HTTP download/resume | `OfficeEngineDownloadWorker.kt` | Copy the `HttpURLConnection`, Range, 206/200, progress, SHA-256, cancel, foreground-notification patterns for APK download. | Office-engine-specific paths and XZ unpacking must not be copied. |
| Durable atomic journal | `SaveRecovery.kt` | Use the same `noBackupFilesDir`, temp-file, fsync/rename, and recovery discipline for the diagnostics queue. | Do not store document content in diagnostics. |
| SharedPreferences settings | `Preferences.kt`, `OnboardingManager.kt` | Use a small dedicated prefs file for consent, install ID, last update check. | Token must not be stored in plaintext prefs. |
| Version-aware state | `OnboardingManager.kt` | Reuse the “last seen versionCode” idea for update/release-notes UI. | Not an update checker. |
| Settings sub-page pattern | `SettingsScreen.kt` | Add a page without restructuring navigation. | Page constants are internal; keep the pattern. |
| Glass design system | `ui/components`, `SettingsScreen.kt` | Reuse `SettingsSection`, `SettingsNavRow`, buttons, chips, headers. | New screens should match light/dark and RTL. |
| Manual DI | `PdfServiceLocator.kt` | Add a diagnostics/update service locator or object; no Hilt. | Keep test seams via constructor injection/fakes. |
| WorkManager | `libs.androidx.work.runtime.ktx` | Diagnostics upload and update check/download workers. | Unique work names; network constraints; backoff. |
| `kotlinx.serialization` | `libs.kotlinx.serialization.json` | API request/response models and queue records. | Add R8 verification for release. |
| ProGuard line numbers | `app/proguard-rules.pro` | Release stack traces retain source file/line info. | Class/method names are obfuscated; see §6/§12. |
| CI signing verification | `.github/workflows/android.yml:154-199` | Reuse for FOSS APK and any new release artifact. | Extend to checksums/package/version checks. |
| Fastlane changelogs | `fastlane/metadata/android/en-US/changelogs/6.txt` | Source release notes for the update dialog and GitHub Release body. | Add a changelog for every new versionCode. |
| Existing JVM test stack | JUnit 4, `androidx.test:core` | Add pure JVM tests for redaction, queue, version compare, checksum, API models. | No Robolectric/MockK currently; avoid adding unless necessary. |
| Existing audit docs | `docs/AUDIT_REPORT.md`, untracked `docs/HMXPDF_FULL_SOURCE_AUDIT.md` | Context and known defects. | Do not modify the untracked file; do not treat stale findings as current without re-verification. |

---

## 5. Missing functionality

### 5.1 Backend

- Dedicated Supabase project and environment configuration.
- `hmx` schema with tables, constraints, indexes, and enums.
- RLS enabled with no anon/authenticated policies; least-privilege grants.
- Tester invite/enrollment/approval/revocation model.
- Per-install bearer token issuance, hashing, rotation, and revocation.
- Tester consent/settings update (`auto_report_enabled`, `consent_version`) kept in sync with the client.
- Admin authentication/authorization (`admin_users`, `is_admin`, MFA/AAL2 check).
- Edge Functions for enrollment, status, deletion, diagnostics submission, support submission, release check, and admin operations.
- Request validation, payload limits, idempotency, rate limiting, redaction, and safe error responses.
- Retention/deletion jobs (`pg_cron`) and admin/user deletion paths.
- Audit log for sensitive admin actions.
- Dashboard aggregate queries.
- Backend tests for RLS, authorization boundaries, malformed/malicious requests, rate limits, redaction, and retention.
- Deployment documentation and secret handling.

### 5.2 Android

- `HMXApplication` and a crash handler that chains to the platform handler.
- `BuildConfig` fields for public endpoints, channel, signer fingerprint, and feature availability.
- Diagnostics consent model and UI; default **off**.
- Automatic crash/error capture only after explicit consent.
- Manual “Report a Problem” flow with preview and approval.
- Offline queue with durable storage, retry/backoff, deduplication, and size caps.
- Client-side redaction and payload validation.
- Diagnostics API client and upload worker.
- Settings → Diagnostics & Support screen, status, queued reports, delete-my-data.
- Update check, release notes, download progress/cancel/retry, storage checks.
- APK checksum and signature verification, package/version compatibility checks.
- FileProvider path and package-installer handoff (FOSS only).
- Update notification channel and permission handling.
- Tests for consent, queue, redaction, version comparison, checksum, and failure recovery.

### 5.3 Admin panel

- Static web app, login, MFA enrollment/challenge, session handling.
- Dashboard summaries.
- Near-real-time event feed (polling is sufficient; Realtime optional).
- Search/filter by severity, kind, app version, tester, and date.
- Event detail with sanitized stack trace.
- Support report list, detail, status changes, and internal notes.
- Tester invite/approve/revoke/delete controls.
- Release metadata and artifact links.
- Audit log view.
- Server-side authorization on every privileged action.
- CSP, responsive layout, safe rendering, and production build.

### 5.4 Release and update pipeline

- Version/tag discipline and changelog per release.
- FOSS signed APK build and GitHub Release publication.
- SHA-256 checksums for every APK.
- Release metadata in Supabase.
- Update-check API.
- Channel policy (`foss` self-update; `play`/`fdroid` disabled).
- Signature continuity verification against the existing key.
- Fresh-install and update-path test procedure.
- No GitHub Release/tag currently exists, so README/Obtainium links are dead.

### 5.5 Tests and CI

- Backend CI job (migrations, RLS, Edge Function unit/integration tests).
- Admin CI job (unit tests + production build).
- Android instrumentation tests and emulator CI.
- Release job dependencies and artifact verification.
- Secret/privacy scans in CI.
- Final end-to-end verification evidence.

---

## 6. Security and privacy risks

| ID | Risk | Impact | Mitigation | Phase |
|---|---|---|---|---|
| R-01 | Service-role key leaks into Android, admin bundle, repo, or logs | Full database compromise | Service role only in Edge Function runtime; never in client code; secret scan; no logging of env; admin SPA uses publishable key only. | 2 |
| R-02 | `hmx` schema exposed through PostgREST | Anonymous read/write of diagnostics | Keep `hmx` out of exposed schemas; revoke all from `anon`/`authenticated`; RLS with no client policies; all access via Edge Functions. | 2 |
| R-03 | Diagnostics contain PII, document content, passwords, tokens, or file paths | Privacy breach, legal exposure | Allowlist fields; client and server redaction; size caps; forbid document/base64/password/token keys; user preview; tests. | 2/3 |
| R-04 | Stack traces contain absolute paths, URLs with tokens, emails, or user text | PII leakage | Redact paths/URLs/emails/tokens; truncate; store as text; render with `textContent`; no HTML. | 2/3/4 |
| R-05 | Bearer token theft from device backup or plaintext prefs | Impersonation/spam | Random 256-bit token; store only SHA-256 server-side; encrypt client token with Android Keystore AES/GCM; exclude from backup. | 3 |
| R-06 | Invite code sharing or endpoint spam | Abuse, cost, data pollution | Single-use/limited-use invites, expiry, admin revoke; per-install + per-IP rate limits; payload caps; idempotency. | 2 |
| R-07 | Admin account compromise | Full admin access | Disable public signup; strong passwords; require TOTP MFA/AAL2; server-side allowlist; audit log; session expiry. | 2/4 |
| R-08 | XSS in admin panel via event message/stack/report text | Admin session theft | Strict CSP; no inline scripts; render untrusted text with `textContent`; no `innerHTML`; sanitize release notes. | 4 |
| R-09 | CSRF/CORS misconfiguration | Unauthorized admin actions | Bearer tokens (not cookies); strict origin allowlist; no wildcard CORS; server-side authorization. | 2/4 |
| R-10 | Tampered APK or MITM download | Malware installation | HTTPS only; host allowlist; SHA-256; verify APK signer against embedded/current signer; package/version checks; system installer confirmation. | 5 |
| R-11 | Signature/channel mismatch | Update impossible or unsafe | `foss` uses existing key; `play` self-update disabled (Play App Signing may re-sign); `fdroid` disabled (different signer). | 5 |
| R-12 | `REQUEST_INSTALL_PACKAGES` in Play build | Play policy rejection | Declare only in `src/foss/AndroidManifest.xml`; Play flavor has no self-updater. | 5 |
| R-13 | Downgrade/rollback abuse | Security regression | Reject `versionCode <= installed`; no silent install; document no automatic rollback. | 5 |
| R-14 | Diagnostics/update data included in Android backup | Data leakage after restore | Exclude queue, update cache, and token prefs in backup rules; use `noBackupFilesDir` for queue. | 3/5 |
| R-15 | Secrets printed in CI logs | Signing/backend compromise | Keep existing secret validation non-echoing; never print env values; use GitHub encrypted secrets; restrict release environment. | 2/5 |
| R-16 | Data retained indefinitely | Privacy/compliance | `pg_cron` retention; user/admin deletion; documented periods. | 2 |
| R-17 | F-Droid “tracking” anti-feature | Distribution rejection/user trust | Disable automatic diagnostics in `fdroid` by default (or entirely) pending policy decision; manual report only if approved. | 3 |
| R-18 | Release stack traces are obfuscated | Reduced diagnostic value | Keep `mapping.txt` as a private CI artifact; document limitation; optional retrace later. | 5 |
| R-19 | Play merged APK already has `INTERNET` and `READ_PHONE_STATE` | Privacy-policy mismatch/unexpected permission | Disclose network use; do not add new permissions unnecessarily; note existing unrelated permission as out of scope. | 3 |
| R-20 | `main` has no branch protection | Accidental direct push | Process discipline now; recommend protection after merge approval; do not change settings during audit. | all |
| R-21 | `backup_rules.xml` uses the wrong root element for `dataExtractionRules` and wrong domains for cache paths | Backup rules may be ignored on Android 12+; explicit cache exclusions are ineffective (cache is normally excluded anyway) | Fix within Phase 3/5 if in scope, use `domain="cache"` for cache paths, and add explicit diagnostics exclusions; verify on device. | 3/5 |
| R-22 | `release` environment has no protection rules/secrets | Unreviewed tag release | Add required reviewers, deployment branch policy, environment secrets before first tag; requires authorization. | 5 |
| R-23 | No local Android SDK/emulator/Docker/Supabase CLI | Unverified runtime behavior | Use GitHub Actions (Docker/emulator) and a staging Supabase project; report NOT RUN where unavoidable. | 2–5 |

**Privacy principle for this project:** collect the minimum needed to diagnose HMXPDF failures. Never collect PDF contents, document names, passwords, authentication tokens, clipboard data, contacts, location, installed-app lists, IMEI/serial numbers, advertising IDs, or full logcat. Do not monitor other applications.

---

## 7. Proposed database schema and access policies

> This section is a design proposal, not a migration. No SQL was executed.

### 7.1 Principles

1. Dedicated Supabase project, separate from the unrelated inactive project.
2. A dedicated `hmx` schema, **not** listed in PostgREST’s exposed schemas.
3. `anon` and `authenticated` have **no** table privileges and **no** RLS policies.
4. All client access goes through Edge Functions using the service role after explicit authentication/authorization.
5. Admins are Supabase Auth users present in `hmx.admin_users`; MFA/AAL2 is required.
6. Testers are per-install identities with random bearer tokens; only token hashes are stored.
7. Every mutation that affects testers, reports, releases, or data deletion is audit-logged.
8. Retention is enforced by `pg_cron`, not by client behavior.

### 7.2 Tables (illustrative DDL)

```sql
create schema if not exists hmx;

revoke all on schema hmx from public, anon, authenticated;
grant usage on schema hmx to service_role;

create type hmx.tester_status as enum ('pending', 'active', 'revoked');
create type hmx.event_kind   as enum ('crash', 'error', 'manual');
create type hmx.severity     as enum ('fatal', 'error', 'warning', 'info');
create type hmx.support_status as enum ('new', 'triaged', 'in_progress', 'resolved', 'closed');
create type hmx.release_channel as enum ('foss', 'play', 'fdroid');

create table hmx.admin_users (
  user_id    uuid primary key references auth.users(id) on delete cascade,
  role       text not null default 'admin' check (role in ('admin', 'readonly')),
  is_active  boolean not null default true,
  created_at timestamptz not null default now()
);

create table hmx.tester_invites (
  id          uuid primary key default gen_random_uuid(),
  code_hash   text not null unique,
  label       text,
  max_uses    int not null default 1 check (max_uses between 1 and 100),
  used_count  int not null default 0,
  expires_at  timestamptz not null,
  created_by  uuid references hmx.admin_users(user_id),
  created_at  timestamptz not null default now(),
  revoked_at  timestamptz
);

create table hmx.testers (
  id                 uuid primary key default gen_random_uuid(),
  install_id         uuid not null unique,
  token_hash         text not null unique,
  status             hmx.tester_status not null default 'pending',
  label              text,
  app_version_code   int,
  app_version_name   text,
  android_api        int,
  device_model       text,
  consent_version    text,
  consent_at         timestamptz,
  auto_report_enabled boolean not null default false,
  created_at         timestamptz not null default now(),
  last_seen_at       timestamptz,
  revoked_at         timestamptz
);

create table hmx.diagnostic_events (
  id                uuid primary key default gen_random_uuid(),
  tester_id         uuid not null references hmx.testers(id) on delete cascade,
  client_event_id   uuid not null,
  occurred_at       timestamptz not null,
  received_at       timestamptz not null default now(),
  kind              hmx.event_kind not null,
  severity          hmx.severity not null,
  message           text not null,
  stack_trace       text,
  screen            text,
  app_version_code  int not null,
  app_version_name  text not null,
  android_api       int not null,
  device_model      text,
  locale            text,
  build_flavor      text,
  build_type        text,
  metadata          jsonb not null default '{}'::jsonb,
  unique (tester_id, client_event_id)
);

create table hmx.support_reports (
  id                uuid primary key default gen_random_uuid(),
  tester_id         uuid references hmx.testers(id) on delete set null,
  client_report_id  uuid not null,
  category          text not null check (category in ('bug', 'crash', 'performance', 'other')),
  title             text not null,
  description       text not null,
  contact_email     text,
  status            hmx.support_status not null default 'new',
  admin_notes       text,
  app_version_code  int,
  app_version_name  text,
  android_api       int,
  device_model      text,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  resolved_at       timestamptz,
  unique (tester_id, client_report_id)
);

create table hmx.releases (
  id            uuid primary key default gen_random_uuid(),
  channel       hmx.release_channel not null,
  version_code  int not null,
  version_name  text not null,
  min_sdk       int not null default 23,
  release_notes text not null default '',
  published_at  timestamptz,
  is_active     boolean not null default true,
  created_at    timestamptz not null default now(),
  unique (channel, version_code)
);

create table hmx.release_artifacts (
  id          uuid primary key default gen_random_uuid(),
  release_id  uuid not null references hmx.releases(id) on delete cascade,
  abi         text not null check (abi in ('universal', 'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64')),
  apk_url     text not null,
  sha256      text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  size_bytes  bigint not null check (size_bytes > 0),
  unique (release_id, abi)
);

create table hmx.admin_audit_log (
  id             bigserial primary key,
  admin_user_id  uuid references hmx.admin_users(user_id),
  action         text not null,
  target_type    text,
  target_id      text,
  metadata       jsonb not null default '{}'::jsonb,
  created_at     timestamptz not null default now()
);

create table hmx.rate_limits (
  bucket        text not null,
  window_start  timestamptz not null,
  count         int not null default 0,
  primary key (bucket, window_start)
);
```

**Indexes (minimum):**

```sql
create index on hmx.diagnostic_events (received_at desc);
create index on hmx.diagnostic_events (severity, received_at desc);
create index on hmx.diagnostic_events (tester_id, received_at desc);
create index on hmx.diagnostic_events (app_version_code);
create index on hmx.support_reports (status, created_at desc);
create index on hmx.releases (channel, is_active, version_code desc);
create index on hmx.admin_audit_log (created_at desc);
```

### 7.3 RLS and grants

```sql
alter table hmx.admin_users       enable row level security;
alter table hmx.tester_invites    enable row level security;
alter table hmx.testers           enable row level security;
alter table hmx.diagnostic_events enable row level security;
alter table hmx.support_reports   enable row level security;
alter table hmx.releases          enable row level security;
alter table hmx.release_artifacts enable row level security;
alter table hmx.admin_audit_log   enable row level security;
alter table hmx.rate_limits       enable row level security;

revoke all on all tables    in schema hmx from public, anon, authenticated;
revoke all on all sequences in schema hmx from public, anon, authenticated;
revoke all on all functions in schema hmx from public, anon, authenticated;

grant all on all tables    in schema hmx to service_role;
grant all on all sequences in schema hmx to service_role;
```

No policies are created for `anon` or `authenticated`. This is intentional: the schema is server-only. If a future direct-PostgREST feature is needed, it must add explicit policies and tests; it is not part of this design.

### 7.4 Authorization and rate-limit functions

```sql
create or replace function hmx.is_admin(p_user uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from hmx.admin_users
    where user_id = p_user
      and is_active
      and role = 'admin'
  );
$$;

revoke all on function hmx.is_admin(uuid) from public, anon, authenticated;
grant execute on function hmx.is_admin(uuid) to service_role;

create or replace function hmx.consume_rate_limit(
  p_bucket text,
  p_limit int,
  p_window_seconds int
) returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  w timestamptz := to_timestamp(
    floor(extract(epoch from clock_timestamp()) / p_window_seconds) * p_window_seconds
  );
  c int;
begin
  if p_limit <= 0 or p_window_seconds <= 0 then
    return false;
  end if;

  insert into hmx.rate_limits (bucket, window_start, count)
  values (p_bucket, w, 1)
  on conflict (bucket, window_start)
  do update set count = hmx.rate_limits.count + 1
  returning count into c;

  return c <= p_limit;
end;
$$;

revoke all on function hmx.consume_rate_limit(text, int, int) from public, anon, authenticated;
grant execute on function hmx.consume_rate_limit(text, int, int) to service_role;
```

### 7.5 Retention

Proposed defaults (adjustable before Phase 2):

| Data | Retention |
|---|---|
| Diagnostic events | 90 days after `received_at` |
| Resolved/closed support reports | 180 days after `updated_at` |
| Admin audit log | 365 days |
| Rate-limit windows | 2 days |
| Expired tester invites | 30 days after expiry |
| Revoked testers | Kept until admin deletes or 90 days after revocation; deletion cascades events |

```sql
create or replace function hmx.run_retention()
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  delete from hmx.diagnostic_events
   where received_at < now() - interval '90 days';

  delete from hmx.support_reports
   where status in ('resolved', 'closed')
     and updated_at < now() - interval '180 days';

  delete from hmx.admin_audit_log
   where created_at < now() - interval '365 days';

  delete from hmx.rate_limits
   where window_start < now() - interval '2 days';

  delete from hmx.tester_invites
   where expires_at < now() - interval '30 days';

  delete from hmx.testers
   where status = 'revoked'
     and revoked_at < now() - interval '90 days';
end;
$$;

revoke all on function hmx.run_retention() from public, anon, authenticated;
grant execute on function hmx.run_retention() to service_role;
```

Schedule with `pg_cron` (for example, daily at 03:00 UTC) after enabling the extension in the Supabase project. The exact schedule is an operational setting, not a client-visible one.

### 7.6 Data classification

| Class | Examples | Storage rule |
|---|---|---|
| Secret | Service-role key, signing passwords, tester bearer token plaintext | Never stored in DB/repo/client logs. Service role only in Edge runtime; tester token stored as SHA-256 hash; client token encrypted with Keystore. |
| Personal (low) | Optional contact email, device model, locale | Only with consent; redacted where possible; deletable. |
| Diagnostic | App version, Android API, event kind/severity, sanitized message/stack, screen | Allowlisted fields; size caps; retention. |
| Operational | Admin audit log, rate-limit counters | Service-only; no client access. |
| Public | Release metadata, APK URL, checksum, release notes | Safe to return from `release-check`; no PII. |

---

## 8. Proposed Edge Functions and API contracts

### 8.1 Common rules

- All functions are HTTPS-only, JSON-only, and return a `request_id` for support correlation.
- Maximum request body: **128 KiB** for diagnostics batches and support reports, **4 KiB** for status/enrollment/release-check.
- Reject unknown top-level fields; validate types, lengths, enums, timestamps, UUIDs, and URLs.
- Never return SQL errors, stack traces, internal IDs, or secret values.
- Log only request ID, function, status, latency, and a short hash prefix of the caller token; never log payloads.
- CORS: no wildcard. `admin-api` allows only the configured admin origin(s). Tester endpoints do not need browser CORS; `release-check` may be called from Android only.
- Rate limits: per tester token, per IP hash, and per function. Proposed defaults:
  - Enrollment: 5 attempts/hour/IP.
  - Diagnostics: 60 events/hour/tester, 500/day/tester.
  - Support: 10 reports/day/tester.
  - Release check: 60/hour/IP.
  - Admin API: 120 requests/minute/admin.
- Idempotency: client-generated UUID per event/report; unique constraint; duplicates return success with `duplicate: true`.
- Redaction runs **both** on the client (preview) and server (authoritative).

### 8.2 Function summary

| Function | Auth | Purpose |
|---|---|---|
| `tester-enroll` | Invite code | Redeem a single-use/limited invite; create tester + token; return token once. |
| `tester-status` | Tester bearer token | Return status, consent flags, server time. |
| `tester-settings` | Tester bearer token | Update `auto_report_enabled` / `consent_version`; server keeps consent state in sync. |
| `tester-delete` | Tester bearer token + confirmation | Delete tester and cascaded diagnostics/support data; audit. |
| `diagnostics-submit` | Tester bearer token | Validate/redact/store a batch of events; idempotent. |
| `support-submit` | Tester bearer token | Store a manual report with optional diagnostics snapshot. |
| `release-check` | None (public, rate-limited) | Return latest active release for channel/ABI/version. |
| `admin-api` | Supabase admin JWT (AAL2) | Action router for dashboard, events, support, testers, releases, audit. |

### 8.3 `POST /functions/v1/tester-enroll`

Request:

```json
{
  "invite_code": "HMX-7F3K-2Q9M",
  "install_id": "3f0c2a5e-6b1d-4f0e-9a7c-2d8b1e4f6a90",
  "app_version_code": 7,
  "app_version_name": "2.2.0",
  "android_api": 35,
  "device_model": "Pixel 8",
  "consent_version": "2026-10-24",
  "auto_report_enabled": false
}
```

Response `201`:

```json
{
  "tester_id": "0b6f...",
  "token": "hmx_...",
  "status": "active",
  "server_time": "2026-10-24T12:00:00Z"
}
```

Rules:

- Invite code is hashed and looked up; reject expired, revoked, exhausted, or unknown codes with the same generic `403`.
- `install_id` must be a UUID generated on-device; re-enrollment with the same install ID rotates/reuses the tester row only if the invite is valid and the tester is not revoked.
- Token is 32 random bytes, base64url, prefixed `hmx_`; returned once; only SHA-256 stored.
- `auto_report_enabled` defaults to `false`; the server must not enable it implicitly.
- Enrollment is rate-limited by IP hash and invite code hash.

### 8.4 `GET /functions/v1/tester-status`

Header: `Authorization: Bearer hmx_...`

Response `200`:

```json
{
  "status": "active",
  "auto_report_enabled": false,
  "consent_version": "2026-10-24",
  "server_time": "2026-10-24T12:00:00Z"
}
```

`401` for missing/invalid token; `403` for revoked/pending (with a generic status message); `429` for rate limit.

**Consent update:** `POST /functions/v1/tester-settings` with the same bearer token and `{ "auto_report_enabled": false, "consent_version": "2026-10-24" }` updates the tester row. The app calls it when the user toggles automatic reporting (best effort; queue locally if offline). `diagnostics-submit` rejects automatic events while the server flag is false, so the two states must not drift.

### 8.5 `POST /functions/v1/tester-delete`

Header: `Authorization: Bearer hmx_...`

```json
{ "confirm": true }
```

Response `204`. Deletes the tester row; cascades diagnostics and nulls support-report tester references (or deletes reports per policy). Writes an audit entry with the tester ID, not the token.

### 8.6 `POST /functions/v1/diagnostics-submit`

Header: `Authorization: Bearer hmx_...`

```json
{
  "events": [
    {
      "client_event_id": "8d1f0a2c-3b4e-4c5d-9e6f-7a8b9c0d1e2f",
      "occurred_at": "2026-10-24T11:58:00Z",
      "kind": "crash",
      "severity": "fatal",
      "message": "Failed to open PDF",
      "stack_trace": "java.lang.IllegalStateException: ...\n\tat com.hmx.pdf...",
      "screen": "pdf_viewer",
      "app_version_code": 7,
      "app_version_name": "2.2.0",
      "android_api": 35,
      "device_model": "Pixel 8",
      "locale": "en-IN",
      "build_flavor": "foss",
      "build_type": "release",
      "metadata": {
        "file_type": "pdf",
        "page_count_bucket": "10-49"
      }
    }
  ]
}
```

Response `200`:

```json
{
  "accepted": 1,
  "duplicates": 0,
  "rejected": 0,
  "request_id": "req_..."
}
```

Rules:

- Max 10 events per batch; max 128 KiB total body; each event is truncated/rejected at 32 KiB after redaction.
- `metadata` is an allowlist of scalar keys (for example `file_type`, `page_count_bucket`, `operation`); reject nested objects, arrays, base64 blobs, and unknown keys.
- Accept only `crash`/`error` here and require `auto_report_enabled=true`; manual reports travel through `support-submit`.
- Server redaction removes emails, bearer/basic tokens, password-like fields, absolute file paths, URL query strings, long base64, and IP addresses.
- Store redacted text only. Do not store the raw payload.
- If a batch has mixed valid/invalid events, accept valid ones and report `rejected`; do not fail the whole batch unless the envelope is invalid.

### 8.7 `POST /functions/v1/support-submit`

Header: `Authorization: Bearer hmx_...`

```json
{
  "client_report_id": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
  "category": "bug",
  "title": "Export fails on large PDF",
  "description": "Tapping Export shows an error after 30 seconds.",
  "contact_email": "optional@example.com",
  "include_diagnostics": true,
  "diagnostics": [
    {
      "client_event_id": "8d1f0a2c-3b4e-4c5d-9e6f-7a8b9c0d1e2f",
      "occurred_at": "2026-10-24T11:58:00Z",
      "kind": "manual",
      "severity": "error",
      "message": "Export failed",
      "stack_trace": "...",
      "screen": "pdf_viewer",
      "app_version_code": 7,
      "app_version_name": "2.2.0",
      "android_api": 35,
      "device_model": "Pixel 8",
      "locale": "en-IN",
      "build_flavor": "foss",
      "build_type": "release",
      "metadata": { "operation": "export" }
    }
  ]
}
```

Response `201`:

```json
{ "report_id": "…", "status": "new", "request_id": "req_…" }
```

Rules:

- Title 1–120 chars; description 1–4000 chars; optional email must be syntactically valid and is stored only if provided.
- `include_diagnostics` requires an explicit client-side preview/approval before upload.
- Max 5 diagnostic snapshots; same validation/redaction as `diagnostics-submit`.
- No attachments; no file contents.

### 8.8 `GET /functions/v1/release-check`

Query:

```
?channel=foss&version_code=6&abi=arm64-v8a&package=com.hmx.pdf
```

Response `200`:

```json
{
  "update_available": true,
  "release": {
    "version_code": 7,
    "version_name": "2.2.0",
    "min_sdk": 23,
    "release_notes": "…",
    "published_at": "2026-10-30T00:00:00Z",
    "artifact": {
      "abi": "arm64-v8a",
      "url": "https://github.com/HMXPANEL/HMXPDF/releases/download/v2.2.0/HMXPDF-2.2.0-arm64-v8a.apk",
      "sha256": "…64 hex…",
      "size_bytes": 33910831
    }
  }
}
```

Rules:

- Public, no tester token; rate-limited by IP hash; cacheable for 5 minutes.
- Only `foss` returns `update_available` in this project. `play` and `fdroid` return `update_available=false` with a `managed_by` field (`google_play` / `fdroid`) so the app can explain that updates are managed externally.
- Reject non-`https` artifact URLs; allowlist GitHub release hosts; verify the URL after redirects on the client.
- If no active release or the latest is not newer, return `update_available=false`.
- Prefer exact ABI artifact; fall back to `universal`.

### 8.9 `POST /functions/v1/admin-api`

Header: `Authorization: Bearer <Supabase access token>`.

The function must:

1. Rely on the platform `verify_jwt = true` gate, then verify the caller with `auth.getUser()`; never trust an unverified decoded JWT.
2. Call `auth.getAuthenticatorAssuranceLevel(jwt)` with the caller's access token and require `currentLevel === "aal2"`. `aal1` means password/OTP only; `aal2` means a verified TOTP factor was used in this session. This is a server-side check, not a UI flag.
3. Check `hmx.is_admin(user.id)` and `is_active = true`.
4. Parse a strict action envelope.
5. Execute the action with the service-role client.
6. Write an audit entry for mutations.
7. Return `{ data, next_cursor?, request_id }`.

Actions:

| Action | Kind | Notes |
|---|---|---|
| `session.me` | read | Returns admin role, MFA level, server time. |
| `dashboard.summary` | read | Counts by severity/kind/version, open support, active testers, latest releases. |
| `events.list` | read | Cursor pagination; filters: severity, kind, versionCode, testerId, from, to, query. |
| `events.get` | read | Full sanitized event. |
| `events.delete` | mutation | Audit. |
| `support.list` | read | Cursor pagination; filters: status, category, from, to. |
| `support.get` | read | Full report + diagnostics. |
| `support.update` | mutation | Status and admin notes; audit. |
| `testers.list` | read | Status/label/last seen. |
| `testers.invite` | mutation | Create invite; return code once; audit (never log code). |
| `testers.approve` | mutation | `pending` → `active`; audit. |
| `testers.revoke` | mutation | `active`/`pending` → `revoked`; audit. |
| `testers.delete` | mutation | Delete tester + data; audit. |
| `releases.list` | read | Releases + artifacts. |
| `releases.upsert` | mutation | Create/update release metadata; validate URL/checksum/size; audit. |
| `releases.deactivate` | mutation | Hide from update-check; audit. |
| `audit.list` | read | Cursor pagination; admin-only. |

Error codes: `400` invalid payload, `401` missing/invalid JWT, `403` not admin / MFA required / revoked, `404` not found, `409` conflict, `413` too large, `429` rate limited, `500` generic.

### 8.10 Edge Function configuration and secrets

- `supabase/config.toml` declares per-function `verify_jwt`:
  - `admin-api`: `verify_jwt = true` (platform validates signature/expiry only; the handler still performs the manual `aal2` + allowlist checks).
  - tester/release functions: `verify_jwt = false` (custom token or public).
- Runtime secrets (Supabase-managed): `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY`.
- Never put the service-role key in Android, the admin SPA, GitHub files, reports, or logs.
- Admin SPA gets only `VITE_SUPABASE_URL` and the publishable/anon key.
- If the Supabase gateway requires an `apikey` header for `verify_jwt = false` functions, Android sends the publishable/anon key in that header; it is public by design and is not a service-role credential.
- Deployment uses Supabase CLI/CI with `SUPABASE_ACCESS_TOKEN` and project ref stored as GitHub environment secrets; no secret values in the repo.
- Database password and service-role key are not needed by Android or the SPA.

---

## 9. Proposed Android integration

### 9.1 New package layout

```
app/src/main/java/com/hmx/pdf/
  HMXApplication.kt
  diagnostics/
    DiagnosticsConsent.kt
    DiagnosticsSettings.kt
    InstallationIdentity.kt
    TokenCipher.kt
    DiagnosticsRedactor.kt
    DiagnosticsQueue.kt
    DiagnosticsPayload.kt
    DiagnosticsApi.kt
    DiagnosticsReporter.kt
    DiagnosticsUploadWorker.kt
    CrashReporter.kt
  update/
    UpdateChannel.kt
    UpdateApi.kt
    UpdateChecker.kt
    UpdateDownloadWorker.kt
    ApkVerifier.kt
    UpdateInstaller.kt
    UpdateStateStore.kt
  ui/screen/
    DiagnosticsSettingsScreen.kt
    ReportProblemScreen.kt
    UpdateDialog.kt
```

Keep classes small and testable. Pure logic (redaction, payload building, version comparison, checksum, queue serialization) must not depend on Android framework classes.

### 9.2 BuildConfig fields

Enable `buildFeatures { buildConfig = true }` and add public, non-secret fields:

| Field | `play` | `foss` | `fdroid` |
|---|---|---|---|
| `DIAGNOSTICS_BASE_URL` | Supabase functions URL | same | same or empty |
| `DIAGNOSTICS_AVAILABLE` | true | true | pending policy (default false) |
| `UPDATE_CHANNEL` | `play` | `foss` | `fdroid` |
| `SELF_UPDATE_ENABLED` | false | true | false |
| `EXPECTED_SIGNER_SHA256` | release cert fingerprint | same | empty (F-Droid re-signs) |

No service-role key, database password, or admin token is ever a BuildConfig field. The function URL and publishable anon key (if needed) are public by design; the current design does not require the Android app to use Supabase Auth at all.

### 9.3 Application and crash handling

- Add `HMXApplication : Application`.
- In `onCreate`, install `CrashReporter.install()` only if diagnostics are available and the user has enabled automatic reporting.
- The handler:
  1. Builds a crash event with app/device metadata.
  2. Redacts and enqueues it to `noBackupFilesDir/diagnostics/queue.jsonl` (bounded, atomic append).
  3. Delegates to the previous/default `UncaughtExceptionHandler` so the app still crashes normally.
- Never swallow the crash, never block for network I/O, and never write PDF contents.
- If automatic reporting is off, do not capture the crash. The user can still submit a manual report after restarting (the app cannot show a preview at crash time).
- Chain the handler exactly once; guard against double installation in tests.

### 9.4 Consent and settings model

Dedicated prefs file (for example `hmx_diagnostics`):

| Key | Meaning |
|---|---|
| `install_id` | Random UUID generated on first diagnostics use. |
| `tester_token_ciphertext` | AES/GCM ciphertext of the bearer token; key in Android Keystore. |
| `auto_report_enabled` | Default `false`; only the user can set it. |
| `consent_version` | Version string of the privacy/consent text accepted. |
| `consent_at` | Timestamp. |
| `last_update_check` | Timestamp. |
| `pending_manual_report_ids` | Optional index for UI. |

Rules:

- The Diagnostics screen explains what is collected before the toggle is usable.
- Enabling automatic reporting requires an explicit confirmation.
- Disabling automatic reporting deletes queued automatic events (manual reports are not silently deleted; show them and let the user cancel/delete).
- “Delete my data” clears local queue/token/install ID and calls `tester-delete` when online; if offline, queue the deletion request? Simpler: require online for server deletion, but always clear local data immediately and tell the user. Document the limitation.
- Token storage uses Android Keystore AES/GCM. If Keystore fails, do not fall back to plaintext; show an error and keep diagnostics disabled.

### 9.5 Offline queue and upload

- Queue file: `noBackupFilesDir/diagnostics/queue.jsonl` (one JSON object per line).
- Atomic append: write to a temp file, fsync, rename; or append with a crash-safe record format. Reuse the `SaveRecovery` discipline.
- Caps: 200 events or 1 MiB, whichever comes first; drop oldest with a logged count. Manual reports are capped separately (20).
- Deduplicate by `client_event_id`; reject duplicate enqueue.
- `DiagnosticsUploadWorker`:
  - Unique work name `hmx-diagnostics-upload`.
  - Network constraint; exponential backoff (30s, 2m, 10m, 30m; cap 6h).
  - Batch up to 10 events / 128 KiB.
  - On `2xx`: remove uploaded events; on `409` duplicate: remove; on `401/403`: stop and mark token invalid; on `429`: honor `Retry-After`; on `5xx`/IO: retry; on other `4xx`: drop the event and record a local error (do not loop forever).
  - Never log payloads.
- Manual report flow enqueues a `support-submit` job with the approved payload; preview is mandatory.

### 9.6 Collected metadata (allowlist)

| Field | Example | Notes |
|---|---|---|
| `client_event_id` | UUID | Generated on device. |
| `occurred_at` | ISO-8601 UTC | Device clock; server also records `received_at`. |
| `kind` | `crash`/`error`/`manual` | No ANR detection in MVP. |
| `severity` | `fatal`/`error`/`warning`/`info` | App-defined. |
| `message` | Short exception message | Redacted/truncated. |
| `stack_trace` | Sanitized trace | Max 16 KiB; no full logcat. |
| `screen` | `pdf_viewer` | Optional route/feature identifier. |
| `app_version_code` / `app_version_name` | `7` / `2.2.0` | BuildConfig/PackageManager. |
| `android_api` | `35` | `Build.VERSION.SDK_INT`. |
| `device_model` | `Pixel 8` | `Build.MANUFACTURER` + `Build.MODEL`; no serial. |
| `locale` | `en-IN` | `Locale.getDefault().toLanguageTag()`. |
| `build_flavor` / `build_type` | `foss` / `release` | BuildConfig. |
| `metadata` | `{ "operation": "export", "file_type": "pdf", "page_count_bucket": "10-49" }` | Allowlisted scalar keys only. |

**Never collect:** PDF/document bytes or text, file names/URIs, passwords, auth tokens, clipboard, contacts, location, installed apps, IMEI/serial/Android ID, advertising ID, full logcat, other apps’ data.

### 9.7 Redaction rules (client and server)

- Email addresses → `[email]`.
- Bearer/Basic tokens, `Authorization:` values, `password=`, `token=`, `secret=`, `api_key=` → `[redacted]`.
- Absolute paths (`/data/...`, `/storage/...`, `C:\...`) → `[path]`.
- URL query strings and fragments → `[url]` (keep scheme/host only if useful).
- Long base64/hex blobs (> 200 chars) → `[blob]`.
- IPv4/IPv6 addresses → `[ip]`.
- Truncate message to 2 KiB and stack to 16 KiB.
- Reject payloads containing forbidden keys (`pdf`, `document`, `content`, `password`, `token`, `authorization`, `cookie`, `file`, `bytes`, `base64`) at any depth.
- Server repeats redaction and validation; never trust the client.

### 9.8 Settings and report UI

- Add a Settings root row: **Diagnostics & Support** (icon, subtitle).
- Page contents:
  - Status: not enrolled / pending / active / revoked; “Join testing program” (invite code).
  - Automatic reporting toggle (default off) with explanation and privacy link.
  - “Report a Problem” button (manual flow).
  - Queued reports count and “Retry now”.
  - “Delete my data” with confirmation.
  - App version, Android version, and installation ID (truncated/copyable).
- Manual report flow:
  1. Category, title, description.
  2. “Include diagnostics” checkbox (default on for crash context? No: default off; user chooses).
  3. Preview screen showing the exact redacted JSON payload.
  4. Required checkbox: “I have reviewed this report and agree to send it.”
  5. Send / Save offline / Cancel.
- Update UI:
  - Settings row “Check for updates” (FOSS) or “Updates are managed by Google Play/F-Droid”.
  - Dialog: version, size, release notes, Download / Later.
  - Download screen/notification with progress, cancel, retry.
  - Install explanation and unknown-sources guidance.
- Match existing glass components and light/dark themes; test RTL and small screens.

### 9.9 Manifest, FileProvider, backup, and permissions

- `AndroidManifest.xml`: add `android:name=".HMXApplication"`.
- `app/src/foss/AndroidManifest.xml`: add `REQUEST_INSTALL_PACKAGES` (FOSS only).
- `file_paths.xml`: add `<cache-path name="updates" path="updates/" />`.
- `backup_rules.xml`: add exclusions for `diagnostics/`, `updates/`, and the diagnostics prefs file; fix the root element if in scope.
- No new runtime permission for diagnostics. `POST_NOTIFICATIONS` is already declared; request it only when the user enables update notifications or starts a download.
- Do not add `REQUEST_INSTALL_PACKAGES` to `play` or `fdroid`.

### 9.10 Channel behavior

| Build | Diagnostics | Self-update | Rationale |
|---|---|---|---|
| `play` | Consent-based, default off | **Disabled** | Play policy; Play App Signing may re-sign; Play handles updates. |
| `foss` | Consent-based, default off | **Enabled** | GitHub Releases; same signing key as the existing release. |
| `fdroid` | **Pending policy** (default disabled) | **Disabled** | F-Droid anti-feature/tracking policy; F-Droid signs with a different key. |

### 9.11 Error-hook scope

Instrument only these high-value sites in Phase 3:

- `PdfViewerViewModel`: open failure, export failure, password/decrypt failure.
- `OfficeEngineClient`: conversion failure/timeouts.
- `UniversalDocumentConverter`: conversion failure.
- `DocxEditorViewModel`: save/export failure.
- `ScanViewModel`: scan/save failure.
- `MainActivity`/`HMXApplication`: uncaught exceptions (consent-gated).

Every hook must be wrapped so reporting cannot change the user-visible error path. Do not add reporting to every `catch` block.

---

## 10. Proposed admin panel architecture

### 10.1 Stack and hosting

- New top-level `admin/` directory: Vite + TypeScript, no React/Vue/Angular.
- `@supabase/supabase-js` for Auth only (password + TOTP MFA).
- All data through `admin-api`; no direct PostgREST table access.
- **Hosting decision: Vercel** (static build). Deployment is a Vercel project connected to this repository, or `vercel --prod` from CI; output is the static `admin/dist/` directory. If using the Git integration, set the Vercel **Root Directory** to `admin`, build command `npm run build`, and output directory `dist`.
  - `admin/vercel.json` provides the SPA rewrite to `/index.html` and the security headers (CSP, `Referrer-Policy`, `X-Content-Type-Options`, `X-Frame-Options`, `Permissions-Policy`).
  - Optional Vercel Deployment Protection (Vercel Authentication or password protection) is defense in depth only; Supabase Auth + server-side authorization remain mandatory.
  - The Vercel URL is public; it must never be treated as a secret or as access control.
- Rejected alternatives: Cloudflare Pages/`_headers` (not the selected host), GitHub Pages (no custom response headers, less control), and serving the SPA from Supabase Storage (not designed for this).
- Environment variables: only `VITE_SUPABASE_URL` and the Supabase publishable/anon key. These are public by design. **Never** put the service-role key, database password, or any secret in Vercel client env vars or the bundle.
- Production build has no source maps; no secrets; no service-role key.

### 10.2 Auth and authorization

**TOTP MFA flow (Supabase Auth):**

1. **Provisioning (manual, not public).** Disable public signup in Supabase Auth. Create each admin user manually (dashboard or admin API with the service role), then insert an active row in `hmx.admin_users`. There is no public signup, no self-service role assignment, and no admin-creation endpoint in the SPA.
2. **First login / enrollment.** `signInWithPassword` yields an `aal1` session. The panel calls `mfa.enroll({ factorType: "totp" })`, shows the QR/secret once, then `mfa.challenge` + `mfa.verify` with the 6-digit code. On success the session is upgraded to `aal2`; the factor is verified.
3. **Later logins.** Password login returns `aal1`. The panel calls `getAuthenticatorAssuranceLevel()`; if `nextLevel === "aal2"`, it lists the verified TOTP factor, runs `mfa.challenge` + `mfa.verify`, and only then loads admin data.
4. **Token contents.** The access token carries an `aal` claim (`aal1`/`aal2`) and the user id. The UI may read it for display, but it is not the authorization decision.
5. **Server enforcement on every `admin-api` call.** The function runs with `verify_jwt = true`, then:
   - `auth.getUser()` to resolve the caller (Auth client using the publishable key, not the service role);
   - `auth.getAuthenticatorAssuranceLevel(jwt)` with the caller’s JWT, requiring `currentLevel === "aal2"`;
   - `hmx.is_admin(user.id)` with `is_active = true`;
   - reject with `401` for missing/invalid JWT and `403` for AAL1, non-admin, inactive, or revoked.
   The `admin-api` handler uses the service-role client only after these checks. The SPA never receives the service-role key and never queries `hmx` tables directly.
6. **Session handling.** Supabase JS persists the session in `localStorage` by default; strict CSP and no XSS are therefore critical. Prefer a short JWT/session lifetime, offer logout, and re-challenge MFA when the session drops back to `aal1`.
7. **Recovery.** If an admin loses the authenticator, another authorized operator removes the factor via the Supabase dashboard/service role and the admin re-enrolls. Document this runbook; do not add a public recovery endpoint.

**Remaining setup required (Phase 2/4, not done in this audit):**

- Create the dedicated Supabase project; confirm TOTP is enabled (enabled by default) and disable public signup.
- Create the first admin user manually and insert the matching `hmx.admin_users` row.
- Set Auth site URL / redirect allowlist to the Vercel domain; set `admin-api` CORS allowlist to that exact origin.
- Configure Vercel project, domain, `vercel.json` headers, and the two public `VITE_` variables.
- Verify the negative cases end to end: no JWT → `401`; valid `aal1` → `403`; valid `aal2` non-admin → `403`; valid `aal2` active admin → allowed.

- Roles: `admin` and optional `readonly`; MVP can ship `admin` only. Read-only role is a small addition if needed.
- Logout, session expiry, and “unauthorized” screens.

### 10.3 Views

| View | Contents |
|---|---|
| Login | Email/password; MFA challenge/enrollment; error states. |
| Dashboard | Event counts (24h/7d) by severity/kind/version; open support reports; active testers; latest releases; “new since last refresh”. |
| Events | Table with time, severity, kind, version, device, tester label; filters; cursor pagination; detail drawer. |
| Event detail | Sanitized message/stack trace, metadata, app/device info, tester label, delete action. |
| Support | Table with status/category/version/date; detail with description, diagnostics, status control, internal notes. |
| Testers | List with status/label/last seen; invite modal; approve/revoke/delete. |
| Releases | List by channel/version; create/edit metadata + artifacts (ABI, URL, SHA-256, size); activate/deactivate. |
| Audit | Append-only log with admin, action, target, timestamp; filters. |

### 10.4 Near-real-time behavior

- Polling every 10–15 seconds on Dashboard and Events when the tab is visible; pause when hidden.
- Use a `since`/cursor parameter to fetch only new rows; show a “new events” badge rather than re-rendering the table.
- Supabase Realtime is optional and not required; if added later, it must use authorized access and RLS-safe channels. Polling is the lazy, secure MVP.

### 10.5 Security

- CSP (served by Vercel via `admin/vercel.json`): `default-src 'self'; script-src 'self'; style-src 'self'; connect-src https://<project>.supabase.co; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'`.
- No inline scripts; no `eval`; no third-party analytics.
- Render all untrusted text with `textContent`/DOM text nodes; never `innerHTML`.
- Release notes rendered as plain text (preserve line breaks); no Markdown HTML.
- Strict CORS allowlist on `admin-api`.
- Rate-limit admin actions; audit all mutations.
- Do not display full tester tokens, invite codes after creation, service keys, or raw IPs.
- Use `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`.

### 10.6 Responsive UI

- CSS grid/flex; mobile navigation drawer; tables become cards on narrow screens.
- Minimum tested widths: 375 px (phone), 768 px (tablet), 1440 px (desktop).
- Dark theme matching the app’s visual language; accessible contrast and focus states.

### 10.7 Tests

- Vitest unit tests for API client, filters, cursor handling, formatters, and auth state (mock `fetch`).
- Build test: `npm run build` must succeed with no TypeScript errors.
- Manual/integration: login, MFA, non-admin 403, anon 401, event filters, support status update, invite/revoke, release upsert, audit entry, XSS payload rendered as text, CSP headers, responsive layout.
- Optional Playwright E2E only if it does not add excessive CI time; not required for MVP.

---

## 11. Proposed APK update workflow

### 11.1 Channels and distribution

| Channel | Distribution | Self-update | Signing |
|---|---|---|---|
| `foss` | GitHub Releases (APK + `SHA256SUMS`) | **Yes** | Existing release key (`e30ab…`) |
| `play` | Google Play (AAB) | **No** | Upload key locally; Play App Signing may re-sign for distribution |
| `fdroid` | F-Droid | **No** | F-Droid’s key (different) |

**Verified signing facts (source + existing APK):**

- `buildTypes.release.signingConfig` is global (`app/build.gradle.kts`); there are **no flavor-specific signing configs**. Any release APK built with the signing properties uses the same certificate, regardless of flavor.
- `play` and `foss` both use `applicationId = com.hmx.pdf`; F-Droid uses the same id but is built and signed externally by F-Droid.
- The existing signed Play universal APK is `versionCode 6`, `versionName 2.1.1`, `minSdk 23`, `targetSdk/compileSdk 36`, signed with certificate SHA-256 `e30ab37d83b52a0fd8a15da51a0d323107043daa9ac773fa44f17968348614d3`.
- That APK verifies with **v1 and v2** schemes; v3, v3.1, and v4 are **false**. v1/v2 are sufficient for `minSdk 23` and current Android; there is no key-rotation support, so the key must be preserved.
- Play App Signing status is **not verified** (requires Play Console access). If enabled, the installed Play app is signed by Google’s app-signing key, not the upload key.

**Why self-update is FOSS-only:**

1. **Play installs may not accept the GitHub APK.** With Play App Signing, the installed signer differs from the upload key; Android rejects an update signed by a different key (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Without Play App Signing the keys could match, but Play distribution is designed around Play-managed updates, and an in-app APK installer adds review/policy risk. (Policy interpretation, not verified against current Play policy text.)
2. **F-Droid installs never accept the GitHub APK.** F-Droid signs with its own key, so the signatures differ. F-Droid’s distribution model also expects F-Droid to deliver updates; a self-updater is at best redundant and at worst an inclusion-policy problem. (Policy interpretation, not verified against current F-Droid policy text.)
3. **FOSS/GitHub installs can update safely.** They are signed with the existing release key and share the application id, so a newer APK signed with the same key is a valid in-place update. The app still verifies package name, versionCode, `minSdk`, and signer before handing off to the system installer.
4. **Channel switching is not an update.** Because all channels share `com.hmx.pdf`, switching channels requires uninstalling (data loss) or a matching signer. The update UI must explain this; it must not offer a GitHub APK to a Play/F-Droid install.

**Limitations to document:** the release key must never be lost or rotated; v3/v4 signing and key rotation are not in place; Play App Signing status is unknown; F-Droid’s recipe is stale, points at a different repository, and is currently blocked by the duplicate `OfficeEngineFlavor` (see §12/§15); and self-update only works for users who installed the FOSS build from GitHub Releases. The comment in `app/build.gradle.kts` that F-Droid “updates like the GitHub build” because it shares the application id is misleading: F-Droid signs externally with its own key, so an in-place cross-channel update is rejected by Android.

### 11.2 Versioning

- Bump `versionCode` and `versionName` in `app/build.gradle.kts` for every release.
- Tag `v<versionName>` (for example `v2.2.0`).
- CI verifies the tag matches `versionName`; fail on mismatch.
- Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
- F-Droid’s `10 * code + n` scheme must remain monotonic; do not reuse version codes across channels.

### 11.3 CI release pipeline (proposed changes)

Current: `release` builds Play APK + AAB on tag/manual dispatch and uploads CI artifacts.

Proposed:

1. `build` job remains the gate: JVM tests, lint, debug APK.
2. Add `backend` and `admin` jobs (see §13/§14).
3. `release` job:
   - `needs: [build, backend, admin]`.
   - `environment: release` with required reviewers and environment-scoped signing secrets (operational change; requires approval).
   - Build `assemblePlayRelease` + `bundlePlayRelease`.
   - Build `assembleFossRelease` (ABI splits + universal).
   - Verify every APK: package name `com.hmx.pdf`, expected versionCode/versionName, signer SHA-256, `apksigner verify`.
   - Generate `SHA256SUMS` for FOSS APKs.
   - On tag only: create a GitHub Release with FOSS APKs + `SHA256SUMS` + release notes; do **not** upload the AAB.
   - Upload private CI artifacts: Play AAB, Play APKs, FOSS APKs, `mapping.txt` (not public).
   - Permissions: job-level `contents: write`; top-level stays `contents: read`.
4. Manual `workflow_dispatch` with `build_signed_release=true` continues to produce private artifacts only; it must not create a GitHub Release.
5. `release-guard` remains and is extended to FOSS release tasks if needed.

**Release metadata:** MVP uses the admin panel to create the `hmx.releases` + `hmx.release_artifacts` rows after the GitHub Release exists (paste URL/SHA-256/size). Optional later automation: a `release-publish` Edge Function protected by a CI token, called from the release job. Manual entry is smaller and auditable; automation can be added when releases become frequent.

### 11.4 Update-check flow (Android)

1. `UpdateCheckWorker` (daily, network constraint) or manual “Check for updates” calls `release-check` with channel, current versionCode, ABI, package.
2. If `update_available=false`, record last check and show “up to date” for manual checks.
3. If true:
   - Validate response: versionCode > current, versionName non-empty, `min_sdk <= Build.VERSION.SDK_INT`, HTTPS URL on an allowlisted host, SHA-256 64 hex, size > 0.
   - Show dialog with version, size, release notes, and Download/Later.
4. Download via `UpdateDownloadWorker`:
   - Check free space (`StatFs`) with a safety margin (APK size + 20 MiB or 1.5×).
   - Download to `cacheDir/updates/<versionCode>-<abi>.apk.part` with Range resume if the server supports it and the ETag/Last-Modified matches.
   - Stream to disk while computing SHA-256; enforce a maximum size (server size + small tolerance).
   - On completion, fsync, verify SHA-256, rename to `.apk`.
   - Foreground notification with progress and cancel; WorkManager cancellation deletes `.part`.
   - Retry on transient failures with backoff; do not retry forever.
5. Verify APK before install:
   - `PackageManager.getPackageArchiveInfo(path, flags)`:
     - package name == `com.hmx.pdf`
     - versionCode > installed versionCode
     - `minSdkVersion <= Build.VERSION.SDK_INT`
   - Signer:
     - API 28+: `GET_SIGNING_CERTIFICATES`; compare SHA-256 of the signing certificate to the app’s current signer and/or `BuildConfig.EXPECTED_SIGNER_SHA256`.
     - API 23–27: `GET_SIGNATURES` fallback; compare SHA-256 of the signature bytes.
   - If any check fails: delete the file, show a generic error, do not hand off to the installer.
6. Installer handoff:
   - `FileProvider.getUriForFile(context, "${applicationId}.provider", apkFile)`.
   - `Intent(ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")` with `FLAG_GRANT_READ_URI_PERMISSION | FLAG_ACTIVITY_NEW_TASK`.
   - If `REQUEST_INSTALL_PACKAGES` / unknown-sources is not granted, explain and open `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for the package.
   - The system installer shows the confirmation; **no silent install**.
7. After handoff, leave the APK in cache for the installer; clean old update files on next launch/check.

### 11.5 Edge cases

| Case | Behavior |
|---|---|
| Offline | Queue check/download via WorkManager; show “will retry when online”. |
| Server returns older/equal version | No update; ignore. |
| Already installed | No update; “up to date”. |
| `minSdk` too high | Show “not compatible with this device”; no download. |
| ABI mismatch | Use exact ABI artifact or universal fallback; otherwise show unsupported. |
| Download interrupted | Resume if possible; otherwise restart; verify checksum. |
| Checksum mismatch | Delete file; show error; retry allowed. |
| Signature mismatch | Delete file; show security error; do not install. |
| Package name/version mismatch | Delete file; show error. |
| Storage full | Stop before download; show required space; clean old files. |
| User cancels | Cancel WorkManager; delete `.part`; no notification residue. |
| Installer unavailable | Show error with manual instructions. |
| Unknown sources blocked | Explain and deep-link to settings; user can retry. |
| App already updated by Play/F-Droid | Channel policy prevents self-update; show managed-by message. |
| Failed install | System leaves the old app installed; show error; keep current data. |
| Rollback | Not automatic. Document manual reinstall of a previous signed APK; Android may block downgrades. |

### 11.6 Signing continuity

- Preserve the existing keystore and certificate; never regenerate. There is no v3/v4 key-rotation setup, so a lost key permanently breaks updates for existing installs.
- The FOSS APK must be signed with the same key as the currently installed FOSS/GitHub build. The app verifies the new APK signer against its own signer and the embedded fingerprint.
- Play builds must not self-update. If Play App Signing is enabled, the installed signer differs from the upload key, so a GitHub APK cannot update a Play install (and vice versa).
- F-Droid builds are signed by F-Droid; self-update is disabled.
- CI already recomputes the keystore certificate SHA-256 and fails when a release APK signer differs (`.github/workflows/android.yml:154-182`); Phase 5 extends this to the FOSS artifacts.
- Document the channel/signature matrix in the final report and in the in-app update UI.

### 11.7 Release notes and notifications

- Release notes come from the Fastlane changelog or the GitHub Release body.
- In-app dialog shows version, size, notes, and actions.
- Optional notification when a background check finds an update; requires `POST_NOTIFICATIONS` on Android 13+. If denied, keep the in-app badge/dialog.
- No notification spam: at most one per new versionCode.

---

## 12. Dependencies and migration risks

| Change / dependency | Why | Risk | Mitigation |
|---|---|---|---|
| New Supabase project | Backend | Cost, region, org ownership, free-tier limits | User approval; dedicated project; document region/plan. |
| Supabase Auth + TOTP MFA | Admin login | Lockout, enrollment complexity | Manual admin provisioning; recovery procedure; test enrollment. |
| `pg_cron` | Retention | Extension availability/plan | Enable in project; fallback to scheduled Edge Function if unavailable. |
| Edge Functions (Deno) | API | Cold starts, runtime limits, CORS | Keep handlers small; cache release-check; strict timeouts. |
| `@supabase/supabase-js` (admin only) | Auth/MFA | Supply-chain risk | Lockfile; npm audit; no service-role key. |
| Vite/TypeScript (admin) | Static SPA | Build/tooling maintenance | Minimal dependencies; production build in CI. |
| Vercel (admin hosting) | Static deployment + headers | Misconfigured headers/CORS, public URL mistaken for access control | `vercel.json` CSP/rewrite; auth always server-enforced; only public `VITE_` env vars; verify deployed headers. |
| `buildConfig = true` | Public config fields | Accidental secret inclusion | Only public values; review diff; secret scan. |
| `HMXApplication` + crash handler | Crash capture | Swallowing crashes / startup cost | Chain default handler; consent gate; unit tests. |
| Android Keystore AES/GCM | Token at rest | API 23 edge cases, key invalidation | Fallback to “diagnostics disabled” rather than plaintext; test on API 23/28/35. |
| `REQUEST_INSTALL_PACKAGES` (FOSS only) | Installer handoff | Play policy if leaked into Play | Flavor-specific manifest; verify merged Play manifest in CI. |
| WorkManager (existing) | Queue/update | Battery/backoff behavior | Network constraints; exponential backoff; unique work. |
| R8/ProGuard | Release build | Serialization/reflection breakage | Test release build; add keep rules only as needed. |
| Stack trace obfuscation | Diagnostics readability | Low-value traces | Keep `mapping.txt` private; document; optional retrace later. |
| VersionCode bump | Release | F-Droid scheme conflicts; downgrade blocks | Monotonic bump; channel matrix; CI tag/version check. |
| GitHub Release publication | FOSS distribution | Token permissions, accidental release | Job-level `contents: write`; tag-only; environment protection. |
| `release` environment hardening | Release safety | Unreviewed releases | Required reviewers, branch/tag policy, environment secrets (requires approval). |
| F-Droid recipe | F-Droid distribution | Recipe points at a different repo, stale commit/version; duplicate `OfficeEngineFlavor`; separate signer | Out of scope unless approved; do not enable self-update; document blocker (source-level; build NOT RUN). |
| Play policy | Distribution | `REQUEST_INSTALL_PACKAGES`, data safety | No installer permission in Play; update Privacy/Data Safety. |
| Privacy policy | Legal/trust | Current text says no crash reporting | Update `PRIVACY.md` and in-app consent before enabling any collection. |
| Backup rules | Data leakage | Wrong root element; new files not excluded | Fix/exclude; test restore behavior. |
| No local Docker/Supabase CLI/Android SDK/emulator | Verification | Cannot run full local tests | CI with Docker/emulator + remote staging; report NOT RUN honestly. |
| No branch protection | Git safety | Accidental main push | Process discipline; recommend protection after merge approval. |

---

## 13. Detailed implementation plan for Phases 2–5

Each phase is a separate, reviewable set of commits on `feature/hmxpdf-diagnostics-admin-updates`. No phase merges to `main`. Each phase ends with a report and an explicit PASS/FAIL; the next phase waits for user approval.

### Phase 2 — Secure backend and Supabase foundation

**Goal:** a deployed, tested backend that no anonymous client can read directly.

Tasks:

1. **Project setup (blocked on approval):**
   - Create/choose a dedicated Supabase project (org, region, plan).
   - Disable public signup; configure Auth; create the first admin user; enroll TOTP.
   - Enable `pg_cron`; configure project secrets.
2. **Migrations:**
   - `supabase/config.toml`
   - Schema/enums/tables/indexes.
   - RLS enable/revoke/grant; `is_admin`, `consume_rate_limit`, `run_retention`.
   - Optional dashboard aggregate SQL/views (service-only).
3. **Edge Functions:**
   - `_shared/` modules: CORS, auth, validation, redaction, rate limit, response, Supabase clients.
   - `tester-enroll`, `tester-status`, `tester-settings`, `tester-delete`.
   - `diagnostics-submit`, `support-submit`.
   - `release-check`.
   - `admin-api` action router.
4. **Tests:**
   - SQL/RLS tests: anon/authenticated cannot select/insert/update/delete; service role can; admin function; rate limit; retention.
   - Deno unit tests: validation, redaction, token hashing, idempotency, error mapping.
   - Integration tests against local Supabase stack (CI) and staging (manual).
   - Malicious payloads: oversized, wrong types, forbidden keys, invalid/revoked token, expired invite, replay, SQL-ish strings, HTML/script strings.
5. **Documentation:**
   - Deployment steps, secret names (not values), project ref, function URLs, admin bootstrap.
   - `docs/HMXPDF_PHASE2_BACKEND_REPORT.md` with PASS/FAIL.

**Exit criteria:** see §14.2. Stop for approval.

### Phase 3 — Android diagnostics and support reporting

**Goal:** consent-based diagnostics that never leak document data and work offline.

Tasks:

1. **Foundation:** `buildConfig`, public config fields, `HMXApplication`, `CrashReporter`, Keystore token store, install identity, consent prefs.
2. **Data path:** redactor, payload models, queue, API client, upload worker, retry/dedupe/size caps.
3. **UI:** Settings row/page, enrollment, auto-report toggle, manual report form + preview + approval, queue status, delete-my-data.
4. **Hooks:** instrument the short list in §9.11; ensure reporting cannot change existing error handling.
5. **Manifest/resources:** Application name, backup exclusions, strings, privacy link; no installer permission yet.
6. **Tests:**
   - JVM: consent default off, disable clears auto queue, queue persistence/caps/dedupe, redaction, payload limits, forbidden fields, retry classification, metadata allowlist.
   - Instrumentation (if emulator CI is available): consent UI, preview, queue persistence, Keystore round-trip.
   - Existing PDF tests + debug builds for `play`/`foss`; F-Droid build only if the existing duplicate-class blocker is resolved/approved.
7. **Report:** `docs/HMXPDF_PHASE3_ANDROID_REPORT.md` with PASS/FAIL and NOT RUN items.

**Exit criteria:** see §14.3. Stop for approval.

### Phase 4 — Private web admin panel

**Goal:** a secure, responsive admin console with server-side authorization and audit.

Tasks:

1. Scaffold `admin/` Vite + TypeScript; auth/MFA; API client; router; layout; CSP/headers in `admin/vercel.json`.
2. Views: dashboard, events + filters + detail, support + status/notes, testers + invites/approve/revoke/delete, releases + artifacts, audit.
3. Polling for near-real-time; pause when hidden; cursor pagination.
4. Safe rendering (textContent), responsive CSS, error/empty/loading states.
5. Tests: Vitest unit tests, production build, manual auth boundary/XSS/responsive checks against staging.
6. **Vercel deployment:** create the Vercel project (GitHub integration with Root Directory `admin`, or CLI), set the two public `VITE_` env vars, deploy the static `admin/dist/`, configure the production domain, and verify the `vercel.json` SPA rewrite + security headers on the deployed URL. No serverless functions and no secrets in Vercel env.
7. Report: `docs/HMXPDF_PHASE4_ADMIN_REPORT.md` with PASS/FAIL, deployed URL, and header/auth evidence.

**Exit criteria:** see §14.4. Stop for approval.

### Phase 5 — APK update system and end-to-end validation

**Goal:** safe FOSS self-update with checksum/signature verification and no silent install.

Tasks:

1. Release metadata + `release-check` integration (backend already has the function; add admin form if not done in Phase 4).
2. Android update check, dialog, download worker, checksum/signature verification, installer handoff, notifications, edge cases.
3. Manifest/FileProvider/backup/permission changes (FOSS only).
4. CI: FOSS release build, checksums, GitHub Release publication, tag/version verification, job dependencies, private mapping artifact.
5. Tests: JVM version/checksum/verification logic; instrumentation/emulator install handoff; fresh install + update path; offline/failed/already-installed/older cases; PDF regression; secret/privacy scans; diff review.
6. Final report: `docs/HMXPDF_DIAGNOSTICS_ADMIN_UPDATE_FINAL_REPORT.md` with all phases, commits, commands/results, limitations, deployment steps, artifacts.
7. Final gate: clean branch, CI on exact commit, full diff vs `main`, no secrets/data/unintended files, target not stale; present merge plan and request explicit approval.

**Exit criteria:** see §14.5. Do not merge until the user approves the final gate.

---

## 14. Test strategy and explicit PASS criteria

### 14.1 Test layers

| Layer | Tooling | Runs where |
|---|---|---|
| Android JVM unit tests | JUnit 4, `androidx.test:core` | Local (if Gradle/SDK available) and CI. |
| Android instrumentation | Compose UI test, AndroidJUnitRunner | GitHub Actions emulator (local emulator unavailable). |
| Backend SQL/RLS | `psql`/Supabase CLI, plain SQL assertions | Local Supabase stack/CI and staging. |
| Edge Functions | Deno tests + HTTP integration | CI/local Supabase; staging for manual. |
| Admin | Vitest + production build | Local/CI. |
| Release artifact | `apksigner`, `aapt2`, `sha256sum`, `jarsigner` | CI and local where tools exist. |
| Device/emulator E2E | `adb`, system installer, manual steps | CI emulator + physical device. |
| Security/privacy | `grep` secret scans, dependency audit, diff review | CI/local. |

**Rule:** a test that cannot run is reported as **NOT RUN**, never PASS. A build success is not a phase PASS by itself.

### 14.2 Phase 2 PASS criteria

- [ ] Migrations apply cleanly from an empty database (`supabase db reset` or equivalent) with no manual steps.
- [ ] `anon` and `authenticated` cannot `select`, `insert`, `update`, or `delete` any `hmx` table (direct PostgREST/SQL attempts fail).
- [ ] `service_role` can perform the required operations.
- [ ] `admin-api` rejects missing/invalid JWT, non-admin users, inactive admins, and AAL1 sessions; accepts an active AAL2 admin.
- [ ] Tester enrollment rejects unknown/expired/revoked/exhausted invites; valid invite returns a token once; token hash is stored, plaintext is not.
- [ ] `tester-settings` updates consent state; `diagnostics-submit` rejects automatic events when the server flag is false.
- [ ] Diagnostics/support endpoints reject missing/invalid/revoked tokens, malformed JSON, oversized bodies, wrong types, forbidden keys, and replayed event IDs.
- [ ] Rate limits return `429` after the configured threshold; `Retry-After` is present.
- [ ] Redaction removes emails/tokens/paths/URL queries/long blobs; tests cover each.
- [ ] Retention deletes old rows and keeps recent rows.
- [ ] Admin mutations write audit entries; audit entries do not contain secrets/tokens.
- [ ] No service-role key, database password, or tester token appears in the repo, admin bundle, logs, or reports.
- [ ] Phase 2 report exists with commands, actual results, and PASS/FAIL.

### 14.3 Phase 3 PASS criteria

- [ ] Automatic reporting defaults to **off**; no network request occurs before explicit consent.
- [ ] Enabling/disabling consent works; disabling clears queued automatic events.
- [ ] Manual report preview shows the exact redacted payload; cancel sends nothing; approval sends/queues.
- [ ] Offline queue survives process restart; retry/backoff works; duplicate `client_event_id` is not uploaded twice.
- [ ] Queue caps and payload size limits are enforced; oversized events are truncated/rejected safely.
- [ ] Redaction tests pass; no PDF content, document names, passwords, tokens, emails, paths, or full logcat in payloads.
- [ ] Crash handler chains to the default handler (app still crashes normally) and only captures when consent is enabled.
- [ ] Enrollment/status/delete flows work against staging (or are reported NOT RUN with a blocker).
- [ ] `assemblePlayDebug` and `assembleFossDebug` succeed; lint passes; existing JVM tests pass.
- [ ] Existing PDF workflows still pass their tests; manual PDF smoke test is reported PASS only if actually run.
- [ ] Phase 3 report exists with PASS/FAIL and NOT RUN items.

### 14.4 Phase 4 PASS criteria

- [ ] Admin production build succeeds with no TypeScript errors.
- [ ] Login + TOTP MFA works; logout/session expiry works.
- [ ] `admin-api` rejects missing/invalid JWT, valid AAL1 sessions, non-admin users, and inactive admins (server-side, not just hidden in UI).
- [ ] Public signup is disabled; admin account creation and `admin_users` role assignment are manual only.
- [ ] Deployed Vercel URL serves the SPA rewrite and the `vercel.json` CSP/security headers; no source maps or secrets in the bundle.
- [ ] Dashboard, event feed, filters, pagination, detail, support status/notes, tester invite/approve/revoke/delete, release upsert, and audit views work.
- [ ] Every mutation writes an audit entry.
- [ ] XSS payloads render as text; CSP has no inline-script allowance; no `innerHTML` with untrusted data.
- [ ] Responsive layout works at 375/768/1440 px; loading/empty/error states are handled.
- [ ] No service-role key or admin token in the bundle; no source maps in production.
- [ ] Phase 4 report exists with PASS/FAIL.

### 14.5 Phase 5 PASS criteria

- [ ] `release-check` returns the correct latest active FOSS release for ABI/version; returns no update for equal/older versions; `play`/`fdroid` return managed-by messages.
- [ ] Download resumes after interruption (or safely restarts), cancels cleanly, retries transient failures, and handles low storage.
- [ ] SHA-256 mismatch, wrong package, wrong versionCode, wrong signer, and incompatible `minSdk` are rejected before installer handoff.
- [ ] Installer handoff opens the system installer with the correct FileProvider URI; no silent install; unknown-sources guidance works.
- [ ] Fresh install of the new signed APK succeeds on a supported device/emulator.
- [ ] Update from the previous signed build to the new build succeeds and preserves app data (where expected).
- [ ] Already-installed/older/offline/failed-download/failed-install cases behave safely.
- [ ] FOSS APK is signed with the existing certificate; checksum file matches; Play AAB is signed and not debug-signed.
- [ ] Existing PDF regression tests pass; manual smoke test reported PASS only if run.
- [ ] Secret/privacy scans find no keys, tokens, tester data, or unintended files.
- [ ] Full diff vs `main` reviewed; CI passes on the exact commit; target branch not stale.
- [ ] Final report exists with phases, commits, commands/results, limitations, deployment steps, and artifacts.

### 14.6 Final verification matrix (to execute in Phase 5)

| Check | Command / method | Expected | Status |
|---|---|---|---|
| Backend migrations | `supabase db reset` / CI | Success | NOT RUN |
| RLS/security tests | SQL/Deno tests | All pass | NOT RUN |
| Android unit tests | `./gradlew testPlayDebugUnitTest :pdf-core:test :ocr-core:test` | Success | NOT RUN |
| Android instrumentation | `./gradlew connectedPlayDebugAndroidTest` on emulator | Success | NOT RUN |
| Android lint | `./gradlew lintPlayDebug` | Success | NOT RUN |
| Debug build | `./gradlew assemblePlayDebug assembleFossDebug` | Success | NOT RUN |
| Signed release | `./gradlew assemblePlayRelease bundlePlayRelease assembleFossRelease` | Success | NOT RUN |
| APK signature | `apksigner verify --print-certs` | Expected SHA-256 | NOT RUN |
| APK checksum | `sha256sum -c SHA256SUMS` | Match | NOT RUN |
| Admin tests | `npm ci && npm test` in `admin/` | Success | NOT RUN |
| Admin build | `npm run build` | Success | NOT RUN |
| Admin deploy | `curl -I https://<vercel-domain>` | SPA rewrite + CSP/security headers | NOT RUN |
| Fresh install | `adb install` on clean emulator/device | Success | NOT RUN |
| Update path | Install old → update → verify version/data | Success | NOT RUN |
| PDF regression | Existing tests + manual smoke | Success | NOT RUN |
| Secret scan | `grep`/GitHub secret scanning | No secrets | NOT RUN |
| Diff review | `git diff main...HEAD` | Only intended changes | NOT RUN |

---

## 15. Open questions and genuine blockers

These require user decisions before or during Phase 2. Recommended defaults are included so work can proceed without stalling, but the user must confirm.

1. **Supabase organization/project/region/plan.** The only visible organization is not HMXPDF-specific, and the only project is unrelated and inactive. Should I create a new dedicated project in that organization, or a new organization? Recommended: new project `hmxpdf-prod`, region `ap-south-1` (Mumbai), free tier initially. **Blocker for Phase 2.** *Correction (2026-10-24): the live project was created in `ap-northeast-2`; the `ap-south-1` recommendation was based on the stale/inactive project observed during the audit. The project was not moved.*
2. **Staging vs production.** Two Supabase projects (staging + prod) or one prod + local CI? Recommended: two if the plan allows; otherwise one prod with CI-local tests and a documented manual staging procedure. **Affects test strategy.**
3. **Admin panel hosting — decided: Vercel** (static Vite build). Remaining Phase 4 tasks: create the Vercel project, set the public `VITE_` env vars, configure the production domain, and verify `vercel.json` headers/rewrite. The URL is public and is not access control. **No longer a blocker; deployment work remains in Phase 4.**
4. **Admin identity and MFA.** Which email(s) become admins? Is TOTP MFA mandatory? Recommended: mandatory TOTP, manual provisioning, no public signup. **Blocker for Phase 2/4.**
5. **F-Droid diagnostics policy.** F-Droid treats crash/telemetry reporting as an anti-feature unless clearly opt-in. Recommended: disable diagnostics in `fdroid` builds by default (or entirely) and do not self-update. Confirm. **Affects Phase 3.**
6. **Play App Signing status.** Is Play App Signing enabled? This determines whether the Play-installed signer differs from the upload key. Recommended: self-update disabled for `play` regardless. **Affects Phase 5.**
7. **Tester enrollment model.** Invite-only for all reports, or public manual reports with anonymous tokens? Recommended: invite-only for MVP; admin can create a broad invite if needed. **Affects schema/UX.**
8. **Retention periods.** Confirm 90 days diagnostics / 180 days resolved support / 365 days audit. **Affects Phase 2.**
9. **Contact email and privacy policy owner.** The manual report has an optional email field; the privacy policy needs a contact. **Affects Phase 3/legal.**
10. **Stack trace deobfuscation.** Release traces are obfuscated. Is raw sanitized tracing acceptable for MVP, with `mapping.txt` kept private? Recommended: yes; optional retrace later. **Affects Phase 4/5 usefulness.**
11. **Release metadata automation.** Manual admin entry after GitHub Release, or a CI token calling a publish endpoint? Recommended: manual MVP; automate later. **Affects Phase 5 scope.**
12. **Release environment hardening.** May I add required reviewers, a tag/branch policy, and environment-scoped signing secrets to the existing `release` environment? This changes repository settings and requires explicit authorization. **Affects Phase 5.**
13. **Device/emulator for Phase 5.** Is a physical device available, or should I rely on a GitHub Actions emulator? Recommended: CI emulator + one physical device if possible. **Affects E2E verification.**
14. **Existing P0/P1 issues.** The F-Droid duplicate `OfficeEngineFlavor` blocks F-Droid builds (source-level; build NOT RUN); FileProvider roots are missing for decrypted PDFs/scans; backup rules use the wrong root element. Fix these within this project or keep them out of scope? Recommended: fix only what blocks this project's verification (F-Droid build if required), and report the rest as pre-existing. **Affects Phase 3/5.**
15. **Branch protection.** Should I enable protection on `main` after the final merge? Not during the audit. Recommended: yes, after approval.
16. **Translations.** New strings are English-only initially with system fallback. Is that acceptable, or are translations required in this project? **Affects Phase 3/4 polish.**
17. **Forced updates.** Is a minimum-supported-version/forced-update policy required? Recommended: no forced updates in MVP; only optional update prompts. **Affects schema/UI.**
18. **Admin roles.** Admin-only MVP, or also a read-only role? Recommended: admin-only; add `readonly` later if needed. **Affects Phase 4.**
19. **Rate-limit thresholds.** Confirm the proposed defaults in §8.1 or provide different limits. **Affects Phase 2.**
20. **Data residency/legal.** Any requirement to keep data in a specific region or delete on request within a specific period? Recommended: `ap-south-1`, documented deletion flow. **Affects Phase 2.** *Correction (2026-10-24): the live project region is `ap-northeast-2`; see item 1.*

---

## 16. File-level change plan

> No files below have been created or modified yet, except this report. Paths are proposals.

> **Phase 2 update (2026-10-24):** the feature-flag backend files listed in Appendix D were actually created and deployed. The rest of this file-level plan remains a proposal for Phases 3–5. The broader Phase 2 list in §13 (diagnostics/support/retention endpoints) was narrowed by the Phase 2 implementation prompt; those endpoints remain Phase 3 work and were **not** implemented.

### 16.1 Additions

**Backend (`supabase/`)**

| Path | Purpose | Phase |
|---|---|---|
| `supabase/config.toml` | Project/function configuration, JWT settings. | 2 |
| `supabase/migrations/20261024000100_hmx_schema.sql` | Schema, enums, tables, indexes. | 2 |
| `supabase/migrations/20261024000200_hmx_rls.sql` | RLS enable/revoke/grants. | 2 |
| `supabase/migrations/20261024000300_hmx_functions.sql` | `is_admin`, rate limit, retention. | 2 |
| `supabase/migrations/20261024000400_hmx_views.sql` | Optional dashboard aggregates. | 2 |
| `supabase/functions/_shared/cors.ts` | Strict CORS. | 2 |
| `supabase/functions/_shared/auth.ts` | Tester token/admin JWT checks. | 2 |
| `supabase/functions/_shared/validate.ts` | Payload validation. | 2 |
| `supabase/functions/_shared/redact.ts` | Server-side redaction. | 2 |
| `supabase/functions/_shared/respond.ts` | Error/response helpers. | 2 |
| `supabase/functions/_shared/rate_limit.ts` | Rate-limit wrapper. | 2 |
| `supabase/functions/_shared/supabase.ts` | Service-role client. | 2 |
| `supabase/functions/tester-enroll/index.ts` | Invite redemption/token issuance. | 2 |
| `supabase/functions/tester-status/index.ts` | Tester status. | 2 |
| `supabase/functions/tester-settings/index.ts` | Consent/auto-report settings update. | 2 |
| `supabase/functions/tester-delete/index.ts` | Data deletion. | 2 |
| `supabase/functions/diagnostics-submit/index.ts` | Event ingestion. | 2 |
| `supabase/functions/support-submit/index.ts` | Manual reports. | 2 |
| `supabase/functions/release-check/index.ts` | Update metadata. | 2/5 |
| `supabase/functions/admin-api/index.ts` | Admin action router. | 2/4 |
| `supabase/tests/rls.sql` | RLS/authorization assertions. | 2 |
| `supabase/tests/functions.sql` | Rate limit/retention assertions. | 2 |
| `supabase/functions/_shared/*_test.ts` | Deno unit tests. | 2 |

**Admin (`admin/`)**

| Path | Purpose | Phase |
|---|---|---|
| `admin/package.json`, `package-lock.json` | Vite/TypeScript/Vitest/Supabase JS. | 4 |
| `admin/tsconfig.json`, `admin/vite.config.ts` | Build config. | 4 |
| `admin/index.html` | App shell. | 4 |
| `admin/src/main.ts`, `router.ts`, `api.ts`, `auth.ts`, `types.ts`, `ui.ts` | SPA core. | 4 |
| `admin/src/views/login.ts` | Login/MFA. | 4 |
| `admin/src/views/dashboard.ts` | Dashboard. | 4 |
| `admin/src/views/events.ts` | Event feed/detail. | 4 |
| `admin/src/views/support.ts` | Support reports. | 4 |
| `admin/src/views/testers.ts` | Tester management. | 4 |
| `admin/src/views/releases.ts` | Release metadata. | 4 |
| `admin/src/views/audit.ts` | Audit log. | 4 |
| `admin/src/styles.css` | Responsive dark UI. | 4 |
| `admin/vercel.json` | Vercel static config: SPA rewrite + CSP/security headers. | 4 |
| `admin/tests/*.test.ts` | Vitest tests. | 4 |
| `admin/README.md` | Local dev and Vercel deployment. | 4 |

**Android**

| Path | Purpose | Phase |
|---|---|---|
| `app/src/main/java/com/hmx/pdf/HMXApplication.kt` | Application/crash install. | 3 |
| `app/src/main/java/com/hmx/pdf/diagnostics/DiagnosticsConsent.kt` | Consent state. | 3 |
| `app/src/main/java/com/hmx/pdf/diagnostics/DiagnosticsSettings.kt` | Prefs/install ID. | 3 |
| `app/src/main/java/com/hmx/pdf/diagnostics/InstallationIdentity.kt` | UUID/token lifecycle. | 3 |
| `app/src/main/java/com/hmx/pdf/diagnostics/TokenCipher.kt` | Keystore AES/GCM. | 3 |
| `app/src/main/java/com/hmx/pdf/diagnostics/DiagnosticsRedactor.kt` | Redaction. | 3 |
| `app/src/main/java/com/hmx/pdf/diagnostics/DiagnosticsQueue.kt` | Durable queue. | 3 |
| `app/src/main/java/com/hmx/pdf/diagnostics/DiagnosticsPayload.kt` | API models. | 3 |
| `app/src/main/java/com/hmx/pdf/diagnostics/DiagnosticsApi.kt` | HTTP client. | 3 |
| `app/src/main/java/com/hmx/pdf/diagnostics/DiagnosticsReporter.kt` | Facade/hooks. | 3 |
| `app/src/main/java/com/hmx/pdf/diagnostics/DiagnosticsUploadWorker.kt` | WorkManager upload. | 3 |
| `app/src/main/java/com/hmx/pdf/diagnostics/CrashReporter.kt` | Uncaught handler. | 3 |
| `app/src/main/java/com/hmx/pdf/update/UpdateChannel.kt` | Channel policy. | 5 |
| `app/src/main/java/com/hmx/pdf/update/UpdateApi.kt` | Release-check client. | 5 |
| `app/src/main/java/com/hmx/pdf/update/UpdateChecker.kt` | Version/ABI logic. | 5 |
| `app/src/main/java/com/hmx/pdf/update/UpdateDownloadWorker.kt` | Download/resume/verify. | 5 |
| `app/src/main/java/com/hmx/pdf/update/ApkVerifier.kt` | Package/signature checks. | 5 |
| `app/src/main/java/com/hmx/pdf/update/UpdateInstaller.kt` | FileProvider/installer intent. | 5 |
| `app/src/main/java/com/hmx/pdf/update/UpdateStateStore.kt` | Last check/state. | 5 |
| `app/src/main/java/com/hmx/pdf/ui/screen/DiagnosticsSettingsScreen.kt` | Settings UI. | 3 |
| `app/src/main/java/com/hmx/pdf/ui/screen/ReportProblemScreen.kt` | Manual report/preview. | 3 |
| `app/src/main/java/com/hmx/pdf/ui/screen/UpdateDialog.kt` | Update UI. | 5 |
| `app/src/test/java/com/hmx/pdf/diagnostics/*Test.kt` | JVM tests. | 3 |
| `app/src/test/java/com/hmx/pdf/update/*Test.kt` | JVM tests. | 5 |
| `app/src/androidTest/java/com/hmx/pdf/diagnostics/*Test.kt` | Instrumentation (if CI emulator). | 3/5 |

**CI/docs**

| Path | Purpose | Phase |
|---|---|---|
| `.github/workflows/backend.yml` | Migrations/RLS/Deno tests. | 2 |
| `.github/workflows/admin.yml` | Admin tests/build. | 4 |
| `.github/workflows/android-instrumentation.yml` | Emulator tests. | 3/5 |
| `docs/HMXPDF_PHASE2_BACKEND_REPORT.md` | Phase 2 report. | 2 |
| `docs/HMXPDF_PHASE3_ANDROID_REPORT.md` | Phase 3 report. | 3 |
| `docs/HMXPDF_PHASE4_ADMIN_REPORT.md` | Phase 4 report. | 4 |
| `docs/HMXPDF_DIAGNOSTICS_ADMIN_UPDATE_FINAL_REPORT.md` | Final report. | 5 |

### 16.2 Modifications

| Path | Change | Phase |
|---|---|---|
| `app/build.gradle.kts` | `buildConfig = true`; public fields; maybe test deps. | 3/5 |
| `app/src/main/AndroidManifest.xml` | `android:name=".HMXApplication"`. | 3 |
| `app/src/foss/AndroidManifest.xml` | `REQUEST_INSTALL_PACKAGES` (FOSS only). | 5 |
| `app/src/main/res/xml/file_paths.xml` | `cache/updates/` path. | 5 |
| `app/src/main/res/xml/backup_rules.xml` | Exclude diagnostics/updates/token prefs; fix root element if in scope. | 3/5 |
| `app/src/main/res/values/strings.xml` | Diagnostics/support/update strings. | 3/5 |
| `app/proguard-rules.pro` | Serialization/reflection keeps if release build requires. | 3/5 |
| `app/src/main/java/com/hmx/pdf/ui/screen/SettingsScreen.kt` | Diagnostics & Support row/page; update row. | 3/5 |
| `app/src/main/java/com/hmx/pdf/ui/navigation/DocsNavGraph.kt` | Report-problem route. | 3 |
| `app/src/main/java/com/hmx/pdf/ui/DocsApp.kt` | Optional update check trigger/state. | 5 |
| `app/src/main/java/com/hmx/pdf/ui/viewmodel/PdfViewerViewModel.kt` | Error hooks only (non-behavioral). | 3 |
| `app/src/main/java/com/hmx/pdf/office/OfficeEngineClient.kt` | Error hooks only. | 3 |
| `app/src/main/java/com/hmx/pdf/utils/UniversalDocumentConverter.kt` | Error hooks only. | 3 |
| `app/src/main/java/com/hmx/pdf/ui/viewmodel/DocxEditorViewModel.kt` | Error hooks only. | 3 |
| `app/src/main/java/com/hmx/pdf/ui/viewmodel/ScanViewModel.kt` | Error hooks only. | 3 |
| `.github/workflows/android.yml` | Backend/admin dependencies; FOSS release; checksums; GitHub Release; artifact verification. | 2–5 |
| `PRIVACY.md` | Disclose consent-based diagnostics and network use. | 3 |
| `readme.md` | Update download/release links and tester program. | 5 |
| `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` | Release notes. | 5 |
| `fdroid/com.chethan616.clearpdf.yml` | Only if F-Droid distribution is in scope; update source/commit/version. | 5 (conditional) |

### 16.3 Deletions

**None planned.** Do not delete existing PDF/Office functionality, tests, resources, or the untracked audit file. If the F-Droid duplicate `OfficeEngineFlavor` is fixed, the deletion/move must be minimal and separately justified.

---

## Appendix A — Key evidence and line references

| Fact | Evidence |
|---|---|
| App version | `app/build.gradle.kts:63-73` |
| Signing config | `app/build.gradle.kts:9-25, 75-105` |
| Flavors | `app/build.gradle.kts:115-144` |
| F-Droid version scheme | `app/build.gradle.kts:27-31, 131-137` |
| Splits | `app/build.gradle.kts:156-168` |
| Release minify | `app/build.gradle.kts:100-103` |
| No BuildConfig | `app/build.gradle.kts:106-108`; no `BuildConfig` references |
| Manifest/application | `app/src/main/AndroidManifest.xml:36-48` |
| FileProvider | `app/src/main/AndroidManifest.xml:178-187`; `app/src/main/res/xml/file_paths.xml` |
| FOSS permissions | `app/src/foss/AndroidManifest.xml:12-16` |
| F-Droid permissions | `app/src/fdroid/AndroidManifest.xml:9-13` |
| Backup rules | `app/src/main/res/xml/backup_rules.xml` |
| Existing downloader | `app/src/main/java/com/hmx/pdf/office/OfficeEngineDownloadWorker.kt` |
| Durable journal | `app/src/main/java/com/hmx/pdf/data/repository/SaveRecovery.kt` |
| Settings sub-pages | `app/src/main/java/com/hmx/pdf/ui/screen/SettingsScreen.kt:655-668` |
| Version display | `app/src/main/java/com/hmx/pdf/ui/screen/SettingsScreen.kt:215-217` |
| Settings nav | `app/src/main/java/com/hmx/pdf/ui/navigation/DocsNavGraph.kt` |
| CI build/release | `.github/workflows/android.yml` |
| Signer verification | `.github/workflows/android.yml:154-199` |
| Privacy claim | `PRIVACY.md:3` |
| F-Droid recipe | `fdroid/com.chethan616.clearpdf.yml` |
| Existing changelog | `fastlane/metadata/android/en-US/changelogs/6.txt` |

---

## Appendix B — Assumptions

1. Supabase is the chosen backend and the user will authorize a dedicated project.
2. The existing release keystore remains valid and available; no key regeneration.
3. GitHub Releases is acceptable for FOSS APK distribution; Google Play and F-Droid remain separate channels.
4. Admin users are few and can be provisioned manually; TOTP MFA is acceptable.
5. Tester enrollment is invite-based for the MVP.
6. The diagnostics feature is consent-based and disabled by default.
7. The FOSS channel is the only self-updating channel; Play/F-Droid self-update is disabled.
8. Retention periods in §7.5 are acceptable defaults.
9. English-only new strings are acceptable initially.
10. Stack traces may remain obfuscated in the MVP; mapping is private.
11. A GitHub Actions emulator and/or a physical device can be used for Phase 5 verification.
12. No unrelated PDF/Office behavior changes are required.

---

## Appendix C — Phase 1 status

| Criterion | Result |
|---|---|
| Repository/remote/default branch verified | PASS |
| Feature branch created from current `main` | PASS |
| Existing untracked work preserved | PASS |
| Read-only audit completed | PASS |
| No app source/migrations/Edge Functions/UI/admin/updater changes | PASS |
| No commits/pushes/repository-setting changes | PASS |
| Audit report written on feature branch | PASS |
| Final independent verification pass | PASS (repository/API/artifact rechecked; corrections applied) |
| Vercel hosting decision reflected in report | PASS |
| Runtime tests/builds claimed as verified | None claimed; **NOT RUN** |
| Phase 2 started | **Yes — after Phase 1 approval; see Appendix D** |

**Phase 1 audit deliverable: PASS.** Implementation phases remain **NOT STARTED** and require explicit user approval.

---

## Appendix D — Phase 2 implementation status (2026-10-24)

**Scope actually implemented:** feature flags, deterministic gradual rollout, tester/segment targeting, admin allowlist + AAL2 enforcement, public configuration delivery, and global/per-flag kill switches. PDF upload, diagnostics ingestion, support reports, retention jobs, the Android client, and the Vercel panel were **not** implemented.

**Supabase project:** `tchoqlaqgcrbbvomaebo` — `https://tchoqlaqgcrbbvomaebo.supabase.co`. Management API reports region `ap-northeast-2`. Edge Function logs label the runtime region `ap-south-1`; this discrepancy is recorded, not resolved, and the project was not moved.

**Remote changes actually applied:**
- Migration `hmx_feature_flags` (remote version `20261024105232`) creating the `hmx` schema, 9 tables, helper functions, triggers, RLS, grants, and the PostgREST schema exposure.
- Edge Functions `config` (v3), `tester-enroll` (v3), `admin-api` (v1).
- No auth users, TOTP factors, admin allowlist rows, `ADMIN_ORIGIN` secret, flags, testers, or invites remain after test cleanup. `kill_switch_all = false`.

**Inventory:**

| Object | Purpose |
|---|---|
| `hmx.feature_flags` | Flag state, 0–100 rollout, version gates, schedule, per-flag kill switch, JSON config, `config_version`. |
| `hmx.testers` | Invite-enrolled testers; random `install_id`; SHA-256 `token_hash`; segment/status/metadata. |
| `hmx.tester_invites` | Single/multi-use invite hashes, expiry, usage cap. |
| `hmx.feature_flag_testers` | Explicit server-side tester grants per flag. |
| `hmx.feature_flag_segments` | Server-side segment grants per flag. |
| `hmx.admin_users` | Active admin allowlist (`admin`/`readonly`), FK to `auth.users`. |
| `hmx.system_settings` | Global kill switch singleton. |
| `hmx.admin_audit_log` | Admin mutation audit trail. |
| `hmx.rate_limits` | Fixed-window counters for public endpoints. |
| `hmx.enroll_tester(...)` | Atomic invite consumption + tester insert (rolls back invite use on duplicate install). |
| `hmx.consume_rate_limit(...)` | Atomic fixed-window counter. |
| `config` Edge Function | Public read-only effective configuration. |
| `tester-enroll` Edge Function | Invite redemption; returns tester token once. |
| `admin-api` Edge Function | Privileged action router; JWT + AAL2 + active allowlist. |

**Verified results:**

| Check | Result | Evidence |
|---|---|---|
| Migration applied to remote project | PASS | `apply_migration` returned success; 9 tables, 6 functions, RLS enabled on all 9. |
| anon/authenticated direct access | PASS | SQL role tests pass; live PostgREST with anon key returned HTTP 401 `42501 permission denied for schema hmx`. |
| anon/authenticated grants | PASS | 0 table grants; no schema `USAGE`; only `postgres`/`service_role` have function `EXECUTE`. |
| service_role operations | PASS | 63 table grants; `config` function read `hmx` successfully via PostgREST. |
| Rollout unit tests | PASS | `node --test supabase/tests/rollout.test.ts`: 18/18. |
| SQL constraints/RLS/rate-limit/invite tests | PASS | `supabase/tests/hmx_security.sql` transaction rolled back; printed `sql_security_tests_passed`. |
| Config 0%/100%/unknown flag | PASS | Unknown flags absent; 100% enabled; 0% off. |
| Global kill switch precedence | PASS | HTTP response changed to `enabled:false`, `source:global_kill_switch`, new revision. |
| Tester enrollment + tester grant at 0% | PASS | 201 token once; config returned `source:tester`, `enabled:true`. |
| Invite reuse / duplicate install | PASS | 404 `invalid_invite`; 409 `already_enrolled`; invite `used_count` stayed 0. |
| Tester revocation | PASS | Revoked token no longer grants the flag; response `source:rollout`, `enabled:false`. |
| Invalid inputs | PASS | 400 for bad keys/ids/JSON/invites; 413 for oversized bodies; 405 for GET. |
| Rate limiting | PASS (re-run) | 130 parallel config calls, fresh bucket → limiter recorded 120 allowed/10 denied; 119×200, 10×429 (`Retry-After: 60`), 1 client-side transport error; hashed bucket key. |
| Rate-limit IP handling | PASS | A client-supplied `x-forwarded-for` was replaced by the platform; `rate_limits` stored a SHA-256 IP bucket key, not the raw IP. |
| Config freshness | PASS | `max_age_seconds` is 60 in source and in the live `config` v3 response (reduced from 300). |
| Supabase security advisor | PASS (INFO only) | 9 `rls_enabled_no_policy` notices; expected because clients have no schema `USAGE` or table grants. No WARN/ERROR findings. |
| Supabase performance advisor | PASS (INFO only) | 6 unindexed-FK and 4 unused-index notices; zero-row tables, no indexes added. |
| Secret scan of logs | PASS (re-run) | No token/JWT/invite values. One `service_role` match in `postgres_logs` is the migration comment text, not a credential. |
| Admin with no credentials | PASS | Platform returned 401 (`UNAUTHORIZED_NO_AUTH_HEADER`); malformed bearer returned 401 (`UNAUTHORIZED_INVALID_JWT_FORMAT`). |
| Admin with publishable `apikey` only / anon JWT | PASS (fail-closed) | 503 `admin_origin_not_configured`; no privileged action possible. |
| AAL2 admin end-to-end | **BLOCKED** | No admin user, TOTP factor, or `ADMIN_ORIGIN` secret exists. `ADMIN_ORIGIN` is checked before `authenticate`, so the 503 rows do not exercise AAL2/allowlist. |
| Public signup setting | **PASS (verified enabled)** | `GET /auth/v1/settings` returned `disable_signup:false`, `external.email:true`. Not changed; must be disabled before admin provisioning. |
| Local `supabase db reset` / Deno type-check | **NOT RUN** | No Supabase CLI, Docker, or Deno in the environment; remote deployment and HTTP execution were used instead. |

**Phase 2 security conclusions:** the public configuration path returns only requested flags and never tester/admin lists; tester status is derived from a server-side token hash, not a client flag; kill switches override tester and percentage grants; missing/invalid configuration defaults off; privileged mutations are separated into `admin-api` and require platform JWT verification, `aal2`, and an active `admin_users` row. Public email signup is currently enabled and should be disabled before admin provisioning. Rate-limit buckets store SHA-256 IP hashes, not raw IPs. Admin mutation audit writes are best-effort; a DB outage could lose the audit row after a successful mutation.

**Remaining setup:** disable public signup first; create the admin user, enroll TOTP, and insert the allowlist row; set the Supabase Edge Function secret `ADMIN_ORIGIN` to the exact Phase 4 Vercel origin; verify the AAL2 rejection/acceptance matrix. No GitHub Actions secrets are required by the committed Phase 2 code because no deployment workflow was added.

**Verification pass (2026-10-24):** Git branch/remote state, remote migration `20261024105232`, Edge Function versions (`config` v3, `tester-enroll` v3, `admin-api` v1) and `verify_jwt` flags, 9/9 RLS, zero client grants, schema `USAGE`, direct PostgREST denial, rollout tests (18/18), SQL security tests, public signup, IP bucket handling, the 120/10 rate-limit boundary, security/performance advisors, and unified-log secret scan were rechecked. The only remaining blockers are dashboard/secret provisioning, not known code defects.

**Report:** `docs/HMXPDF_PHASE2_BACKEND_REPORT.md`.
