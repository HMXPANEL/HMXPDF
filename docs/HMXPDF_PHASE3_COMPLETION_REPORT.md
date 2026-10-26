# HMXPDF Phase 3 — Completion Report

- **Report date:** 2026-10-26
- **Repository:** `HMXPANEL/HMXPDF`
- **Branch:** `feature/hmxpdf-diagnostics-admin-updates`
- **Code HEAD validated:** `2a92d6fd116207a0f3212be6e383f55d4fa9f150` (source); this report's commit is the latest commit on the feature branch
- **`main` / `origin/main`:** `912e9977916c4f365147a294e60c3cd95d96960e` (unchanged)
- **Distribution:** private APK; no Google Play listing or Data Safety work
- **Status:** Phase 3 source is complete on the feature branch. Android CI is green on `cc8129d`, and the Android source is unchanged, so the verified debug/release artifacts remain valid. Backend source passes Node and Deno checks. The isolated staging project was revalidated live, including `report-submit` v2 after a Supabase JS API fix, `config` validation, both SQL suites, and the active retention scheduler. `PRIVACY.md` was rewritten for private distribution. Admin live authorization remains **BLOCKED** on `ADMIN_ORIGIN`; reviewed translations and device QA are **BLOCKED**; production was not deployed.

---

## 1. Gate status

