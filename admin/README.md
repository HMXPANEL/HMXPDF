# HMX PDF admin panel

Private, owner-only dashboard for the HMX PDF staging backend. It is a Vite +
React + TypeScript single-page app that talks directly to the existing
`admin-api` Supabase Edge Function. There is no separate backend and no
service-role key in the browser.

## Scope

- Dashboard: report totals, pending count, counts by kind and status, recent
  audit activity, and a live backend status based on real API responses.
- Reports: cursor-paginated list, kind/status filters, detail view, status and
  admin-note updates, single delete, and a 30-day purge with explicit
  confirmation.
- Feature controls: `diagnostics.enabled` and `problem_reports.enabled` only,
  with 0% or 100% availability and a per-flag kill switch. Fractional rollout is
  not offered because Android config requests send no install ID.
- Audit logs: read-only, cursor-paginated, filterable by action.

The backend remains the authority for every action. The UI never writes to
Supabase tables directly.

## Security model

- Sign-in uses Supabase Auth email/password. The publishable key is public by
  design; it grants no admin access.
- After sign-in, the app calls `session.whoami`. `admin-api` verifies the Bearer
  token with `auth.getUser`, then requires an active `hmx.admin_users` row with
  role `admin`. A successful login without that row shows **Not authorized**.
- `ADMIN_ORIGIN` must equal the exact deployed browser origin. It is checked
  before authentication and is independent of the allowlist.
- Sessions are stored in `sessionStorage` (tab-scoped), not `localStorage`.
  Expired sessions are cleared and redirected to sign-in.
- The browser bundle contains only `VITE_SUPABASE_URL`,
  `VITE_SUPABASE_PUBLISHABLE_KEY`, `VITE_ENVIRONMENT`, and an optional
  `VITE_ADMIN_API_URL`. Never add `SUPABASE_SERVICE_ROLE_KEY`, a database
  password, or an access token.
- `vercel.json` sets a restrictive CSP, `frame-ancestors 'none'`,
  `X-Frame-Options: DENY`, `noindex`, and `no-referrer`.

## Local development

```bash
cd admin
npm install
cp .env.example .env.local   # fill in the staging publishable key
npm run dev
```

Required public variables:

| Variable | Value |
|---|---|
| `VITE_SUPABASE_URL` | `https://jxeuwqmdxwqqvyhisrhg.supabase.co` |
| `VITE_SUPABASE_PUBLISHABLE_KEY` | staging publishable key (`sb_publishable_…`) |
| `VITE_ENVIRONMENT` | `staging` |
| `VITE_ADMIN_API_URL` | optional; defaults to `<VITE_SUPABASE_URL>/functions/v1/admin-api` |

Use the modern publishable key, not the legacy anon JWT.

Checks:

```bash
npm run typecheck
npm run lint
npm test
npm run build
```

If the checkout is on a filesystem that cannot execute native modules
(for example a FUSE-mounted SD card), run the checks from a copy under `/tmp`.
This is a host limitation, not an application requirement.

## Vercel Preview deployment

1. Import `HMXPANEL/HMXPDF` into Vercel and set **Root Directory** to `admin`.
2. Framework preset: Vite. Build command `npm run build`, output `dist`.
3. Add the public environment variables above to **Preview** only.
4. Deploy a Preview, not Production.
5. Enable Vercel **Deployment Protection** (Vercel Authentication or Password
   Protection) for Preview. An unlisted URL is not access control.
6. Record the exact preview origin, for example
   `https://hmxpdf-admin-git-<branch>-<team>.vercel.app`. The origin is scheme +
   host only: no path, no trailing slash, no wildcard.

The repository does not contain a Vercel project ID or token, so deployment
must be performed by an account with access to the Vercel project.

## Deployed staging Preview (recorded 2026-10-26)

- Vercel account `ninjahattori53636-4371`, team `hmxpanels-projects`.
- Project `hmxpdf-admin` (`prj_nBuaZngpmgToItrwNEJlAbdEE5F0`), Root Directory
  `admin`, framework Vite.
