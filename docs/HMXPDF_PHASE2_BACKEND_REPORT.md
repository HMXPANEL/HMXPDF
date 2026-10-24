# HMXPDF Phase 2 — Supabase Backend Report

**Date:** 2026-10-24
**Branch:** `feature/hmxpdf-diagnostics-admin-updates`
**Supabase project:** `tchoqlaqgcrbbvomaebo` — `https://tchoqlaqgcrbbvomaebo.supabase.co`
**Project region:** `ap-northeast-2` (management API). Edge Function logs label the runtime region `ap-south-1`; this is recorded as an observation, not changed.
**Status:** Implementation complete and deployed. A second verification pass on 2026-10-24 rechecked Git/remote state, the live migration, deployed functions, RLS/grants, public signup, rate-limit IP handling, and the available tests. Public signup is **enabled** and must be disabled before admin provisioning. Admin AAL2 end-to-end verification remains **BLOCKED** on admin/TOTP/`ADMIN_ORIGIN` provisioning. Phase 3 has not started.

---

## 1. Scope and outcome

Phase 2 delivers the backend foundation for remote feature configuration:

- Feature flags with enabled/disabled state, 0–100 % rollout, app-version gates, optional schedule, typed JSON config, `config_version`, and per-flag kill switch.
- Deterministic per-install/per-flag rollout using SHA-256, stable across refreshes.
- Invite-only tester enrollment and server-side tester/segment grants.
- Global and per-flag emergency kill switches with explicit precedence.
- Public, read-only `config` delivery separated from privileged mutation endpoints.
- Admin authentication foundation: Supabase Auth JWT, mandatory `aal2`, active `admin_users` allowlist, server-side authorization on every request.
- RLS and deny-by-default grants for `anon`/`authenticated`; `service_role` is used only inside Edge Functions.

**Explicitly out of scope and not implemented:** PDF/document upload, diagnostics ingestion, support reports, retention jobs, Android changes, Vercel admin UI, release automation, broad analytics.

---

## 2. Remote state actually applied

| Item | State |
|---|---|
| Migration `hmx_feature_flags` | Applied. Remote version `20261024105232`, matching `supabase/migrations/20261024105232_hmx_feature_flags.sql`. |
| `hmx` tables | 9 created, RLS enabled on all 9. |
| `hmx` functions | `is_admin`, `admin_role`, `consume_rate_limit`, `enroll_tester`, `touch_feature_flag`, `touch_system_settings`. |
| Edge Function `config` | Deployed, version 3, `verify_jwt = false`. v3 adds the 60 s freshness cap and SHA-256 rate-limit bucket keys. |
| Edge Function `tester-enroll` | Deployed, version 3, `verify_jwt = false`. v3 adds SHA-256 rate-limit bucket keys. |
| Edge Function `admin-api` | Deployed, version 1, `verify_jwt = true`. |
| Test data | Removed after tests. 0 flags, 0 testers, 0 invites, 0 audit rows, 0 rate-limit rows. |
| `system_settings.kill_switch_all` | `false`. |
| Auth users / MFA factors | None created. |
| Public signup | **Enabled** (`disable_signup: false`, email provider enabled). Not changed; must be disabled in the dashboard before creating the admin. |
| `ADMIN_ORIGIN` secret | Not set. `admin-api` fails closed with HTTP 503 until it is configured. |
| Config freshness | `max_age_seconds` is **60** in source and in the live `config` v3 response (reduced from 300 for kill-switch responsiveness). |
| Rate-limit bucket keys | SHA-256 of the client IP (pseudonymous), never the raw IP. The platform overwrites client-supplied `x-forwarded-for`; verified live. |

The project was empty before this work. No existing database objects, auth users, policies, or data were modified or deleted.

---

## 3. Architecture and trust boundaries

```
Android (Phase 3) ── POST /functions/v1/config ──► config ── service_role ──► hmx
Tester device     ── POST /functions/v1/tester-enroll ─► tester-enroll ── service_role ──► hmx
Vercel admin (Phase 4) ── POST /functions/v1/admin-api ─► admin-api ── service_role ──► hmx
                              │
                              └─ Supabase Auth JWT + aal2 + active admin_users row
```

