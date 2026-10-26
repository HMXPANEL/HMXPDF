# HMXPDF Phase 3 — Completion Report

- **Report date:** 2026-10-26
- **Repository:** `HMXPANEL/HMXPDF`
- **Branch:** `feature/hmxpdf-diagnostics-admin-updates`
- **Code HEAD validated:** `24b2a902a40be7c3b3598669f9dd42112927d5d3` (source); report commit follows `d394143`
- **`main` / `origin/main`:** `912e9977916c4f365147a294e60c3cd95d96960e` (unchanged)
- **Status:** Phase 3 source is complete on the feature branch. Android CI, JVM tests, lint, debug APK, and signed release artifacts pass. The backend was deployed to an isolated staging project and validated live: migrations, SQL security/retention suites, `report-submit`, `config`, and the hourly retention scheduler pass. **Production was not deployed.** Admin report actions remain **BLOCKED** on the `ADMIN_ORIGIN` secret, and policy, translations, and device QA remain open.

---

## 1. Gate status

| Gate | Status | Evidence |
|---|---|---|
| Android implementation | **COMPLETE** | Commits `deb6373`, `4f47bdf`, `cc8129d` |
| Android JVM tests | **PASS** | CI run [40225659634](https://github.com/HMXPANEL/HMXPDF/actions/runs/40225659634), build job step 5 |
| Android lint | **PASS** | 0 errors, 24 warnings, 1 hint; pre-existing baseline unchanged |
| Debug APK | **PASS** | `hmx-pdf-debug-apk`, 5 APKs, Android Debug signer |
| Signed release APK | **PASS** | `hmx-pdf-release-apk`, 5 APKs, existing release signer |
| Signed release bundle | **PASS** | `hmx-pdf-release-bundle`, `jarsigner` verified |
| Backend source | **COMPLETE** | Commit `24b2a90` |
| Backend Node tests | **PASS** | `node --test supabase/tests/*.test.ts` → 69 passed, 0 failed |
| Backend syntax checks | **PASS** | `node --check` on 12 Supabase TypeScript files |
| Backend CI | **NOT RUN** | No backend workflow exists; only `.github/workflows/android.yml` |
| SQL security suite | **PASS** | Executed on staging `jxeuwqmdxwqqvyhisrhg`; explicit `reports_security_tests_passed` |
| SQL retention suite | **PASS** | Executed on staging; explicit `reports_retention_tests_passed` |
| Deno runtime execution | **PASS** | `report-submit` v1, `config` v1, and `admin-api` v1 deployed ACTIVE on staging; live probes below |
| Staging deployment | **PASS** | Two migrations, three Edge Functions, and the retention scheduler applied to staging |
| Automatic retention | **PASS (staging)** | `pg_cron` 1.6.4 job active; four successful runs; 30-day purge observed. Production **NOT RUN** |
| Admin live integration | **BLOCKED** | `admin-api` fails closed with 503 because `ADMIN_ORIGIN` cannot be set via the exposed MCP tools |
| Production deployment | **NOT RUN** | No production migration, function, secret, or scheduler change |
| Privacy policy / store metadata | **NOT RUN** | `PRIVACY.md` is outdated and must be updated before distribution |
| New-string translations | **NOT RUN** | English-first by plan; lint scoped to the Phase 3 strings only |
| Device/emulator QA | **NOT RUN** | No Android SDK, emulator, or device in this environment |

The release artifacts are **private CI artifacts only**. No GitHub release, tag, public upload, or production deployment was created. The existing release signing identity was reused; no new signing identity was created.

---

## 2. Branch and commits

| Commit | Purpose |
|---|---|
| `deb6373` | Add Phase 3 privacy onboarding and explicit reports (Android slice) |
| `4f47bdf` | Make the report queue exception open for subclassing |
| `cc8129d` | Scope missing-translation lint to Phase 3 strings |
| `24b2a90` | Add Phase 3 reports backend, retention, and config privacy |
| `d394143` | Document Phase 3 completion and open deployment gates |

`origin/feature/hmxpdf-diagnostics-admin-updates` matches the local feature branch. `main` and `origin/main` remain at `912e9977916c4f365147a294e60c3cd95d96960e`. No force-push, rebase, merge, branch replacement, or `main` checkout occurred.

The only untracked files left in the working tree are the two pre-existing audit inputs that the plan requires to remain unchanged and untracked:

- `docs/HMXPDF_FULL_SOURCE_AUDIT.md`
- `docs/HMXPDF_PHASE3_SCOPE_AUDIT.md`

---

## 3. Android delivered

### 3.1 First-run privacy flow

- The existing seven-page feature tour is unchanged.
- After the tour, the app shows **Privacy & Security** (step 1) and **Diagnostic & Support Preferences** (step 2), then Home or What's New.
- Both consent options default **OFF**.
- **Next** on step 2 saves the exact selections. **Not now** saves both OFF.
- The decision is persisted in `clearpdf_privacy.xml`; ordinary restarts do not show the flow again.
- Existing installs see the flow once because no privacy-completion flag existed before this version.
- Replaying the tour from Settings never re-enters the privacy flow.
- Back on step 2 returns to step 1. Leaving at step 1 leaves privacy onboarding pending for the next ordinary launch; no partial consent is assumed.
- Incoming share/shortcut launches skip the tour and privacy flow and leave them pending for the next ordinary launch.

### 3.2 Settings

- Exactly one informational **Privacy & Security** row was added directly below **Office engine**.
- Existing Settings items, ordering, theme, navigation, and **Watch intro again** are preserved.
- The page shows consent status, report entry points, retention text, and the policy link.
- It contains **no consent toggles**. Consent is changed only in the first-run flow in this phase.
- Diagnostic and problem report actions are disabled when the corresponding consent is OFF.

### 3.3 Reports

- **Diagnostic report:** four approved self-checks only — storage write, cache write, PDF engine initialization, and report queue initialization.
- **Problem report:** required user description (1–4,000 characters) plus a fixed category (`bug`, `crash`, `performance`, `other`). The optional attached-error input is not implemented in the UI.
- Both flows show a full payload preview and require an explicit **Send** confirmation.
- Reports are queued in `noBackupFilesDir`; failures remain pending with explicit **Retry** and **Discard** actions.
- There is no automatic report submission, no periodic uploader, no background retry, and no crash upload.
- Local consent is checked first; remote availability cannot override an OFF choice.
- Failed or unavailable sends fail closed.
- The install UUID is random, report-only, stored in private excluded preferences, and never sent in config requests. It has no reinstall durability.

### 3.4 Config

- The Android request contains only the understood flag keys and `app_version_code`.
- No install ID, tester token, device model, or persistent identifier is sent.
- Response parsing rejects malformed or oversized data and ignores unknown flags/fields.
- Cache freshness is 60 seconds; last-known-good is honored for at most 24 hours; missing, stale, invalid, disabled, or killed flags resolve OFF.
- One non-blocking refresh runs after privacy completion and once per ordinary launch after completion. Opening onboarding, Settings, or changing a consent toggle performs no network request.
- Explicit report Send may refresh availability once and still fails closed.

### 3.5 Backup and manifest

- `clearpdf_privacy.xml` and `clearpdf_flags.xml` are excluded from cloud backup and device transfer.
- Report envelopes live in `noBackupFilesDir` and are inherently excluded.
- The manifest keeps `usesCleartextTraffic="false"` and declares `INTERNET`.

### 3.6 Strings and lint

- 130 Phase 3 privacy/report strings were moved to `app/src/main/res/values/strings_phase3.xml`.
- `app/lint.xml` suppresses `MissingTranslation` **for that file only**, because the plan forbids machine-translated privacy disclosures. All other resource files keep the check.
- `lintConfig = file("lint.xml")` makes the scoping explicit.
- Remove the ignore when reviewed translations are added.

---

## 4. Backend delivered

### 4.1 Reports storage

`supabase/migrations/20261024164504_hmx_reports.sql` adds:

- `hmx.reports` with UUID identity, kind/category/description/self-check constraints, size caps, and `(install_id, client_report_id)` uniqueness.
- Indexes for received time, kind/status, and install ID.
- RLS enabled with **no policies**; `public`, `anon`, and `authenticated` have no table grants; only `service_role` is granted.
- `hmx.purge_expired_reports(batch_size)` deleting at most 1–5,000 rows older than 30 days by server `received_at`.
- `hmx.purge_expired_rate_limits(batch_size)` deleting at most 1–20,000 buckets older than 2 days, with a supporting index.
- Default-OFF, 0% seeds for `diagnostics.enabled` and `problem_reports.enabled`.

Applied to staging as migration `20261026013546 hmx_reports`; the transactional security and retention suites passed against it.

### 4.2 `report-submit`

- Public `verify_jwt = false` endpoint; no service-role key in the app.
- 16 KiB body cap, strict top-level allowlist, fixed self-check keys, fixed categories, UUID validation, timestamp validation, and description limits.
- Unknown fields, including `device_model`, are rejected.
- Duplicate lookup happens before rate-limit consumption so a lost response can be retried without a false 429.
- Hashed-IP bucket: 10/hour. Hashed install bucket: 5/hour and 20/24 hours.
- Insert uses the unique identity with duplicate suppression; a concurrent race resolves to the existing row.
- Success returns only `report_id`, `status`, and `received_at`; `duplicate: true` is added for an existing row.
- Rate-limiter errors fail closed with 503.

Deployed to staging as `report-submit` v1 (`verify_jwt = false`) and exercised with live requests.

### 4.3 Admin API

- `reports.list`, `reports.get`, `reports.update`, `reports.delete`, and `reports.purge` were added.
- Existing admin authorization, active-allowlist checks, origin checks, and audit logging remain in front of the new actions.
- List filters and limits are validated; update accepts only `status` and `admin_notes`; purge is fixed at 30 days and a bounded batch.
- Audit records contain action metadata, not report content.

Deployed to staging as `admin-api` v1 (`verify_jwt = false`). Live POST and OPTIONS requests currently return `503 admin_origin_not_configured`; `GET` returns `405 method_not_allowed`. This is the intended fail-closed behavior, but it prevents live admin authorization and report-action tests until `ADMIN_ORIGIN` is configured.

### 4.4 Config privacy

- `config/request.ts` rejects `install_id` with `400 install_id_not_allowed` and rejects unknown top-level fields.
- Flag evaluation always uses `installId: null`; fractional rollout therefore fails closed with `missing_install_id`.
- The existing optional backend-only `tester_token` is preserved for tester/segment targeting, as the plan directs. No Android code sends it, and there is no tester enrollment UI in Phase 3.
- `supabase/config.toml` registers `report-submit` with `verify_jwt = false`.

Deployed to staging as `config` v1 (`verify_jwt = false`) and exercised with live requests.

### 4.5 Retention scheduler

`supabase/retention/report_retention_cron.sql` is a deployment-time file, not a migration. It:

- Refuses to run if `pg_cron` or either purge function is missing.
- Replaces the `hmx-purge-expired-reports` job if it already exists.
- Schedules an hourly run at minute 17.
- Includes verification queries for `cron.job`, `cron.job_run_details`, and expired-row counts.

Applied to staging after installing `pg_cron` 1.6.4 (`cron.database_name = postgres`, `cron.timezone = GMT`). Job `hmx-purge-expired-reports` (jobid 1) is active at `17 * * * *`; four runs succeeded in `cron.job_run_details`. It has **not** been applied to production.

---

## 5. Verification evidence

### 5.1 Android CI

Run: [40225659634](https://github.com/HMXPANEL/HMXPDF/actions/runs/40225659634) on `cc8129d` (Android source unchanged by the later docs/backend commit).

| Job | Result | Steps |
|---|---|---|
| `build` | **success** | JVM unit tests, Android lint, debug APK build, debug APK upload |
| `release` | **success** | signing-secret validation, keystore materialization, signed APK, signed bundle, APK signature verification, bundle verification, artifact upload, keystore removal |
| `release-guard` | **success** | release/aggregate tasks reject missing signing |

Lint result: `Lint found 24 warnings, 1 hint (and 72 errors, 320 warnings and 8 hints filtered by baseline lint-baseline.xml)`. The 72 baseline errors are pre-existing; the new Phase 3 strings no longer produce `MissingTranslation` errors.

### 5.2 Android artifacts

Downloaded from the CI run and verified locally:

| Artifact | Contents | Signature |
|---|---|---|
| `hmx-pdf-debug-apk` | 5 debug APKs (arm64-v8a, armeabi-v7a, universal, x86, x86_64) | Android Debug, SHA-256 `55c522257ada04efd391b4607d2d3a7f4477417eea1b996fee5051fb50d32351` |
| `hmx-pdf-release-apk` | 5 release APKs | Existing release certificate, SHA-256 `e30ab37d83b52a0fd8a15da51a0d323107043daa9ac773fa44f17968348614d3` on all five |
| `hmx-pdf-release-bundle` | `app-play-release.aab` | `jarsigner` verified; same release DN, self-signed, expires 2054-03-10 |

Artifact sizes: debug zip 270,216,331 bytes; release zip 132,999,214 bytes; bundle zip 50,421,429 bytes.

### 5.3 Backend local checks

Commands run on the committed tree:

```bash
node --test supabase/tests/*.test.ts
node --check supabase/functions/report-submit/index.ts
node --check supabase/functions/report-submit/handler.ts
node --check supabase/functions/admin-api/index.ts
node --check supabase/functions/admin-api/reports.ts
node --check supabase/functions/config/index.ts
node --check supabase/functions/config/request.ts
node --check supabase/functions/_shared/report-schema.ts
```

Result: **69 passed, 0 failed, 0 skipped, 0 todo**; all seven source files listed above and the five new TypeScript test files parse.

Additional static checks:

- `git diff --check` clean.
- XML parsed successfully for the manifest, both backup-rule files, both string files, `lint.xml`, and the lint baseline.
- Secret scan found no service-role key, private key, token, or password in the new source.
- No `device_model`, document content, filename, path, URI, annotation, form value, signature, password, token, raw log, screenshot, location, contact, or hardware identifier field exists in the report schema or Android payload.
- No production caller of a periodic report uploader or WorkManager report scheduler.

### 5.4 Read-only deployment check

The pre-existing app-configured project (`tchoqlaqgcrbbvomaebo`) was inspected read-only and left unchanged:

| Check | Result |
|---|---|
| `hmx.reports` table | **absent** |
| `hmx.purge_expired_reports(integer)` | **absent** |
| `hmx.purge_expired_rate_limits(integer)` | **absent** |
| `pg_cron` extension | **absent** |
| Phase 3 flag seeds | **absent** |
| `report-submit` Edge Function | **not deployed** |
| Deployed `config` | **version 3**; still accepts optional `install_id`; no `install_id_not_allowed` rejection |
| Deployed `admin-api` | **version 1**; no `reports.list` or `reports.purge` |

No production SQL writes, migrations, deployments, or live probes were performed.

### 5.5 Staging deployment and live validation

An isolated free-plan project `hmxpdf-staging` (`jxeuwqmdxwqqvyhisrhg`, region `ap-northeast-2`, status `ACTIVE_HEALTHY`) was created for this validation. It is separate from the app-configured project `tchoqlaqgcrbbvomaebo`; no production data or configuration was touched.

**Migrations applied**

| Version | Name | Notes |
|---|---|---|
| `20261026013533` | `hmx_feature_flags` | Phase 2 base schema |
| `20261026013546` | `hmx_reports` | Phase 3 reports schema, purge functions, flag seeds |
| `20261026014711` | `enable_pg_cron_staging` | Staging-only `create extension pg_cron`; not a repository migration |

**Edge Functions deployed**

| Function | Version | `verify_jwt` | Result |
|---|---|---|---|
| `report-submit` | 1 | `false` | ACTIVE; live probes pass |
| `config` | 1 | `false` | ACTIVE; live probes pass |
| `admin-api` | 1 | `false` | ACTIVE; fails closed pending `ADMIN_ORIGIN` |

**SQL suites (transactional, rolled back)**

| Suite | Result |
|---|---|
| `supabase/tests/reports_security.sql` | **PASS** — explicit `reports_security_tests_passed` |
| `supabase/tests/reports_retention.sql` | **PASS** — explicit `reports_retention_tests_passed` |

The psql-only `\set ON_ERROR_STOP on` line was stripped because validation ran through the MCP SQL executor; each suite's `begin; ... rollback;` body and success marker were executed unchanged.

The suites confirmed RLS with no policies, no client grants, service-role-only access, all kind/field/size constraints, idempotency identity, strict `< 30 days` retention, batch clamping (1–5,000), audit-row survival, 2-day rate-limit cleanup, and service-role-only purge functions.

**Live `report-submit` probes**

| Probe | Result |
|---|---|
| Valid diagnostic | `201` with `report_id`, `status`, `received_at` |
| Duplicate diagnostic | `200` with `duplicate: true` |
| Valid problem | `201` |
| Unknown field (`device_model`) | `400 unknown_field` |
| Invalid kind | `400 invalid_kind` |
| Oversized body | `413 body_too_large` |
| Install rate limit (5/hour) | `429 rate_limited`, `retry-after: 3600` |
| IP rate limit (10/hour) | `429 rate_limited` after the limit |

**Live `config` probes**

| Probe | Result |
|---|---|
| Valid request | `200`; both flags OFF; `max_age_seconds: 60` |
| `install_id` present | `400 install_id_not_allowed` |
| Unknown field | `400 unknown_field` |
| Invalid flag keys | `400 invalid_keys` |
| Malformed tester token | `400 invalid_tester_token` |
| Valid-format unenrolled token | `200`; flags OFF |
| Oversized body | `413` |
| `GET` / `OPTIONS` | `405` / `204` |
| Global kill switch, flag kill switch, disabled, 0%, 100%, 50% without install ID, version mismatch, not-started, expired, unknown flag | Fail closed as designed (`disabled`, `rollout`, `missing_install_id`, `version_mismatch`, `not_started`, `expired`, absent) |
| Tester grant / segment grant / disabled overrides tester | Enabled via `tester` / `segment`; disabled wins |

**Retention scheduler**

- `pg_cron` 1.6.4 installed; `cron.database_name = postgres`, `cron.timezone = GMT`.
- `supabase/retention/report_retention_cron.sql` applied with SQL execution, not as a repository migration.
- Job `hmx-purge-expired-reports` (jobid 1) is active at `17 * * * *`, owner `postgres`, database `postgres`.
- Temporarily changed to `* * * * *` to obtain scheduler run history, then restored to `17 * * * *`.
- `cron.job_run_details`: runids 1–4, all `succeeded`.
- Disposable rows: a 31-day-old and a 30-day-plus-1-hour report were deleted; 29-day-23-hour and fresh reports were kept. A 3-day-old rate-limit bucket was deleted; a fresh bucket was kept.
- The exact 30-day boundary is strict (`<`) and was covered by the SQL retention suite. All test rows were removed afterward; staging currently has 0 reports and 0 rate-limit buckets.

**Admin API**

- Deployment succeeded, but live POST and OPTIONS requests return `503 admin_origin_not_configured`; `GET` returns `405 method_not_allowed`. Function logs show `admin-api: ADMIN_ORIGIN is not configured`.
- The exposed MCP tool set has no secrets-management operation, so `ADMIN_ORIGIN` cannot be set from this environment. This is an environment/credential blocker, not a source defect.
- No admin JWT, allowlist row, or report action was exercised live. Admin report actions remain covered only by local Node tests.

---

## 6. Requirement compliance

| Requirement | Status | Notes |
|---|---|---|
| Work only on the feature branch; `main` untouched | **PASS** | `main`/`origin/main` unchanged |
| Seven-page tour preserved; privacy after tour | **PASS (source/static)** | Device QA not run |
| Privacy & Security, then Diagnostic Preferences, then Home/What's New | **PASS (source/static)** | Static tests in CI |
| Both consent options default OFF | **PASS (source/static)** | |
| Next saves exact selections; Not now saves both OFF | **PASS (source/static)** | |
| Saved selection does not reappear on ordinary restarts | **PASS (source/static)** | |
| Exactly one informational Settings entry below Office engine | **PASS (source)** | No toggles in Settings |
| No network from opening onboarding/Settings or changing consent | **PASS (source)** | No device network trace |
| No automatic report submission or periodic uploader | **PASS (source/static)** | Explicit Send only |
| Approved self-checks only; problem description required | **PASS (source/static)** | |
| Preview and explicit Send for both reports | **PASS (source/static)** | Live `report-submit` accepted both kinds; Android-to-staging end-to-end not run on a device |
| Forbidden data never collected | **PASS (source/schema/static)** | |
| Install UUID report-only, random, private/no-backup | **PASS (source/JVM tests)** | |
| Config request contains only keys + installed version code | **PASS (Android source/JVM tests)** | Backend preserves backend-only `tester_token`; no app code sends it |
| Config fails closed on unavailable/invalid/stale/disabled flags | **PASS (JVM tests)** | |
| Retention copy marks automatic deletion as not yet active | **PASS (source)** | Staging scheduler verified; copy remains conservative until production deployment |
| Backend strict allowlist, idempotency, rate limits, RLS/grants, purge, rate-limit cleanup | **PASS (staging SQL/live)** | Security and retention suites plus live report/config probes pass |
| Admin authorization, active allowlist, origin checks, audit | **BLOCKED (live)** | Source/Node tests pass; `admin-api` fails closed until `ADMIN_ORIGIN` is set |
| Staging validation isolated from production | **PASS** | Disposable `hmxpdf-staging`; production project inspected read-only |
| No production deployment, destructive tests, new signing identity, public release, or security weakening | **PASS** | Private CI artifacts only; staging only |

---

## 7. Remaining work before deployment

1. **Configure `ADMIN_ORIGIN` and finish admin live validation.** Set the secret through the Supabase dashboard or Management API (the exposed MCP tools have no secrets operation), then verify foreign-origin rejection, unauthenticated `401`, active-allowlist authorization, `reports.list/get/update/delete/purge`, and audit rows with a disposable staging admin user.
2. **Deploy to production.** Not authorized in this task. Apply both migrations, deploy `report-submit`, `config`, and `admin-api`, set `ADMIN_ORIGIN`, install `pg_cron`, apply `supabase/retention/report_retention_cron.sql`, and confirm a successful production run before announcing automatic retention.
3. **Update policy and store metadata.** Rewrite `PRIVACY.md` to disclose config requests, optional reports, the report-only install ID, access, retention, and deletion. Update Play Data Safety. The current policy still says there is no crash-reporting service.
4. **Translate the Phase 3 strings.** Add reviewed translations to the six locales and remove the `MissingTranslation` ignore for `strings_phase3.xml`.
5. **Device QA.** Verify the tour/privacy order, both completion paths, restart persistence, share/shortcut bypass, report success/failure/retry/discard, consent-OFF behavior, backup/restore exclusions, accessibility, and signed-release upgrade.
6. **Admin frontend.** No admin UI exists in this repository. The Phase 4 Vercel panel remains separate.
7. **Re-run Android CI after any Android change.** The current green run applies to the Android source at `cc8129d`; `24b2a90` changed only backend files and docs.
8. **Update retention copy after production verification.** The app currently says automatic 30-day deletion is not yet active. Change that only after the production scheduler has a successful run and the boundary is re-verified there.

---

## 8. Known limitations

- `DocsApp.needsPrivacyOnboarding` is read once for the session. After privacy completion it remains true until the next launch, suppressing recovery and star prompts for that session. This is not a privacy or data-loss issue; the prompts return on the next launch.
- Fractional config rollout is unavailable to the app because no install ID is sent. 1–99% flags resolve OFF with `missing_install_id`. Tester/segment targeting remains backend-only.
- The backend accepts the existing optional `tester_token`; the Android app never sends it.
- There is no backend CI workflow. Local Node tests and syntax checks are the only automated backend validation.
- The staging admin API cannot be exercised until `ADMIN_ORIGIN` is configured; live admin authorization and report actions are unverified.
- Automatic 30-day deletion is verified on staging only. Production has no scheduler until the production runbook is executed.
- The staging project is disposable validation infrastructure; it is not the app-configured backend.
- The debug and release APKs have not been installed or exercised on a device or emulator.
- New privacy/report strings are English-only pending reviewed translations.
- `PRIVACY.md` is outdated and must not be published as-is.

---

## 9. Conclusion

Phase 3 source is complete on the feature branch and Android CI is green, including JVM tests, lint, a debug APK, five signed release APKs, and a signed release bundle. Backend source is complete and its local Node suites pass. The backend is deployed and validated on an isolated staging project: both SQL suites pass, `report-submit` and `config` pass live validation, and the hourly `pg_cron` retention job has successful run history with observed 30-day deletion.

Phase 3 is **not production-ready**. Admin report actions remain blocked on `ADMIN_ORIGIN`; production has not been deployed; the policy/store-metadata update, reviewed translations, and device QA remain open. No production system was modified.
