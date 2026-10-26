# HMXPDF Phase 3 — Completion Report

- **Report date:** 2026-10-26
- **Repository:** `HMXPANEL/HMXPDF`
- **Branch:** `feature/hmxpdf-diagnostics-admin-updates`
- **Code HEAD validated:** `7c2fadd` (Phase 3 source + private admin panel); this report's commit is a docs-only follow-up on the feature branch
- **`main` / `origin/main`:** `912e9977916c4f365147a294e60c3cd95d96960e` (unchanged)
- **Distribution:** private APK; no Google Play listing or Data Safety work
- **Status:** Phase 3 source and the private admin panel are complete on the feature branch. Android CI is green on `cc8129d`, and the Android source is unchanged, so the verified debug/release artifacts remain valid. Backend source passes 72 Node tests plus Deno and strict TypeScript checks. The admin frontend passes typecheck, lint, 30 tests, a production build, and a secret scan. Staging `admin-api` v2 is deployed with `session.whoami`, `reports.stats`, and bounded/filterable `audit.list`. The isolated staging project was revalidated live, including `report-submit` v2, `config` validation, both SQL suites, and the active retention scheduler. `PRIVACY.md` was rewritten for private distribution. Admin live authorization and the Vercel Preview remain **BLOCKED** on valid Vercel access, an actual Preview origin, `ADMIN_ORIGIN`, and an approved staging admin identity; reviewed translations and device QA are **BLOCKED**; production was not deployed.

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
| Backend source | **PASS** | Commit `24b2a90`, later fixes, and admin API v2 in `7c2fadd` |
| Backend Node tests | **PASS** | `node --test supabase/tests/*.test.ts` → 72 passed, 0 failed |
| Backend type checks | **PASS** | `deno check` on all four entrypoints; strict `tsc` on the pure modules |
| Backend CI | **NOT RUN** | No backend workflow exists; only `.github/workflows/android.yml` |
| SQL security suite | **PASS** | Rerun on staging; explicit `security_ok` marker after the full suite |
| SQL retention suite | **PASS** | Rerun on staging; explicit `retention_ok` marker after the full suite |
| Staging deployment | **PASS** | Migrations, `report-submit` v2, `config` v1, `admin-api` v2, retention scheduler |
| `report-submit` live | **PASS** | Valid `201`, duplicate `200`, concurrent race `201` + `200`; rows cleaned |
| `config` live | **PASS** | Valid `200`; `install_id`, unknown fields, and invalid keys rejected |
| Automatic retention (staging) | **PASS** | `pg_cron` job active; runids 1–7 succeeded through 04:17 UTC; 30-day purge observed |
| Admin frontend source | **PASS** | `admin/` Vite + React + TypeScript SPA; no service-role key |
| Admin frontend checks | **PASS** | Typecheck, lint, 30 tests, production build |
| Admin frontend secret scan | **PASS** | No privileged secret or source map in source/bundle |
| `admin-api` v2 staging deployment | **PASS** | New actions deployed; fail-closed probes pass |
| Vercel Preview deployment | **BLOCKED** | No valid Vercel token/session or project access |
| Vercel private access protection | **BLOCKED** | Requires a Vercel project and account access |
| `ADMIN_ORIGIN` configuration | **BLOCKED** | No Supabase secret-management access and no deployed origin |
| Admin live integration | **BLOCKED** | `admin-api` fails closed with `503 admin_origin_not_configured`; staging has 0 auth users and 0 admin allowlist rows |
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
| `2d1b2f8` | Document private distribution and fix report upsert |
| `7c2fadd` | Add the private admin panel, admin API v2 actions, tests, and deployment documentation |
| This report's commit | Record the admin panel commit hash and CI status; refresh this report |

`origin/feature/hmxpdf-diagnostics-admin-updates` matches the local feature branch after the push. `main` and `origin/main` remain at `912e9977916c4f365147a294e60c3cd95d96960e`. No force-push, rebase, merge, branch replacement, or `main` checkout occurred.

