# HMXPDF Phase 3 — Backend Verification Report and Deployment Runbook

**Date:** 2026-10-24 (revision 2 — hardening pass)
**Repository:** `HMXPANEL/HMXPDF`
**Branch:** `feature/hmxpdf-diagnostics-admin-updates`
**Branch HEAD at verification:** `6230523eda322fede16f61ba1392e61b1185e458`
**`main` / `origin/main`:** `912e9977916c4f365147a294e60c3cd95d96960e`
**Status:** Backend source implementation and hardening complete. **Nothing was deployed.** All runnable Node tests pass. SQL/database, Deno-runtime, scheduler, and live checks are **blocked** and are not claimed as passing. A later Phase 3.2 task added Android privacy/config/report-core source; it is **not compiled or tested locally** and is recorded in §11.

---

## 1. Scope and boundaries

This task implemented the Phase 3 backend source required by `docs/HMXPDF_PHASE3_IMPLEMENTATION_PLAN.md` revision 4 and the owner's backend authorization:

- `hmx.reports` schema, constraints, indexes, RLS, grants, and the 30-day purge function.
- Bounded 2-day cleanup for expired rate-limit buckets; hashed install-ID bucket keys.
- Strict public report validation and the `report-submit` Edge Function.
- Admin report list/get/update/delete/purge actions in `admin-api`, with audit logging.
- Default-OFF `diagnostics.enabled` and `problem_reports.enabled` flags.
- Config-request privacy enforcement: `install_id` is rejected by `config`.
- Node tests, SQL security/retention suites, and a deployment-time scheduler file.
- This verification report and runbook.

Explicitly **not** done:

- No Android app code was modified by this backend task. The later Phase 3.2 Android foundation is recorded separately in §11.
- No migration, Edge Function, scheduler, admin panel, or Supabase setting was deployed.
- No live database state was changed.
- No commit, push, merge, force-push, or branch switch was performed.
- No APK was built.
- No privacy policy, store metadata, or Phase 4 work was changed.
- `docs/HMXPDF_PHASE3_SCOPE_AUDIT.md` and `docs/HMXPDF_FULL_SOURCE_AUDIT.md` were preserved unchanged.

> **Addendum note (2026-10-25):** §11 records the Phase 3.2 Android source and its validation status. The backend claims in §§2–10 remain limited to the backend task and are not upgraded by the Android work.

---

## 2. Implemented files

### 2.1 New files

| File | Purpose |
|---|---|
| `supabase/migrations/20261024164504_hmx_reports.sql` | Reports table, constraints, indexes, RLS, grants, 30-day report purge, bounded 2-day rate-limit purge, Phase 3 flag seeds |
| `supabase/functions/_shared/report-schema.ts` | Pure report payload types and strict validation |
| `supabase/functions/report-submit/handler.ts` | Pure request handler with an injectable store; duplicate-first lookup and hashed rate-limit keys |
| `supabase/functions/report-submit/index.ts` | Thin Deno/Supabase wiring for the public report ingestion endpoint |
| `supabase/functions/admin-api/reports.ts` | Pure admin report list/update/purge validators |
| `supabase/functions/config/request.ts` | Pure config-request validator; rejects `install_id` |
| `supabase/tests/report_schema.test.ts` | Report payload validation tests |
| `supabase/tests/report_submit.test.ts` | Handler tests: duplicate/race, rate limits, bucket privacy, invalid payloads, failures |
| `supabase/tests/admin_reports.test.ts` | Admin report input validation tests |
| `supabase/tests/config_request.test.ts` | Config request privacy/shape tests |
| `supabase/tests/phase3_source.test.ts` | Static source/migration/scheduler assertions for checks that cannot run here |
| `supabase/tests/reports_security.sql` | RLS, grant, column, constraint, flag, and rate-limit-purge privilege assertions; fail-closed `ON_ERROR_STOP` |
| `supabase/tests/reports_retention.sql` | 30-day boundary, batch, audit-preservation, and 2-day rate-limit cleanup assertions; isolated from pre-existing rows |
| `supabase/retention/report_retention_cron.sql` | Deployment-time `pg_cron` scheduler for both purge functions; not a migration |
| `docs/HMXPDF_PHASE3_BACKEND_VERIFICATION_REPORT.md` | This report |

### 2.2 Modified files

| File | Change |
|---|---|
| `supabase/functions/admin-api/index.ts` | Added `reports.list`, `reports.get`, `reports.update`, `reports.delete`, `reports.purge`; maps validation errors; audit writes |
| `supabase/functions/config/index.ts` | Uses `parseConfigRequest`; always evaluates with `installId: null` |
| `supabase/config.toml` | Added `[functions.report-submit] verify_jwt = false` |
| `docs/HMXPDF_PHASE2_BACKEND_REPORT.md` | Corrected the `config` example to omit `install_id`; documented the strict contract and the deployed-v3 supersession |