| Boundary | Rule |
|---|---|
| Android → `config` | No Supabase user session. `apikey` header only. Returns requested flags only. |
| Tester → `tester-enroll` | Invite code is the credential. Rate-limited by IP and invite hash. Token returned once. |
| Admin → `admin-api` | Platform-verified JWT, `aal2` claim, live `auth.getUser` check, active `admin_users` allowlist. Exact `ADMIN_ORIGIN` CORS. |
| Edge Functions → database | `SUPABASE_SERVICE_ROLE_KEY` is injected by Supabase. It never leaves the server runtime. |
| Public PostgREST | `hmx` is in `db-schemas` so service-role calls resolve, but `anon`/`authenticated` have no schema `USAGE` and no table grants. |
| Client privilege | A feature flag is **not** an authorization boundary. Any future sensitive backend action must authorize independently. |

**Threat model (Phase 2):**

| Threat | Mitigation | Residual risk |
|---|---|---|
| Client edits local preference to become tester | Tester status is resolved from a server-side SHA-256 token hash; no client role flag is trusted. | A stolen tester token can be replayed until revoked. |
| Client guesses another user's rollout cohort | Bucket is SHA-256(`install_id:flag_key`) → 0–9999; install id is a random UUID, not a personal identifier. | A user can clear app data to obtain a new install id and re-roll. Documented. |
| Direct PostgREST read/write of flags/admin data | RLS + no grants + no schema USAGE for `anon`/`authenticated`; verified `42501`. | A future migration that grants access would expose the schema; review grants on every change. |
| Admin token theft / AAL1 session | `admin-api` requires `aal2`, validates the token with Auth, and checks the live allowlist. | No hardware-key requirement; TOTP phishing is out of scope. |
| Invite brute force | 32-byte random invite codes, SHA-256 at rest, IP and code rate limits, expiry and use caps. | Distributed brute force remains rate-limited per IP/code. |
| Kill switch ignored by client | Response carries `kill_switch` and per-flag `enabled:false`; Phase 3 must fail closed. | Offline devices cannot be killed instantly. |
| Service-role leakage | Key only in Edge Function environment; no `VITE_*`, Android, logs, or Git. | Supabase project compromise is out of scope. |

---

## 4. Database schema

Migration: `supabase/migrations/20261024105232_hmx_feature_flags.sql`.

| Table | Key columns | Notes |
|---|---|---|
| `hmx.feature_flags` | `key` PK, `name`, `description`, `enabled`, `rollout_percentage`, `min_app_version_code`, `max_app_version_code`, `starts_at`, `ends_at`, `kill_switch`, `config` jsonb, `config_version`, timestamps | Checks reject malformed keys, percentage outside 0–100, inverted version ranges, inverted schedules, non-object config, and config > 16 KiB. |
| `hmx.testers` | `id`, `install_id` unique, `token_hash` unique, `status`, `segment`, optional app/device metadata | `token_hash` must be 64 lowercase hex characters. Plaintext tokens are never stored. |
| `hmx.tester_invites` | `code_hash` unique, `max_uses`, `used_count`, `expires_at`, `revoked_at` | `used_count <= max_uses`; invite redemption is atomic. |
| `hmx.feature_flag_testers` | (`flag_key`, `tester_id`) PK | Explicit tester grant per flag. |
| `hmx.feature_flag_segments` | (`flag_key`, `segment`) PK | Segment grant per flag. |
| `hmx.admin_users` | `user_id` PK → `auth.users`, `role`, `is_active` | Manual provisioning only; no insert policy for clients. |
| `hmx.system_settings` | `id` boolean singleton, `kill_switch_all`, `updated_by` | Seeded with `false`. |
| `hmx.admin_audit_log` | `action`, `target_type`, `target_id`, `metadata`, `admin_user_id` | Metadata capped at 8 KiB. Never stores tokens or invite codes. |
| `hmx.rate_limits` | (`bucket`, `window_start`) PK, `count` | Fixed-window counters. |

**Functions:**

- `hmx.is_admin(uuid)` / `hmx.admin_role(uuid)` — `security definer`, `search_path = ''`, service-role execute only.
- `hmx.consume_rate_limit(bucket, limit, window_seconds)` — atomic upsert/increment; returns whether the caller is within the limit.
- `hmx.enroll_tester(code_hash, install_id, token_hash, ...)` — atomically consumes an invite and inserts the tester. A duplicate `install_id` raises `23505` and rolls back the invite use.
- `hmx.touch_feature_flag()` — sets `updated_at` and increments `config_version` on every update.
- `hmx.touch_system_settings()` — sets `updated_at`.

**PostgREST exposure:** `authenticator` has `pgrst.db_schemas = 'public, storage, graphql_public, hmx'`. This is required for service-role Edge Function queries. `anon` and `authenticated` have no `USAGE` on `hmx` and no table grants; a live request returned HTTP 401 with `42501 permission denied for schema hmx`. If the Supabase dashboard "Exposed schemas" setting is changed later, re-apply the role setting.

