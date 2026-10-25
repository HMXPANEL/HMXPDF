# HMXPDF Phase 3 — Implementation Plan

**Plan date:** 2026-10-24
**Repository:** `HMXPANEL/HMXPDF`
**Branch:** `feature/hmxpdf-diagnostics-admin-updates`
**Branch HEAD:** `6230523eda322fede16f61ba1392e61b1185e458`
**`main` / `origin/main`:** `912e9977916c4f365147a294e60c3cd95d96960e`
**Primary source:** `docs/HMXPDF_PHASE3_SCOPE_AUDIT.md`
**Plan revision:** 5 — Phase 3.2 Android foundation implemented 2026-10-25
**Status:** Backend source implementation and all runnable backend tests are complete on the feature branch. Phase 3.2 Android privacy/config/report-core source is implemented and its JVM tests are written, but **not compiled or executed locally** (per the owner's instruction to validate through GitHub Actions). **Nothing was deployed.** SQL/database, Deno-runtime, scheduler, Android build/test, and live verification remain **blocked** or **CI-pending**. Phase 3.3 and later, the Phase 4 admin panel, and deployment remain out of scope.

This document converts the Phase 3 scope audit into an approval-ready implementation plan. Revision 3 records the backend source work authorized for this task. Revision 5 records the Phase 3.2 Android foundation. No migration, Edge Function, scheduler, live database setting, or admin panel was deployed.

---

## Revision 3 — backend implementation record (2026-10-24)

**Scope completed:** Phase 3 backend source only.

| Area | Implemented source |
|---|---|
| Reports schema | `supabase/migrations/20261024164504_hmx_reports.sql`: `hmx.reports`, constraints, indexes, RLS enabled with no policies, service-role-only grants, `hmx.purge_expired_reports(batch_size)`, and default-OFF `diagnostics.enabled` / `problem_reports.enabled` seeds. |
| Report validation | `supabase/functions/_shared/report-schema.ts`: strict allowlisted payloads, UUID/timestamp/size checks, unknown-field rejection (including `device_model`), no document/file fields. |
| Public ingestion | `supabase/functions/report-submit/index.ts`: 16 KiB cap, hashed-IP and install-ID rate limits, idempotent `(install_id, client_report_id)` insert, minimal acknowledgement. |
| Admin actions | `supabase/functions/admin-api/reports.ts` plus `admin-api/index.ts`: `reports.list`, `reports.get`, `reports.update`, `reports.delete`, `reports.purge`, with audit writes that contain field names/counts only. |
| Config privacy | `supabase/functions/config/request.ts` plus `config/index.ts`: config requests reject `install_id` and unknown fields; `installId` is always `null` during evaluation. Rollout, version gates, schedules, tester/segment grants, and kill switches are otherwise preserved. |
| Tests | 54/54 Node tests pass. SQL security/retention suites and a deployment-time `pg_cron` file are written but not executed. |
| Runbook | `docs/HMXPDF_PHASE3_BACKEND_VERIFICATION_REPORT.md`. |

**Verification results:** `node --test supabase/tests/*.test.ts` → 54 passed, 0 failed (23 pre-existing rollout/admin tests + 31 new tests). SQL suites were **not run** because `psql` and a safe database are unavailable. Deno Edge Function execution was **not run** because Deno is unavailable; `node --check` passed as a syntax-only check. No live Supabase probe, deployment, scheduler, or admin-panel verification was performed.

**Not done:** migration application, Edge Function deployment, `ADMIN_ORIGIN` configuration, `pg_cron` scheduling, live admin authorization matrix, Android integration, privacy-policy/store metadata changes, staging/production rollout.

---

## Revision 4 — hardening pass (2026-10-24)

**Scope:** fixes for the issues demonstrated by the verification audit. No deployment, migration application, scheduler run, live probe, commit, or Android change.

| Area | Change |
|---|---|
| Rate-limit storage | `report-submit` hashes the install UUID before building `report-install-hour:*` and `report-install-day:*` bucket keys; raw install IDs are no longer written to `hmx.rate_limits`. The duplicate lookup now runs before rate-limit consumption, so a retried submission returns the existing report instead of `429`. |
| Rate-limit cleanup | `hmx.purge_expired_rate_limits(batch_size)` deletes buckets older than **2 days** in bounded batches (1–20,000), is `security definer` with a fixed `search_path`, and is service-role-only. `rate_limits(window_start)` is indexed. The deployment-time scheduler calls it alongside `hmx.purge_expired_reports`. |
| Report-submit tests | The request handler is extracted to `report-submit/handler.ts` with an injectable store; `index.ts` is thin Deno/Supabase wiring. New Node tests cover duplicate 201/200, concurrent insert race, rate-limit 429, hashed bucket privacy, invalid/oversized payloads, store failures, and method/configuration guards. |
| SQL suites | Both files set `\set ON_ERROR_STOP on`; success markers moved inside the `DO` block; the retention suite clears pre-existing expired rows inside the rolled-back transaction; rate-limit purge boundary and privilege assertions added. |
| Config docs | The Phase 2 `config` example no longer sends `install_id`; the contract note records that deployed v3 behavior is superseded. `tester-enroll` is unchanged. |
| Retention docs | Scheduler prerequisites and verification queries updated. Automatic retention remains unverified until a staging scheduler run deletes old rows. |

**Tests:** `node --test supabase/tests/*.test.ts` → 69 passed, 0 failed, 0 skipped. SQL suites remain **unexecuted** because no disposable database or `psql` is available. Deno remains unavailable; `node --check` is syntax-only.

**Not done:** migration application, function deployment, `ADMIN_ORIGIN`, `pg_cron` scheduling, live probes, Android.

---

## Revision 5 — Phase 3.2 Android foundation (2026-10-25)

**Scope completed:** Phase 3.2 only — privacy/consent storage, report-only install ID, remote-config client/cache, report models/sanitizer/local queue, and WorkManager naming foundation. No onboarding/settings/report UI, no startup wiring, no automatic or manual submission path, no `Application` class, and no Phase 3.3+ work.

| Area | Implemented source |
|---|---|
| Consent | `data/privacy/ConsentStore.kt`, `PrivacyStorage.kt`: independent diagnostic/problem toggles, consent version 1, decision timestamp, explicit OFF default. Missing, malformed, unknown-version, or interrupted state reads OFF. Saving removes the version first so a partial write cannot combine a new toggle with an older decision. |
| Install identity | `data/privacy/InstallIdProvider.kt`, `data/UuidFormat.kt`: `UUID.randomUUID()` (v4, `SecureRandom`), lowercase canonical form, stored in `SharedPreferences("clearpdf_privacy")`. Report-only; the remote package has no reference. No hardware identifier and no logging. |
| Config transport | `data/remote/RemoteHttpClient.kt`: `HttpURLConnection`, HTTPS-only, 5 s connect/read timeouts, no redirects, 64 KiB bounded response read, `apikey` header. No OkHttp/Ktor/Retrofit or DI dependency. |
| Config contract | `data/remote/FeatureFlagClient.kt`, `FeatureFlagModels.kt`: request contains only `keys` and `app_version_code`; response is size/type checked, unknown fields/flags ignored, malformed requested flags fail closed. No `install_id`, `tester_token`, device model, or persistent identifier. |
| Config cache | `data/remote/FeatureFlagCache.kt`: `SharedPreferences("clearpdf_flags")`, revision/fetch time/max-age/kill switch/flags only. 60 s freshness; last-known-good is honored up to 24 h; no cache or older than 24 h is all-OFF. 429 honors a bounded `Retry-After`; 5xx uses bounded exponential backoff; 401/403 and invalid responses do not write a new cache. |
| Effective flags | Local consent AND a valid enabled remote flag. Global kill switch, per-flag kill-switch sources, stale/no cache, and unknown flags force OFF. Flags cannot write consent. Fractional rollout is unsupported because config has no install ID; flags must be 0/100 or use version/tester/segment rules. |
| Report models | `data/report/ReportModels.kt`: exact backend schema (`schema_version`, kind, UUIDs, ISO timestamp, app metadata, category/description/attached_error/self_checks), fixed self-check allowlist, 16 KiB body, 1–4000 description, 1–200/1–4000 attached error, 30-day/5-minute timestamp window. Diagnostic and problem shapes are mutually exclusive; forbidden document/device/contact fields do not exist. |
| Sanitizer | `data/report/ReportSanitizer.kt`: deterministic, side-effect-free normalization; user descriptions are flagged (not silently rewritten) for paths, `content://`/`file://`/HTTP(S)/FTP/mailto/tel URIs, document/media filenames, tokens/passwords, email addresses, phone numbers, and long opaque values. Error summaries/details are redacted automatically. The preview can require an edit; server validation remains authoritative. |
| Local queue | `data/report/ReportQueueStore.kt`, `ReportQueueFactory.kt`: one JSON envelope per report under `noBackupFilesDir/reports`, stable `client_report_id`, states `pending/failed/submitted/rejected/cancelled`, immutable terminal states, bounded 50-entry history (oldest terminal pruned first), corrupt files ignored/removed only when space is needed. No credentials, no upload path. |
| WorkManager foundation | `ReportUploadScheduler` interface plus `ReportWorkName.uniqueName()` only. No worker class, no scheduling call, no periodic work, no startup diagnostics. |

**Validation performed in this task:** `git diff --check` clean; XML parsed successfully for the main/FOSS/F-Droid manifests and both backup-rule files; static greps found no `install_id`/`InstallIdProvider` in `data/remote`, no `tester_token` in Android, no forbidden report fields, no logging in the new privacy/remote/report code, and no production caller of `RemoteConfig`, `FeatureFlagClient.refresh()`, `ReportQueueStore`, or `ReportPayloadFactory`. The 11 new JVM test files (80 `@Test` methods) are written but **not compiled or run locally**.

**Android validation path:** the owner instructed that builds/tests run in GitHub Actions, not locally. `.github/workflows/android.yml` already runs `./gradlew testPlayDebugUnitTest :pdf-core:test :ocr-core:test`, `lintPlayDebug`, and `assemblePlayDebug` on PRs/manual dispatch. A CI run requires the branch to be pushed/opened as a PR; this task did not commit or push, so no Android pass/fail claim is made. The expected local/CI task is `:app:testPlayDebugUnitTest` (or `:app:testFossDebugUnitTest`).

**Deviations from the earlier proposal:** the submitter and worker were deliberately not implemented in 3.2; the approved scope is the scheduler interface/name foundation only. The plan's proposed `ConsentManager.kt` is `ConsentStore.kt`. The queue cap is 50. The sanitizer additionally flags contact info and filenames. The 24 h last-known-good behavior is the plan's documented max-stale rule, not a claim that offline devices receive kill switches.

**Not done:** local Gradle build/test, CI execution, device/emulator verification, onboarding/Settings/report UI, startup or navigation wiring, report submission, migration/function deployment, commit/push/merge, and Phase 3.3+.

---

## Status vocabulary

| Tag | Meaning |
|---|---|
| **VERIFIED** | Observed directly in the current branch during this planning task. |
| **EXISTING / UNVERIFIED** | Code or tests exist but were not executed during this planning task. |
| **PLANNED** | Proposed for a later, separately approved implementation subphase. |
| **OPEN** | Requires an explicit product-owner, security, legal, or deployment decision. |
| **BLOCKED** | Cannot be completed in the current environment or deployment state. |
| **NOT IMPLEMENTED** | No implementation exists in the current branch. |

---

## Confirmed owner decisions (2026-10-24)

These decisions supersede the corresponding proposals in `docs/HMXPDF_PHASE3_SCOPE_AUDIT.md`. The audit itself remains unchanged as the historical input.

| ID | Confirmed decision | Effect on this plan |
|---|---|---|
| **CD-1** | **Keep the existing seven-page feature tour on first launch.** After it finishes, show **Privacy & Security**, then **Diagnostic Preferences**. Diagnostic Reports and Report a Problem both default OFF. **Next** saves the selections; **Not now** explicitly saves both OFF. Both paths go to Home. Keep What’s New. | The tour is no longer replaced. The privacy flow runs after tour completion, and the startup gate keeps all three surfaces distinct. |
| **CD-2** | **Install ID is included only in an explicit report submission.** Never include it in remote-configuration requests. Do not assume the locally stored UUID survives uninstall/reinstall. Document the limitation and evaluate privacy-preserving options without selecting an unverified solution. | Config requests send keys + app version only. Fractional config rollout is unavailable to the app; this limitation must be explicit. Install identity is report-only. |
| **CD-3** | **Device model is pending. Do not collect it** unless explicitly approved later. | No `device_model` column, payload field, UI disclosure, or test in Phase 3. A later approval would require a separate migration/client change. |
| **CD-4** | **Server-side reports are retained for 30 days.** Document the required automatic expiry/deletion mechanism. A written policy alone does not delete data. | Every retention reference becomes 30 days. An automatic purge job and scheduler verification are required before production. |
| **CD-5** | **F-Droid is not currently planned.** Mark F-Droid-specific distribution work out of scope for now without compromising the privacy or security of current builds. | No F-Droid rollout/testing work in Phase 3. Play and FOSS privacy behavior remains in scope. Existing `fdroid` flavor code is not expanded. |

---

## 1. Executive summary

Phase 3 adds four user-facing capabilities and two supporting layers:

1. **Remote feature flags** — consume the existing Supabase `config` endpoint with safe defaults, cache, freshness, and kill-switch handling.
2. **Two-step privacy onboarding after the existing tour** — a Privacy & Security step followed by independent Diagnostic Reports and Report a Problem preferences, both OFF by default.
3. **Diagnostic Reports** — a user-initiated “Send test report” flow with an exact payload preview, explicit Send, server acknowledgement, local history, and retry.
4. **Report a Problem** — a user-authored description with optional sanitized error details, preview, explicit Send, report ID, status, history, and retry.
5. **Backend and private administration** — one report table, one public ingestion function, service-role-only database access, audited admin list/get/update/delete actions, retention and deletion.
6. **Privacy and security** — no document content, filenames, paths, annotations, secrets, or unrelated personal data; no automatic submission; updated copy and backup protection.

**Confirmed shape**

- Keep the first-run seven-page feature tour. After it completes, show the two-step privacy/support flow: Privacy & Security, then Diagnostic & Support Preferences. Keep What’s New unchanged. The tour also remains replayable from Settings → “Watch intro again”.
- Use **one** `hmx.reports` table (not separate diagnostic/support tables), one public `report-submit` Edge Function, and report actions inside the existing `admin-api`.
- Generate a random install UUID on-device and include it **only in explicit report submissions**, never in config requests. Treat it as per-install state, not a durable account identifier.
- Retain server-side reports for **30 days** and delete them with an automatic job. A written policy is not a deletion mechanism.
- Do **not** collect device model in Phase 3.
- Do **not** plan F-Droid-specific distribution work. Play and FOSS builds retain their privacy/security requirements.
- Use the existing `HttpURLConnection` + kotlinx.serialization + coroutines stack for explicit sends. Add no networking dependency and no periodic WorkManager uploader.
- Do **not** add automatic crash reporting, screenshots, contact email, attachments, or server-side user self-deletion in Phase 3.
- Implement in six reviewable subphases, each with its own exit gate. Stop after each for approval.

**Why the sequence starts with the backend**

The report payload, server validation, idempotency, and RLS policy determine what the Android UI is allowed to show. Building onboarding first would encode privacy copy before the payload and retention implementation are settled. The recommended order is therefore:

```text
3.1 backend report foundation
3.2 Android privacy/config foundation (no UI)
3.3 onboarding + Settings
3.4 diagnostic test report
3.5 problem report
3.6 flag rollout + privacy copy + verification package
```

**Explicit non-goals**

- No Phase 4 Vercel admin panel.
- No automatic crash capture or background upload.
- No PDF/document content, filename, URI, path, annotation, form value, signature, password, token, or key upload.
- No screenshots or file attachments.
- No contact email field.
- No third-party analytics or crash SDK.
- No new feature-flag targeting beyond what the existing backend supports.
- No fractional remote-config rollout from the app in Phase 3, because config requests carry no install ID. A privacy-preserving alternative must be approved before attempting it.
- No device-model collection in Phase 3.
- No F-Droid-specific distribution, testing, metadata, or anti-feature work in Phase 3.
- No production deployment during implementation without separate approval.

---

## 2. Verified current state and audit discrepancies

### 2.1 Git state — VERIFIED

| Item | State |
|---|---|
| Current branch | `feature/hmxpdf-diagnostics-admin-updates` |
| HEAD | `6230523eda322fede16f61ba1392e61b1185e458` |
| `main` / `origin/main` | `912e9977916c4f365147a294e60c3cd95d96960e` |
| Staged changes | None |
| Tracked-file modifications (revision 3) | `supabase/config.toml`, `supabase/functions/admin-api/index.ts`, `supabase/functions/config/index.ts` |
| New backend files (revision 3) | Reports migration, shared report schema, `report-submit`, admin report validators, config request validator, tests, retention scheduler file |
| Untracked | `docs/HMXPDF_FULL_SOURCE_AUDIT.md`, `docs/HMXPDF_PHASE3_SCOPE_AUDIT.md`, `docs/HMXPDF_PHASE3_IMPLEMENTATION_PLAN.md`, `docs/HMXPDF_PHASE3_BACKEND_VERIFICATION_REPORT.md`, and the new backend files |
| Upstream for feature branch | None configured |
| Deployments performed | None |

> The revision-2 planning baseline had no tracked-file modifications. Revision 3 changed the working tree as listed above. Nothing was staged, committed, pushed, merged, or deployed.

`docs/HMXPDF_FULL_SOURCE_AUDIT.md` is pre-existing and must remain unchanged. `docs/HMXPDF_PHASE3_SCOPE_AUDIT.md` is the audit input and must remain unchanged by implementation work.

### 2.2 Current implementation — VERIFIED

| Area | Current state | Evidence |
|---|---|---|
| First-run flow | Seven-page feature tour, including locale/theme demos | `ui/screen/OnboardingScreen.kt` |
| Onboarding persistence | `SharedPreferences("clearpdf_onboarding")` private mode | `data/repository/OnboardingManager.kt` |
| Onboarding gate | Only `!hasCompletedOnboarding`; no version-aware re-show | `OnboardingManager.shouldShowOnboarding()` |
| What’s New | Separate sheet after an update, when onboarding is complete | `OnboardingManager.shouldShowWhatsNew()`; `DocsApp.kt` |
| Settings pages | Appearance, Glass, Language, Personalization, Files, Office, About, Licenses | `SettingsScreen.kt:655-668` |
| Privacy/diagnostics Settings | **None** | No page enum value, no row, no screen |
| Privacy policy link | `settings_privacy_policy` exists but is unused | `res/values/strings.xml:99` |
| Diagnostics/consent | **None** | Repository-wide search finds no implementation |
| Report queue/history | **None** | No models, storage, worker, or screens |
| Android flag client | **None** | No config request, cache, or flag evaluator |
| BuildConfig | Disabled | `app/build.gradle.kts` enables Compose only |
| Application class | None; no app-wide init hook | `AndroidManifest.xml` `<application>` |
| General HTTP client | None; `HttpURLConnection` used for Office engine download | `office/OfficeEngineDownloadWorker.kt` |
| Available libraries | WorkManager, kotlinx.serialization, Compose, Coil | `gradle/libs.versions.toml` |
| `INTERNET` | Commented in main manifest; declared in `foss` and `fdroid` | `AndroidManifest.xml`; flavor manifests |
| Backups | `allowBackup="true"`; wrong rules schema; cache domain wrong | `res/xml/backup_rules.xml` |
| Report backend | **None** | Only the Phase 2 feature-flag migration exists |
| Admin report actions | **None** | `admin-api` supports flags/testers only |
| Phase 2 admin deployment | MFA-optional source undeployed; `ADMIN_ORIGIN` unset | `docs/HMXPDF_PHASE2_BACKEND_REPORT.md` |
| Android JVM tests | 29 files; not run in this planning task | `app/src/test/**` |
| Android instrumentation tests | None | `app/src/androidTest` absent |
| Robolectric | Not present | `app/build.gradle.kts` test dependencies |
| Backend Node tests | 23 total: 18 rollout + 5 admin/auth; verified during the audit | `supabase/tests/*.test.ts` |
| SQL security suite | Exists; not run against a database | `supabase/tests/hmx_security.sql` |

### 2.3 Audit discrepancies and corrections

The audit is the primary source, but the following claims required correction or qualification. The plan uses the corrected version.

| ID | Audit claim | Verified reality | Plan treatment |
|---|---|---|---|
| **D-1** | The audit’s `BE-18` labels “23/23 config-rollout tests.” | The 23 tests are **18 rollout** (`rollout.test.ts`) + **5 admin/auth** (`admin_authz.test.ts`). | Record 18 + 5 separately. Do not describe all 23 as rollout tests. |
| **D-2** | The audit’s config request contract omits an `apikey` header; earlier notes said config “requires apikey.” | `config/index.ts` does **not** inspect `apikey` or `Authorization`. The Phase 2 report documents clients sending `apikey` and shows gateway 401 behavior for missing credentials. Gateway semantics for `verify_jwt = false` were not re-probed. | Android will send `apikey: <publishable key>` as the Phase 2 report documents. A 401/403 is treated as “config unavailable,” not as an app crash. Mark gateway behavior **unverified** until a staging probe. |
| **D-3** | Audit sometimes names `README.md`. | `git ls-files` tracks **`readme.md`**. A case-variant `README.md` with identical content (md5 `83b04f39…`) also exists in the working directory; `git status` does not list it separately. | Target the tracked **`readme.md`**. Do not rename the case variant in Phase 3. |
| **D-4** | Proposed `hmx.reports` table allowed `category` on diagnostic reports and did not require `description` for problem reports. | The illustrative SQL in the audit has these gaps. | Tighten constraints: description required for `kind='problem'`; diagnostic rows must not have description/attached error; self-checks only for diagnostic rows. |
| **D-5** | Audit says `DocsApp.kt:115-118` claims the tour re-shows after updates. | The source comment says “Shows on first launch and again after each app update (version-aware),” but `shouldShowOnboarding()` only checks completion. The behavior is not version-aware. | Fix the misleading comment when wiring the new gate. Do not add version-aware tour re-showing. |
| **D-6** | Audit refers to the report payload with `attached_error.included`. | The flag is redundant: presence of the object is the signal. | Use `attached_error: { summary, detail }` when attached; omit the field otherwise. Server treats presence as opt-in. |
| **D-7** | Audit says “no direct HTTP client dependency.” | True for JSON/network clients. Coil is present but is an image loader, not a general API client. | Use `HttpURLConnection` + kotlinx.serialization. No OkHttp/Ktor/Retrofit. |
| **D-8** | Audit reports 29 JVM test files. | Verified: 29. No Robolectric, so SharedPreferences/storage tests cannot run as plain JVM tests. | Add only pure-JVM tests. Storage/UI/backup checks are device/emulator tests (currently **BLOCKED**). |
| **D-9** | Audit notes F-Droid version codes 61–64 vs Play 6. | Verified in `app/build.gradle.kts`: `versionCode = 6`, F-Droid `10 * 6 + index + 1`. F-Droid distribution is now out of scope. | Send the actual installed `versionCode` from `PackageManager` for current builds. Do not use version gates for security. Do not plan F-Droid-specific gates. |
| **D-10** | Audit says backup rules use the wrong schema/domain. | Verified: `dataExtractionRules` points at a `<full-backup-content>` file; cache exclusions use `domain="file" path="cache/…"`. | Fix both before adding consent/report storage. Add a proper `<data-extraction-rules>` file. |
| **D-11** | Prior `docs/HMXPDF_DIAGNOSTICS_ADMIN_UPDATE_AUDIT.md` proposed automatic crash capture/upload. | That conflicts with the current requirement that every submission requires explicit user action. | Superseded. Excluded from Phase 3. No `Application` crash hook. |
| **D-12** | Audit says `INTERNET` is commented in the main manifest and may arrive transitively on Play. | Verified: main manifest has it commented; `foss`/`fdroid` declare it. Merged Play manifest was not inspected because no build was run. | Declare `INTERNET` explicitly in the main manifest. Verify merged manifests later on a build machine. |
| **D-13** | Audit’s Phase 2 status could be read as complete. | Phase 2 remains **PARTIAL/BLOCKED**: MFA-optional `admin-api` is undeployed and `ADMIN_ORIGIN` is unset. | Keep the caveat. Do not claim live admin verification. |

### 2.4 Claims deliberately not changed

- The Phase 2 migration, `config` function, `tester-enroll` function, and existing tests are treated as working baseline. This plan does not modify `config/index.ts` or `tester-enroll/index.ts`.
- No live Supabase setting, secret, Auth configuration, MFA factor, or production row was read or changed while producing this plan.

### 2.5 Owner decisions that supersede audit proposals

| Topic | Audit proposal | Confirmed decision |
|---|---|---|
| First-run sequence | Replace the tour with the two-step flow | Keep the seven-page tour; run Privacy & Security and Diagnostic Preferences after it |
| Config identity | May send install ID when diagnostics is ON | Never send install ID in config requests |
| Install ID durability | Not addressed | Do not assume it survives uninstall/reinstall; document and evaluate options |
| Device model | OPEN; omit by default | Not collected unless separately approved |
| Server retention | 90 days, configurable | 30 days, with automatic expiry/deletion |
| F-Droid | Review anti-feature policy and possible per-flavor keys | Out of scope for now; do not compromise current builds |

---

## 3. Proposed architecture and data flow

### 3.1 Component overview

```text
┌──────────────────────────── Android app ────────────────────────────┐
│                                                                    │
│  DocsApp startup gate                                              │
│    ├─ incoming document  → open document (onboarding stays pending)│
│    ├─ feature tour       → existing seven-page tour                │
│    ├─ privacy onboarding → 2-step consent flow after the tour      │
│    ├─ What's New         → existing sheet                          │
│    └─ home                                                         │
│                                                                    │
│  ConsentManager ──────────┐                                        │
│  FeatureFlagCache ────────┼─ FeatureFlagClient ── HTTPS ──┐        │
│                           │  (keys + app version only)     │        │
│                                                          │        │
│  InstallIdProvider ───────┼─ ReportRepository (reports only)        │
│                                                          │        │
│  Settings → Privacy & diagnostics                        │        │
│    ├─ toggles                                            │        │
│    ├─ Send test report → ReportRepository                │        │
│    └─ Report a problem → ReportRepository                │        │
│                                                          │        │
│  ReportRepository                                        │        │
│    ├─ ReportSanitizer (pure)                             │        │
│    ├─ preview (Compose)                                  │        │
│    ├─ ReportQueueStore (noBackupFilesDir)                │        │
│    └─ ReportUploadWorker (WorkManager) ── HTTPS ─────────┼──┐     │
└──────────────────────────────────────────────────────────┼──┼─────┘
                                                           │  │
                                      ┌────────────────────┘  │
                                      ▼                       ▼
                          Supabase Edge Functions      Supabase Postgres
                          ├─ config (existing)         ├─ hmx.feature_flags (existing)
                          └─ report-submit (new) ──────►├─ hmx.reports (new)
                                                       ├─ hmx.rate_limits (existing)
                          admin-api (extended) ───────►├─ hmx.admin_audit_log (existing)
                                                       └─ hmx.admin_users (existing)
```

### 3.2 Android package responsibilities

| Package | Responsibility | New/modified |
|---|---|---|
| `data/privacy` | Consent state, consent version/timestamp, and an install UUID used only for explicit report submissions | New |
| `data/remote` | Config HTTP request, response parsing, cache, effective flags | New |
| `data/report` | Payload models, sanitizer, queue files, submission, WorkManager worker | New |
| `data/repository` | Existing onboarding/settings; unchanged unless integration requires | Mostly unchanged |
| `ui/screen` | Privacy onboarding, privacy/diagnostics Settings, problem report | New + `SettingsScreen` |
| `ui/navigation` | Routes and callbacks | Modified |
| `ui` | Startup gate and config refresh trigger | Modified |

### 3.3 Startup gate — proposed order

```text
1. documentStartRoute != null              → open the document
2. OnboardingManager.shouldShowOnboarding() → existing seven-page feature tour
3. !privacyOnboardingCompleted            → Privacy & Security, then Diagnostic Preferences
4. shouldShowWhatsNew()                    → What’s New
5. otherwise                               → home
```

Rules:

- A share/shortcut/document window must never be blocked by onboarding. This preserves current behavior in `DocsApp.kt`; the flags remain unset for the next ordinary launch.
- The **existing seven-page tour runs first** and is not replaced. Its completion still calls `OnboardingManager.setOnboardingComplete(context)`, which records `onboarding_completed`, the onboarded version, and the What’s New baseline.
- After the tour finishes, route to the privacy flow instead of Home. The privacy flow has two steps: **Privacy & Security**, then **Diagnostic & Support Preferences**.
- Finishing the privacy flow (Next or Not now) marks privacy onboarding complete and navigates Home. It does not call `setOnboardingComplete()` a second time.
- What’s New remains unchanged. The start-destination calculation must require **both** the tour and the privacy flow to be complete before What’s New can win; the privacy gate has priority.
- Replaying the tour from Settings must not re-enter the privacy flow. Replay only marks the tour complete and returns to Settings.
- Config fetch happens only after the privacy flow is complete, never on the Privacy & Security screen and never blocking startup.
- **Existing installs are an OPEN decision.** `onboarding_completed` is already true for them, but no privacy flag exists. The straightforward gate shows the two-step flow once after the update. If the owner does not want a retroactive disclosure, an explicit one-time backfill is required; do not add that backfill without approval. Either way, both preferences default OFF and Settings exposes the informational page (no toggles).

### 3.4 Remote config flow

```text
app start after the privacy flow
  → read cached flags + fetch time
  → show cached values immediately
  → launch a single background refresh (short timeout)
      → POST /functions/v1/config
      → send keys + installed app version code ONLY
      → no install ID, UUID, device ID, or persistent client identifier
      → validate response size/types
      → store revision, generated_at, max_age_seconds, kill_switch, flags
      → effective availability = local consent AND flag enabled
  → on failure:
      cache age ≤ max stale → keep cache
      cache age > max stale → all flags off (fail closed)
      no cache              → all flags off
```

- No network call on the first privacy screen.
- No retry storm. At most one refresh per launch plus a bounded backoff on 429/5xx.
- Unknown flags/fields are ignored.
- `kill_switch = true` forces every flag off.
- A per-flag kill switch forces that flag off.
- Flags never turn a user consent OFF into ON.
- Flags are not an authorization mechanism.
- **Fractional rollout is unavailable to the app** because requests carry no install ID. The server will return the flag OFF with `missing_install_id` for 1–99% rollouts. Supported behavior is enabled/disabled, 0%/100%, schedules, version gates, and global/per-flag kill switches. Do not present fractional rollout as working in Phase 3.

### 3.5 Report submission flow

```text
Settings → Privacy & diagnostics
  → toggle ON (permission only; nothing sent)
  → "Send test report" or "Report a problem"
  → build allowlisted payload
  → sanitizer (defense in depth)
  → preview shows every field
  → user taps Send
  → client_report_id assigned once
  → write envelope to noBackupFilesDir/reports/<client_report_id>.json
  → enqueue unique WorkManager work `report-<client_report_id>`
  → HTTPS POST /functions/v1/report-submit
      → 201 new / 200 duplicate: server report_id + received_at → state submitted
      → 400/413: rejected; no automatic retry
      → 429: failed/deferred; honor retry-after
      → 5xx/timeout/offline: failed/pending; retry with same client_report_id
  → history lists kind, status, timestamps, server ID, last error
```

### 3.6 Storage map

| Data | Location | Backup | Notes |
|---|---|---|---|
| Consent flags, consent version/timestamp | `SharedPreferences("clearpdf_privacy")` | Excluded | Defaults OFF |
| Install UUID | Same privacy prefs | Excluded | Report submissions only; never sent to config; resets on uninstall/reinstall |
| Flag cache | `SharedPreferences("clearpdf_flags")` | Excluded | Revision + fetch time + flags JSON; no client identifier |
| Report envelopes/payloads | `context.noBackupFilesDir/reports/` | Not backed up | One JSON file per report; capped |
| Document content/annotations | Existing app storage | Existing rules | Never read by report code |
| Reports server-side | `hmx.reports` | Supabase backups per project policy | Retained **30 days**, then automatically deleted |

### 3.7 Trust boundaries

| Boundary | Rule |
|---|---|
| APK → Supabase | Publishable/anon key only. Never the service-role key. Assume the key is public. |
| Public `report-submit` | Untrusted input. Validate schema, size, types, and rate limits server-side. |
| Public `config` | Read-only and public. Never place secrets or personal data in flag config. |
| `admin-api` | Supabase Auth JWT + active allowlist + exact `ADMIN_ORIGIN`. No service-role key in any client. |
| Client flags | Hints for availability/rollout only. Never authorization. |
| Report preview | The user sees exactly the allowlisted fields that will be sent. |
| Admin UI (Phase 4) | Private owner use; not part of Phase 3. |

---

## 4. Module-by-module implementation sequence

### 4.1 Mapping of modules A–F to subphases

| Module | Subphase(s) |
|---|---|
| **A — Remote feature flags** | 3.2 (client/cache), 3.6 (rollout/kill switches/verification) |
| **B — First-launch onboarding** | 3.3 |
| **C — Diagnostic reports** | 3.4 |
| **D — Report a problem** | 3.5 |
| **E — Backend/private administration** | 3.1 (ingestion/RLS), 3.6 (admin actions verification) |
| **F — Privacy/security** | Cross-cutting; 3.1–3.6, finalized in 3.6 |

### 4.2 Subphase 3.1 — Backend report foundation (module E)

**Goal:** a reviewed, testable report store and ingestion function that is **not deployed**.

**Deliverables**

1. Migration for `hmx.reports` with constraints, indexes, RLS, grants, and the 30-day purge function. **Implemented** in `supabase/migrations/20261024164504_hmx_reports.sql`.
2. Shared schema/validation module for report payloads. **Implemented** in `supabase/functions/_shared/report-schema.ts`.
3. `report-submit` Edge Function with strict validation, idempotency, and rate limits. **Implemented** in `supabase/functions/report-submit/index.ts`.
4. `admin-api` report read/update/delete actions with audit writes. **Implemented** in `supabase/functions/admin-api/index.ts` and `reports.ts`.
5. Pure backend tests for validation and idempotency semantics. **Implemented**; 31 new Node tests pass.
6. SQL security assertions for RLS/grants. **Written** in `supabase/tests/reports_security.sql`; **not executed** because no safe database is available.
7. Phase 3 flags `diagnostics.enabled` and `problem_reports.enabled`, default OFF. **Implemented** in the migration.
8. Config privacy enforcement: reject `install_id` on config requests. **Implemented** in `config/request.ts` and `config/index.ts`.

**Explicitly not in this subphase**

- No deployment.
- No production migration.
- The 30-day purge function is defined; the scheduler is a deployment-time step and is **not applied**. `supabase/retention/report_retention_cron.sql` is provided for staging verification.

**Exit gate**

- Migration and function reviewed line by line. **Source review complete; database review remains blocked.**
- `node --test` passes for new pure tests and the existing 18 rollout + 5 admin/auth tests. **Passed: 69/69.**
- SQL suite passes in a safe database/staging transaction, including the 30-day retention boundary. **BLOCKED: not run.**
- No production change. **Confirmed.**

### 4.3 Subphase 3.2 — Android privacy/config foundation (modules A, F)

**Goal:** default-off consent, report-only install identity, config client, cache, and report core — **no user-visible UI yet**.

**Deliverables**

1. Enable `buildConfig`; add flavor endpoint fields with empty safe defaults.
2. Privacy preferences store with explicit defaults OFF and consent version/timestamp.
3. Random install UUID store in private prefs for explicit report submissions only; excluded from backup; never sent in config requests.
4. Minimal `HttpURLConnection` JSON client (timeouts, body caps, no redirects to cleartext).
5. Config request builder (keys + installed app version only) and response parser.
6. Feature-flag cache with 60-second freshness and maximum stale window.
7. Report payload models, sanitizer, queue store, and WorkManager naming foundation. The submitter and worker are deferred to 3.4/3.5 by the approved Phase 3.2 scope.
8. Pure JVM tests for sanitizer, parser, state mapping, request builder, and consent choices.
9. Document the reinstall/uninstall limitation and list privacy-preserving config-rollout options for later decision; select none.

**Explicitly not in this subphase**

- No onboarding screen.
- No Settings page.
- No report UI.
- No automatic transmission.
- No `Application` class.
- No fractional config rollout implementation.

**Exit gate**

- JVM tests pass.
- No network call occurs unless `refresh()` is explicitly invoked after the privacy flow.
- No report can be enqueued without an explicit repository call.
- `git grep` confirms no service-role key and no forbidden payload fields.
- The config request contains no install ID or persistent identifier.

**Implementation status (2026-10-25):** Source is implemented in `data/privacy`, `data/remote`, `data/report`, `data/storage`, plus `data/UuidFormat.kt`, the manifest/backup changes, and the `buildConfig` endpoint fields. Eleven JVM test files are written. **No Android build or JVM test was run locally** per the owner's GitHub Actions instruction; CI is pending because nothing was committed or pushed. `git diff --check`, XML parsing, and static privacy greps passed. The exit gate is therefore **source-complete / execution-pending**, not a pass claim. See Revision 5 for the full record and deviations.

### 4.4 Subphase 3.3 — Onboarding and Settings (module B)

**Goal:** preserve the existing seven-page tour, then add the two-step privacy/support flow and an informational Settings page. Consent is recorded only in the first-run flow in this version; Settings shows the saved choices, report actions, pending history, retention status, and the policy link. It does not expose consent toggles.

**Deliverables**

1. `PrivacyOnboardingScreen` with Step 1 (Privacy & Security) and Step 2 (Diagnostic & Support Preferences), shown **after** the existing tour completes.
2. Independent switches, both OFF by default.
3. Next persists exact selections; Not now persists both OFF.
4. Both privacy paths mark privacy onboarding complete and continue to What’s New when it is due, otherwise Home. Tour completion routes to the privacy flow, not directly Home.
5. Startup gate integration; incoming documents bypass the gate; existing installs see the flow once because no privacy flag exists.
6. Existing tour remains unchanged and replayable; replay never re-enters privacy. What’s New behavior preserved and ordered after the privacy flow.
7. Settings “Privacy & Security” page with saved consent status (no toggles), report actions disabled when the corresponding consent is OFF, pending-report history, retention status, and policy link.
8. Accessibility labels, state descriptions, focus order, 48 dp targets.
9. Fix the stale version-aware onboarding comment.

**Explicitly not in this subphase**

- No test-report submission.
- No problem-report form.
- No server calls from Settings.
- No replacement or redesign of the seven-page tour.

**Exit gate**

- ONB-01…ONB-19 pass where runnable (pure JVM + device/emulator).
- “Not now” writes explicit OFF even if pre-existing values were ON.
- No report/network request is triggered by the tour or either privacy step.
- Tour → Privacy & Security → Diagnostic Preferences → Home is the observed first-launch order.

### 4.5 Subphase 3.4 — Diagnostic test report (module C)

**Goal:** a safe, previewed, explicitly sent diagnostic report with history.

**Deliverables**

1. Fixed self-check payload (storage write, cache write, PDF engine init, report queue init).
2. Preview showing every field.
3. Explicit Send action.
4. Queue + WorkManager submission.
5. Server ID, status, timestamp in local history.
6. Failure/retry UI with correct states.
7. Toggle OFF cancels unsent diagnostic work.

**Exit gate**

- REP-01…REP-16 pass where runnable.
- Canary tests show no filename/path/URI/token/document text.
- A failed request is never displayed as submitted.
- Retries reuse `client_report_id` and produce one row.

### 4.6 Subphase 3.5 — Report a problem (module D)

**Goal:** a user-authored problem report with optional sanitized error details.

**Deliverables**

1. Description field with client/server length validation.
2. Fixed category selector.
3. Optional “attach error details” flow with sanitizer and preview.
4. Explicit Send.
5. Server ID, acknowledgement, history, retry.
6. Toggle OFF hides the form and cancels unsent work.

**Exit gate**

- REP-17…REP-28 pass where runnable.
- Attached error details are absent unless the user opted in and reviewed them.
- No document content can enter the payload through any form field.

### 4.7 Subphase 3.6 — Flag rollout, privacy copy, verification package (modules A, E, F)

**Goal:** turn the foundation into an approved, releasable state without deploying.

**Deliverables**

1. Define flags `diagnostics.enabled` and `problem_reports.enabled`, default OFF.
2. Confirm flags never override local consent.
3. Confirm config requests contain no install ID and that fractional rollout is treated as unsupported.
4. Reconcile onboarding strings, privacy policy, readme, and store metadata, including 30-day server retention.
5. Fix backup rules and manifest permissions.
6. Run the full runnable test set; record blockers.
7. Verify the 30-day automatic purge function and scheduler in staging; a policy statement alone is not accepted.
8. Produce a deployment runbook and rollback checklist.
9. Verify admin report actions against the updated source in a safe environment.
10. Keep F-Droid-specific work out of scope.

**Exit gate**

- FLG-01…FLG-25 pass where runnable.
- Privacy copy contains no “100% offline/no data collection” claim for network-capable builds and states 30-day retention.
- Config requests demonstrably contain no persistent identifier.
- The purge job demonstrably deletes only reports older than 30 days.
- No Phase 4 work started.
- Deployment still requires separate explicit approval.

---

## 5. Exact anticipated files

All paths are relative to the repository root. Names are proposals consistent with the current package/style conventions.

### 5.1 Android — new source files

| File | Purpose |
|---|---|
| `app/src/main/java/com/hmx/pdf/data/privacy/ConsentManager.kt` | Persist diagnostic/problem toggles, consent version, decided-at timestamp; explicit OFF defaults |
| `app/src/main/java/com/hmx/pdf/data/privacy/InstallIdProvider.kt` | Generate/store a random UUID in private prefs for explicit report submissions only; never sent in config; resets on uninstall/reinstall |
| `app/src/main/java/com/hmx/pdf/data/remote/RemoteHttpClient.kt` | Small `HttpURLConnection` JSON POST helper with timeouts/body caps |
| `app/src/main/java/com/hmx/pdf/data/remote/FeatureFlagModels.kt` | Serializable request/response/cache models |
| `app/src/main/java/com/hmx/pdf/data/remote/FeatureFlagClient.kt` | Build request, parse response, expose effective flags |
| `app/src/main/java/com/hmx/pdf/data/remote/FeatureFlagCache.kt` | Store revision/fetch time/flags; freshness and max-stale logic |
| `app/src/main/java/com/hmx/pdf/data/report/ReportModels.kt` | Diagnostic/problem payloads, envelope, status, error codes |
| `app/src/main/java/com/hmx/pdf/data/report/ReportSanitizer.kt` | Pure allowlist/redaction/truncation; canary-testable |
| `app/src/main/java/com/hmx/pdf/data/report/ReportQueueStore.kt` | Read/write/delete report envelopes in `noBackupFilesDir` |
| `app/src/main/java/com/hmx/pdf/data/report/ReportSubmitter.kt` | HTTP submission, response mapping, idempotency |
| `app/src/main/java/com/hmx/pdf/data/report/ReportUploadWorker.kt` | WorkManager `CoroutineWorker`; network constraint; bounded retry |
| `app/src/main/java/com/hmx/pdf/data/report/ReportRepository.kt` | User-initiated create/preview/send/retry/history/cancel operations |
| `app/src/main/java/com/hmx/pdf/ui/screen/PrivacyOnboardingScreen.kt` | Two-step onboarding; Step 1 disclosure, Step 2 switches |
| `app/src/main/java/com/hmx/pdf/ui/screen/PrivacyDiagnosticsScreen.kt` | Settings page: toggles, actions, history, policy link |
| `app/src/main/java/com/hmx/pdf/ui/screen/ReportProblemScreen.kt` | Description/category/attach-error form + preview + Send |
| `app/src/main/java/com/hmx/pdf/ui/viewmodel/ReportViewModel.kt` | Async report state, history, retry; survives configuration changes |

**Deliberately not added**

- No `HMXApplication`/`Application` subclass. WorkManager's default initializer and the `DocsApp` composition are sufficient.
- No crash handler, no analytics SDK, no screenshot library, no networking library.

### 5.2 Android — modified files

| File | Change |
|---|---|
| `app/build.gradle.kts` | Enable `buildConfig`; add `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`, `CONFIG_ENDPOINT`, `REPORT_ENDPOINT` fields with empty defaults; no secrets committed |
| `app/src/main/AndroidManifest.xml` | Declare `INTERNET`; point `dataExtractionRules` and `fullBackupContent` at the corrected files; no new permissions |
| `app/src/main/res/xml/backup_rules.xml` | Use correct `<full-backup-content>` domains; exclude privacy/flag prefs; report queue is in `noBackupFilesDir` and needs no exclusion |
| `app/src/main/res/xml/data_extraction_rules.xml` | **New**: proper `<data-extraction-rules>` for Android 12+ with matching cloud-backup/device-transfer excludes |
| `app/src/main/java/com/hmx/pdf/ui/DocsApp.kt` | Tour → privacy gate; config refresh only after the privacy flow; callbacks; fix stale comment |
| `app/src/main/java/com/hmx/pdf/ui/navigation/DocsNavGraph.kt` | Add privacy onboarding route; wire Settings callbacks/actions |
| `app/src/main/java/com/hmx/pdf/ui/screen/SettingsScreen.kt` | Add `PagePrivacy`; row; page content; callbacks |
| `app/src/main/res/values/strings.xml` | Add onboarding/privacy/report strings; correct “fully offline”/“100% offline” copy |
| `PRIVACY.md` | Disclose config requests, optional reports, access, retention, deletion |
| `readme.md` | Reconcile privacy/network claims; tracked file only |
| `fastlane/metadata/android/en-US/full_description.txt` | Reconcile store description if it repeats offline/no-collection claims |

**Optional, decision-dependent**

- `app/src/main/res/values-{ar,es,it,pt,pt-rBR,ru}/strings.xml` — new strings fall back to English until translated. Do not machine-translate privacy disclosures.
- Flavor manifests — only if `INTERNET` should remain flavor-specific rather than main.

**Explicitly unchanged**

- `app/src/main/java/com/hmx/pdf/ui/screen/OnboardingScreen.kt` — remains replayable.
- `app/src/main/java/com/hmx/pdf/data/repository/OnboardingManager.kt` — existing methods are reused; no storage migration needed.
- `supabase/functions/config/index.ts`
- `supabase/functions/tester-enroll/index.ts`
- `supabase/migrations/20261024105232_hmx_feature_flags.sql`

### 5.3 Android — new JVM tests

| File | Covers |
|---|---|
| `app/src/test/java/com/hmx/pdf/data/report/ReportSanitizerTest.kt` | Paths, URIs, filenames, tokens, control chars, truncation, canaries |
| `app/src/test/java/com/hmx/pdf/data/report/ReportStateTest.kt` | HTTP/exception → submitted/rejected/failed/pending; retryable rules |
| `app/src/test/java/com/hmx/pdf/data/remote/FeatureFlagParserTest.kt` | Valid/invalid/oversize/unknown flags; kill switch; missing fields |
| `app/src/test/java/com/hmx/pdf/data/remote/ConfigRequestTest.kt` | Keys + version only; no install ID/persistent identifier; no forbidden fields |
| `app/src/test/java/com/hmx/pdf/data/privacy/ConsentChoicesTest.kt` | Defaults OFF; Next preserves; Not now forces OFF |

These tests must not require Android framework objects. Storage, WorkManager, Compose, backup, and manifest behavior are instrumentation tests, currently **BLOCKED**.

### 5.4 Backend — new files

| File | Purpose |
|---|---|
| `supabase/migrations/20261024164504_hmx_reports.sql` | `hmx.reports`, constraints, indexes, RLS, grants, `hmx.purge_expired_reports(batch_size)`, and Phase 3 flag seeds |
| `supabase/functions/_shared/report-schema.ts` | Shared payload types, constants, pure validation/parsing |
| `supabase/functions/report-submit/index.ts` | Public ingestion endpoint |
| `supabase/functions/admin-api/reports.ts` | Pure admin report query/update/purge validators |
| `supabase/functions/config/request.ts` | Pure config-request validator; rejects `install_id` |
| `supabase/tests/report_schema.test.ts` | Pure payload validation tests |
| `supabase/tests/admin_reports.test.ts` | Admin report input validation tests |
| `supabase/tests/config_request.test.ts` | Config privacy/shape tests |
| `supabase/tests/phase3_source.test.ts` | Static source/migration assertions where runtime tests are unavailable |
| `supabase/tests/reports_security.sql` | RLS/grant/constraint assertions for `hmx.reports` |
| `supabase/tests/reports_retention.sql` | 30-day purge assertions: old rows deleted, fresh rows kept, batches bounded |
| `supabase/retention/report_retention_cron.sql` | Deployment-time `pg_cron` scheduler; not a migration and not applied |
| `docs/HMXPDF_PHASE3_BACKEND_VERIFICATION_REPORT.md` | Backend verification report and deployment runbook |

The migration timestamp must be later than `20261024105232`; `20261024164504` was the actual creation time.

### 5.5 Backend — modified files

| File | Change |
|---|---|
| `supabase/functions/admin-api/index.ts` | Added `reports.list`, `reports.get`, `reports.update`, `reports.delete`, `reports.purge`; audit writes; no content logging |
| `supabase/functions/config/index.ts` | Uses the strict request validator; always evaluates with `installId: null` |
| `supabase/config.toml` | Added `[functions.report-submit] verify_jwt = false` |
| `supabase/tests/hmx_security.sql` or new `reports_security.sql` | Added report RLS/constraint assertions in the new file to avoid disturbing existing assertions |
| Retention scheduler (deployment-time) | Enable the verified automatic purge after staging checks; the base migration deliberately does not schedule it |

No new secrets are required. `admin-api` continues to use `SUPABASE_SERVICE_ROLE_KEY` and `ADMIN_ORIGIN` from the environment. `report-submit` uses the service role server-side only.

### 5.6 Documentation and release metadata

| File | Change |
|---|---|
| `docs/HMXPDF_PHASE3_IMPLEMENTATION_PLAN.md` | This plan; updated by revision 3 with implementation and verification status |
| `docs/HMXPDF_PHASE3_BACKEND_VERIFICATION_REPORT.md` | New backend verification report and deployment runbook |
| `docs/HMXPDF_PHASE3_SCOPE_AUDIT.md` | Unchanged input; remains untracked |
| `docs/HMXPDF_FULL_SOURCE_AUDIT.md` | Unchanged pre-existing file |
| `PRIVACY.md`, `readme.md`, store metadata | Updated only in subphase 3.6 with approved copy; not changed in this task |

---

## 6. Proposed database migrations and Edge Functions

### 6.1 Migration: `hmx.reports`

**Implemented** in `supabase/migrations/20261024164504_hmx_reports.sql`; **not applied**. The SQL below is the reviewed design; the implemented file adds comments, explicit grants, and Phase 3 flag seeds.

```sql
create table if not exists hmx.reports (
  id                 uuid primary key default gen_random_uuid(),
  client_report_id   uuid not null,
  install_id         uuid not null,
  kind               text not null check (kind in ('diagnostic_test', 'problem')),
  category           text,
  description        text,
  attached_error     jsonb,
  app_version_code   integer not null check (app_version_code between 0 and 2100000000),
  app_version_name   text not null check (char_length(app_version_name) between 1 and 64),
  build_flavor       text not null check (build_flavor in ('play', 'foss', 'fdroid')),
  build_type         text not null check (build_type in ('debug', 'release')),
  android_api        integer check (android_api between 1 and 100),
  locale             text check (char_length(locale) between 1 and 16),
  self_checks        jsonb,
  status             text not null default 'received'
                       check (status in ('received', 'reviewing', 'resolved')),
  admin_notes        text check (char_length(admin_notes) <= 4000),
  client_created_at  timestamptz not null,
  received_at        timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  constraint reports_identity unique (install_id, client_report_id),
  constraint reports_category check (
    category is null or category in ('bug', 'crash', 'performance', 'other')
  ),
  constraint reports_kind_fields check (
    (kind = 'problem'
      and description is not null
      and char_length(description) between 1 and 4000
      and self_checks is null)
    or
    (kind = 'diagnostic_test'
      and description is null
      and attached_error is null
      and category is null
      and self_checks is not null)
  ),
  constraint reports_attached_error_object check (
    attached_error is null
    or (jsonb_typeof(attached_error) = 'object' and pg_column_size(attached_error) <= 4096)
  ),
  constraint reports_self_checks_object check (
    self_checks is null
    or (jsonb_typeof(self_checks) = 'object' and pg_column_size(self_checks) <= 2048)
  )
);

create index if not exists reports_received_idx
  on hmx.reports (received_at desc);
create index if not exists reports_kind_status_idx
  on hmx.reports (kind, status, received_at desc);
create index if not exists reports_install_idx
  on hmx.reports (install_id, received_at desc);

alter table hmx.reports enable row level security;
revoke all on table hmx.reports from public, anon, authenticated;
grant all on table hmx.reports to service_role;

-- Required for the 30-day retention rule. This deletes at most batch_size rows
-- per call so a scheduler can drain large backlogs without a long lock.
create or replace function hmx.purge_expired_reports(batch_size integer default 1000)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  removed integer;
begin
  with expired as (
    select id
    from hmx.reports
    where received_at < now() - interval '30 days'
    order by received_at
    limit greatest(1, least(coalesce(batch_size, 1000), 5000))
  )
  delete from hmx.reports r
  using expired e
  where r.id = e.id;

  get diagnostics removed = row_count;
  return removed;
end;
$$;

revoke all on function hmx.purge_expired_reports(integer) from public, anon, authenticated;
grant execute on function hmx.purge_expired_reports(integer) to service_role;
```

Notes:

- `device_model` is intentionally absent (confirmed decision CD-3). A later approval would require a separate migration and payload change.
- No `contact_email`, no `document_id`, no file/URI/path column, no raw log column.
- No foreign key to `hmx.testers`; reports are not tester-only.
- No RLS policies. Access is only through Edge Functions using the service role.
- `updated_at` is set by `admin-api` on mutation; no trigger is required.
- The `received_at` index supports both retention deletion and admin listing.
- `build_flavor` keeps `fdroid` for compatibility with the existing build variant, but F-Droid distribution work is out of scope.
- **Retention is fixed at 30 days.** The migration defines the purge function; it does not schedule itself. Scheduling is a deployment-time step, and production must not start until the scheduler is verified.

### 6.2 `report-submit` Edge Function

**Implemented** in `supabase/functions/report-submit/index.ts`; **not deployed**.

**Route:** `POST /functions/v1/report-submit`
**Config:** `verify_jwt = false` (public, like `config`)
**Gateway:** send `apikey: <publishable key>` from Android, matching the documented Phase 2 client pattern. Treat gateway 401/403 as unavailable.

**Request limits**

| Limit | Proposed value |
|---|---|
| Body size | 16 KB |
| Description | 1–4,000 chars |
| Attached error summary | ≤ 200 chars |
| Attached error detail | ≤ 4,000 chars |
| `client_report_id`, `install_id` | UUID |
| `schema_version` | `1` |
| Unknown top-level fields | Rejected |
| `self_checks` keys | Fixed allowlist only |
| Rate limit per hashed IP | 10/hour |
| Rate limit per `install_id` | 5/hour and 20/24 hours |

**Diagnostic payload**

```json
{
  "schema_version": 1,
  "kind": "diagnostic_test",
  "client_report_id": "<uuid>",
  "install_id": "<uuid>",
  "client_created_at": "2026-10-24T15:04:05Z",
  "app": {
    "version_code": 6,
    "version_name": "2.1.1",
    "flavor": "play",
    "build_type": "release",
    "android_api": 35,
    "locale": "en"
  },
  "self_checks": {
    "storage_write": "ok",
    "cache_write": "ok",
    "pdf_engine_init": "ok",
    "report_queue_init": "ok"
  }
}
```

**Problem payload**

```json
{
  "schema_version": 1,
  "kind": "problem",
  "client_report_id": "<uuid>",
  "install_id": "<uuid>",
  "client_created_at": "2026-10-24T15:06:00Z",
  "category": "bug",
  "description": "The export button did nothing after I rotated the screen.",
  "app": {
    "version_code": 6,
    "version_name": "2.1.1",
    "flavor": "play",
    "build_type": "release",
    "android_api": 35,
    "locale": "en"
  },
  "attached_error": {
    "summary": "NullPointerException while exporting",
    "detail": "Sanitized detail reviewed by the user."
  }
}
```

`attached_error` is omitted unless the user opted in and reviewed it. `install_id` is present only because this is an explicit report submission; it is never sent in config requests and resets on uninstall/reinstall. No device model is collected.

**Success responses**

| Case | HTTP | Body |
|---|---|---|
| New report | `201` | `{ "report_id": "<uuid>", "status": "received", "received_at": "<iso>" }` |
| Idempotent retry | `200` | Same, plus `"duplicate": true` |

**Error responses**

| HTTP | Error | Android state |
|---|---|---|
| `400` | `invalid_body` / `invalid_field` | rejected; no auto-retry |
| `413` | `body_too_large` | rejected; user must shorten |
| `429` | `rate_limited` + `retry-after` | failed/deferred; retry after delay |
| `503` | `service_unavailable` | failed; bounded retry |
| `500` | `internal_error` | failed; bounded retry |

**Implementation rules**

- Validate exact field names/types per `kind`; reject unknown top-level fields.
- Reject control characters and over-length strings.
- Validate `client_created_at` as ISO-8601; reject more than 5 minutes in the future and more than 30 days old (reports may queue offline). Retention runs from server `received_at`, not `client_created_at`.
- Hash the IP with SHA-256 for rate-limit buckets; never store the raw IP.
- Use `hmx.consume_rate_limit` with the same pattern as `config`/`tester-enroll`.
- Note that `install_id` is client-supplied and not an authentication identity; the IP bucket remains necessary.
- Accept `install_id` only on report submission. Config requests must not contain it, and `config` rejects it server-side with `400 install_id_not_allowed`. The report function rejects `device_model` as an unknown field.
- Insert with `on conflict (install_id, client_report_id) do nothing`; on conflict, select and return the existing row with `duplicate: true`.
- Return only the report ID/status/timestamp, never stored content.
- Log error codes only, never payloads or descriptions.
- Fail closed with `503` if the database or rate limiter fails.
- CORS: `*` is acceptable for this public write-only endpoint; allow `POST, OPTIONS` only.
- Never accept attachments, base64 blobs, HTML, or arbitrary JSON.

### 6.3 `admin-api` report actions

**Implemented** in `supabase/functions/admin-api/index.ts` and `reports.ts`; **not deployed**. The live `admin-api` remains the older AAL2 build until deployment is approved.Add to the existing action switch, reusing `authenticate()` and `isAuthorizedAdmin()`.

| Action | Request | Behavior | Audit |
|---|---|---|---|
| `reports.list` | `kind?`, `status?`, `before?`, `limit?` (≤50) | Cursor by `received_at`/`id`; newest first | Not required |
| `reports.get` | `id` | Full row | Not required |
| `reports.update` | `id`, `status?`, `admin_notes?` | Update allowed fields only | Required |
| `reports.delete` | `id` | Hard delete | Required |
| `reports.purge` (fallback/manual) | `older_than_days` (fixed at 30 in Phase 3) | Batched delete if the scheduler is unavailable or for incident cleanup | Required |

Rules:

- Keep `ADMIN_ORIGIN` exact-origin CORS and `verify_jwt = true`.
- Validate UUIDs, enum values, notes length, and page size.
- Audit only action, report ID, and changed field names — never content.
- Do not expose report content in error messages or logs.
- No bulk export in Phase 3.
- `readonly` admins remain rejected unless a separate decision changes `authz.ts`. The current behavior is intentional.
- The admin panel remains Phase 4.

### 6.4 `config` contract — server hardened, new client

**Revision 3 change:** the server is no longer unchanged. `config` now rejects any request containing `install_id` (`400 install_id_not_allowed`) and rejects unknown top-level fields. It accepts only `keys`, optional `app_version_code`, and the existing optional backend-only `tester_token`. `installId` is always `null` during flag evaluation. This enforces CD-2 at the trust boundary; legacy clients that still send `install_id` will be rejected and must be updated before this function is deployed.

Android must send:

```json
{
  "keys": ["diagnostics.enabled", "problem_reports.enabled"],
  "app_version_code": 6
}
```

The request must not contain `install_id`, a UUID, `ANDROID_ID`, an advertising ID, `tester_token`, or any other persistent client identifier. `app_version_code` comes from `PackageManager`; `keys` are the flags this build understands.

and parse:

```json
{
  "revision": "<64-hex>",
  "generated_at": "<iso>",
  "max_age_seconds": 60,
  "kill_switch": false,
  "flags": {
    "diagnostics.enabled": {
      "enabled": false,
      "config": {},
      "version": 1,
      "source": "default_off"
    }
  }
}
```

- Send only keys the build understands.
- Use the installed `versionCode` from `PackageManager`.
- Never send an install ID or any stable client identifier, even when diagnostic consent is ON (confirmed decision CD-2).
- Because no identifier is sent, the server returns `missing_install_id` and the flag OFF for 1–99% rollouts. Do not implement or advertise fractional app rollout. 0%/100%, enable/disable, schedules, version gates, and kill switches still work.
- Privacy-preserving config-rollout options (for example a separate rotating cohort identifier, a server-issued short-lived token, or no rollout at all) must be evaluated and explicitly approved before implementation. Do not select an unverified option in Phase 3.
- Do not require `tester_token`; no tester enrollment UI exists in Phase 3. Tester/segment targeting remains backend-only.
- Ignore unknown response fields and flags.
- Cap response size and string lengths.
- Never block startup.
- Never send the config request before the privacy flow is complete.

### 6.5 Deployment boundary

No deployment is part of subphases 3.1–3.6. A separate approval is required for:

1. applying the migration to staging;
2. deploying `report-submit`;
3. deploying the updated `admin-api`;
4. setting `ADMIN_ORIGIN`;
5. verifying the 30-day automatic purge and its scheduler in staging;
6. production migration/function rollout;
7. live admin authorization matrix;
8. store metadata/privacy policy publication.

F-Droid-specific distribution work is not part of this deployment boundary.

---

## 7. Privacy, consent, security and failure-handling requirements

### 7.1 Consent invariants

| ID | Invariant |
|---|---|
| C1 | Diagnostic Reports defaults OFF. |
| C2 | Report a Problem defaults OFF. |
| C3 | The two choices are independent. |
| C4 | Enabling a toggle grants access to a workflow only; it never authorizes automatic transmission. |
| C5 | Every report requires a preview and a final explicit Send. |
| C6 | “Not now” writes both preferences OFF explicitly, regardless of prior values. |
| C7 | Preferences can be changed in Settings at any time and persist immediately. |
| C8 | Turning a toggle OFF cancels queued unsent work and hides/disables the entry point. |
| C9 | Already submitted reports remain on the server; the UI must say they cannot be recalled. |
| C10 | Local history can be deleted by the user; server deletion is admin-only in Phase 3. |
| C11 | A remote flag can disable a feature but can never turn local consent ON. |
| C12 | No crash, startup, background task, or flag fetch may enqueue a report automatically. |
| C13 | The install UUID is included only in an explicit report submission; config requests contain no install ID or persistent client identifier. |
| C14 | Server-side reports expire automatically after 30 days; retention is not satisfied by policy text alone. |

### 7.2 Data classification

| Data | Classification | Handling |
|---|---|---|
| Document content/pages/text | Highly sensitive | Never collected or transmitted |
| Filenames, URIs, paths | Sensitive | Never collected; sanitizer strips path-like strings |
| Annotations, form values, signatures, edits | Highly sensitive | Never collected |
| Passwords, PDF passwords, keys, tokens | Secret | Never collected; redact if accidental |
| Problem description | User-authored; may contain PII | Preview, warning, editable, capped |
| Error summary/detail | Potentially sensitive | Only with explicit attach choice; sanitized; previewed |
| Install UUID | Pseudonymous, per-install identifier | Included only in explicit report submissions; never config; resets on uninstall/reinstall |
| App version/flavor/build/API/locale | Low-risk metadata | Included after preview |
| Device model | Identifying | **Not collected** (deferred; CD-3) |
| IP address | Personal data in many jurisdictions | Hash for rate limiting only; never store raw |
| Report ID/timestamps/status | Operational | Stored; shown to the user; deleted after 30 days |

### 7.3 Never collected or uploaded

- PDF/document bytes, rendered pages, extracted text, thumbnails.
- Filenames, display names, `content://`/`file://` URIs, SAF tree URIs, absolute paths.
- Annotations, highlights, comments, form values, signatures, edits.
- Passwords, PDF passwords, encryption keys, access tokens, API keys, cookies, auth headers.
- Email addresses, phone numbers, contacts, advertising IDs, `ANDROID_ID`, IMEI, serial numbers.
- Device model or hardware model strings.
- Installed-app lists, clipboard contents, location, camera/microphone data.
- Full logcat, raw stack traces with local paths, or arbitrary log buffers.
- Screenshots or file attachments.

### 7.4 Sanitizer and preview

Sanitization is defense in depth. The primary control is the allowlisted payload schema plus the user preview.

- Allowlist fields and enumerated values; drop everything else.
- Apply path/URI/token detection to the user-authored description as well. If a match is found, require the user to edit before Send; do not silently alter their sentence.
- Reject/redact `content://`, `file://`, `file:`, `/storage/`, `/sdcard/`, `Android/data`, `Android/media`, and path-like document names.
- Redact bearer tokens, `api_key=`, `token=`, `password=`, `secret=`, long hex/base64 strings.
- Truncate free text and error details to fixed maxima.
- Strip control characters and collapse excessive whitespace.
- Do not upload raw exception stack traces by default; use exception class + short sanitized message.
- Show the exact sanitized text in the preview and let the user edit/remove it.
- Apply the same length/type checks server-side where feasible. The server must not attempt to infer whether free text contains a filename; the preview is the real control.

### 7.5 Local storage and backup

- Store consent, report-only install ID, and flag cache in private SharedPreferences.
- The install UUID is written when first needed for an explicit report submission. It is never read for config, is excluded from backup, and is replaced if the app is uninstalled and reinstalled. Do not describe it as durable or account-linked.
- Store report envelopes in `context.noBackupFilesDir/reports/`; this directory is inherently excluded from backup.
- One JSON file per report: `{ schema_version, payload, state, server_id, attempts, last_error, updated_at }`.
- Cap history at 50 reports; prune oldest terminal entries first.
- Never store raw log buffers or document data.
- Exclude `clearpdf_privacy.xml` and `clearpdf_flags.xml` from backup.
- Fix the rules before adding storage:

```xml
<!-- full-backup-content -->
<exclude domain="sharedpref" path="clearpdf_privacy.xml" />
<exclude domain="sharedpref" path="clearpdf_flags.xml" />
<exclude domain="cache" path="decrypted_pdfs/" />
<exclude domain="cache" path="shared/" />
<exclude domain="file" path="save_copy_recovery/" />
<exclude domain="file" path="save_recovery/" />
```

- Add a separate `data_extraction_rules.xml` with `<data-extraction-rules>` and matching `<cloud-backup>` / `<device-transfer>` excludes.
- Do not rely on `allowBackup=false` alone.
- Device/emulator restore behavior is currently **BLOCKED**; record it as unverified.

### 7.6 Transport and secrets

- HTTPS only; no cleartext traffic.
- Use the publishable/anon key in the APK. It is public by design.
- Never embed the service-role key in Android, Phase 4 client code, logs, or git.
- Do not add certificate pinning in Phase 3; it adds rotation risk and is not a substitute for TLS.
- Use timeouts and a single retry path. Do not retry validation failures.
- Do not log request bodies, descriptions, error details, tokens, or IPs.

### 7.7 Failure handling matrix

| Condition | Report state | UI | Retry |
|---|---|---|---|
| Toggle OFF | No workflow | Entry point hidden/disabled | N/A |
| Preview dismissed | `draft` only; nothing queued | Return to form | N/A |
| Send tapped, offline | `pending`/`failed` | “Not sent — will retry” | WorkManager when network returns |
| Timeout | `failed` | “Not sent” + Retry | Manual or bounded automatic |
| HTTP 201 | `submitted` | Server ID + timestamp | No |
| HTTP 200 duplicate | `submitted` | Same server ID | No |
| HTTP 400/413 | `rejected` | Reason; edit required | No automatic retry |
| HTTP 429 | `failed` | “Try later”; honor `retry-after` | After delay |
| HTTP 5xx | `failed` | “Server unavailable” | Bounded backoff |
| Invalid response body | `failed` | “No acknowledgement” | Bounded |
| Lost response after server insert | `pending` | Retry | Same `client_report_id`; server dedupes |
| Toggle turned OFF while queued | `cancelled` locally | Removed from pending | No |
| Config fetch fails | Flags off/stale | No blocking error | Next launch |

**Never show “Submitted” without a 2xx response containing a server report ID.**

### 7.8 Idempotency and retry

- Generate `client_report_id` once per user-initiated report; reuse it on every retry.
- Server unique constraint `(install_id, client_report_id)`.
- On conflict, return the existing row with `duplicate: true`.
- WorkManager unique name `report-<client_report_id>` with `ExistingWorkPolicy.KEEP`.
- Retry network/timeout/5xx/429 only.
- Cap attempts and backoff; after the cap, leave `failed` and require manual retry.
- Do not auto-retry 400/413.

### 7.9 Remote flags and consent

- `diagnostics.enabled` and `problem_reports.enabled` are availability/rollout hints, default OFF.
- Effective access = local toggle ON **and** flag enabled.
- Local toggle OFF always wins, even if the flag is ON.
- A flag must never trigger a report.
- Global/per-flag kill switches force OFF.
- After the maximum stale window, flags fail closed. Document that an offline device cannot receive a kill switch.
- Flags are not authorization for paid, security, or destructive server operations.
- Config requests carry no install ID, so fractional rollout is unsupported in the app; 1–99% flags resolve OFF. Do not add a substitute identifier without approval.
- No secrets, tokens, or personal data in flag `config`.

### 7.10 Retention and deletion — CONFIRMED 30 days

- Reports are retained for **30 days from `received_at`**, then deleted.
- The migration defines `hmx.purge_expired_reports(batch_size)`; it is not a policy-only statement.
- The function deletes only rows older than 30 days, in bounded batches, ordered by `received_at`. The scheduler must run often enough to drain the backlog.
- Rate-limit buckets are retained for **2 days** and pruned by `hmx.purge_expired_rate_limits(batch_size)` in bounded batches. Bucket keys contain a SHA-256 hash of the IP and a domain-separated SHA-256 hash of the install UUID, never the raw values.
- A scheduler must invoke both purge functions automatically. The mechanism is **OPEN** until verified:
  - `pg_cron` inside Supabase, if the extension is available; or
  - a Supabase scheduled function / external scheduler using the service role.
- Do not assume `pg_cron` exists. Do not put a `cron.schedule` call in the base migration unless staging has verified it.
- Production must not start until the scheduler is configured and a staging test shows old rows deleted and fresh rows retained.
- Admin manual delete remains available for incident response; it is audited.
- Audit rows retain action metadata only, not report content.
- Local history deletion is separate. Uninstalling removes local history and the install UUID; server rows remain until the 30-day expiry.
- No legal-hold mechanism exists in Phase 3; if one is required, it needs separate approval and design.

### 7.11 Copy reconciliation

| Current claim | Conflict | Required change |
|---|---|---|
| “Your fully offline document companion” | Network config/report features | “Your documents stay on your device” / “works on-device” |
| “100% offline · No data collection · Open source” | False for network-capable builds | Replace with accurate on-device + optional diagnostics wording |
| “All your documents, always private” | Vague; may imply no network | Qualify: documents are not uploaded; optional reports are explicit |
| `PRIVACY.md` “no analytics SDK or crash-reporting service” | Phase 3 adds optional reports | Distinguish config, diagnostics, support reports, and third parties |
| `PRIVACY.md` network list | Omits Supabase endpoints | Add categories, data, purpose, access, 30-day retention, deletion |
| `readme.md` privacy section | Same issue | Update alongside `PRIVACY.md` |
| Play Data Safety / store listing | Must disclose collection/sharing | Update before release; release gate |
| F-Droid metadata/anti-features | F-Droid work is out of scope | Do not add F-Droid-specific copy or review work in Phase 3 |
| Retention statement | Must match the implemented job | State 30 days and automatic deletion; do not promise manual-only handling |

### 7.12 Accessibility

- Every switch has a programmatic label and state description.
- Touch targets at least 48 dp.
- Dynamic font scaling must not clip copy.
- Step headings are announced on navigation.
- Next and Not now are distinguishable visually and to screen readers.
- Do not rely on color alone for ON/OFF.
- Edge-to-edge and IME behavior must be checked on a device/emulator.

---

## 8. Dependencies and blockers

| ID | Dependency / blocker | State | Blocks | Resolution |
|---|---|---|---|---|
| B1 | Updated `admin-api` deployment | MFA-optional source committed, **not deployed** | Live admin report actions; live matrix | Deploy after approval |
| B2 | `ADMIN_ORIGIN` | **Unset** | Admin API; CORS verification | Set exact Phase 4 origin after Vercel exists |
| B3 | Phase 4 Vercel admin | **Not present** | Owner report UI | Phase 4, after Phase 3 approval |
| B4 | Report backend | **PLANNED** | All report features | Subphase 3.1 |
| B5 | Install UUID | **EXISTING / UNVERIFIED** | Report identity only | Implemented in 3.2 (`InstallIdProvider`); no config use. Fractional config rollout remains unavailable without a separate approved identifier |
| B6 | Android flag client | **EXISTING / UNVERIFIED** | Remote availability/kill switches | Implemented in 3.2 (`data/remote`); no startup wiring; build/test pending |
| B7 | BuildConfig disabled | **EXISTING / UNVERIFIED** | Endpoint fields/flavor/build type | Enabled in 3.2 source with empty safe defaults; compile pending |
| B8 | `INTERNET` permission | Main commented; flavors declare | Guaranteed network on Play | Declare in main; verify merged manifest later |
| B9 | Privacy copy/policy | Conflicts with Phase 3 | Release/compliance | Subphase 3.6 |
| B10 | Retention period | **CONFIRMED 30 days** | Automatic deletion mechanism | Verify scheduler in staging before production |
| B11 | Payload fields | Device model **deferred**; limits proposed | Schema churn | CD-3; approve limits |
| B12 | Re-consent policy | **OPEN** | Future consent versioning | Separate decision |
| B13 | F-Droid distribution | **OUT OF SCOPE** | No F-Droid-specific work | Revisit in a later phase |
| B14 | Backup rules | Wrong schema/domain | Consent/report backup safety | Fix in 3.2/3.6; test restore |
| B15 | Device/emulator | **Unavailable** | UI, storage, WorkManager, backup tests | Provide emulator/device |
| B16 | SQL security suite | Exists, **unrun** | RLS/grant confidence | Safe test DB/staging |
| B17 | Live admin matrix | Blocked by B1/B2 | Admin auth/origin verification | After deployment |
| B18 | Phase 2 completeness | **PARTIAL/BLOCKED** | Cannot claim full Phase 2 | Keep caveats; finish after approval |
| B19 | Signed release | Not verified | Distribution/signature risk | Build machine, separately |
| B20 | Legal/privacy review | Not performed | Consent/policy sufficiency | Review before deployment |
| B21 | No Robolectric | **VERIFIED** | JVM storage/UI tests | Device instrumentation; do not add a dependency without approval |
| B22 | Edge gateway `apikey` semantics | **Unverified** | Exact 401 behavior for `config`/`report-submit` | Staging probe after deployment approval |
| B23 | `pg_cron` availability | **Unverified** | Automatic 30-day purge | Check staging; otherwise use a verified scheduled function/external scheduler |
| B24 | Duplicate `README.md` case artifact | **VERIFIED** | Docs edits could target the wrong file | Edit tracked `readme.md` only |
| B25 | Config fractional rollout | **Unsupported by confirmed decision** | 1–99% flags | Do not implement; evaluate privacy-preserving options separately |
| B26 | Install UUID across reinstall | **Known limitation** | Cross-install identity/rate-limit continuity | Document; do not claim durability |
| B27 | Existing-user privacy gate | **OPEN** | Whether existing installs see the privacy flow once | Decide before 3.3 implementation |

**Can proceed now (planning only):** resolve open decisions, review payload/schema, prepare test cases.

**Can proceed after approval without deployment:** 3.1 source/tests, 3.2–3.5 implementation, local/CI JVM tests.

**Blocked until environment/deployment exists:** device UI tests, backup restore, merged-manifest verification, SQL run, live function probes, admin matrix, signed release.

---

## 9. Phase-by-phase acceptance criteria and test matrix

### 9.1 Acceptance gates

| Subphase | Acceptance gate |
|---|---|
| **3.1 Backend** | Migration/function reviewed; pure Node tests pass; SQL suite passes in a safe DB; no production change; idempotency proven with a duplicate insert test |
| **3.2 Foundation** | No UI; consent defaults OFF; no automatic network report; config cache fails closed; pure JVM tests pass; no forbidden fields/secrets in source |
| **3.3 Onboarding/Settings** | Both paths complete correctly; Not now writes OFF; preferences persist; incoming documents bypass; tour replay/What’s New preserved; no report sent |
| **3.4 Diagnostic** | Preview matches payload; explicit Send only; success/failure/rejected states correct; history/retry; canary tests pass |
| **3.5 Problem** | Description validation; optional sanitized error; preview; explicit Send; status/history/retry; toggle OFF cancels queue |
| **3.6 Rollout/copy** | Flags never override consent; kill switches honored; copy reconciled; backup/manifest changes reviewed; full runnable test set recorded; deployment runbook ready |

### 9.2 Test status legend

| Tag | Meaning |
|---|---|
| **P** | Planned; not implemented |
| **R** | Planned and runnable in the current environment (pure JVM/Node) |
| **D** | Planned but requires a device/emulator |
| **S** | Planned but requires a safe database/staging |
| **L** | Planned but requires live deployment/admin session |
| **EV** | Existing and verified (audit) |
| **EU** | Existing but unverified |
| **BLK** | Blocked |

Backend tests have been written and run; see §9.6.1 for actual results. Phase 3.2 Android source and JVM tests are written, but **none were executed locally**; the owner directed validation through GitHub Actions, and no CI run has occurred because the branch was not committed or pushed. Android rows below therefore remain intended coverage, not results. SQL/database, scheduler, and live rows remain planned or blocked. The table records intended coverage, not results.

### 9.3 Onboarding and consent

| ID | Test | Expected | Subphase | Status |
|---|---|---|---|---|
| ONB-01 | First ordinary launch, no state | Existing seven-page feature tour appears before Home | 3.3 | D |
| ONB-02 | Tour finishes | Privacy & Security (Step 1) appears; Home not shown yet | 3.3 | D |
| ONB-03 | Step 1 Next | Diagnostic Preferences (Step 2) appears; nothing saved yet | 3.3 | D |
| ONB-04 | Step 2 defaults | Both toggles OFF | 3.3 | R/D |
| ONB-05 | Diagnostics only | Diagnostic ON, problem OFF; independent | 3.3 | R/D |
| ONB-06 | Problem only | Problem ON, diagnostic OFF; independent | 3.3 | R/D |
| ONB-07 | Next with selections | Exact values persisted; Home; no send | 3.3 | R/D |
| ONB-08 | Not now | Home; both OFF; no send | 3.3 | R/D |
| ONB-09 | Not now with prior ON | Both become OFF; old consent not reused | 3.3 | R/D |
| ONB-10 | Relaunch after tour + privacy complete | Tour/privacy not shown; What’s New behavior unchanged | 3.3 | D |
| ONB-11 | Relaunch after tour, before privacy complete | Privacy flow appears; no partial consent assumed | 3.3 | D |
| ONB-12 | Rotation/process death | Step/toggles survive or reset safely; no false completion | 3.3 | D |
| ONB-13 | Incoming share/shortcut | Document opens; tour/privacy remain pending for ordinary launch | 3.3 | D |
| ONB-14 | Existing user update | Matches the approved existing-user decision; no silent backfill | 3.3 | D |
| ONB-15 | Settings changes persist | Relaunch keeps value | 3.3 | D |
| ONB-16 | Replay tour from Settings | Tour works; privacy flow not re-entered | 3.3 | D |
| ONB-17 | What’s New after an update | Still appears once per version after tour + privacy are complete | 3.3 | D |
| ONB-18 | Accessibility | Labels/state/focus/48 dp/font scaling | 3.3 | D |
| ONB-19 | Privacy copy | No false offline claim; 30-day retention stated; policy link works | 3.6 | R/D |

### 9.4 Diagnostic and problem reports

| ID | Test | Expected | Subphase | Status |
|---|---|---|---|---|
| REP-01 | Both toggles OFF at startup | No report request | 3.2/3.3 | R/D |
| REP-02 | Enable toggle | Nothing sent automatically | 3.3 | R/D |
| REP-03 | Send test report | Preview shows every field | 3.4 | D |
| REP-04 | Preview fields | No content/filename/path/URI/token | 3.4 | R/D |
| REP-05 | Explicit Send | Request only after Send | 3.4 | D |
| REP-06 | Diagnostic success | Server ID + received; history | 3.4 | D/S |
| REP-07 | Problem success | Server ID + received; description correct | 3.5 | D/S |
| REP-08 | 400 validation | Rejected; not submitted; no auto-retry | 3.4/3.5 | R |
| REP-09 | 413 too large | Rejected; user can shorten | 3.4/3.5 | R |
| REP-10 | 429 | Failed/deferred; retry-after; not submitted | 3.4/3.5 | R |
| REP-11 | Offline/timeout | Pending/failed; not submitted; retry | 3.4/3.5 | D |
| REP-12 | Retry after lost response | Same ID; no duplicate row | 3.4/3.5 | S |
| REP-13 | Retry after 5xx | Bounded backoff; no duplicate | 3.4/3.5 | R/S |
| REP-14 | History survives restart | Correct kind/status/time/server ID | 3.4/3.5 | D |
| REP-15 | Delete local history | Local removed; server copy remains | 3.4/3.5 | D |
| REP-16 | Toggle OFF cancels queue | Unsent canceled; submitted remains | 3.4 | D |
| REP-17 | Filename/path canary | Removed/rejected before preview/server | 3.2/3.4 | R |
| REP-18 | Token/password canary | Redacted/rejected; no upload | 3.2/3.5 | R |
| REP-19 | Error containing path | Preview warns; user can edit/remove | 3.5 | D |
| REP-20 | Forbidden field injection | Unknown fields rejected | 3.1/3.5 | R |
| REP-21 | Payload size cap | Rejected client and server | 3.1/3.2 | R |
| REP-22 | No automatic crash upload | Crash/restart enqueues nothing | 3.2 | D |
| REP-23 | Empty/too-long description | Rejected | 3.5 | R |
| REP-24 | Attach error OFF | No detail included | 3.5 | R/D |
| REP-25 | Attach error ON | Sanitized, previewed, sent only after Send | 3.5 | D |
| REP-26 | Problem toggle OFF | Workflow inaccessible; no send | 3.5 | D |
| REP-27 | Concurrent duplicates | One row | 3.1 | S |
| REP-28 | HTML/script in response | Rendered as text; no XSS | 3.4/3.5 | D |

### 9.5 Feature flags

| ID | Test | Expected | Subphase | Status |
|---|---|---|---|---|
| FLG-01 | Valid response | Known flags parsed; unknown ignored | 3.2 | R |
| FLG-02 | Missing/unknown flag | OFF | 3.2 | R |
| FLG-03 | 0% rollout | OFF | 3.2/3.6 | R |
| FLG-04 | 100% rollout | ON without install ID | 3.2 | R |
| FLG-05 | Fractional rollout | **Not available to the app**; server-only behavior with a supplied ID | — | BLK / out of scope |
| FLG-06 | Missing install ID + fractional | OFF (`missing_install_id`) for every app request | 3.2 | R |
| FLG-07 | Global kill switch | All OFF | 3.2 | R |
| FLG-08 | Per-flag kill switch | That flag OFF | 3.2 | R |
| FLG-09 | Disabled + tester grant | OFF | 3.2 | R |
| FLG-10 | Schedule not started/expired | OFF | 3.2 | R |
| FLG-11 | Version gates | OFF outside range; inclusive | 3.2 | R |
| FLG-12 | Current-build version codes | Play/FOSS send the installed versionCode | 3.2 | R/D |
| FLG-13 | Tester grant | ON at 0% for that tester | 3.6 | S (backend); client BLK |
| FLG-14 | Segment grant | ON for that segment | 3.6 | S (backend); client BLK |
| FLG-15 | Fresh cache | Cached within 60 s | 3.2 | R |
| FLG-16 | Stale cache | Refresh; max stale enforced | 3.2 | R |
| FLG-17 | Offline, no cache | All OFF; app functional | 3.2 | R/D |
| FLG-18 | Offline, cache | Last-known-good within window | 3.2 | R/D |
| FLG-19 | Invalid/oversize response | Safe defaults; no crash | 3.2 | R |
| FLG-20 | Kill switch while offline | Cannot receive; fail closed after stale | 3.2 | R |
| FLG-21 | Startup performance | No blocking network | 3.3 | D |
| FLG-22 | Flag vs consent | Local OFF stays OFF | 3.6 | R/D |
| FLG-23 | Config request privacy | No install ID, UUID, or persistent identifier | 3.2/3.6 | R |
| FLG-24 | No secrets in config | Config contains no keys/PII | 3.6 | S |
| FLG-25 | Config request shape | Body contains only understood flag keys and app version code | 3.2 | R |

### 9.6 Backend, admin, and security

| ID | Test | Expected | Subphase | Status |
|---|---|---|---|---|
| BE-01 | Anonymous direct read/write | Denied | 3.1 | S |
| BE-02 | Authenticated non-admin direct access | Denied | 3.1 | S |
| BE-03 | Service-role insert/select | Allowed | 3.1 | S |
| BE-04 | Bad kind/fields/size | 400/413; no row; `device_model` rejected | 3.1 | R/S |
| BE-05 | IP rate limit | 429; raw IP not stored | 3.1 | S |
| BE-06 | Install rate limit | 429 | 3.1 | S |
| BE-07 | Idempotent duplicate | One row; same ID; duplicate flag | 3.1 | R/S |
| BE-08 | Acknowledgement | ID/status/received_at | 3.1 | R/S |
| BE-09 | Admin list/get | Authorized only; pagination/filter | 3.1 | S/L |
| BE-10 | Admin update/delete | Authorized; audit row | 3.1 | S/L |
| BE-11 | Non-admin/inactive/readonly | Rejected per policy | 3.1 | R/L |
| BE-12 | Wrong Origin | 403; correct allowed | 3.6 | L |
| BE-13 | Missing `ADMIN_ORIGIN` | 503 fail closed | 3.6 | EU/L |
| BE-14 | MFA-optional deployment | Source behavior matches live | 3.6 | L |
| BE-15 | Audit content | No report content | 3.1 | S/L |
| BE-16 | Retention/purge function | Deletes only rows older than 30 days; audit preserved | 3.1 | S |
| BE-17 | SQL security suite | All assertions pass | 3.1 | S |
| BE-18 | Rollout tests | 18/18 pass | Baseline | EV |
| BE-19 | Admin auth tests | 5/5 pass | Baseline | EV |
| BE-20 | Config live probe | 200/freshness/kill switch/revision | 3.6 | EU/L |
| BE-21 | Automatic purge scheduler | Runs without manual action; deletes expired rows | 3.6 | S/L |
| BE-22 | Retention boundary | 29-day row kept; 31-day row deleted | 3.1 | S |
| BE-23 | Reinstall behavior | New install UUID; no claim of continuity | 3.2 | D |

#### 9.6.1 Backend implementation results (2026-10-24)

| Check | Result | Evidence |
|---|---|---|
| Existing rollout tests | **PASS** | 18/18 in `supabase/tests/rollout.test.ts` |
| Existing admin/auth tests | **PASS** | 5/5 in `supabase/tests/admin_authz.test.ts` |
| New report-schema tests | **PASS** | `supabase/tests/report_schema.test.ts` |
| New report-submit handler tests | **PASS** | `supabase/tests/report_submit.test.ts` |
| New admin-report validation tests | **PASS** | `supabase/tests/admin_reports.test.ts` |
| New config-request privacy tests | **PASS** | `supabase/tests/config_request.test.ts` |
| New static source/migration tests | **PASS** | `supabase/tests/phase3_source.test.ts` |
| Full Node run | **PASS** | `node --test supabase/tests/*.test.ts` → 69/69 |
| SQL security suite | **BLOCKED** | `psql` and a safe database are unavailable; file written |
| SQL retention boundary suite | **BLOCKED** | Same environment blocker; file written |
| Deno Edge Function execution | **BLOCKED** | Deno is unavailable; `node --check` is syntax-only |
| Live `report-submit` / `admin-api` probes | **BLOCKED** | Nothing deployed; `ADMIN_ORIGIN` unset; live `admin-api` is the old build |
| Automatic purge scheduler | **BLOCKED** | `pg_cron` availability unverified; scheduler file not applied |

### 9.7 Regression

| ID | Test | Expected | Status |
|---|---|---|---|
| REG-01 | Open PDF | Renders | EU (device) |
| REG-02 | Edit and save PDF | No data loss | EU (device) |
| REG-03 | Annotations/signatures | Persist/export correctly | EU (device) |
| REG-04 | Merge/split/compress | Unchanged | EU (device) |
| REG-05 | DOCX/XLSX open/save | Unchanged | EU (device) |
| REG-06 | Share/export | Unchanged | EU (device) |
| REG-07 | Offline document use | Works without network | EU (device) |
| REG-08 | Play/FOSS builds | Compile/run; permissions correct; F-Droid-specific work not tested | BLK (no build) |
| REG-09 | Signed release update | Signature continuity | BLK |
| REG-10 | Backup/restore | Consent/report data not unexpectedly restored | BLK |

### 9.8 Plan-specific verification

| ID | Check | Expected | Status |
|---|---|---|---|
| PLN-01 | BuildConfig fields per flavor | Endpoints present; empty defaults fail closed | Source present; compile/CI pending |
| PLN-02 | Merged manifest `INTERNET` | Present in all network-capable builds | Main manifest source present; merged manifest not built |
| PLN-03 | Backup XML schema | Correct element/domain for API range | XML parsed; device restore not run |
| PLN-04 | No service-role key | `git grep` finds none in app/UI/docs | PASS (manual static grep) |
| PLN-05 | No forbidden payload fields | Models/sanitizer contain no file/content fields | PASS (manual static grep); JVM static test not run |
| PLN-06 | Config 401/403 | Treated as unavailable; cache/fail-closed | Test written; not run |
| PLN-07 | Toggle OFF cancels work | Unique work canceled; unsent payload removed | Deferred to 3.4/3.5 |
| PLN-08 | Report body cap | Client rejects >16 KB before queue | Test written; not run |

### 9.9 Phase 3.2 Android test inventory — WRITTEN / NOT EXECUTED

These JVM tests exist on the feature branch. They were **not compiled or run locally**; the owner directed validation through GitHub Actions. No pass/fail/skip count is available yet.

| Test file | Coverage |
|---|---|
| `app/src/test/java/com/hmx/pdf/data/privacy/ConsentStoreTest.kt` | Missing/malformed/unknown-version consent fails OFF; independent toggles; explicit OFF decision; clear |
| `app/src/test/java/com/hmx/pdf/data/privacy/InstallIdProviderTest.kt` | v4 UUID generation/persistence; corrupt replacement; no generation on peek; non-UUID generator failure |
| `app/src/test/java/com/hmx/pdf/data/remote/FeatureFlagClientTest.kt` | Request contains only keys/version; strict response parsing; unknown flags ignored; consent/kill-switch/stale/unknown-flag behavior; refresh outcomes; no request when unconfigured/cleartext |
| `app/src/test/java/com/hmx/pdf/data/remote/FeatureFlagCacheTest.kt` | Round trip; corrupt/unknown cache fails closed; unknown flags dropped; bounded 429/5xx backoff; reset/clear |
| `app/src/test/java/com/hmx/pdf/data/report/ReportModelsTest.kt` | Exact backend field sets; null omission; validation limits; timestamps; identifier normalization; forbidden fields absent |
| `app/src/test/java/com/hmx/pdf/data/report/ReportSanitizerTest.kt` | Deterministic normalization; paths/URIs/filenames/tokens/contact info flagged; error redaction; truncation; ordinary prose unaffected |
| `app/src/test/java/com/hmx/pdf/data/report/ReportQueueStoreTest.kt` | Stable IDs; retry transitions; terminal immutability; 50-entry bound; corrupt/partial files; delete |
| `app/src/test/java/com/hmx/pdf/data/report/ReportWorkNameTest.kt` | Stable normalized WorkManager unique name; invalid ID rejection |
| `app/src/test/java/com/hmx/pdf/Phase32StaticConfigTest.kt` | Manifest INTERNET/HTTPS; backup schemas/exclusions; BuildConfig fields; no install identity in remote package; noBackup queue |
| `app/src/test/java/com/hmx/pdf/data/InMemoryStringStore.kt`, `data/report/ReportTestData.kt` | JVM test helpers |

**CI command:** `./gradlew testPlayDebugUnitTest :pdf-core:test :ocr-core:test` (already in `.github/workflows/android.yml`). A CI run requires a push/PR, which this task did not perform.

---

## 10. Rollback strategy

### 10.1 Android rollback

- Each subphase should be a separate reviewable commit. Reverting a subphase must not require reverting earlier ones.
- Report file envelopes include `schema_version`; readers ignore unknown fields and versions they cannot parse.
- If a privacy defect is found:
  1. disable report entry points immediately (Settings toggle/flag);
  2. cancel unique report work and delete queued unsent payloads;
  3. ship a patch/revert;
  4. preserve already-submitted server rows until their 30-day expiry unless earlier deletion is explicitly approved.
- Local history deletion is always available; it does not delete server rows.
- Version downgrades are not supported by Android; rollback means a new higher versionCode patch.

### 10.2 Backend rollback

- The migration is additive. Leaving `hmx.reports` in place after disabling ingestion is safe.
- Do not auto-run a destructive down migration. Dropping the table requires explicit approval, a backup, and confirmation that no reports must be retained.
- Roll back `report-submit` by deploying the immediately previous function version (or a stub returning `503`).
- Roll back `admin-api` by redeploying the previous deployed version. The current live version is already the old AAL2 build; do not deploy the new one until approved.
- Roll back `config` carefully: revision 3 rejects `install_id`. If a legacy client still sends it, redeploy the previous config function or update the client before rollout. The rejection is intentional and must not be removed to accommodate an unapproved identifier flow.
- Retention/purge jobs must be disabled before an incident rollback if they could delete data needed for investigation; record the disablement and re-enable the 30-day job afterward.
- Audit rows are preserved; they contain action metadata, not report content.

### 10.3 Feature-flag rollback

- All new flags default OFF.
- Global kill switch (`kill_switch_all`) disables every flag.
- Per-flag kill switch disables one feature.
- Removing a flag definition must be treated as OFF on the client (missing = OFF).
- Do not repurpose a flag key for a different behavior; create a new key.

### 10.4 Deployment rollback triggers

| Trigger | Action |
|---|---|
| Report content/privacy leak | Disable ingestion + UI; preserve evidence; incident review |
| Duplicate reports / idempotency failure | Disable submission; fix server; client retries remain queued |
| Admin auth/origin bypass | Disable `admin-api`; rotate keys if needed; audit |
| RLS/grant failure | Stop ingestion; revoke access; fix before re-enable |
| Rate-limit abuse | Tighten limits or disable endpoint; monitor |
| Crash/regression in document flows | Revert app subphase; ship patch |

### 10.5 Data rollback

- The 30-day retention job continues during a normal rollback. Do not silently extend retention. Disable it only for a documented incident hold, then re-enable.
- If the table must be dropped, export/backup first and record the approval.
- Consent preferences survive app updates; clearing app data resets them to OFF.
- Uninstalling removes local history and the install UUID; the next install starts a new UUID. Server rows remain until their 30-day expiry.

---

## 11. Decisions: confirmed and remaining

### 11.1 Confirmed decisions

| ID | Decision |
|---|---|
| **CD-1** | Keep the seven-page tour first; then Privacy & Security, then Diagnostic Preferences. Both toggles OFF by default. Next saves selections; Not now explicitly saves both OFF. Both paths go Home. Keep What’s New. |
| **CD-2** | Install ID is included only in an explicit report submission, never in config requests. Do not assume it survives uninstall/reinstall. Evaluate privacy-preserving options without selecting an unverified solution. |
| **CD-3** | Device model is pending and is **not collected** unless explicitly approved later. |
| **CD-4** | Server-side reports are retained for 30 days; automatic expiry/deletion is required. Policy text alone is not a deletion mechanism. |
| **CD-5** | F-Droid-specific distribution work is out of scope for now, without weakening current builds’ privacy/security. |

### 11.2 Remaining decisions requiring explicit approval

Each item has a recommendation. Recommendations are not decisions.

| # | Decision | Recommendation | Blocks |
|---|---|---|---|
| 1 | Should existing users see the privacy flow once after updating? | Yes, show it once because no privacy flag exists; if that is undesirable, approve a one-time backfill that marks existing installs complete | 3.3 |
| 2 | Should privacy-policy changes trigger re-consent? | Store `consent_version`; separate versioned flow only for material changes | 3.3/3.6 |
| 3 | What privacy-preserving config-rollout option, if any, should replace the install ID? | Evaluate no-identifier, rotating cohort, or short-lived token options; do not implement any in Phase 3 | 3.2 |
| 4 | How should install-ID loss on reinstall be handled? | Accept per-install identity only; do not claim continuity or cross-install analytics | 3.2/3.6 |
| 5 | Include a contact email field? | No for Phase 3 | 3.1/3.5 |
| 6 | Who can read reports? | Owner/admin only via `admin-api`; service-role-only DB access | 3.1 |
| 7 | Should users see server-side `reviewing/resolved`? | Local status/history only; no server status lookup in Phase 3 | 3.4/3.5 |
| 8 | Maximum flag stale window? | 24 hours for ordinary flags; fail closed after | 3.2 |
| 9 | Fetch flags on first launch? | Only after the privacy flow, asynchronously, never blocking | 3.2/3.3 |
| 10 | Report categories? | `bug`, `crash`, `performance`, `other`; fixed and validated | 3.5 |
| 11 | Read-only admin report access? | Keep `readonly` rejected unless deliberately changed; no bulk export | 3.1 |
| 12 | Legal basis and disclosures? | Legal/privacy review before deployment; update Play Data Safety | 3.6 |
| 13 | Queued report when toggle turns OFF? | Cancel unsent work and delete queued payload; keep submitted history | 3.4/3.5 |
| 14 | One report table or separate tables? | One `hmx.reports` table | 3.1 |
| 15 | Require `apikey` on public functions? | Send the publishable key; verify gateway behavior in staging | 3.1/3.2 |
| 16 | Translate new strings to all locales? | English first; no machine-translated privacy disclosures; translate before release if required | 3.3 |
| 17 | Canonical readme file? | Edit tracked `readme.md`; leave `README.md` artifact alone | 3.6 |
| 18 | Deployment timing? | Staging first; production only after separate explicit approval | 3.6 |
| 19 | Add tester-token plumbing or keep tester targeting backend-only? | Backend-only in Phase 3; no enrollment UI; add token storage/entry later if needed | 3.2 |
| 20 | Automatic retention scheduler mechanism? | `pg_cron` if verified in staging; otherwise a scheduled function or external scheduler; never assume policy alone deletes data | 3.1/3.6 |
| 21 | Revisit device model later? | Keep deferred; any future collection needs a separate approval, migration, preview disclosure, and policy update | 3.1 |

---

## 12. Recommended implementation order

### Step 0 — Approval of this plan

- Completed in plan revision 2: keep the tour first, then Privacy & Security, then Diagnostic Preferences; both toggles OFF by default; Next saves; Not now saves both OFF; both paths go Home; What’s New preserved.
- Confirmed: report-only install ID; never in config; no durability claim across reinstall.
- Confirmed: no device-model collection.
- Confirmed: 30-day server retention with an automatic purge.
- Confirmed: F-Droid-specific work out of scope.
- The backend source scope was authorized and implemented in revision 3. Remaining product decisions in §11.2 still require approval before Android work.

### Step 1 — Subphase 3.1: backend report foundation

- Migration + shared schema + `report-submit` + `admin-api` actions + tests. **Implemented in source on 2026-10-24.**
- No deployment.
- Node tests pass 69/69. SQL/Deno/live checks are blocked and documented.
- **Stop for review before deployment.**

### Step 2 — Subphase 3.2: Android privacy/config foundation

- BuildConfig, consent store, install UUID, HTTP client, flag cache, report core.
- No UI, no automatic network report.
- **Stop for review.**

### Step 3 — Subphase 3.3: onboarding and Settings

- Preserve the existing seven-page tour; add the two-step privacy flow after it.
- Gate integration, Settings page, accessibility.
- **Stop for review.**

### Step 4 — Subphase 3.4: diagnostic test report

- Payload, preview, Send, history, retry, cancellation.
- **Stop for review.**

### Step 5 — Subphase 3.5: report a problem

- Form, optional sanitized error, preview, Send, history, retry.
- **Stop for review.**

### Step 6 — Subphase 3.6: flags, copy, verification package

- Flag rollout, privacy copy, backup/manifest fixes, full runnable test pass, deployment runbook.
- **Stop for approval before any deployment.**

### Step 7 — Deployment (separate approval)

Exact commands and checks are in `docs/HMXPDF_PHASE3_BACKEND_VERIFICATION_REPORT.md`. Summary:

1. Preflight: confirm the branch, HEAD, clean review state, Supabase CLI availability, a staging project ref, and `psql`/`TEST_DATABASE_URL`. Confirm no deployed client still sends `install_id` to `config`.
2. Apply the migration to staging: `supabase link --project-ref <staging-ref>` then `supabase db push`. Verify `hmx.reports`, RLS, grants, the purge function, and both default-OFF flags.
3. Run `supabase/tests/reports_security.sql` and `supabase/tests/reports_retention.sql` against the staging database. Both must pass before proceeding.
4. Deploy `report-submit`, `config`, and `admin-api` to staging. `supabase/config.toml` sets `verify_jwt = false` for the public functions and `true` for `admin-api`.
5. Set `ADMIN_ORIGIN` with `supabase secrets set ADMIN_ORIGIN=<exact-origin>` and verify the admin origin/auth matrix.
6. Run live probes for validation, size cap, rate limits, idempotency, minimal acknowledgement, wrong origin, and missing `ADMIN_ORIGIN`.
7. Enable the automatic purge scheduler only after `pg_cron` is verified: run `supabase/retention/report_retention_cron.sql`, then confirm `cron.job` is active and `cron.job_run_details` shows a successful run. Confirm a 31-day-old row is deleted and a fresh row is kept. Do not claim retention until this is observed.
8. Update the privacy policy and store metadata, including 30-day retention.
9. Production rollout only after explicit approval and a repeat of steps 1–7 against production.

### Step 8 — Phase 4

Do not start Phase 4 until Phase 3 is accepted. The private Vercel admin panel is Phase 4 and is not designed or implemented here.

---

## Appendix A — Proposed onboarding copy (for approval)

Sequence: existing seven-page feature tour → **Privacy & Security** → **Diagnostic & Support Preferences** → Home. The tour itself is unchanged. What’s New is unchanged.

### Step 1 — Privacy & Security

**Heading:** Your documents stay on your device

**Body:**

> HMX PDF opens, edits, annotates, searches, and exports documents on your device. It does not upload document content, filenames, file paths, annotations, or edits.
>
> The app may contact the app administrator to check feature availability. Those requests send only the feature keys this version understands and its installed version number. They do not include an install ID.
>
> If you enable optional diagnostics or problem reports, you can review exactly what will be sent before anything leaves your device. Reports are never sent automatically. A report you explicitly send includes a random per-install ID so duplicate submissions can be recognized. It is not linked to an account and is replaced if you uninstall and reinstall the app.
>
> Server-side reports are designed to be deleted automatically 30 days after receipt. Automatic deletion is not yet active in this build; until it is deployed, reports are retained until an administrator deletes them.
>
> Diagnostic Reports and Report a Problem are both off by default. These choices are asked once in this version; Settings shows the saved choices and lets you send or manage reports but does not change consent.

**Action:** Next

### Step 2 — Diagnostic & Support Preferences

**Heading:** Diagnostics & support

**Body:** Both options are off by default. Turning one on lets you use that workflow; it does not send anything automatically.

| Control | Copy |
|---|---|
| Diagnostic Reports | Lets you send a test report and, when you choose, attach sanitized app details to a problem report. It does not read your documents. |
| Report a Problem | Lets you describe a problem and send it to the app administrator. You will see a preview before anything is sent. |

**Primary:** Next
**Secondary:** Not now
**Footer:** Privacy policy link

### Settings page copy guardrails

- Say “documents stay on your device”, not “no data leaves your device.”
- Say “only when you choose Send”, not “we collect.”
- Explain that problem descriptions may contain personal information; ask users not to include sensitive data.
- Explain that error messages can contain filenames or paths and that the preview allows removal.
- State that turning a toggle off cancels unsent reports but cannot recall already submitted ones.
- State that server-side reports expire after 30 days; do not promise manual-only deletion.
- Do not claim the install ID is durable across uninstall/reinstall or linked to an account.
- Do not mention or request device model; it is not collected.

---

## Appendix B — Subphase review checklist

For each subphase, the reviewer should confirm:

- [ ] Only the files listed for that subphase changed.
- [ ] No `main` branch change, merge, push, or force-push.
- [ ] No production migration, function deployment, secret, or Supabase setting change.
- [ ] No service-role key in Android, docs, logs, or git.
- [ ] No document content, filename, path, URI, annotation, password, or token in payload/storage/logs.
- [ ] No automatic report submission.
- [ ] Failure is never displayed as success.
- [ ] Tests claimed as passing were actually run; untested items are listed as blocked.
- [ ] Privacy copy matches the implemented behavior.
- [ ] Rollback path is documented and does not delete data silently.

---

## Appendix C — Verification commands

These commands were run on 2026-10-24 (backend) and 2026-10-25 (Phase 3.2 static checks) where the environment supported them. They do not build an APK. **Android JVM tests were not run locally**; the owner directed validation through GitHub Actions.

```bash
# All pure backend tests (run; 69/69 passed)
node --test supabase/tests/*.test.ts

# Individual suites
node --test supabase/tests/rollout.test.ts
node --test supabase/tests/admin_authz.test.ts
node --test supabase/tests/report_schema.test.ts
node --test supabase/tests/admin_reports.test.ts
node --test supabase/tests/config_request.test.ts
node --test supabase/tests/phase3_source.test.ts

# Syntax-only check (Deno is unavailable; this is not a Deno runtime test)
node --check supabase/functions/report-submit/index.ts
node --check supabase/functions/admin-api/index.ts
node --check supabase/functions/config/index.ts

# Android JVM tests: GitHub Actions only; NOT RUN in this task.
# The existing workflow already runs:
#   ./gradlew testPlayDebugUnitTest :pdf-core:test :ocr-core:test
#   ./gradlew lintPlayDebug
#   ./gradlew assemblePlayDebug
# A CI run requires a push/PR; none was performed.

# Static checks (Phase 3.2; run)
git diff --check
python3 -c "import xml.etree.ElementTree as ET; [ET.parse(p) for p in ['app/src/main/AndroidManifest.xml','app/src/foss/AndroidManifest.xml','app/src/fdroid/AndroidManifest.xml','app/src/main/res/xml/backup_rules.xml','app/src/main/res/xml/data_extraction_rules.xml']]"
git grep -n "service_role" -- app/src/main
git grep -n "device_model" -- app/src/main
git grep -n "install_id" -- app/src/main/java/com/hmx/pdf/data/remote
git grep -n "tester_token" -- app/src/main/java
git grep -nE "Log\.|println|printStackTrace" -- app/src/main/java/com/hmx/pdf/data/privacy app/src/main/java/com/hmx/pdf/data/remote app/src/main/java/com/hmx/pdf/data/report
git grep -n "RemoteConfig\|FeatureFlagClient\|ReportQueueStore\|ReportPayloadFactory" -- app/src/main/java | grep -v "data/remote\|data/report"

# SQL security and retention suites (disposable/staging database only; NOT RUN here)
psql -v ON_ERROR_STOP=1 "$TEST_DATABASE_URL" -f supabase/tests/reports_security.sql
psql -v ON_ERROR_STOP=1 "$TEST_DATABASE_URL" -f supabase/tests/reports_retention.sql

# Purge smoke test: seed a 31-day-old row and a fresh row, run the function,
# then confirm only the expired row was deleted.
psql -v ON_ERROR_STOP=1 "$TEST_DATABASE_URL" -c "select hmx.purge_expired_reports(1000);"
```

Expected static-check results: no `device_model` in app source; no `install_id` in the config request builder; no `tester_token` in Android; no secrets or forbidden document fields anywhere in the report code; no production caller of the new remote/report entry points.

Do not run a local APK build. Do not run these against production. Do not deploy from this plan.

---

## Appendix D — Implementation statement (revision 3)

This backend implementation task:

- implemented the Phase 3 backend source: reports migration, shared validation, `report-submit`, `admin-api` report actions, config privacy hardening, Phase 3 flags, tests, and a deployment-time retention scheduler file;
- ran all available Node tests: 54 passed, 0 failed;
- wrote SQL security and retention suites but did **not** run them because `psql` and a safe database are unavailable;
- did **not** run Deno Edge Functions because Deno is unavailable;
- did **not** deploy any migration, Edge Function, scheduler, or admin panel;
- did **not** change live database state or Supabase settings;
- did **not** build an APK or modify Android app code;
- did **not** stage, commit, or push;
- did **not** modify `main`;
- did **not** start Phase 4;
- preserved `docs/HMXPDF_FULL_SOURCE_AUDIT.md` and `docs/HMXPDF_PHASE3_SCOPE_AUDIT.md` unchanged.

**Approval requested.** Review the backend source and the verification report. Approve deployment to staging only after the SQL/RLS and retention suites can run there, `pg_cron` is verified, and the exact `ADMIN_ORIGIN` is available. Android integration remains a separate approval.

---

## Appendix E — Phase 3.2 implementation statement (revision 5)

This Phase 3.2 Android task:

- implemented the Android privacy/consent store, report-only install UUID, HTTPS config client/cache, report models/sanitizer/local queue, and WorkManager naming foundation;
- added the `INTERNET` permission, HTTPS-only manifest flag, correct backup/data-extraction schemas, and exclusions for consent, install UUID, flag cache, and no-backup report queue;
- wrote 11 JVM test files covering consent, install ID, config request/parser/cache, report models, sanitizer, queue, work naming, and static manifest/backup/build-config checks;
- did **not** compile or run any Android test locally, per the owner's instruction to use GitHub Actions; no CI run occurred because the branch was not committed or pushed;
- did **not** add onboarding, Settings, report UI, startup/navigation wiring, an `Application` class, a report submitter, a worker, or any automatic upload;
- did **not** change Supabase migrations/functions, deploy anything, or modify live state;
- did **not** stage, commit, push, merge, force-push, or switch branches;
- did **not** modify `main` or start Phase 3.3/Phase 4;
- preserved `docs/HMXPDF_FULL_SOURCE_AUDIT.md` and `docs/HMXPDF_PHASE3_SCOPE_AUDIT.md` unchanged.

**Validation status:** source-complete; Android build/JVM-test/lint status **CI-pending**. The existing `.github/workflows/android.yml` is the validation path (`testPlayDebugUnitTest`, `lintPlayDebug`, `assemblePlayDebug`). No pass/fail/skip claim is made.