---

## 3. Security and privacy properties

### 3.1 Reports storage

- `hmx.reports` has RLS enabled and **no policies**. `public`, `anon`, and `authenticated` have no table grants. `service_role` has the required grants.
- The purge functions are `security definer`, have `search_path = ''`, and are executable only by `service_role`.
- `hmx.purge_expired_rate_limits(batch_size)` is `security definer`, has `search_path = ''`, deletes only buckets older than 2 days in bounded batches (1–20,000), and is executable only by `service_role`. `rate_limits(window_start)` is indexed for the cleanup scan.
- No `device_model`, contact email, document ID, filename, path, URI, raw log, location, screenshot, annotation, form-value, password, or token column exists.
- The unique constraint `(install_id, client_report_id)` provides idempotency.
- Retention is fixed at 30 days from server `received_at`, not client time. The purge function deletes at most `batch_size` rows per call, clamped to 1–5000.

### 3.2 Public report submission

- Body cap: 16 KiB.
- Strict schema version, kind, UUID, app metadata, timestamp, description, category, attached-error, and self-check validation.
- Unknown top-level fields are rejected, including `device_model` and all document/file fields.
- Diagnostic reports cannot carry a description, category, or attached error; problem reports cannot carry self-checks.
- `client_created_at` must be ISO-8601, no more than 5 minutes in the future, and no more than 30 days old.
- Rate limits: 10/hour per hashed IP, 5/hour per install ID, and 20/24 hours per install ID.
- Rate-limit buckets contain only SHA-256 values: `report-ip:<hash>` and `report-install-{hour,day}:<domain-separated hash>`. Neither the raw IP nor the raw install UUID is written to `hmx.rate_limits`.
- The duplicate lookup runs before rate-limit consumption, so a lost 201 followed by a retry returns the existing report instead of `429`. The lookup requires both UUIDs and reveals only the same acknowledgement as a normal retry.
- Insert uses `on conflict (install_id, client_report_id) do nothing`. A retry selects and returns the existing row with `duplicate: true`; a concurrent insert race is resolved the same way. No second row is created.
- The response contains only `report_id`, `status`, `received_at`, and the duplicate flag. Stored content is never echoed.
- Logs contain error codes only, never payloads or descriptions.

### 3.3 Admin actions

- Existing authentication is preserved: platform JWT verification, `auth.getUser(token)`, active-admin allowlist, and exact `ADMIN_ORIGIN` CORS/origin checks.
- `reports.list` returns metadata only; `reports.get` returns the full row for an authorized admin.
- `reports.update` allows only `status` and `admin_notes`, validates both, and updates `updated_at`.
- `reports.delete` hard-deletes one report.
- `reports.purge` is fixed at 30 days and accepts only a bounded batch size.
- Audit metadata contains action, report ID, changed field **names**, and counts only. It never contains descriptions, attached-error text, self-check content, install IDs, or raw IPs.
- `readonly` admins remain rejected by the existing `isAuthorizedAdmin()` policy.

### 3.4 Config privacy

- `config` rejects any request containing `install_id` with `400 install_id_not_allowed`.
- Unknown top-level config fields are rejected, including `device_model`, `ANDROID_ID`, and advertising/document identifiers.
- Accepted fields: `keys`, optional `app_version_code`, and the existing optional backend-only `tester_token`.
- Flag evaluation always receives `installId: null`. This means fractional rollouts fail closed with `missing_install_id`, as planned. 0%/100%, schedules, version gates, tester/segment grants, and global/per-flag kill switches retain their existing behavior.
- The Phase 2 `config` request example was updated to omit `install_id`. The untracked `docs/HMXPDF_PHASE3_SCOPE_AUDIT.md` still contains the historical identifier example and remains unchanged; CD-2 and revision 4 supersede it.
- The deployed `config` v3 still accepts an optional `install_id`; the strict source is not deployed. Do not reintroduce the field to satisfy the old example.

---

## 4. Verification results

### 4.1 Environment

| Tool | Result |
|---|---|
| Node.js | `v24.18.0` |
| npm | `11.16.0` |
| Deno | Not available |
| Supabase CLI | Not available |
| `psql` | Not available |
| Docker | Not available |
| Safe/staging database | Not available |

### 4.2 Node tests — PASS

Command:

```bash
node --test supabase/tests/*.test.ts
```

Result: **69 passed, 0 failed, 0 skipped, 0 todo.**

Breakdown:

| Suite | Tests | Result |
|---|---:|---|
| `supabase/tests/rollout.test.ts` (pre-existing) | 18 | PASS |
| `supabase/tests/admin_authz.test.ts` (pre-existing) | 5 | PASS |
| `supabase/tests/report_schema.test.ts` | 15 | PASS |
| `supabase/tests/report_submit.test.ts` (new) | 12 | PASS |
| `supabase/tests/admin_reports.test.ts` | 5 | PASS |
| `supabase/tests/config_request.test.ts` | 6 | PASS |
| `supabase/tests/phase3_source.test.ts` | 8 | PASS |

The tests cover:

- valid diagnostic/problem payloads;
- unknown-field rejection, including `device_model`, contact, document, file, path, raw-log, location, screenshot, annotation, form-value, password, and token fields;
- diagnostic/problem field separation;
- UUID, ISO timestamp, 30-day age, future-time, app metadata, category, attached-error, and self-check validation;
- lowercasing of UUID identifiers for idempotency and rate-limit consistency;
- duplicate submission: first 201, retry 200 with `duplicate:true`, one stored row, no second insert, and no rate-limit charge on the retry;
- concurrent insert race resolving to the existing row;
- rate-limit 429 behavior and `retry-after`;
- rate-limit bucket privacy: raw install UUID and raw IP never appear in bucket keys, and install buckets are 64-hex hashes;
- oversized bodies, invalid payloads, store failures, method/configuration guards, and database check-violation mapping;
- admin list/update/purge validation and changed-field metadata;
- config rejection of `install_id` and unknown identifiers;
- static assertions that the migration has RLS/grants/no policies/30-day report purge/2-day rate-limit purge/default-OFF seeds, the public function is size-capped/rate-limited/idempotent, the scheduler calls both purge functions, both SQL suites set `ON_ERROR_STOP` and print their marker only inside the `DO` block, admin audit metadata is field-name-only, and `config` always uses `installId: null`.

### 4.3 Syntax checks — PASS (syntax only)

```bash
node --check supabase/functions/report-submit/index.ts
node --check supabase/functions/report-submit/handler.ts
node --check supabase/functions/admin-api/index.ts
node --check supabase/functions/config/index.ts
node --check supabase/functions/_shared/report-schema.ts
node --check supabase/functions/admin-api/reports.ts
node --check supabase/functions/config/request.ts
```

All commands exited successfully. This is **not** a Deno type-check, Deno runtime test, or deployment validation.

### 4.4 SQL security and retention suites — BLOCKED

Written, hardened, but **not run**:

- `supabase/tests/reports_security.sql`
- `supabase/tests/reports_retention.sql`

Reason: `psql` and a disposable database/staging project are unavailable in this environment. The suites now:

- set `\set ON_ERROR_STOP on` themselves, so a failed assertion exits non-zero;
- emit their `*_passed` marker only after the final assertion inside the `DO` block, so a marker cannot print after a failure;
- clear pre-existing expired reports and rate-limit buckets at the start of the retention transaction, so a non-empty staging database cannot cause false failures;
- assert table existence, RLS enabled, no RLS policies, no forbidden columns, and constraint rejection for invalid kind/status/category/versions/flavors/build types/API levels/locales/notes, missing problem descriptions, diagnostic problem-only fields, array/oversized JSON, and duplicate identity;
- assert `anon` cannot read or insert, `authenticated` cannot read or update, and neither can execute either purge function;
- assert `service_role` can insert/update/select/delete and execute both purge functions;
- assert 31-day and 30-day-plus-one-second reports are deleted; 29-day, fresh, and exactly-30-day reports are kept;
- assert report batch size 0 is clamped to 1, oversized batches are capped, and audit rows survive;
- assert rate-limit buckets older than 2 days are deleted, fresh buckets remain, and the 2-day batch clamp works.

**No SQL assertion is claimed as passing.** A disposable Postgres 17 database or staging project is required before the suites can be executed.

### 4.5 Deno Edge Function execution — BLOCKED

Deno is unavailable, so `report-submit`, `config`, and `admin-api` were not executed. `node --check` only parses the source. The Deno-specific `jsr:@supabase/supabase-js@2` import and `Deno.env`/`Deno.serve` runtime were not exercised.

### 4.6 Live probes and admin matrix — BLOCKED

No function was deployed. The live `admin-api` remains the older AAL2 build, and `ADMIN_ORIGIN` is not configured. Therefore:

- no live `report-submit` request was made;
- no live idempotency, rate-limit, or size-cap response was observed;
- no live admin list/get/update/delete/purge call was made;
- no live origin/auth matrix was run;
- no live `config` response was observed.