---

## 5. Edge Functions

### 5.1 `config` — public read-only delivery

`POST https://tchoqlaqgcrbbvomaebo.supabase.co/functions/v1/config`
`verify_jwt = false`

**Request:**

```json
{
  "keys": ["feature.example"],
  "install_id": "00000000-0000-4000-8000-000000000000",
  "app_version_code": 42,
  "tester_token": "64-hex-characters"
}
```

- `keys`: required, 1–50 unique keys matching `^[a-z][a-z0-9_.-]{2,63}$`.
- `install_id`: optional UUID; required for fractional rollout.
- `app_version_code`: optional integer 0–2,100,000,000.
- `tester_token`: optional 64 hex characters. A malformed token returns `400 invalid_tester_token`; a well-formed but unknown or revoked token is accepted but does not grant tester status.
- Body limit: 4 KiB. Methods: `POST`/`OPTIONS`. Rate limit: 120 requests/minute/IP → `429` with `Retry-After: 60`. The limiter bucket stores a SHA-256 hash of the IP, not the IP itself.

**Response (200):**

```json
{
  "revision": "sha256-hex",
  "generated_at": "2026-10-24T10:57:29.618Z",
  "max_age_seconds": 60,
  "kill_switch": false,
  "flags": {
    "feature.example": {
      "enabled": true,
      "config": {"mode": "safe"},
      "version": 3,
      "source": "rollout"
    }
  }
}
```

- Only requested, existing flags are returned. Unknown flags are **absent**; clients must treat absence as OFF.
- `source` is one of `global_kill_switch`, `flag_kill_switch`, `disabled`, `not_started`, `expired`, `version_mismatch`, `tester`, `segment`, `rollout`, `missing_install_id`, `default_off`.
- `revision` is a SHA-256 over the effective flag map and the global kill switch. It changes when effective configuration changes.
- `Cache-Control: no-store`; clients may cache up to `max_age_seconds` (60 s). Safety-critical flags should use a shorter client-side interval (for example 15–30 s). The 60 s value is a cap, not a requirement to wait that long.
- Errors: `400` invalid input, `413` body too large, `405` wrong method, `429` rate limited, `503` dependency failure. Error bodies are `{"error":"code"}`.

### 5.2 `tester-enroll` — invite redemption

`POST https://tchoqlaqgcrbbvomaebo.supabase.co/functions/v1/tester-enroll`
`verify_jwt = false`

**Request:**

```json
{
  "invite_code": "64-hex-characters",
  "install_id": "uuid",
  "app_version_code": 42,
  "app_version_name": "1.2.3",
  "android_api": 34,
  "device_model": "optional label"
}
```

**Response (201):**

```json
{
  "tester_token": "64-hex-characters",
  "segment": "default",
  "label": "invite label"
}
```

- The token is returned **once**. The server stores only its SHA-256 hash.
- Unknown, expired, revoked, or exhausted invite → `404 invalid_invite` (indistinguishable by design).
- Duplicate `install_id` → `409 already_enrolled`; the invite use is rolled back.
- Rate limits: 10 attempts/minute/IP and 5 attempts/hour/invite hash → `429`.
- Body limit: 4 KiB; optional metadata is length/type validated.

### 5.3 `admin-api` — privileged mutations

`POST https://tchoqlaqgcrbbvomaebo.supabase.co/functions/v1/admin-api`
`verify_jwt = true`

**Authentication:**

1. Supabase platform verifies the JWT signature.
2. Function requires the `aal` claim to be exactly `aal2`.
3. `auth.getUser(token)` confirms the user still exists and the session is valid.
4. `hmx.admin_users` must contain an active row with `role = 'admin'`.
5. `ADMIN_ORIGIN` must be configured; a browser `Origin` that does not match is rejected with `403`.

**Envelope:**

```json
{"action": "flags.upsert", "flag": {"key": "feature.example", "name": "Example"}}
```

Response: `{"ok":true,"data":...}` or `{"error":"code"}`.

**Actions:**

