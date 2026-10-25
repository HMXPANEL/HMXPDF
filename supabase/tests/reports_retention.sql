-- HMXPDF Phase 3 report-retention and rate-limit-cleanup checks.
-- Run with psql against a disposable/staging database that has migration
-- 20261024164504_hmx_reports.sql applied:
--   psql -v ON_ERROR_STOP=1 "$TEST_DATABASE_URL" -f supabase/tests/reports_retention.sql
-- This file sets ON_ERROR_STOP itself, so any failed assertion exits non-zero.
-- Do not run against production. The transaction is rolled back; no test rows
-- survive. The success marker is emitted only after every assertion passes.
\set ON_ERROR_STOP on

begin;

do $$
declare
  v_removed int;
  v_count int;
begin
  -- Isolate from any pre-existing expired rows so batch-size assertions are
  -- deterministic on a non-empty staging database. These deletes are part of
  -- the test transaction and are rolled back.
  delete from hmx.reports where received_at < now() - interval '30 days';
  delete from hmx.rate_limits where window_start < now() - interval '2 days';

  -- The purge functions must be security definer with a fixed search path.
  select count(*) into v_count
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'hmx' and p.proname = 'purge_expired_reports' and p.prosecdef;
  if v_count <> 1 then raise exception 'FAIL: purge function missing or not security definer'; end if;

  select count(*) into v_count
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'hmx' and p.proname = 'purge_expired_reports'
    and p.proconfig::text like '%search_path=%';
  if v_count <> 1 then raise exception 'FAIL: purge function lacks fixed search_path'; end if;

  select count(*) into v_count
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'hmx' and p.proname = 'purge_expired_rate_limits' and p.prosecdef;
  if v_count <> 1 then raise exception 'FAIL: rate-limit purge missing or not security definer'; end if;

  select count(*) into v_count
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'hmx' and p.proname = 'purge_expired_rate_limits'
    and p.proconfig::text like '%search_path=%';
  if v_count <> 1 then raise exception 'FAIL: rate-limit purge lacks fixed search_path'; end if;

  -- Seed a 31-day-old row, a just-over-30-day row, a 29-day row, a fresh row,
  -- and an audit row that must survive the purge.
  insert into hmx.reports (client_report_id, install_id, kind, description, app_version_code, app_version_name, build_flavor, build_type, client_created_at, received_at)
  values
    ('00000000-0000-4000-8000-000000000301', '00000000-0000-4000-8000-000000000401', 'problem', '31 days old', 6, '2.1.1', 'play', 'release', now(), now() - interval '31 days'),
    ('00000000-0000-4000-8000-000000000302', '00000000-0000-4000-8000-000000000402', 'problem', '30 days plus one second', 6, '2.1.1', 'play', 'release', now(), now() - interval '30 days' - interval '1 second'),
    ('00000000-0000-4000-8000-000000000303', '00000000-0000-4000-8000-000000000403', 'problem', '29 days old', 6, '2.1.1', 'play', 'release', now(), now() - interval '29 days'),
    ('00000000-0000-4000-8000-000000000304', '00000000-0000-4000-8000-000000000404', 'problem', 'fresh', 6, '2.1.1', 'play', 'release', now(), now()),
    ('00000000-0000-4000-8000-000000000305', '00000000-0000-4000-8000-000000000405', 'problem', 'exactly 30 days old', 6, '2.1.1', 'play', 'release', now(), now() - interval '30 days');

  insert into hmx.admin_audit_log (action, target_type, target_id, metadata)
  values ('test.retention.audit', 'report', 'retention', '{"ok":true}'::jsonb);

  -- Batch size 2 removes the two oldest expired rows only.
  v_removed := hmx.purge_expired_reports(2);
  if v_removed <> 2 then raise exception 'FAIL: expected 2 removed, got %', v_removed; end if;

  select count(*) into v_count from hmx.reports
  where client_report_id in (
    '00000000-0000-4000-8000-000000000301',
    '00000000-0000-4000-8000-000000000302'
  );
  if v_count <> 0 then raise exception 'FAIL: expired rows were not deleted'; end if;

  select count(*) into v_count from hmx.reports
  where client_report_id in (
    '00000000-0000-4000-8000-000000000303',
    '00000000-0000-4000-8000-000000000304',
    '00000000-0000-4000-8000-000000000305'
  );
  if v_count <> 3 then raise exception 'FAIL: fresh or boundary rows were deleted'; end if;

  -- A second call drains nothing because no expired rows remain.
  v_removed := hmx.purge_expired_reports(1000);
  if v_removed <> 0 then raise exception 'FAIL: expected 0 removed, got %', v_removed; end if;

  -- Batch size 0 is clamped to 1; oversized batches are capped at 5000.
  insert into hmx.reports (client_report_id, install_id, kind, description, app_version_code, app_version_name, build_flavor, build_type, client_created_at, received_at)
  values
    ('00000000-0000-4000-8000-000000000306', '00000000-0000-4000-8000-000000000406', 'problem', 'old one', 6, '2.1.1', 'play', 'release', now(), now() - interval '40 days'),
    ('00000000-0000-4000-8000-000000000307', '00000000-0000-4000-8000-000000000407', 'problem', 'old two', 6, '2.1.1', 'play', 'release', now(), now() - interval '41 days');

  v_removed := hmx.purge_expired_reports(0);
  if v_removed <> 1 then raise exception 'FAIL: batch size 0 was not clamped to 1 (got %)', v_removed; end if;

  v_removed := hmx.purge_expired_reports(999999);
  if v_removed <> 1 then raise exception 'FAIL: oversized batch did not drain remaining row (got %)', v_removed; end if;

  -- Audit rows are not reports and must survive.
  select count(*) into v_count from hmx.admin_audit_log
  where action = 'test.retention.audit';
  if v_count <> 1 then raise exception 'FAIL: audit row was removed by report purge'; end if;

  -- The exactly-30-day row remains because retention uses a strict < boundary.
  select count(*) into v_count from hmx.reports
  where client_report_id = '00000000-0000-4000-8000-000000000305';
  if v_count <> 1 then raise exception 'FAIL: exact 30-day boundary row was deleted'; end if;

  -- Rate-limit buckets older than 2 days are removed in bounded batches;
  -- fresh buckets and active windows are never touched.
  insert into hmx.rate_limits (bucket, window_start, count)
  values
    ('test-retention-rate-old-1', now() - interval '3 days', 1),
    ('test-retention-rate-old-2', now() - interval '2 days 1 minute', 1),
    ('test-retention-rate-fresh', now() - interval '1 hour', 1);

  v_removed := hmx.purge_expired_rate_limits(0);
  if v_removed <> 1 then raise exception 'FAIL: rate-limit batch size 0 was not clamped to 1 (got %)', v_removed; end if;

  v_removed := hmx.purge_expired_rate_limits(1000);
  if v_removed <> 1 then raise exception 'FAIL: expected 1 remaining expired rate-limit bucket, got %', v_removed; end if;

  select count(*) into v_count from hmx.rate_limits
  where bucket like 'test-retention-rate-old-%';
  if v_count <> 0 then raise exception 'FAIL: expired rate-limit buckets were not deleted'; end if;

  select count(*) into v_count from hmx.rate_limits
  where bucket = 'test-retention-rate-fresh';
  if v_count <> 1 then raise exception 'FAIL: fresh rate-limit bucket was deleted'; end if;

  if has_function_privilege('anon', 'hmx.purge_expired_rate_limits(integer)', 'execute') then
    raise exception 'FAIL: anon has rate-limit purge execute';
  end if;
  if has_function_privilege('authenticated', 'hmx.purge_expired_rate_limits(integer)', 'execute') then
    raise exception 'FAIL: authenticated has rate-limit purge execute';
  end if;
  if not has_function_privilege('service_role', 'hmx.purge_expired_rate_limits(integer)', 'execute') then
    raise exception 'FAIL: service_role lacks rate-limit purge execute';
  end if;

  raise notice 'reports_retention_tests_passed';
end $$;

rollback;