### 4.7 Automatic retention scheduler — BLOCKED

`pg_cron` is **not installed** in the only available project (production `tchoqlaqgcrbbvomaebo`), and `cron.job` does not exist there. The scheduler file `supabase/retention/report_retention_cron.sql` was written but **not applied**. No scheduled run, deletion, or `cron.job_run_details` success is claimed.

The `hmx.purge_expired_reports`, `hmx.purge_expired_rate_limits`, and manual `reports.purge` actions exist in source, but automatic retention is **not verified** until the scheduler runs in staging and old rows are observed deleted. The rate-limit cleanup is included in the same hourly job and is subject to the same blocker.

---

## 5. Acceptance matrix

Legend: **PASS** = executed here; **WRITTEN/BLOCKED** = source or SQL exists but could not run; **N/A** = Android/out of scope; **PASS (source)** = static assertion only.

| ID | Check | Result |
|---|---|---|
| BE-01 | Anonymous direct read/write denied | WRITTEN/BLOCKED (SQL) |
| BE-02 | Authenticated non-admin direct access denied | WRITTEN/BLOCKED (SQL) |
| BE-03 | Service-role insert/select allowed | WRITTEN/BLOCKED (SQL) |
| BE-04 | Bad kind/fields/size rejected; `device_model` rejected | PASS (Node schema) + WRITTEN/BLOCKED (SQL/live) |
| BE-05 | IP rate limit; raw IP not stored | PASS (Node 429/privacy tests); SQL/live WRITTEN/BLOCKED |
| BE-06 | Install rate limit with hashed bucket key | PASS (Node 429/privacy tests); SQL/live WRITTEN/BLOCKED |
| BE-07 | Idempotent duplicate: one row, same ID, duplicate flag; retry not rate-limited | PASS (Node handler duplicate/race tests) + WRITTEN/BLOCKED (SQL/live) |
| BE-08 | Acknowledgement contains ID/status/timestamp only | PASS (source contract) |
| BE-09 | Admin list/get authorized, paginated/filtered | PASS (validators) + WRITTEN/BLOCKED (SQL/live) |
| BE-10 | Admin update/delete authorized; audit row | PASS (validators/source) + WRITTEN/BLOCKED (SQL/live) |
| BE-11 | Non-admin/inactive/readonly rejected | PASS (existing 5/5 auth tests) + live BLOCKED |
| BE-12 | Wrong Origin 403; correct allowed | WRITTEN/BLOCKED (live) |
| BE-13 | Missing `ADMIN_ORIGIN` 503 fail closed | WRITTEN/BLOCKED (live); source preserved |
| BE-14 | MFA-optional source matches live | WRITTEN/BLOCKED (live); live is still old AAL2 build |
| BE-15 | Audit content excludes report content | PASS (source) + WRITTEN/BLOCKED (SQL/live) |
| BE-16 | Purge deletes only older than 30 days; audit preserved | WRITTEN/BLOCKED (SQL) |
| BE-17 | SQL security suite passes | WRITTEN/BLOCKED |
| BE-18 | Rollout tests 18/18 | PASS |
| BE-19 | Admin auth tests 5/5 | PASS |
| BE-20 | Config live probe | WRITTEN/BLOCKED (live) |
| BE-21 | Automatic purge scheduler for reports and rate limits | WRITTEN/BLOCKED (not applied; `pg_cron` absent) |
| BE-22 | 29-day kept; 31-day deleted | WRITTEN/BLOCKED (SQL) |
| BE-23 | Reinstall behavior | N/A (Android) |
| BE-24 | Rate-limit buckets older than 2 days deleted; fresh kept | WRITTEN/BLOCKED (SQL); scheduler includes the call |
| FLG-01…FLG-12 | Rollout/version/schedule/kill-switch behavior | PASS for the existing 18 rollout tests; config request shape PASS in new tests; live BLOCKED |
| FLG-22 | Local consent stays OFF | N/A (Android); backend flags do not override consent |
| FLG-23 | Config request has no install ID | PASS (Node config tests + source) |
| FLG-24 | No secrets/PII in config | PASS (Node config tests) |
| FLG-25 | Config request shape | PASS (Node config tests) |
| REP-01…REP-28 | Android report UI/flow tests | N/A (Android not modified) |

---

## 6. Deployment runbook (staging first; requires separate approval)

**Exact staging sequence:**