| Action | Body | Effect |
|---|---|---|
| `flags.list` | — | All flags, ordered by key. |
| `flags.upsert` | `flag` object | Insert/update a flag; `config_version` increments on update. |
| `flags.delete` | `key` | Delete a flag and cascade assignments. |
| `flags.assign-tester` | `flag_key`, `tester_id` | Explicit tester grant. |
| `flags.unassign-tester` | `flag_key`, `tester_id` | Remove tester grant. |
| `flags.assign-segment` | `flag_key`, `segment` | Segment grant. |
| `flags.unassign-segment` | `flag_key`, `segment` | Remove segment grant. |
| `flags.assignments` | — | All tester and segment grants. |
| `settings.get` | — | Global kill-switch state. |
| `settings.kill-switch` | `enabled` boolean | Toggle the global kill switch. |
| `testers.list` | — | Testers without token hashes. |
| `testers.revoke` | `tester_id` | Mark a tester revoked. |
| `invites.list` | — | Invites without code hashes. |
| `invites.create` | `label?`, `max_uses?`, `expires_in_hours?` | Returns a new plaintext invite code once. |
| `invites.revoke` | `invite_id` | Revoke an invite. |
| `audit.list` | — | Latest 100 audit rows. |

`flags.upsert` is a **full replacement**, not a patch. Omitted optional fields reset to their defaults (`enabled: false`, `rollout_percentage: 0`, `kill_switch: false`, `config: {}`, null schedule/version fields). Read the current row with `flags.list` and send the full desired object when changing one field.

Every successful mutation attempts an `admin_audit_log` insert. Audit writes are best-effort; a DB failure after the mutation is logged but does not roll back the mutation.

**CORS:** only the exact `ADMIN_ORIGIN` is allowed. If `ADMIN_ORIGIN` is unset, every request returns `503 admin_origin_not_configured`. This is intentional fail-closed behavior, not a deployment success.

---

## 6. Feature-flag semantics

**Precedence (first match wins):**

1. Global kill switch (`system_settings.kill_switch_all`) → OFF.
2. Per-flag kill switch (`feature_flags.kill_switch`) → OFF.
3. `enabled = false` → OFF.
4. Schedule: before `starts_at` → OFF (`not_started`); at/after `ends_at` → OFF (`expired`).
5. App version outside `[min_app_version_code, max_app_version_code]` → OFF (`version_mismatch`). Unknown client version with a gate → OFF.
6. Explicit tester grant → ON (`tester`).
7. Tester segment grant → ON (`segment`).
8. Percentage rollout → ON/OFF (`rollout`).
9. Missing/invalid install id at a fractional percentage → OFF (`missing_install_id`).
10. Invalid percentage → OFF (`default_off`).

**Bucketing:**

```
bucket = uint32_be(SHA-256(install_id.lower() + ":" + flag_key)) % 10000
enabled = bucket < rollout_percentage * 100
```

- 0 % is always OFF; 100 % is always ON and does not require an install id.
- The same install/flag pair always maps to the same bucket, so a user stays in the same cohort across refreshes.
- Increasing the percentage only adds users below the new threshold; it does not reshuffle existing users. Decreasing it removes users above the threshold.
- Clearing app data or reinstalling creates a new install id and a new cohort. This is documented and intentional; no personal identifier is used.
- `install_id` is a random UUID generated on-device in Phase 3, never an email, phone number, advertising ID, or account ID.

**Config version:** `config_version` starts at 1 and increments on every update via trigger. The response `version` is the flag's `config_version`; the top-level `revision` is a stable hash of the whole effective response.

---

## 7. Tester targeting and privacy

- Enrollment is invite-only. There is no public signup path for tester or admin privileges.
- Invite codes and tester tokens are 32 random bytes encoded as 64 hex characters.
- The database stores only SHA-256 hashes of invite codes and tester tokens. Plaintext is returned once and never logged.
- Tester status is server-authenticated by token hash. A client cannot become a tester by editing a preference or sending an arbitrary ID.
- Explicit tester grants are keyed by `tester_id` in `hmx.feature_flag_testers`; segment grants by `segment` in `hmx.feature_flag_segments`.
- Optional metadata (`app_version_code`, `app_version_name`, `android_api`, `device_model`) is stored only for enrolled testers. It is not collected from ordinary users.
- Rate-limit buckets store a SHA-256 hash of the client IP, not the raw IP. This is pseudonymous rather than anonymous, is service-role-only, and still needs a retention policy.
- Revocation is immediate for future config requests; an already-cached client may keep the last response until its freshness window expires.
- Reinstall/data-clear: the client gets a new `install_id` and loses the tester token. The old tester row remains until an admin deletes it; re-enrollment with the old `install_id` is rejected with `409`.
- Phase 2 does not collect PDFs, document contents, diagnostics, stack traces, file paths, or analytics.

**Retention:** not implemented in Phase 2. Recommended: delete revoked testers/invites and `rate_limits` rows older than the longest rate-limit window (one hour) periodically; define a retention period before Phase 3 diagnostics ships. The pseudonymous IP hashes should not be retained indefinitely.

---

## 8. Admin security and bootstrap

