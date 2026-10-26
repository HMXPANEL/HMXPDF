import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function source(path: string): Promise<string> {
  return readFile(new URL(`../${path}`, import.meta.url), "utf8");
}

test("reports migration is service-role-only, retained 30 days, and has no forbidden columns", async () => {
  const sql = await source("migrations/20261024164504_hmx_reports.sql");

  assert.match(sql, /create table if not exists hmx\.reports/);
  assert.match(sql, /alter table hmx\.reports enable row level security/);
  assert.match(sql, /revoke all on table hmx\.reports from public, anon, authenticated/);
  assert.match(sql, /grant all on table hmx\.reports to service_role/);
  assert.match(sql, /hmx\.purge_expired_reports/);
  assert.match(sql, /interval '30 days'/);
  assert.match(sql, /hmx\.purge_expired_rate_limits/);
  assert.match(sql, /interval '2 days'/);
  assert.match(sql, /security definer/);
  assert.doesNotMatch(sql, /create policy/i);
  assert.doesNotMatch(sql, /\bdevice_model\b/);
  assert.doesNotMatch(sql, /\bcontact_email\b/);
  assert.doesNotMatch(sql, /\bdocument_id\b/);
  assert.doesNotMatch(sql, /\bfile_(name|path)\b/);
  assert.doesNotMatch(sql, /\braw_log\b/);
  assert.match(sql, /'diagnostics\.enabled'[\s\S]*?false,\s*0/);
  assert.match(sql, /'problem_reports\.enabled'[\s\S]*?false,\s*0/);
});

test("report-submit is size-capped, rate-limited, idempotent, and never logs content", async () => {
  const handler = await source("functions/report-submit/handler.ts");
  const entry = await source("functions/report-submit/index.ts");

  assert.match(handler, /const MAX_BODY_BYTES = 16384/);
  assert.match(handler, /parseReportSubmission/);
  assert.match(handler, /report-ip:/);
  assert.match(handler, /report-install-hour:/);
  assert.match(handler, /report-install-day:/);
  assert.match(handler, /sha256Hex\(`report-install:\$\{report\.install_id\}`\)/);
  assert.doesNotMatch(handler, /report-install-(hour|day):\$\{report\.install_id\}/);
  assert.match(handler, /RATE_LIMITS\.ipPerHour/);
  assert.match(handler, /RATE_LIMITS\.installPerHour/);
  assert.match(handler, /RATE_LIMITS\.installPerDay/);
  assert.match(handler, /duplicate = true/);
  assert.doesNotMatch(handler, /\bdevice_model\b/);
  assert.doesNotMatch(handler, /console\.log/);
  assert.doesNotMatch(handler, /document|file_name|file_path|raw_log/i);

  assert.match(entry, /\.upsert\(record, \{/);
  assert.match(entry, /onConflict: "install_id,client_report_id"/);
  assert.match(entry, /ignoreDuplicates: true/);
  assert.doesNotMatch(entry, /console\.log/);
});

test("admin-api exposes report actions with audit writes for mutations", async () => {
  const ts = await source("functions/admin-api/index.ts");

  for (const action of [
    "session.whoami",
    "reports.stats",
    "reports.list",
    "reports.get",
    "reports.update",
    "reports.delete",
    "reports.purge",
    "audit.list",
  ]) {
    assert.match(ts, new RegExp(`case "${action.replace(".", "\\.")}"`));
  }
  assert.match(ts, /report\.update/);
  assert.match(ts, /report\.delete/);
  assert.match(ts, /report\.purge/);
  assert.match(ts, /changed_fields: update\.changed_fields/);
  assert.match(ts, /purge_expired_reports/);
  assert.match(ts, /isAuthorizedAdmin\(admin\)/);
  assert.match(ts, /origin !== null && origin !== ADMIN_ORIGIN/);
  assert.match(ts, /parseAuditListQuery\(body\)/);
  assert.match(ts, /countReports\(\{ status: "received" \}\)/);
  assert.doesNotMatch(ts, /\.select\("\*"\)/);
});

test("config rejects install_id and always evaluates without one", async () => {
  const ts = await source("functions/config/index.ts");
  const request = await source("functions/config/request.ts");

  assert.match(ts, /parseConfigRequest/);
  assert.match(ts, /installId: null/);
  assert.doesNotMatch(ts, /body\.install_id/);
  assert.match(request, /install_id_not_allowed/);
  assert.match(request, /unknown_field/);
});

test("report-submit is configured as a public function", async () => {
  const toml = await source("config.toml");
  assert.match(toml, /\[functions\.report-submit\]\s*\nverify_jwt = false/);
});

test("retention scheduler purges reports and expired rate-limit buckets", async () => {
  const sql = await source("retention/report_retention_cron.sql");

  assert.match(sql, /cron\.schedule/);
  assert.match(sql, /hmx\.purge_expired_reports\(1000\)/);
  assert.match(sql, /hmx\.purge_expired_rate_limits\(5000\)/);
  assert.match(sql, /pg_extension where extname = 'pg_cron'/);
});

test("SQL suites fail closed and print their marker only after assertions", async () => {
  for (const file of ["tests/reports_security.sql", "tests/reports_retention.sql"]) {
    const sql = await source(file);
    assert.match(sql, /\\set ON_ERROR_STOP on/);
    assert.match(sql, /raise notice 'reports_(security|retention)_tests_passed'/);
    assert.doesNotMatch(sql, /^select 'reports_(security|retention)_tests_passed'/m);
  }
});

test("retention suite isolates pre-existing expired rows", async () => {
  const sql = await source("tests/reports_retention.sql");

  assert.match(
    sql,
    /delete from hmx\.reports where received_at < now\(\) - interval '30 days'/,
  );
  assert.match(
    sql,
    /delete from hmx\.rate_limits where window_start < now\(\) - interval '2 days'/,
  );
});