1. Preflight the branch, HEAD, Node tests, and syntax checks.
2. Confirm a disposable staging project and `TEST_DATABASE_URL`; never use production.
3. Apply the reports migration to staging.
4. Verify the table, RLS, zero policies, grants, flags, and both purge functions.
5. Run both SQL suites with `ON_ERROR_STOP`; require exit `0` and the `*_passed` notice.
6. Deploy `report-submit`, then `config`, then `admin-api`; set `ADMIN_ORIGIN` before the first admin call.
7. Run the public, config, and admin live probes, including duplicate and rate-limit cases.
8. Confirm `hmx.rate_limits` contains hashed bucket keys only.
9. Enable the `pg_cron` scheduler and verify a real run deletes old reports and old rate-limit buckets while keeping fresh rows.
10. Stop. Production requires a separate explicit approval and a repeat of steps 1–9.

### 6.1 Preflight

```bash
git branch --show-current          # must be feature/hmxpdf-diagnostics-admin-updates
git rev-parse HEAD                 # record the reviewed commit
node --test supabase/tests/*.test.ts
node --check supabase/functions/report-submit/index.ts
git diff --check
supabase --version
psql --version
```

Confirm:

- no deployed client still sends `install_id` to `config` (revision 4 rejects it);
- a staging Supabase project ref and `TEST_DATABASE_URL` are available;
- the reviewer has approved the migration and function diff;
- `ADMIN_ORIGIN` is known exactly (scheme + host, no trailing slash).

### 6.2 Apply the migration to staging

```bash
supabase link --project-ref <staging-project-ref>
supabase db push
```

Verify:

```sql
select to_regclass('hmx.reports');
select relrowsecurity from pg_class where oid = 'hmx.reports'::regclass;
select count(*) from pg_policies where schemaname = 'hmx' and tablename = 'reports'; -- must be 0
select key, enabled, rollout_percentage from hmx.feature_flags
 where key in ('diagnostics.enabled', 'problem_reports.enabled'); -- both false, 0
```

### 6.3 Run the SQL suites

```bash
psql -v ON_ERROR_STOP=1 "$TEST_DATABASE_URL" -f supabase/tests/reports_security.sql
psql -v ON_ERROR_STOP=1 "$TEST_DATABASE_URL" -f supabase/tests/reports_retention.sql
```

Both files also set `\set ON_ERROR_STOP on` internally. Each must exit `0` and print its `*_passed` notice; a failed assertion exits non-zero before the marker. Do not proceed if either fails. Never point `TEST_DATABASE_URL` at production.

### 6.4 Deploy the functions

```bash
supabase functions deploy report-submit
supabase functions deploy config
supabase functions deploy admin-api
supabase secrets set ADMIN_ORIGIN=https://<exact-admin-origin>
```

`supabase/config.toml` supplies `verify_jwt = false` for `report-submit` and `config`, and `true` for `admin-api`. Verify the deployed configuration in the dashboard.

Deployment order and caveats:

1. Apply the migration and run both SQL suites before deploying functions.
2. Deploy `report-submit`, then `config`, then `admin-api`. Set `ADMIN_ORIGIN` before the first admin request.
3. `config` must be deployed only after confirming no deployed client sends `install_id`; no client in this repository does, and the Phase 2 documentation examples were corrected.
4. Do **not** redeploy or modify `tester-enroll`; its install-ID contract is unchanged and still requires a separate review.

### 6.5 Live probes

Use the staging project URL and publishable key. Do not use the service-role key in client-side commands.

```bash
# Valid report: expect 201 with report_id/status/received_at only.
curl -i -X POST "$SUPABASE_URL/functions/v1/report-submit" \
  -H "apikey: $SUPABASE_ANON_KEY" \
  -H "content-type: application/json" \
  --data @valid-report.json

# Retry the same payload: expect 200 with duplicate:true and the same report_id.
# Invalid field (device_model): expect 400.
# Oversized body: expect 413.
# Exceed the rate limit: expect 429 with retry-after.

# Config: expect 200 and only the requested flags.
curl -i -X POST "$SUPABASE_URL/functions/v1/config" \
  -H "apikey: $SUPABASE_ANON_KEY" \
  -H "content-type: application/json" \
  --data '{"keys":["diagnostics.enabled","problem_reports.enabled"],"app_version_code":6}'

# Config with install_id: expect 400 install_id_not_allowed.
# Config with device_model: expect 400 unknown_field.

# Admin: send a real admin Bearer token and the exact Origin.
# Wrong Origin: expect 403.
# No ADMIN_ORIGIN: expect 503 admin_origin_not_configured.
```

Admin action probes (after authentication):

```json
{"action":"reports.list","limit":10}
{"action":"reports.get","id":"<report-id>"}
{"action":"reports.update","id":"<report-id>","status":"reviewing","admin_notes":"triaged"}
{"action":"reports.delete","id":"<report-id>"}
{"action":"reports.purge","older_than_days":30,"batch_size":100}
```