**Enforced by `admin-api`:**

- Platform JWT verification (`verify_jwt = true`).
- `aal2` claim required. A valid AAL1 session is rejected with `403 mfa_required`.
- Live user lookup with `auth.getUser(token)`; deleted/banned users are rejected.
- Active `hmx.admin_users` row with `role = 'admin'` required. Client-supplied roles/emails/IDs are ignored.
- Exact `ADMIN_ORIGIN` CORS and origin check.
- Strict body/field validation and 32 KiB body limit.

**Live auth setting verified (2026-10-24):** `GET /auth/v1/settings` returns `disable_signup: false` and `external.email: true`. Public email signup is therefore **enabled**. This does not grant admin access because AAL2 and the allowlist are still enforced, but it is unnecessary account-creation surface for this project and must be disabled before the admin account is created. It was **not changed** during this verification pass.

**Exact dashboard action:** Authentication → Sign In / Providers → Email → turn off **Allow new users to sign up**. Do not disable the Email provider itself; the admin still needs email/password. The same setting is `disable_signup` in `PATCH /v1/projects/<project-ref>/config/auth`. Recheck `GET /auth/v1/settings` for `disable_signup: true`.

**Bootstrap steps (manual, after approval):**

1. Disable public signup as above.
2. Authentication → Users → **Add user**: email + strong password, enable **Auto Confirm User**. Copy the user UUID.
3. Enroll TOTP for that user. The project dashboard does not provide a per-user TOTP enrollment screen in the current UI, so use a trusted one-off local script with `@supabase/supabase-js` and the project URL + publishable/anon key. Do not run this in an untrusted browser page and do not log the access token or TOTP secret.

```ts
// One-off local script. Do not commit real values or the resulting session.
import { createClient } from "@supabase/supabase-js";

const supabase = createClient(SUPABASE_URL, PUBLISHABLE_KEY);
const { error: signInError } = await supabase.auth.signInWithPassword({
  email: ADMIN_EMAIL,
  password: ADMIN_PASSWORD,
});
if (signInError) throw signInError;

const { data: enrollment, error: enrollError } = await supabase.auth.mfa.enroll({
  factorType: "totp",
  friendlyName: "HMXPDF admin",
});
if (enrollError) throw enrollError;
// Scan enrollment.totp.qr_code with the authenticator and store the secret securely.
// enrollment.id is the factor id.
```

Then complete the challenge with a current 6-digit code:

```ts
const { data: challenge, error: challengeError } = await supabase.auth.mfa.challenge({
  factorId: FACTOR_ID,
});
if (challengeError) throw challengeError;

const { data: verified, error: verifyError } = await supabase.auth.mfa.verify({
  factorId: FACTOR_ID,
  challengeId: challenge.id,
  code: TOTP_CODE,
});
if (verifyError) throw verifyError;
// verified.access_token now has aal: "aal2"; use it only as a Bearer token.
```

Supabase does not issue TOTP recovery codes. Register a second TOTP factor on a different device, or store the secret in a secure offline location; losing all factors can lock the admin out.

4. Insert the allowlist row in the SQL editor (service-role/dashboard only; never expose this SQL to clients):

```sql
insert into hmx.admin_users (user_id, role)
values ('<auth-user-uuid>', 'admin')
on conflict (user_id) do update set role = 'admin', is_active = true;
```

5. Set the Edge Function secret `ADMIN_ORIGIN` only after the real Phase 4 Vercel deployment exists. Use the exact origin, including scheme, with **no trailing slash** and no wildcard. Do not set a placeholder.
6. Verify the authorization matrix from a browser on that exact origin:
   - AAL1 access token → `403 mfa_required`.
   - AAL2 access token + active `admin` allowlist row → `200`.
   - AAL2 access token + no allowlist row → `403 forbidden`.
   - AAL2 access token + inactive allowlist row → `403 forbidden`.
   - Wrong `Origin` header → `403 origin_not_allowed`.
   - `settings.kill-switch` with `enabled: false` succeeds and creates an `audit.list` row.
   - A request with no `Origin` header is allowed by design; CORS is a browser boundary, not the authentication boundary.

No service-role key is needed in the browser for these steps; use the Supabase dashboard, SQL editor, and the user's AAL2 session.

---

## 9. Kill switch and safe defaults