- Preview deployment `dpl_5V3xw4sJbgH2s6bQ3zyCvaCmPADP`, `target: null`
  (Preview), READY.
- Stable branch alias (use this exact origin for `ADMIN_ORIGIN`):
  `https://hmxpdf-admin-git-feature-hmxpdf-diagn-95b12e-hmxpanels-projects.vercel.app`
- Deployment URL:
  `https://hmxpdf-admin-3nrctbvwt-hmxpanels-projects.vercel.app`
- Vercel Authentication is enabled (`ssoProtection:
  all_except_custom_domains`). An unauthenticated request returns `302` to
  `https://vercel.com/sso-api`; no app HTML is served.
- Preview-only, non-sensitive variables: `VITE_SUPABASE_URL`,
  `VITE_SUPABASE_PUBLISHABLE_KEY`, `VITE_ENVIRONMENT=staging`.
- Vercel forces a new project's first deployment to production. The production
  slot is a static, SSO-protected "Not available." placeholder
  (`dpl_ZxQg7XZ6ckj9he9xa1VJEBgyzauC`), not the admin build. The admin app is
  Preview-only.

## Configure `ADMIN_ORIGIN` (manual, required)

The Supabase MCP connection exposes no secrets-management operation, so this
step must be done by the owner:

1. Open the Supabase Dashboard for `hmxpdf-staging`
   (`jxeuwqmdxwqqvyhisrhg`).
2. Go to **Project Settings → Edge Functions → Secrets** (or use the Management
   API secret endpoint with an authorized token).
3. Add `ADMIN_ORIGIN` with this exact value:
   `https://hmxpdf-admin-git-feature-hmxpdf-diagn-95b12e-hmxpanels-projects.vercel.app`
4. Do not use `*`, a placeholder, a localhost origin, or a production origin.

Secrets apply without redeploying the function. Then verify:

```bash
curl -i -X OPTIONS 'https://jxeuwqmdxwqqvyhisrhg.supabase.co/functions/v1/admin-api' \
  -H 'Origin: https://<exact-preview-origin>'
# expect 204 and access-control-allow-origin: https://<exact-preview-origin>

curl -i -X POST 'https://jxeuwqmdxwqqvyhisrhg.supabase.co/functions/v1/admin-api' \
  -H 'Origin: https://evil.example' -H 'content-type: application/json' \
  --data '{"action":"reports.stats"}'
# expect 403 origin_not_allowed

curl -i -X POST 'https://jxeuwqmdxwqqvyhisrhg.supabase.co/functions/v1/admin-api' \
  -H 'Origin: https://<exact-preview-origin>' -H 'content-type: application/json' \
  --data '{"action":"reports.stats"}'
# expect 401 unauthorized (no Bearer token)
```

## Staging admin identity

Staging currently has no `auth.users` row and no `hmx.admin_users` row. Create a
dedicated staging owner identity in the Supabase Dashboard, then insert its UUID
into `hmx.admin_users` with role `admin` and `is_active = true`. Do not copy a
production owner UUID or reuse production credentials. The admin panel does not
create users and does not include a sign-up flow.

## Backend contract used

`session.whoami`, `reports.stats`, `reports.list`, `reports.get`,
`reports.update`, `reports.delete`, `reports.purge`, `flags.list`,
`flags.upsert`, and `audit.list`. New backend actions are validated and audited
in `supabase/functions/admin-api/`; tests live in `supabase/tests/`.

## Limitations

- Staging only. No production deployment, secret, migration, or data change.
- No search field: the backend exposes no supported report search.
- No bulk report actions: the backend supports single delete and bounded purge.
- No MFA enforcement: the current backend accepts any valid session for an
  active admin. Do not weaken or bypass the allowlist to compensate.
- The report detail view intentionally omits `install_id` and
  `client_report_id`; list rows omit report content.