Confirm `hmx.admin_audit_log` contains `report.update`, `report.delete`, and `report.purge` rows whose metadata contains no report content. `reports.get` is not audited, matching the plan's action table.

Confirm the rate-limit keys are pseudonymous (staging SQL only):

```sql
select bucket, window_start from hmx.rate_limits where bucket like 'report-%';
-- Every install bucket must match report-install-{hour,day}:<64 hex>.
-- No raw install UUID or IP may appear.
```

### 6.6 Enable and verify the 30-day scheduler

Prerequisites, all of which must be confirmed first:

1. The reports migration is applied, so `hmx.purge_expired_reports(integer)` and `hmx.purge_expired_rate_limits(integer)` exist.
2. `pg_cron` is installed and `cron.database_name` points at this database.
3. The scheduler SQL is run by `postgres`/the function owner, or by a role with `EXECUTE` on both purge functions. pg_cron jobs run as their owner.
4. `hmx.rate_limits` contains no active window older than the 2-day cleanup boundary.

Check `pg_cron` first:

```sql
select extname from pg_extension where extname = 'pg_cron';
```

If it is absent, enable it in the Supabase dashboard or use a verified external scheduler. Do not assume it exists. The only available project currently has **no** `pg_cron` and no `cron.job`.

Then run `supabase/retention/report_retention_cron.sql` in the staging SQL editor. Verify:

```sql
select jobid, jobname, schedule, active
  from cron.job where jobname = 'hmx-purge-expired-reports';

select status, return_message, start_time, end_time
  from cron.job_run_details
 where jobid = (select jobid from cron.job where jobname = 'hmx-purge-expired-reports')
 order by start_time desc
 limit 5;

select count(*) from hmx.reports where received_at < now() - interval '30 days';
select count(*) from hmx.rate_limits where window_start < now() - interval '2 days';
```

To prove deletion, insert two committed staging-only reports: one with `received_at = now() - interval '31 days'` and one fresh. Also insert one rate-limit bucket older than 2 days and one fresh. Wait for the scheduled run (or call the functions manually once for the smoke test), then confirm the old rows are gone, the fresh rows remain, and both verification counts are 0. Remove the fresh test rows afterward.

**Do not claim automatic retention until a scheduled run and its deletion behavior are observed.**

### 6.7 Production

Repeat 6.1–6.6 against production only after explicit approval. Update the privacy policy and store metadata, including 30-day retention, before enabling the feature for users.

---

## 7. Rollback

- **Migration:** additive. Do not drop `hmx.reports` without explicit approval, a backup, and confirmation that no reports must be retained.
- **`report-submit`:** redeploy the previous function version or a stub returning `503`.
- **`admin-api`:** redeploy the previous deployed version. The current live version is still the old AAL2 build.
- **`config`:** revision 4 rejects `install_id`. If a legacy client still sends it, update the client or redeploy the previous config function. Do not remove the rejection to accommodate an unapproved identifier flow.
- **Scheduler:** disable with `select cron.unschedule('hmx-purge-expired-reports');` before an incident hold; record the disablement and re-enable the 30-day job afterward.
- **Audit:** audit rows are preserved and contain no report content.

---

## 8. Blockers and open items

### 8.1 Environment blockers

1. **SQL suites not executed.** `psql`, a disposable Postgres, Docker, and the Supabase CLI are unavailable, and no staging project exists. RLS, grants, constraints, retention boundaries, rate-limit cleanup, and audit preservation remain unverified in a database.
2. **Deno not executed.** Edge Function runtime, Supabase client behavior, gateway `apikey` semantics, and Deno type-checking remain unverified.
3. **No live deployment.** The updated `admin-api` is not deployed, `ADMIN_ORIGIN` is unset, and no live admin matrix or public probe has run.
4. **`pg_cron` absent.** The only available project has no `pg_cron` and no `cron.job`. Automatic 30-day retention and 2-day rate-limit cleanup are not proven. The scheduler must be enabled and observed in staging.
5. **Android build/test not executed.** Phase 3.2 Android privacy/config/report-core source and JVM tests exist, but no Gradle build, JVM test, or lint ran locally (owner-directed GitHub Actions validation), and no CI run occurred because the branch was not committed or pushed. Report UI, WorkManager upload, startup wiring, and consent screens remain unimplemented and out of scope for Phase 3.2.

### 8.2 Deployment preconditions

- Confirm no deployed client sends `install_id` to `config`; revision 4 returns `400 install_id_not_allowed`.
- Confirm `ADMIN_ORIGIN` is exact and available before deploying `admin-api`.
- Confirm the staging SQL suites pass before deploying to production.
- Confirm `pg_cron` or a verified external scheduler is available before claiming retention.