- Global switch: `hmx.system_settings.kill_switch_all`. Overrides everything, including testers and 100 % rollout.
- Per-flag switch: `hmx.feature_flags.kill_switch`. Overrides testers and percentage rollout for that flag.
- Disabled (`enabled = false`) is also a hard OFF before tester/segment/rollout.
- The response includes both `kill_switch` (global) and per-flag `enabled`/`source`, so a client can fail closed even if it does not understand a flag's config.
- **Maximum practical delay:** `max_age_seconds` (60 s) + the client's refresh interval + time offline. A device that is offline or not refreshing cannot be killed instantly. Phase 2 does not claim otherwise.
- **Cold start / failed request / invalid response / server error:** the Phase 3 client must default every flag to OFF and may use a cached response only while it is within its freshness window. Safety-critical features should use a shorter window (for example 15–30 s) and must not block app startup on the network.
- The backend never claims it can remove already-downloaded native code. Flags only gate code that is already in the APK.

---

## 10. RLS and security verification

Verified on the live project:

| Check | Result |
|---|---|
| RLS enabled on every `hmx` table | 9/9 |
| `anon`/`authenticated` table grants | 0 |
| `anon`/`authenticated` schema `USAGE` | false |
| Function `EXECUTE` ACL | only `postgres` and `service_role` |
| Direct PostgREST with anon key, `Accept-Profile: hmx` | HTTP 401, `42501 permission denied for schema hmx` |
| `service_role` table grants | 63 table privileges across 9 tables |
| SQL role tests | anon/authenticated denied; service_role can select/insert/delete and insert audit rows |
| SQL constraint tests | invalid percentage/key/config/schedule/version range and array/oversized audit metadata rejected |
| Rate-limit SQL boundary | 2 allowed, 3rd denied |
| Invite SQL tests | valid enrolls once; exhausted/expired rejected; duplicate install rolls back invite use |
| `config_version` trigger | increments on update |
| Public signup setting | **Enabled** (`disable_signup: false`); verified via `GET /auth/v1/settings`. Must be disabled before admin provisioning. |
| Client-supplied `x-forwarded-for` | Ignored/replaced by the platform in a live request; the rate limiter used the real client IP. |
| Rate-limit bucket privacy | Source hashes the IP before storage; live bucket key is `config:<64 hex>`, not the raw IP. |
| Config freshness | Response advertises `max_age_seconds: 60` after the `config` redeploy. |

The `hmx` schema is exposed to PostgREST for service-role resolution. This does **not** grant client access; the security boundary is schema `USAGE` + table grants + RLS. Any future table added to `hmx` must be followed by explicit `REVOKE ALL ... FROM public, anon, authenticated` and a grant review.

---

## 11. Deployment and secrets

### 11.1 Deploying the migration and functions

The Phase 2 migration was applied with the Supabase MCP tool; the remote version matches the local filename. For future CLI deployment:

```bash
supabase login
supabase link --project-ref tchoqlaqgcrbbvomaebo
supabase db push
supabase functions deploy          # reads supabase/config.toml verify_jwt settings
```

The CLI requires a Supabase access token and, for `db push`, database credentials. Do not run this in CI until the secrets and environment protections are configured.

### 11.2 Required secrets

**Actually required now:** one Supabase Edge Function secret.

| Name | Purpose | Where | Required now? |
|---|---|---|---|
| `ADMIN_ORIGIN` | Exact Phase 4 Vercel origin allowed to call `admin-api`; without it the function returns 503. | Supabase Edge Function secret (dashboard → Edge Functions → Secrets, or `supabase secrets set`) | **Yes**, before Phase 4/admin use |

**Auto-injected by Supabase into Edge Functions (do not add to GitHub, Android, or Vercel):**

| Name | Purpose |
|---|---|
| `SUPABASE_URL` | Project URL used by the Edge Functions. |
| `SUPABASE_SERVICE_ROLE_KEY` | Service-role database access inside the Edge Functions. Never expose to clients. |

**GitHub Actions secrets:** the committed Phase 2 code adds **no deployment workflow**, so **no GitHub Actions secret is required for Phase 2**. If you later add a CI deployment workflow, use exactly:

| GitHub Secret name | Purpose | Workflow |
|---|---|---|
| `SUPABASE_ACCESS_TOKEN` | Authenticates the Supabase CLI for function deployment. | Deployment workflow only |
| `SUPABASE_DB_PASSWORD` | Allows `supabase db push` to apply migrations. | Deployment workflow only |
| `SUPABASE_URL` | Project URL for build-time injection/tests. | Optional CI/Android build |
| `SUPABASE_ANON_KEY` | Public anon/publishable key for Android/Vercel clients. | Android/Vercel build, never server-only |

Do **not** add `SUPABASE_SERVICE_ROLE_KEY` to GitHub Actions, Android, or Vercel. The Edge Functions receive it automatically; the browser and APK must never contain it. Prefer the modern publishable key (`sb_publishable_...`) for new clients where supported; the legacy anon key was used only for the Phase 2 HTTP tests.