GitHub Actions: commit `7c2fadd` did not trigger a workflow run because `.github/workflows/android.yml` runs only on pushes to `main`, version tags, and pull requests. The last Android CI run remains [40225659634](https://github.com/HMXPANEL/HMXPDF/actions/runs/40225659634) on `cc8129d`; Android source is unchanged. No backend workflow exists, so backend/frontend checks are local only.

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

- Existing actions cover flags, assignments, settings/kill switch, testers, invites, audit, and report list/get/update/delete/purge.
- `reports.list`, `reports.get`, `reports.update`, `reports.delete`, and `reports.purge` remain the only report mutations.
- New `session.whoami` returns the authorized user ID and role, so the frontend can prove the allowlist check instead of treating sign-in as access.
- New `reports.stats` performs six exact count queries and returns `total`, `pending`, `by_kind`, and `by_status`. Counts are not inferred from a page of rows.
- `audit.list` now accepts an optional exact `action`, a `before` cursor, and a limit of 1–100; the previous hard-coded 100-row response is gone.
- `reports.get` and `reports.update` select explicit detail columns and omit `install_id` and `client_report_id`. List rows already omit report content.
- Existing admin authorization, active-allowlist checks, origin checks, and audit logging remain in front of every action. Audit records contain action metadata, not report content.
- Request validation is strict: bounded bodies, UUID/status/kind checks, a 1–4,000 character admin note, a fixed 30-day purge age, and a bounded purge batch.

Staging `admin-api` v2 is deployed with platform `verify_jwt = false`; `supabase/config.toml` specifies `true`, and the function performs its own `auth.getUser` plus active-allowlist check. Production `admin-api` v1 is deployed with `verify_jwt = true` but predates the Phase 3 report actions and v2 additions. Live staging POST, OPTIONS, and bogus-token probes currently return `503 admin_origin_not_configured`; `GET` returns `405 method_not_allowed`. Function logs show `admin-api: ADMIN_ORIGIN is not configured`. This is the intended fail-closed behavior, but it prevents live origin/auth/report-action tests until `ADMIN_ORIGIN` is configured. No admin user or allowlist row exists in staging.

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

### 4.6 Admin panel frontend

- Located at `admin/` on the feature branch. No separate owner-approved admin frontend repository exists, so the existing HMXPDF repository is used; no repository URL was invented.
- Vite + React + TypeScript single-page app. Routes: `/login`, `/dashboard`, `/reports`, `/reports/:id`, `/flags`, and `/audit`. All non-login routes are protected by `RequireAdmin`.
- Sign-in uses Supabase Auth email/password. The session is tab-scoped in `sessionStorage`, not `localStorage`. After sign-in the app calls `session.whoami`; only a successful backend response sets the authorized state. A 401 clears the local session, a 403 shows **Not authorized**, and report data is never fetched before authorization succeeds.
- The API client posts `{action, ...body}` to `admin-api` with the public publishable key and the session Bearer token, uses `cache: no-store`, a 20-second timeout, and stable error codes. It rejects success-shaped responses without `data`.
- Dashboard: total, pending, by-kind and by-status counts from `reports.stats`; recent activity from `audit.list`; connection status is shown only after a real authorized response.
- Reports: cursor-paginated list (25/page), kind/status filters, detail view, status/admin-note updates, single delete, and a confirmed 30-day purge. No search or bulk actions are offered because the backend supports neither.
- Feature controls: only `diagnostics.enabled` and `problem_reports.enabled`, with explicit confirmation, 0% or 100% availability, and a kill switch. Fractional rollout is not offered because Android config requests send no install ID and fail closed.
- Audit logs: read-only, 50/page, filterable by exact action, with no edit or delete control.
- `admin/vercel.json` adds an SPA rewrite, a restrictive CSP, `frame-ancestors 'none'`, `X-Frame-Options: DENY`, `nosniff`, `no-referrer`, and `noindex`. `admin/.env.example` contains only public variables. No service-role key, database password, admin secret, or private token is used or committed.
- `admin/README.md` documents local setup, Vercel Preview deployment, the exact `ADMIN_ORIGIN` manual step, the staging identity requirement, and the security model.
- No Vercel project, token, or deployment exists in this environment. `vercel whoami` fails with an invalid-token error, so no Preview URL or private access protection was created or claimed.

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
  supabase/functions/admin-api/audit.ts \
  supabase/functions/report-submit/handler.ts
```

Result: **72 passed, 0 failed, 0 skipped, 0 todo**; Deno type checking passes for all four entrypoints; strict TypeScript checking passes for the pure modules. The Deno check caught the `insert`/`upsert` misuse described in §4.2; after the fix and the added source assertion, both checks pass. The new audit tests cover defaults, exact action/cursor filters, and rejected limits.

Frontend checks (run from a `/tmp` copy because the SD-card filesystem cannot execute native rollup/esbuild modules):

```bash
node node_modules/typescript/bin/tsc -b
node node_modules/eslint/bin/eslint.js .
node node_modules/vitest/vitest.mjs run
VITE_SUPABASE_URL=https://jxeuwqmdxwqqvyhisrhg.supabase.co \
VITE_SUPABASE_PUBLISHABLE_KEY=sb_publishable_… \
VITE_ENVIRONMENT=staging \
node node_modules/vite/bin/vite.js build
```

Result: **typecheck PASS**, **lint PASS**, **30 tests PASS** (9 files), **production build PASS** (`431.11 kB` JS / `123.35 kB` gzip, `11.15 kB` CSS). The build contains no source maps.

Additional static checks:

- `git diff --check` clean.
- Secret scan of `admin/src`, `admin/dist`, and tracked source found no service-role key, `sb_secret_`, private key, access token, or password. The only Supabase key in the bundle is the public `sb_publishable_…` key.
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
| `admin-api` | 2 | `false` | ACTIVE; fails closed pending `ADMIN_ORIGIN` |

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

- `admin-api` v2 was deployed on 2026-10-26 with `index.ts`, `audit.ts`, `authz.ts`, `reports.ts`, and `_shared/report-schema.ts`; the stored function source contains `session.whoami`, `reports.stats`, and `parseAuditListQuery`, and no `.select("*")`.
- Live probes after deployment: `GET` → `405 method_not_allowed`; `OPTIONS` → `503`; POST with no origin, `https://evil.example`, a fake Vercel origin, or a bogus Bearer token → `503 admin_origin_not_configured`. No response carried `access-control-allow-origin`. This is the intended fail-closed order while `ADMIN_ORIGIN` is unset.
- The exposed MCP tool set has no secrets-management operation. `npx supabase` is available but has no login or access token. The admin frontend now exists at `admin/`, but no hosting/deployment credentials or Vercel project are available. There is no actual staging admin origin to configure, and inventing one or using a wildcard is prohibited.
- No admin JWT, allowlist row, or report action was exercised live. The new origin/auth/report paths remain covered only by local Node and frontend tests.

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

### 5.9 Admin panel validation

| Check | Status | Evidence |
|---|---|---|
| TypeScript type check | **PASS** | `tsc -b` clean |
| ESLint | **PASS** | `eslint .` clean |
| Unit/route/API tests | **PASS** | 30 tests in 9 files |
| Route protection | **PASS** | authorized renders protected content; signed-out, unauthorized, unavailable, and config-error redirect to login without report data; loading shows a status state |
| API client | **PASS** | Bearer/apikey/action envelope, 401 callback, no fetch without a session, network/timeout codes, malformed success rejection, `ADMIN_ORIGIN` messages |
| Report filters/pagination | **PASS** | empty filters omitted, kind sent, `before` cursor used for Older, full-page cursor only |
| Feature controls | **PASS** | confirmation dialog, 0%/100% mapping, kill-switch preservation, metadata preservation, server-confirmed success text |
| Audit rendering | **PASS** | rows render, empty state, no delete/edit control, bounded query |
| Login states | **PASS** | sign-in form, unauthorized state, config-error state |
| Production build | **PASS** | Vite build succeeded; no source maps |
| Secret scan | **PASS** | no service-role/secret/token markers; only the public publishable key appears |
| Backend Node tests | **PASS** | 72 passed, including new audit validation |
| Deno type checks | **PASS** | all four entrypoints |
| Staging `admin-api` v2 deployment | **PASS** | version 2, `verify_jwt = false`, new source verified |
| Live fail-closed probes | **PASS** | 405/503 matrix above; no CORS allow-origin while unset |
| Live origin rejection (`403`) | **BLOCKED** | Requires `ADMIN_ORIGIN` to be set first |
| Live unauthenticated (`401`) | **BLOCKED** | Requires `ADMIN_ORIGIN` to be set first |
| Live non-admin (`403`) | **BLOCKED** | Requires a staging auth user without an allowlist row |
| Live authorized admin | **BLOCKED** | Staging has 0 auth users and 0 active admin rows |
| Live report/flag/audit mutations | **BLOCKED** | Require the authorized admin path |
| Vercel Preview + exact URL | **BLOCKED** | No valid Vercel token/session or project |
| Private access protection | **BLOCKED** | No Vercel project/account access |

The frontend checks were run from a copy under `/tmp` because the SD-card mount cannot execute native rollup/esbuild binaries (`failed to map segment from shared object`). The copied tree was the same source; no test was skipped or altered for this. Vercel's normal install and build are not affected by that host limitation.

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
| Admin authorization, active allowlist, origin checks, audit | **BLOCKED (live)** | Source/Node tests pass; `admin-api` v2 fails closed until `ADMIN_ORIGIN` is set and a staging admin exists |
| Private admin panel source | **PASS** | `admin/` on the feature branch; no service-role key |
| Admin frontend checks | **PASS** | Typecheck, lint, 30 tests, production build, secret scan |
| Admin report management UI | **PASS (source/tests)** | List, filters, cursor pagination, detail, update, delete, purge confirmation |
| Admin feature controls UI | **PASS (source/tests)** | Two Phase 3 flags, 0%/100%, kill switch, confirmation, fail-closed errors |
| Admin audit UI | **PASS (source/tests)** | Read-only, pagination, exact-action filter, no mutation control |
| Browser secret handling | **PASS** | Public publishable key only; tab-scoped session; no service-role/DB/admin secret |
| Vercel Preview deployment | **BLOCKED** | No valid Vercel token/session or project access |
| Vercel private access protection | **BLOCKED** | No Vercel project/account access; unlisted URL not treated as access control |
| `ADMIN_ORIGIN` configured | **BLOCKED** | No secret-management access and no deployed origin; no wildcard/placeholder used |
| Privacy documentation for private distribution | **PASS** | `PRIVACY.md` updated; in-app link merge gate |
| Reviewed translations | **BLOCKED** | No reviewed translations; scoped lint exception justified |
| Accessibility review | **PASS (source)** | Device TalkBack blocked; two gaps documented |
| Device QA | **BLOCKED** | No device/emulator/SDK; checklist added |
| Staging validation isolated from production | **PASS** | Disposable `hmxpdf-staging`; production inspected read-only |
| No production deployment, destructive tests, new signing identity, public release, or security weakening | **PASS** | Private CI artifacts only; staging only |

---

## 7. Remaining work before private distribution

1. **Deploy `admin/` to a Vercel Preview and enable access protection.** This requires a valid Vercel account/session with access to the HMXPDF project, Preview-only public environment variables (`VITE_SUPABASE_URL`, `VITE_SUPABASE_PUBLISHABLE_KEY`, `VITE_ENVIRONMENT=staging`), and Deployment Protection. Record the exact Preview origin. Do not create a public Production deployment and do not treat an unlisted URL as access control.
2. **Set `ADMIN_ORIGIN` and provision a staging admin identity.** Create a dedicated staging Supabase Auth user and an active `hmx.admin_users` row with role `admin`; do not copy a production UUID. Set `ADMIN_ORIGIN` to the exact Preview origin through the Supabase Dashboard or Management API only; no wildcard, placeholder, or invented origin. Then test foreign-origin rejection, unauthenticated `401`, non-admin `403`, authorized `session.whoami`, `reports.stats`, `reports.list/get/update/delete/purge`, `flags.list/upsert`, and audit rows.
3. **Merge the privacy policy to `main` or repoint the in-app link.** The Settings link targets `main`; until the updated `PRIVACY.md` is merged, the APK will open the previous policy.
4. **Translate the Phase 3 strings.** Add reviewed translations to the six locales and remove the `MissingTranslation` ignore for `strings_phase3.xml`. Do not use machine-translated privacy disclosures.
5. **Run the device QA checklist.** Use `docs/HMXPDF_PHASE3_DEVICE_QA_CHECKLIST.md` on the OPPO A38 Android 15. Record actual results; do not claim device testing before it is run.
6. **Production deployment (not authorized).** See §8.
7. **Update the retention copy after production verification.** The app currently says automatic 30-day deletion is not yet active. Change that only after the production scheduler has a successful run and the boundary is re-verified there.

---

## 8. Production deployment checklist (not executed)

1. Merge the reviewed feature branch to `main`; do not force-push or rewrite `main`.
2. Back up production data/configuration and confirm the current function versions for rollback.
3. Apply migrations `hmx_feature_flags` and `hmx_reports` to production; confirm RLS, grants, constraints, and purge functions.
4. Deploy the updated `config`, `report-submit`, and `admin-api` functions. Use `verify_jwt = true` for `admin-api`, matching `supabase/config.toml`; keep `report-submit`/`config` public as designed. Confirm the release build's Supabase URL, publishable key, config endpoint, and report endpoint point to production.
5. Deploy `admin/` to an approved Vercel project with Deployment Protection, Preview first. Set `ADMIN_ORIGIN` to the actual admin frontend origin through the Supabase Dashboard or Management API. Do not use a wildcard or a public unprotected URL. Create/verify the production admin allowlist and audit path.
6. Install `pg_cron`, apply `supabase/retention/report_retention_cron.sql`, and confirm the job is active and a run succeeds.
7. Verify live: config rejects `install_id`/unknown fields; report-submit returns `201` and duplicate `200`; admin rejects foreign origins, unauthenticated requests, and non-allowlisted users; authorized report actions work; audit rows are written; retention deletes an expired disposable row while keeping a newer one.
8. Rollback considerations: restore the previous function versions; remove the cron job if retention must stop; disable report submission in the app by clearing the report endpoint/publishable key if the backend must be taken out of service; do not drop `hmx.reports` without a backup and explicit authorization.
9. Update the in-app retention copy only after production retention is verified.
10. Keep the private-distribution policy link current after merge.

---

## 9. Known limitations

- Admin live authorization and report actions are unverified. The admin frontend exists at `admin/`, but no Vercel Preview exists, no admin origin is configured, and no admin user/allowlist row exists in staging.
- No Vercel token, project, or private-access configuration is available in this environment; no Preview URL or protection status is claimed.
- The admin frontend checks ran from a `/tmp` copy because the SD-card mount cannot execute native rollup/esbuild modules. This does not affect Vercel builds.
- Staging `admin-api` v2 uses `verify_jwt = false` and performs its own `auth.getUser` plus allowlist check. Production `admin-api` v1 is `verify_jwt = true` but predates the Phase 3 report actions and v2 additions; the production deployment must use `verify_jwt = true` with the new code.
- The exposed MCP tool set has no secret-management operation; `npx supabase` has no login/token; no Dashboard session or Management API token is available in this environment.
- Local Android Gradle checks cannot run: no Android SDK, no `local.properties`, no `key.properties`, and no release signing environment. CI remains the verified build.
- No device or emulator is available; all device QA is pending.
- Reviewed Phase 3 translations are unavailable; six locales fall back to English.
- The problem-description field label association and choice-chip selected-state semantics remain accessibility gaps; no TalkBack pass was possible.
- Automatic 30-day deletion is verified on staging only. Production has no scheduler, reports table, or purge function.
- The updated privacy policy is on the feature branch; the in-app link points to `main` until merge.
- There is no backend CI workflow. Local Node/Deno/TypeScript checks are the only automated backend validation.
- The staging project is disposable validation infrastructure; it is not the app-configured backend.
- The debug and release APKs have not been installed or exercised on a device or emulator.

---

## 10. Conclusion

Phase 3 source is complete on the feature branch. Android CI is green on `cc8129d` and the Android source is unchanged, so the existing debug APK, five signed release APKs, and signed release bundle remain the verified artifacts. Backend source passes 72 Node tests plus Deno and strict TypeScript checks. The private admin frontend in `admin/` passes typecheck, lint, 30 tests, a production build, and a secret scan. Staging was revalidated live: both SQL suites pass, `report-submit` v2 handles normal and concurrent duplicates correctly, `config` rejects identifiers and unknown fields, the hourly retention scheduler has seven successful runs, and `admin-api` v2 is deployed with the new dashboard/audit actions.

`PRIVACY.md` now describes the actual private-distribution behavior, including the limits of production retention. Admin live authorization remains blocked on the missing Vercel Preview, `ADMIN_ORIGIN`, and staging admin identity, so no live authorized report/flag/audit mutation is claimed. Reviewed translations and device QA remain open. Production was not modified.

**Phase 3 is not production-ready.** No production migration, deployment, secret, scheduler, data change, or signing change was made.