### 8.3 Open decisions / limitations

- **`tester-enroll` still accepts `install_id`.** It is the pre-existing Phase 2 invite-redemption contract and was not changed because it is not a Phase 3 config/report client and changing it would break tester enrollment. If "install ID only in report submissions" is intended to apply to every endpoint, tester enrollment needs a separate redesign and approval.
- **Rate-limit cleanup is scheduled, not active.** Until the scheduler runs in staging/production, expired buckets accumulate. The 2-day window is safe for the 1-day maximum rate-limit window, but it is only effective once `hmx.purge_expired_rate_limits` is scheduled.
- **Duplicate lookups are not rate-limited.** They run before the limiter so a retry can never be blocked by a 429. A duplicate requires both the install UUID and client report UUID and performs one indexed read; it creates no row. This is a deliberate trade-off for idempotency, not a write-path bypass.
- **Install-ID rate limiting remains bypassable by a client that generates new UUIDs.** Hashing removes the plaintext copy but cannot make a client-supplied identifier authoritative. The per-IP limit is the primary abuse control; this is unchanged from the design.
- **Body cap is checked after `req.text()` buffers the body.** Supabase's edge limits bound the practical request size; a streaming cap would be a later hardening step.
- **`X-Forwarded-For` is client-spoofable in principle.** The platform normally sets it; per-IP limiting should not be treated as a hard security boundary.
- **Config request strictness is a behavior change.** Unknown fields and `install_id` now return `400`. The Phase 2 example was updated; no client in this repository sends the field.
- **Admin list cursor is timestamp-based.** `reports.list` filters on `before` (`received_at < before`) and orders by `received_at` then `id`. With multiple rows at the exact same timestamp, a page boundary could theoretically skip rows. Report volume is expected to be low; a compound `(received_at, id)` cursor can be added in Phase 4 if needed.
- **Client-side sanitization is not implemented here.** The backend enforces schema, size, and forbidden-field rejection. The Android sanitizer/preview must still remove document names, paths, tokens, and other sensitive text before submission.
- **No server-side flag enforcement on submission.** Flags are availability hints; local consent remains authoritative. The endpoint validates and rate-limits submissions but does not reject them when a flag is OFF.
- **Audit write failures are logged but do not block the admin action.** This matches the existing Phase 2 admin behavior. If audit durability must be strict, make the audit insert part of the action and fail closed; that is a separate approval.
- **No admin panel.** The Phase 4 Vercel panel is not implemented.

---

## 9. Explicit non-claims

- No SQL assertion passed; the SQL files are hardened but unexecuted.
- No Deno Edge Function ran.
- No live endpoint returned a response.
- No automatic purge or rate-limit cleanup ran.
- No migration or function was deployed.
- No Android build, JVM test, lint, or device check ran; the Phase 3.2 source is uncompiled and CI-pending.
- No Phase 3.2 runtime path is wired: no startup refresh, no consent UI, no report submission, no worker.
- No commit, push, merge, or branch switch occurred.

---

## 10. Review checklist

- [ ] Migration reviewed against the live/staging schema and Postgres version.
- [ ] SQL security and retention suites exit `0` and print their markers only on success.
- [ ] Deno functions type-check and run in staging.
- [ ] `report-submit` valid/invalid/oversized/rate-limited/idempotent probes pass.
- [ ] Rate-limit bucket keys contain hashes only; no raw install UUID or IP.
- [ ] `admin-api` auth, origin, list/get/update/delete/purge, and audit probes pass.
- [ ] `config` rejects `install_id` and accepts only the intended fields.
- [ ] `pg_cron` (or an approved external scheduler) runs and deletes only expired reports and rate-limit buckets.
- [ ] Privacy policy and store metadata state 30-day retention and the report data categories.
- [ ] Android Phase 3.2 JVM tests, lint, and assemble pass in GitHub Actions.
- [ ] Phase 3.3 onboarding/Settings wiring is reviewed separately.
- [ ] Android integration remains a separate approval.

---

## 11. Phase 3.2 Android foundation addendum (2026-10-25)

This addendum records a separate task that implemented **Phase 3.2 only** on the same feature branch. It does not upgrade any backend claim above.

### 11.1 What was added