**Phase 4 Vercel public variables (not GitHub secrets):** `VITE_SUPABASE_URL` and `VITE_SUPABASE_ANON_KEY` (or `VITE_SUPABASE_PUBLISHABLE_KEY`). These are public by design; never use `VITE_*` for the service-role key.

---

## 12. Tests and results

### PASS

| Test | Command / method | Result |
|---|---|---|
| Rollout algorithm | `node --test supabase/tests/rollout.test.ts` | 18/18 pass: 0/1/5/50/100 %, determinism, unknown flags, disabled, tester/segment, schedule, version gates, invalid percentage, kill switches, revision stability. |
| SQL constraints/RLS/rate-limit/invites/audit | `supabase/tests/hmx_security.sql` via SQL editor/MCP, transaction rolled back | `sql_security_tests_passed` (re-run 2026-10-24) |
| Migration apply | Supabase MCP `apply_migration` | Success; 9 tables, 6 functions, RLS 9/9. |
| Direct PostgREST denial | anon key + `Accept-Profile: hmx` | 401 `42501 permission denied for schema hmx` (re-run 2026-10-24) |
| Public signup inspection | `GET /auth/v1/settings` with publishable key | `disable_signup:false`, `external.email:true`; **enabled**, remediation pending |
| Client-supplied `x-forwarded-for` | live config request with a spoofed header, then inspect `rate_limits` | Platform replaced it; limiter bucketed the real client IP |
| Rate-limit bucket privacy | live config request, then inspect `rate_limits` | Bucket key is `config:<64-hex SHA-256>`; no raw IP stored |
| Config freshness | live config response | `max_age_seconds:60` (after `config` redeploy) |
| Config 100 % + config payload | live HTTP | `enabled:true`, `source:rollout`, `config:{value:1}` |
| Global kill switch | live HTTP after SQL toggle | `enabled:false`, `source:global_kill_switch`, `kill_switch:true`, new revision |
| Tester enrollment | live HTTP | 201, token returned once, segment `default` |
| Tester grant at 0 % | live HTTP | `enabled:true`, `source:tester` |
| Invite reuse | live HTTP | 404 `invalid_invite` |
| Duplicate install | live HTTP | 409 `already_enrolled`; invite `used_count = 0` |
| Tester revocation | live HTTP | flag off, `source:rollout` |
| Invalid inputs | live HTTP | 400 for bad keys/ids/JSON/invites; 413 oversized; 405 GET |
| Rate limiting | 130 parallel config calls | 120×200, 10×429, `Retry-After: 60` |
| Admin with no credentials | live HTTP, no `apikey`/`Authorization` | platform 401 `UNAUTHORIZED_NO_AUTH_HEADER` |
| Admin with publishable `apikey` only | live HTTP | 503 `admin_origin_not_configured` (fail closed) |
| Admin with anon JWT | live HTTP | 503 `admin_origin_not_configured` (fail closed) |
| Admin with malformed bearer | live HTTP | platform 401 `UNAUTHORIZED_INVALID_JWT_FORMAT` |
| Log secret scan | unified logs query for `tester_token`, `eyJhbGci`, `service_role` | No matches |

### BLOCKED

| Test | Reason | Next action |
|---|---|---|
| AAL1 rejected / AAL2 accepted | No admin user, TOTP factor, or `ADMIN_ORIGIN` secret. `ADMIN_ORIGIN` is checked before `authenticate`, so the 503 rows above do not exercise the AAL2/allowlist path. | Provision the admin, enroll TOTP, set `ADMIN_ORIGIN`, rerun. |
| Non-admin/inactive-admin rejection | Same. | Same. |
| Audit rows on mutations | Same. | Same. |
| Admin mutations via `admin-api` | Same. | Same. |

### NOT RUN

| Test | Reason |
|---|---|
| `supabase db reset` from empty local database | No Supabase CLI, Docker, or local Postgres. Remote apply succeeded; local reproducibility not executed. |
| Deno type-check/unit tests | Deno is not installed. Functions were deployed and executed against the live project; Node tests cover the rollout module. |
| Android regression/build | No Android changes in Phase 2; no Android SDK/emulator in this environment. |
| Disable public signup | Verified enabled but not changed; requires the dashboard/Management API action above. |

---

## 13. Known risks and unresolved decisions