| Gate | Status | Evidence |
|---|---|---|
| Android implementation | **PASS** | Commits `deb6373`, `4f47bdf`, `cc8129d` |
| Android JVM tests (CI) | **PASS** | CI run [40225659634](https://github.com/HMXPANEL/HMXPDF/actions/runs/40225659634), build job |
| Android lint (CI) | **PASS** | Same run; 0 errors, 24 warnings, 1 hint; baseline unchanged |
| Debug APK (CI) | **PASS** | `hmx-pdf-debug-apk`, 5 APKs, Android Debug signer |
| Signed release APK (CI) | **PASS** | `hmx-pdf-release-apk`, 5 APKs, existing release signer |
| Signed release bundle (CI) | **PASS** | `hmx-pdf-release-bundle`, `jarsigner` verified |
| Local Android Gradle re-run | **BLOCKED** | No Android SDK; release signing environment also absent. Exact errors in §5.1 |
| Backend source | **PASS** | Commit `24b2a90` plus the `report-submit` fix in this report's commit |
| Backend Node tests | **PASS** | `node --test supabase/tests/*.test.ts` → 69 passed, 0 failed |
| Backend type checks | **PASS** | `deno check` on all four entrypoints; strict `tsc` on the pure modules |
| Backend CI | **NOT RUN** | No backend workflow exists; only `.github/workflows/android.yml` |
| SQL security suite | **PASS** | Rerun on staging; explicit `security_ok` marker after the full suite |
| SQL retention suite | **PASS** | Rerun on staging; explicit `retention_ok` marker after the full suite |
| Staging deployment | **PASS** | Migrations, `report-submit` v2, `config` v1, `admin-api` v1, retention scheduler |
| `report-submit` live | **PASS** | Valid `201`, duplicate `200`, concurrent race `201` + `200`; rows cleaned |
| `config` live | **PASS** | Valid `200`; `install_id`, unknown fields, and invalid keys rejected |
| Automatic retention (staging) | **PASS** | `pg_cron` job active; runids 1–7 succeeded through 04:17 UTC; 30-day purge observed |
| Admin live integration | **BLOCKED** | `admin-api` fails closed with `503 admin_origin_not_configured`; no admin origin, frontend, or admin user exists |
| Privacy documentation | **PASS** | `PRIVACY.md` rewritten for private distribution; in-app link merge gate in §7 |
| Reviewed translations | **BLOCKED** | No reviewed Phase 3 translations; scoped lint exception retained |
| Accessibility source review | **PASS** | Labels/state descriptions/dialogs/errors audited; two gaps documented in §3.7 |
| TalkBack device verification | **BLOCKED** | No device or emulator |
| Device/emulator QA | **BLOCKED** | `adb devices` empty; no Android SDK; manual checklist added |
| Production deployment | **NOT RUN** | Read-only inspection only; no production writes |
| Production retention | **NOT RUN** | No `pg_cron`, reports table, or purge function in production |

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
| `ff430ec` | Record staging validation and remaining Phase 3 blockers |
| `2a92d6f` | Clarify staging SQL suite execution |
| This report's commit | Update private-distribution privacy policy, fix `report-submit` insert/upsert, add the source assertion and device QA checklist, refresh this report |

`origin/feature/hmxpdf-diagnostics-admin-updates` matches the local feature branch after the push. `main` and `origin/main` remain at `912e9977916c4f365147a294e60c3cd95d96960e`. No force-push, rebase, merge, branch replacement, or `main` checkout occurred.

The only untracked files left in the working tree are the two pre-existing audit inputs that the plan requires to remain unchanged and untracked:

- `docs/HMXPDF_FULL_SOURCE_AUDIT.md`
- `docs/HMXPDF_PHASE3_SCOPE_AUDIT.md`

---

## 3. Android delivered

### 3.1 First-run privacy flow

- The existing seven-page feature tour is unchanged.
- After the tour, the app shows **Privacy & Security** (step 1) and **Diagnostic Preferences** (step 2), then Home or What's New.
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
- The six supported non-English locale directories (`ar`, `es`, `it`, `pt`, `pt-rBR`, `ru`) contain no Phase 3 strings; English fallback is intentional until reviewed translations exist.
- Remove the ignore when reviewed translations are added.

### 3.7 Accessibility source review

Present in source:

- Privacy preference toggles have `contentDescription` set to the row title and `stateDescription` set to **On**/**Off**, with a 48 dp minimum target.
- Section titles use heading semantics.
- Step progress is also expressed as text (`Step 1 of 2` / `Step 2 of 2`).
- Confirmation dialogs have explicit titles, bodies, and labelled Cancel/Send or Cancel/Discard actions.
- Error states are text messages; adjacent icons are decorative (`contentDescription = null`).
- Report buttons use visible text labels; pending reports show state and attempt count as text.

Source-audit gaps (not changed in this pass):

- The problem-description `BasicTextField` has a separate visible label and hint but no explicit semantics association, so TalkBack may not announce the field label when focus enters it.
- `GlassChoiceChip` exposes its text label but not a selected-state semantic, so category selection may not be announced as selected.
- No TalkBack pass was possible without a device/emulator. These gaps are recorded for the manual QA checklist rather than claimed as verified.

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

Applied to staging as migration `20261026013546 hmx_reports`; the transactional security and retention suites pass against it.

### 4.2 `report-submit`

- Public `verify_jwt = false` endpoint; no service-role key in the app.
- 16 KiB body cap, strict top-level allowlist, fixed self-check keys, fixed categories, UUID validation, timestamp validation, and description limits.
- Unknown fields, including `device_model`, are rejected.
- Duplicate lookup happens before rate-limit consumption so a lost response can be retried without a false 429.
- Hashed-IP bucket: 10/hour. Hashed install bucket: 5/hour and 20/24 hours.
- Success returns only `report_id`, `status`, and `received_at`; `duplicate: true` is added for an existing row.
- Rate-limiter errors fail closed with 503.

A Deno type check found that the real Supabase store called `.insert(record, { onConflict, ignoreDuplicates })`, but `onConflict`/`ignoreDuplicates` are `upsert` options in `supabase-js`, not `insert` options. The store now uses `.upsert(record, { onConflict: "install_id,client_report_id", ignoreDuplicates: true })`. A source assertion was added to `supabase/tests/phase3_source.test.ts`. Staging was redeployed as `report-submit` v2 and re-tested with both a normal duplicate retry and a concurrent duplicate race.

### 4.3 Admin API

- `reports.list`, `reports.get`, `reports.update`, `reports.delete`, and `reports.purge` were added.
- Existing admin authorization, active-allowlist checks, origin checks, and audit logging remain in front of the new actions.
- List filters and limits are validated; update accepts only `status` and `admin_notes`; purge is fixed at 30 days and a bounded batch.
- Audit records contain action metadata, not report content.

Staging `admin-api` v1 is deployed with platform `verify_jwt = false`; `supabase/config.toml` specifies `true`, and the function performs its own `auth.getUser` plus active-allowlist check. Production `admin-api` v1 is deployed with `verify_jwt = true` but predates the Phase 3 report actions. Live staging POST and OPTIONS requests currently return `503 admin_origin_not_configured`; `GET` returns `405 method_not_allowed`. Function logs show `admin-api: ADMIN_ORIGIN is not configured`. This is the intended fail-closed behavior, but it prevents live admin authorization and report-action tests until `ADMIN_ORIGIN` is configured. No admin user or allowlist row exists in staging.

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

Applied to staging after installing `pg_cron` 1.6.4 (`cron.database_name = postgres`, `cron.timezone = GMT`). Job `hmx-purge-expired-reports` (jobid 1) is active at `17 * * * *`; runids 1–7 succeeded in `cron.job_run_details` through 2026-10-26 04:17 UTC. It has **not** been applied to production.

---

## 5. Verification evidence

### 5.1 Android CI and local Gradle checks

CI run: [40225659634](https://github.com/HMXPANEL/HMXPDF/actions/runs/40225659634) on `cc8129d` (Android source unchanged by later docs/backend commits).

| Job | Result | Steps |
|---|---|---|
| `build` | **success** | JVM unit tests, Android lint, debug APK build, debug APK upload |
| `release` | **success** | signing-secret validation, keystore materialization, signed APK, signed bundle, APK signature verification, bundle verification, artifact upload, keystore removal |
| `release-guard` | **success** | release/aggregate tasks reject missing signing |

Lint result: `Lint found 24 warnings, 1 hint (and 72 errors, 320 warnings and 8 hints filtered by baseline lint-baseline.xml)`. The 72 baseline errors are pre-existing; the new Phase 3 strings no longer produce `MissingTranslation` errors.

Local re-run was attempted with `bash gradlew` because `gradlew` lacks the executable bit in this checkout (the file mode was not changed):

```bash
bash gradlew testPlayDebugUnitTest :pdf-core:test :ocr-core:test
bash gradlew lintPlayDebug assemblePlayDebug :pdf-core:test :ocr-core:test
bash gradlew lintPlayDebug assemblePlayDebug assemblePlayRelease bundlePlayRelease :pdf-core:test :ocr-core:test
```

Results:

- Unit tests, lint, debug APK, `:pdf-core:test`, and `:ocr-core:test`: **BLOCKED** — `SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path ...`.
- Release APK/bundle: **BLOCKED** — `Release signing is not configured. Set CLEARPDF_UPLOAD_STORE_FILE, CLEARPDF_UPLOAD_STORE_PASSWORD, CLEARPDF_UPLOAD_KEY_ALIAS and CLEARPDF_UPLOAD_KEY_PASSWORD (or key.properties) before building a release artifact.` No `key.properties` exists and no signing secrets are present. No new signing identity was created.

The CI artifacts remain the verified build for the unchanged Android source.

### 5.2 Android artifacts

From the successful CI run; not rebuilt because Android source is unchanged:

| Artifact | Contents | Size | Archive SHA-256 |
|---|---|---|---|
| `hmx-pdf-debug-apk` | 5 debug APKs | 270,216,331 bytes | `b3318dd5dbaebcab98c3b7ca392293f6133885bfec3f554891acd083dd776f80` |
| `hmx-pdf-release-apk` | 5 release APKs | 132,999,214 bytes | `c36cfb3ae96714dbe269d3512209c551c6d66b74cb6c76e45973e27695746a4f` |
| `hmx-pdf-release-bundle` | `app-play-release.aab` | 50,421,429 bytes | `a87768b667770f4ec73ed21b363827c55832822517095df42c4a59dad76a795f` |

The hashes above are GitHub's SHA-256 of the downloaded artifact archives. Earlier local verification found the debug APK signed by Android Debug, the five release APKs signed by the existing release certificate, and the AAB `jarsigner`-verified with the same release DN. The run page is the private CI link; the artifact downloads require repository authentication.

### 5.3 Backend local checks

Commands run on the committed tree:

```bash
node --test supabase/tests/*.test.ts
npx --yes deno check supabase/functions/admin-api/index.ts \
  supabase/functions/report-submit/index.ts \
  supabase/functions/config/index.ts \
  supabase/functions/tester-enroll/index.ts
npx --yes -p typescript@5.9.3 tsc --noEmit --strict --target es2022 \
  --module nodenext --moduleResolution nodenext \
  --allowImportingTsExtensions --skipLibCheck \
  supabase/functions/config/request.ts supabase/functions/config/rollout.ts \
  supabase/functions/admin-api/authz.ts supabase/functions/admin-api/reports.ts \
  supabase/functions/report-submit/handler.ts
```

Result: **69 passed, 0 failed, 0 skipped, 0 todo**; Deno type checking passes for all four entrypoints; strict TypeScript checking passes for the pure modules. The Deno check caught the `insert`/`upsert` misuse described in §4.2; after the fix and the added source assertion, both checks pass.

Additional static checks:

- `git diff --check` clean.
- Secret scan found no service-role key, private key, token, or password in the changed source.
- No `device_model`, document content, filename, path, URI, annotation, form value, signature, password, token, raw log, screenshot, location, contact, or hardware identifier field exists in the report schema or Android payload.
- No production caller of a periodic report uploader or WorkManager report scheduler.

### 5.4 Production read-only check

The pre-existing app-configured project (`tchoqlaqgcrbbvomaebo`) was inspected read-only and left unchanged:

| Check | Result |
|---|---|
| `hmx.reports` table | **absent** |
| `hmx.purge_expired_reports(integer)` | **absent** |
| `hmx.purge_expired_rate_limits(integer)` | **absent** |
| `pg_cron` extension | **absent** |
| Phase 3 flag seeds | **absent** |
| Migrations | only `hmx_feature_flags` (`20261024105232`) |
| `report-submit` Edge Function | **not deployed** |
| Deployed `config` | **version 3**; still accepts optional `install_id`; no `install_id_not_allowed` rejection |
| Deployed `admin-api` | **version 1**, `verify_jwt = true`; no `reports.list` or `reports.purge` |
| Deployed `tester-enroll` | **version 3** |

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
| `report-submit` | 2 | `false` | ACTIVE; live probes pass, including the concurrent duplicate race |
| `config` | 1 | `false` | ACTIVE; live probes pass |
| `admin-api` | 1 | `false` | ACTIVE; fails closed pending `ADMIN_ORIGIN` |

**SQL suites (transactional, rolled back)**

| Suite | Result |
|---|---|
| `supabase/tests/reports_security.sql` | **PASS** — full suite reached the end; `security_ok` marker returned |
| `supabase/tests/reports_retention.sql` | **PASS** — full suite reached the end; `retention_ok` marker returned |

The psql-only `\set ON_ERROR_STOP on` line was stripped because validation ran through the MCP SQL executor; each suite's `begin; ... rollback;` body and success marker were executed unchanged. The marker query was appended only to prove the script reached its end; it did not alter the suite body. Staging counts after both suites: 0 reports, 0 rate-limit buckets, 0 audit rows.

The suites confirmed RLS with no policies, no client grants, service-role-only access, all kind/field/size constraints, idempotency identity, strict `< 30 days` retention, batch clamping (1–5,000), audit-row survival, 2-day rate-limit cleanup, and service-role-only purge functions.

**Live `report-submit` probes (v2)**

| Probe | Result |
|---|---|
| Valid diagnostic | `201` with `report_id`, `status`, `received_at` |
| Same payload retried | `200` with the same `report_id` and `duplicate: true` |
| Concurrent duplicate race | `201` on one request and `200 duplicate: true` on the other; exactly one row |
| Unknown field (`device_model`) | `400 unknown_field` |
| Invalid kind | `400 invalid_kind` |
| Oversized body | `413 body_too_large` |
| Install rate limit (5/hour) | `429 rate_limited`, `retry-after: 3600` |
| IP rate limit (10/hour) | `429 rate_limited` after the limit |

All live test rows and rate-limit buckets were deleted afterward. Staging is back to 0 reports and 0 rate-limit buckets.

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
- `cron.job_run_details`: runids 1–7, all `succeeded`, through 2026-10-26 04:17 UTC.
- Disposable rows: a 31-day-old and a 30-day-plus-1-hour report were deleted; 29-day-23-hour and fresh reports were kept. A 3-day-old rate-limit bucket was deleted; a fresh bucket was kept.
- The exact 30-day boundary is strict (`<`) and was covered by the SQL retention suite. All test rows were removed afterward.

**Admin API**

- Deployment succeeded, but live POST and OPTIONS requests return `503 admin_origin_not_configured`; `GET` returns `405 method_not_allowed`. Function logs show three `admin-api: ADMIN_ORIGIN is not configured` errors at 05:04:29–05:04:30 UTC, matching the three POST probes.
- The exposed MCP tool set has no secrets-management operation. `npx supabase` is available but has no login or access token. No admin frontend source exists in the repository, and no hosting/deployment credentials or Vercel project are available. There is no actual staging admin origin to configure, and inventing one or using a wildcard is prohibited.
- No admin JWT, allowlist row, or report action was exercised live. Admin report actions remain covered only by local Node tests.

**Staging cleanup state**

`hmx.reports` 0, `hmx.rate_limits` 0, `hmx.testers` 0, `hmx.admin_users` 0, `hmx.admin_audit_log` 0, `auth.users` 0, global kill switch `false`, Phase 3 flags OFF with 0% rollout.

### 5.6 Privacy documentation

`PRIVACY.md` was rewritten for private APK distribution. It now discloses:

- On-device document processing by default.
- The remote-configuration request fields (`keys` plus `app_version_code`) and that it runs after privacy onboarding/once per launch, with no install ID.
- Optional diagnostic/problem reports, explicit confirmation, preview, no background upload or automatic retry, and pending local reports.
- The report-only random install ID, its private no-backup storage, its reset behavior, and its deduplication/rate-limit purpose.
- Exact report fields for both report types, the optional attached-error schema field, and the current UI not filling it.
- Deliberately excluded data and the limits of client-side sanitization.
- Server storage in the private `hmx` schema, RLS with no policies, service-role-only access, admin origin/auth/allowlist/audit controls, and the current optional MFA.
- Staging-verified 30-day retention and 2-day rate-limit cleanup, plus the explicit limitation that production retention is not deployed or verified.
- Pending-report deletion, no self-service server deletion, and how to request deletion.
- Private distribution status and the absence of a Play Store listing/Data Safety form.

The in-app policy link still points to `https://github.com/HMXPANEL/HMXPDF/blob/main/PRIVACY.md`. Because `main` was not modified, the updated policy will not be visible through that link until the feature branch is merged. This is a distribution gate, not a code claim.

### 5.7 Translations and accessibility

- Phase 3 strings: 130 in `app/src/main/res/values/strings_phase3.xml`.
- Non-English locales `ar`, `es`, `it`, `pt`, `pt-rBR`, and `ru` have no Phase 3 translations. No machine translations were fabricated.
- `app/lint.xml` retains the `MissingTranslation` ignore scoped to `strings_phase3.xml` only; all other resources keep the check.
- Accessibility source review found the labels/state descriptions/dialogs/errors listed in §3.7, plus two gaps: the problem-description field label is not explicitly associated, and `GlassChoiceChip` does not expose selected-state semantics.
- No TalkBack or device accessibility pass was possible.

### 5.8 Device QA

`adb devices` returned no devices. No emulator, Android SDK, or `local.properties` is available. No device tests were executed, and no device results are claimed.

A precise manual checklist for the OPPO A38 (Android 15) was added at `docs/HMXPDF_PHASE3_DEVICE_QA_CHECKLIST.md`. It covers the 20 requested behaviors plus accessibility and signed-upgrade checks, with reset commands, evidence capture, and redaction rules. The conditional remote-flag test is marked blocked until admin/config access exists.

---

## 6. Requirement compliance

| Requirement | Status | Notes |
|---|---|---|
| Work only on the feature branch; `main` untouched | **PASS** | `main`/`origin/main` unchanged |
| Seven-page tour preserved; privacy after tour | **PASS (source/static)** | Device QA blocked |
| Privacy & Security, then Diagnostic Preferences, then Home/What's New | **PASS (source/static)** | Static tests in CI |
| Both consent options default OFF | **PASS (source/static)** | |
| Next saves exact selections; Not now saves both OFF | **PASS (source/static)** | |
| Saved selection does not reappear on ordinary restarts | **PASS (source/static)** | |
| Watch intro again does not re-enter privacy | **PASS (source/static)** | |
| Exactly one informational Settings entry below Office engine | **PASS (source)** | No toggles in Settings |
| No network from opening onboarding/Settings or changing consent | **PASS (source)** | No device network trace |
| No automatic report submission or periodic uploader | **PASS (source/static)** | Explicit Send only |
| Local consent wins over remote flags | **PASS (source/JVM)** | Device test conditional on admin access |
| Approved self-checks only; problem description required | **PASS (source/static)** | |
| Preview and explicit Send for both reports | **PASS (source/static)** | Live backend accepted both kinds; Android-to-staging end-to-end not run on a device |
| Success shown only after server acknowledgement | **PASS (source/static)** | |
| Failed reports stay pending with Retry/Discard | **PASS (source/static)** | |
| Duplicate retries do not create duplicate reports | **PASS (live staging)** | `201` + `200 duplicate`, concurrent race |
| Forbidden data never collected | **PASS (source/schema/static)** | |
| Install UUID report-only, random, private/no-backup | **PASS (source/JVM)** | |
| Config request contains only keys + installed version code | **PASS (Android source/JVM)** | Backend preserves backend-only `tester_token`; no app code sends it |
| Config fails closed on unavailable/invalid/stale/disabled flags | **PASS (JVM tests)** | |
| Retention copy marks automatic deletion as not yet active | **PASS (source)** | Staging scheduler verified; copy remains conservative until production deployment |
| Backend strict allowlist, idempotency, rate limits, RLS/grants, purge, rate-limit cleanup | **PASS (staging SQL/live)** | Security/retention suites plus live report/config probes pass |
| Admin authorization, active allowlist, origin checks, audit | **BLOCKED (live)** | Source/Node tests pass; `admin-api` fails closed until `ADMIN_ORIGIN` is set |
| Privacy documentation for private distribution | **PASS** | `PRIVACY.md` updated; in-app link merge gate |
| Reviewed translations | **BLOCKED** | No reviewed translations; scoped lint exception justified |
| Accessibility review | **PASS (source)** | Device TalkBack blocked; two gaps documented |
| Device QA | **BLOCKED** | No device/emulator/SDK; checklist added |
| Staging validation isolated from production | **PASS** | Disposable `hmxpdf-staging`; production inspected read-only |
| No production deployment, destructive tests, new signing identity, public release, or security weakening | **PASS** | Private CI artifacts only; staging only |

---

## 7. Remaining work before private distribution

1. **Configure `ADMIN_ORIGIN` and finish admin live validation.** This requires an actual admin frontend origin, a Supabase Dashboard session or Management API token with project secret access, and a disposable staging admin user/allowlist row. Set `ADMIN_ORIGIN` through the authorized dashboard/Management API path only; do not use a wildcard or invent an origin. Then test foreign-origin rejection, unauthenticated `401`, unauthorized `403`, active-admin authorization, `reports.list/get/update/delete/purge`, and audit rows. No admin frontend source exists in this repository.
2. **Merge the privacy policy to `main` or repoint the in-app link.** The Settings link targets `main`; until the updated `PRIVACY.md` is merged, the APK will open the previous policy.
3. **Translate the Phase 3 strings.** Add reviewed translations to the six locales and remove the `MissingTranslation` ignore for `strings_phase3.xml`. Do not use machine-translated privacy disclosures.
4. **Run the device QA checklist.** Use `docs/HMXPDF_PHASE3_DEVICE_QA_CHECKLIST.md` on the OPPO A38 Android 15. Record actual results; do not claim device testing before it is run.
5. **Production deployment (not authorized).** See §8.
6. **Update the retention copy after production verification.** The app currently says automatic 30-day deletion is not yet active. Change that only after the production scheduler has a successful run and the boundary is re-verified there.

---

## 8. Production deployment checklist (not executed)

1. Merge the reviewed feature branch to `main`; do not force-push or rewrite `main`.
2. Back up production data/configuration and confirm the current function versions for rollback.
3. Apply migrations `hmx_feature_flags` and `hmx_reports` to production; confirm RLS, grants, constraints, and purge functions.
4. Deploy the updated `config`, `report-submit`, and `admin-api` functions. Use `verify_jwt = true` for `admin-api`, matching `supabase/config.toml`; keep `report-submit`/`config` public as designed. Confirm the release build's Supabase URL, publishable key, config endpoint, and report endpoint point to production.
5. Set `ADMIN_ORIGIN` to the actual admin frontend origin through the Supabase Dashboard or Management API. Do not use a wildcard. Create/verify the production admin allowlist and audit path.
6. Install `pg_cron`, apply `supabase/retention/report_retention_cron.sql`, and confirm the job is active and a run succeeds.
7. Verify live: config rejects `install_id`/unknown fields; report-submit returns `201` and duplicate `200`; admin rejects foreign origins, unauthenticated requests, and non-allowlisted users; authorized report actions work; audit rows are written; retention deletes an expired disposable row while keeping a newer one.
8. Rollback considerations: restore the previous function versions; remove the cron job if retention must stop; disable report submission in the app by clearing the report endpoint/publishable key if the backend must be taken out of service; do not drop `hmx.reports` without a backup and explicit authorization.
9. Update the in-app retention copy only after production retention is verified.
10. Keep the private-distribution policy link current after merge.

---

## 9. Known limitations

- Admin live authorization and report actions are unverified. No admin frontend exists in the repository, no admin origin is configured, and no admin user/allowlist row exists in staging.
- The exposed MCP tool set has no secret-management operation; `npx supabase` has no login/token; no Dashboard session or Management API token is available in this environment.
- Local Android Gradle checks cannot run: no Android SDK, no `local.properties`, no `key.properties`, and no release signing environment. CI remains the verified build.
- No device or emulator is available; all device QA is pending.
- Reviewed Phase 3 translations are unavailable; six locales fall back to English.
- The problem-description field label association and choice-chip selected-state semantics remain accessibility gaps; no TalkBack pass was possible.
- Automatic 30-day deletion is verified on staging only. Production has no scheduler, reports table, or purge function.
- The updated privacy policy is on the feature branch; the in-app link points to `main` until merge.
- Staging `admin-api` v1 was deployed with `verify_jwt = false`; production `admin-api` v1 is `verify_jwt = true` but predates the Phase 3 report actions. The function performs its own auth, but the production deployment must use `verify_jwt = true` with the new code.
- There is no backend CI workflow. Local Node/Deno/TypeScript checks are the only automated backend validation.
- The staging project is disposable validation infrastructure; it is not the app-configured backend.
- The debug and release APKs have not been installed or exercised on a device or emulator.

---

## 10. Conclusion

Phase 3 source is complete on the feature branch. Android CI is green on `cc8129d` and the Android source is unchanged, so the existing debug APK, five signed release APKs, and signed release bundle remain the verified artifacts. Backend source passes 69 Node tests plus Deno and strict TypeScript checks. Staging was revalidated live: both SQL suites pass, `report-submit` v2 handles normal and concurrent duplicates correctly, `config` rejects identifiers and unknown fields, and the hourly retention scheduler has seven successful runs.

`PRIVACY.md` now describes the actual private-distribution behavior, including the limits of production retention. Admin report actions remain blocked on the missing admin origin/frontend and cannot be claimed as live-tested. Reviewed translations and device QA remain open. Production was not modified.

**Phase 3 is not production-ready.** No production migration, deployment, secret, scheduler, data change, or signing change was made.