| Area | Files | Guarantee |
|---|---|---|
| Consent | `data/privacy/ConsentStore.kt`, `PrivacyStorage.kt`, `data/storage/StringKeyValueStore.kt` | Independent diagnostic/problem toggles, consent version 1, explicit OFF default. Missing, malformed, unknown-version, or interrupted state reads OFF. Save removes the version first so a partial write cannot pair a new toggle with an older decision. No network access. |
| Install identity | `data/privacy/InstallIdProvider.kt`, `data/UuidFormat.kt` | `UUID.randomUUID()` v4, canonical lowercase, stored in `SharedPreferences("clearpdf_privacy")`. Report-only; `data/remote` has no reference. No hardware identifier and no logging. |
| Config transport | `data/remote/RemoteHttpClient.kt` | Existing `HttpURLConnection`; HTTPS-only; 5 s connect/read; no redirects; 64 KiB bounded read; publishable `apikey` header. No OkHttp/Ktor/Retrofit/DI. |
| Config contract | `data/remote/FeatureFlagClient.kt`, `FeatureFlagModels.kt` | Request is only `keys` and `app_version_code`. No `install_id`, install UUID, Android ID, device model, `tester_token`, or persistent identifier. Unknown fields/flags ignored; malformed requested flags fail closed. |
| Config cache | `data/remote/FeatureFlagCache.kt`, `RemoteConfig.kt` | `SharedPreferences("clearpdf_flags")`; 60 s freshness; last-known-good honored up to 24 h; no cache or older than 24 h is all-OFF. 429 honors bounded `Retry-After`; 5xx uses bounded exponential backoff; 401/403/invalid responses do not write a cache. Global/per-flag kill switches force OFF. |
| Effective flags | `FeatureFlagClient.kt` | Local consent AND a valid enabled remote flag. Flags never mutate consent. Fractional rollout is not supported client-side because config carries no install ID; flags must be 0/100 or version/tester/segment rules. |
| Report models | `data/report/ReportModels.kt` | Exact backend schema; fixed self-check allowlist; 16 KiB body, 1–4000 description, 1–200/1–4000 error text, 30-day/5-minute timestamp window; diagnostic and problem shapes mutually exclusive; forbidden document/device/contact fields do not exist. |
| Sanitizer | `data/report/ReportSanitizer.kt` | Deterministic, side-effect-free. User descriptions are flagged, not silently rewritten, for paths/URIs (content/file/HTTP(S)/FTP/mailto/tel), document/media filenames, tokens/passwords, email, phone, and long opaque values. Error text is auto-redacted. Server validation remains authoritative. |
| Local queue | `data/report/ReportQueueStore.kt`, `ReportQueueFactory.kt` | One JSON envelope per report under `noBackupFilesDir/reports`; stable `client_report_id`; states `pending/failed/submitted/rejected/cancelled`; terminal states immutable; 50-entry bound pruning oldest terminal first; corrupt files ignored and removed only when space is needed. No credentials, no upload path. |
| WorkManager | `ReportQueueFactory.kt` | `ReportUploadScheduler` interface plus `ReportWorkName.uniqueName()` only. No worker class, scheduling call, periodic work, or startup diagnostics. |
| Manifest/backup | `AndroidManifest.xml`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` | `INTERNET` declared in the main manifest; `usesCleartextTraffic="false"`; cloud backup and device transfer exclude `clearpdf_privacy.xml` and `clearpdf_flags.xml`; the no-backup report queue is excluded by location. PDF user data behavior is unchanged. |
| Build config | `app/build.gradle.kts` | `buildConfig = true`; `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`, `CONFIG_ENDPOINT`, `REPORT_ENDPOINT` with empty safe defaults and optional `local.properties`/environment overrides. No service-role key. |

### 11.2 Tests written, not executed

Eleven JVM test files (80 `@Test` methods) cover consent, install ID, config request/parser/cache, report models, sanitizer, queue, work naming, and static manifest/backup/build-config checks. They were **not compiled or run locally**, per the owner's instruction to validate through GitHub Actions. No CI run occurred because this task did not commit or push.

CI path: `.github/workflows/android.yml` runs `./gradlew testPlayDebugUnitTest :pdf-core:test :ocr-core:test`, `lintPlayDebug`, and `assemblePlayDebug` on PRs/manual dispatch.

### 11.3 Static checks that did run

- `git diff --check` — exit 0.
- Python `xml.etree.ElementTree` parsed the main/FOSS/F-Droid manifests and both backup XML files successfully.
- Greps found no `install_id`/`InstallIdProvider` in `data/remote`, no `tester_token` in Android, no forbidden report fields, no logging in the new privacy/remote/report code, and no production caller of `RemoteConfig`, `FeatureFlagClient.refresh()`, `ReportQueueStore`, or `ReportPayloadFactory`.

### 11.4 Not done

No onboarding/Settings/report UI, no startup or navigation wiring, no `Application` class, no report submitter/worker, no automatic or manual transmission, no Supabase migration/function change, no deployment, no commit/push/merge, and no Phase 3.3+ work.