1. **`ADMIN_ORIGIN` is unset.** `admin-api` returns 503 until it is configured. This is intentional, but it means Phase 2 admin functionality is not usable yet.
2. **No admin user/TOTP exists.** The AAL2 and allowlist path is implemented but not end-to-end verified.
3. **Public signup is enabled.** Verified live (`disable_signup:false`). It does not bypass AAL2/allowlist, but it is unnecessary account-creation surface. Disable it before creating the admin account.
4. **Config is structurally validated, not schema-validated per flag.** The server enforces JSON object + 16 KiB. Phase 3 must validate each flag's typed payload and ignore unknown keys. No executable code is stored.
5. **Audit writes are best-effort.** A DB failure after a mutation can lose the audit row. The failure is logged, but the mutation is not rolled back. A transactional mutation+audit RPC can close this gap in Phase 4 if audit completeness becomes a hard requirement. The current admin volume is zero because no admin exists.
6. **No retention/cleanup job.** `rate_limits`, revoked testers, and invites grow until manually cleaned. Rate-limit buckets contain SHA-256 IP hashes, which are pseudonymous but should still be deleted after the longest window. Define retention before diagnostics ships.
7. **Kill switch is not instantaneous.** Maximum practical delay is the client cache window (60 s) + refresh interval + offline time. The previous 300 s value was reduced to 60 s; clients may use a shorter interval for safety-critical flags.
8. **Reinstall/data-clear re-rolls rollout.** A new `install_id` gets a new bucket; testers lose their token and need a new invite.
9. **PostgREST exposure requires discipline.** `hmx` is in `db-schemas`; any new table must revoke client grants. The dashboard "Exposed schemas" setting can overwrite the role configuration; re-apply if changed.
10. **Region label discrepancy.** Management API says `ap-northeast-2`; Edge Function logs label `ap-south-1`. The project was not moved. Confirm the desired region before production data exists.
11. **Tester token is a bearer credential.** Store it in Android Keystore in Phase 3; never log it or put it in URLs. Revocation is server-side.
12. **`last_seen_at` write per tester config request.** Acceptable at current scale; revisit if request volume grows.

---

## 14. Phase 3 and Phase 4 integration

### Android (Phase 3)

- Base URL: `https://tchoqlaqgcrbbvomaebo.supabase.co/functions/v1/config`.
- Send `apikey: <anon/publishable key>` and JSON body. No Supabase user JWT is needed.
- Generate a random UUID `install_id` once and persist it; never use email/phone/advertising ID.
- Store the tester token in Keystore; send it only in the request body over HTTPS.
- Cache the response with `revision`; refresh no later than `max_age_seconds` (60 s). Use a shorter interval for safety-critical flags.
- Treat absent flags, invalid JSON, non-200 responses, and expired cache as **OFF**. Enforce the global `kill_switch` before any feature code runs.
- Keep flag keys as compile-time constants; remote config gates code already in the APK.
- Do not use flags as an authorization boundary for sensitive actions.

### Vercel admin (Phase 4)

- Use Supabase Auth email/password + TOTP; require an `aal2` session before calling `admin-api`.
- Send `Authorization: Bearer <access token>` and `apikey: <publishable key>`.
- Set `ADMIN_ORIGIN` to the exact deployed Vercel origin; no wildcard.
- Use the `admin-api` action envelope and render all returned strings as text (no `innerHTML`).
- Keep the service-role key out of Vercel entirely; `VITE_*` variables are public.
- Add CSP/security headers and verify them on the deployed URL.

---

## 15. Git and next steps

Phase 2 changes are committed only to `feature/hmxpdf-diagnostics-admin-updates`; `main`/`origin/main` are untouched. The exact commit hash and push status are reported in the Phase 2 handoff message.

**Required next actions:**

1. Disable public signup in Authentication → Sign In / Providers → Email (or `PATCH /v1/projects/<project-ref>/config/auth` with `disable_signup: true`). Recheck `GET /auth/v1/settings`.
2. Create the admin user (auto-confirm), enroll TOTP, and confirm the session reaches `aal2`.
3. Insert the `hmx.admin_users` allowlist row for that user.
4. Set the Supabase Edge Function secret `ADMIN_ORIGIN` to the real Phase 4 Vercel origin; do not use a placeholder.
5. Rerun the blocked AAL1/AAL2/non-admin/inactive-admin/audit tests.
6. Approve Phase 2 before Phase 3 begins.

**Optional, if CI deployment is wanted:** add `SUPABASE_ACCESS_TOKEN` and `SUPABASE_DB_PASSWORD` as GitHub Actions secrets, protect the deployment environment, and add a workflow that runs `supabase db push` and `supabase functions deploy`. Do not add the service-role key to CI.
