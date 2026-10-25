-- HMXPDF Phase 3 report security/constraint checks.
-- Run with psql against a disposable/staging database that has migration
-- 20261024164504_hmx_reports.sql applied:
--   psql -v ON_ERROR_STOP=1 "$TEST_DATABASE_URL" -f supabase/tests/reports_security.sql
-- This file sets ON_ERROR_STOP itself, so any failed assertion exits non-zero.
-- Do not run against production. The transaction is rolled back; no test rows
-- survive. The success marker is emitted only after every assertion passes.
\set ON_ERROR_STOP on

begin;

do $$
declare
  v_count int;
begin
  -- The report table exists and has RLS enabled.
  select count(*) into v_count
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'hmx' and c.relname = 'reports' and c.relkind = 'r';
  if v_count <> 1 then raise exception 'FAIL: hmx.reports table missing'; end if;

  select count(*) into v_count
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'hmx' and c.relname = 'reports' and c.relrowsecurity;
  if v_count <> 1 then raise exception 'FAIL: hmx.reports RLS not enabled'; end if;

  -- No RLS policies: access is service-role-only through Edge Functions.
  select count(*) into v_count
  from pg_policies where schemaname = 'hmx' and tablename = 'reports';
  if v_count <> 0 then raise exception 'FAIL: unexpected hmx.reports RLS policies'; end if;

  -- Forbidden columns must not exist.
  select count(*) into v_count
  from information_schema.columns
  where table_schema = 'hmx' and table_name = 'reports'
    and column_name in (
      'device_model', 'contact_email', 'document_id', 'file_name', 'file_path',
      'uri', 'raw_log', 'location', 'screenshot', 'annotations', 'form_values',
      'password', 'token'
    );
  if v_count <> 0 then raise exception 'FAIL: forbidden report columns exist'; end if;

  -- Constraint checks.
  begin
    insert into hmx.reports (client_report_id, install_id, kind, description, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
    values ('00000000-0000-4000-8000-000000000101', '00000000-0000-4000-8000-000000000201', 'support', 'x', 6, '2.1.1', 'play', 'release', now());
    raise exception 'FAIL: accepted invalid kind';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.reports (client_report_id, install_id, kind, description, status, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
    values ('00000000-0000-4000-8000-000000000102', '00000000-0000-4000-8000-000000000202', 'problem', 'x', 'closed', 6, '2.1.1', 'play', 'release', now());
    raise exception 'FAIL: accepted invalid status';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.reports (client_report_id, install_id, kind, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
    values ('00000000-0000-4000-8000-000000000103', '00000000-0000-4000-8000-000000000203', 'problem', 6, '2.1.1', 'play', 'release', now());
    raise exception 'FAIL: accepted problem without description';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.reports (client_report_id, install_id, kind, description, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
    values ('00000000-0000-4000-8000-000000000104', '00000000-0000-4000-8000-000000000204', 'problem', repeat('d', 4001), 6, '2.1.1', 'play', 'release', now());
    raise exception 'FAIL: accepted oversized description';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.reports (client_report_id, install_id, kind, description, self_checks, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
    values ('00000000-0000-4000-8000-000000000105', '00000000-0000-4000-8000-000000000205', 'problem', 'x', '{"storage_write":"ok"}'::jsonb, 6, '2.1.1', 'play', 'release', now());
    raise exception 'FAIL: accepted self_checks on problem';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.reports (client_report_id, install_id, kind, description, self_checks, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
    values ('00000000-0000-4000-8000-000000000106', '00000000-0000-4000-8000-000000000206', 'diagnostic_test', 'x', '{"storage_write":"ok"}'::jsonb, 6, '2.1.1', 'play', 'release', now());
    raise exception 'FAIL: accepted description on diagnostic';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.reports (client_report_id, install_id, kind, attached_error, self_checks, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
    values ('00000000-0000-4000-8000-000000000107', '00000000-0000-4000-8000-000000000207', 'diagnostic_test', '{"summary":"x"}'::jsonb, '{"storage_write":"ok"}'::jsonb, 6, '2.1.1', 'play', 'release', now());
    raise exception 'FAIL: accepted attached_error on diagnostic';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.reports (client_report_id, install_id, kind, category, self_checks, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
    values ('00000000-0000-4000-8000-000000000108', '00000000-0000-4000-8000-000000000208', 'diagnostic_test', 'bug', '{"storage_write":"ok"}'::jsonb, 6, '2.1.1', 'play', 'release', now());
    raise exception 'FAIL: accepted category on diagnostic';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.reports (client_report_id, install_id, kind, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
    values ('00000000-0000-4000-8000-000000000109', '00000000-0000-4000-8000-000000000209', 'diagnostic_test', 6, '2.1.1', 'play', 'release', now());
    raise exception 'FAIL: accepted diagnostic without self_checks';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.reports (client_report_id, install_id, kind, description, attached_error, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
    values ('00000000-0000-4000-8000-000000000110', '00000000-0000-4000-8000-000000000210', 'problem', 'x', '[]'::jsonb, 6, '2.1.1', 'play', 'release', now());
    raise exception 'FAIL: accepted array attached_error';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.reports (client_report_id, install_id, kind, description, attached_error, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
    values ('00000000-0000-4000-8000-000000000111', '00000000-0000-4000-8000-000000000211', 'problem', 'x', jsonb_build_object('summary', 'x', 'detail', repeat('d', 5000)), 6, '2.1.1', 'play', 'release', now());
    raise exception 'FAIL: accepted oversized attached_error';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.reports (client_report_id, install_id, kind, self_checks, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
    values ('00000000-0000-4000-8000-000000000112', '00000000-0000-4000-8000-000000000212', 'diagnostic_test', '[]'::jsonb, 6, '2.1.1', 'play', 'release', now());
    raise exception 'FAIL: accepted array self_checks';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.reports (client_report_id, install_id, kind, self_checks, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
    values ('00000000-0000-4000-8000-000000000113', '00000000-0000-4000-8000-000000000213', 'diagnostic_test', jsonb_build_object('blob', repeat('x', 3000)), 6, '2.1.1', 'play', 'release', now());
    raise exception 'FAIL: accepted oversized self_checks';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.reports (client_report_id, install_id, kind, category, description, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
    values ('00000000-0000-4000-8000-000000000114', '00000000-0000-4000-8000-000000000214', 'problem', 'security', 'x', 6, '2.1.1', 'play', 'release', now());
    raise exception 'FAIL: accepted invalid category';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.reports (client_report_id, install_id, kind, description, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
    values ('00000000-0000-4000-8000-000000000115', '00000000-0000-4000-8000-000000000215', 'problem', 'x', -1, '2.1.1', 'play', 'release', now());
    raise exception 'FAIL: accepted negative version code';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.reports (client_report_id, install_id, kind, description, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
    values ('00000000-0000-4000-8000-000000000116', '00000000-0000-4000-8000-000000000216', 'problem', 'x', 6, '2.1.1', 'amazon', 'release', now());
    raise exception 'FAIL: accepted invalid build flavor';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.reports (client_report_id, install_id, kind, description, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
    values ('00000000-0000-4000-8000-000000000117', '00000000-0000-4000-8000-000000000217', 'problem', 'x', 6, '2.1.1', 'play', 'beta', now());
    raise exception 'FAIL: accepted invalid build type';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.reports (client_report_id, install_id, kind, description, app_version_code, app_version_name, build_flavor, build_type, android_api, client_created_at)
    values ('00000000-0000-4000-8000-000000000118', '00000000-0000-4000-8000-000000000218', 'problem', 'x', 6, '2.1.1', 'play', 'release', 0, now());
    raise exception 'FAIL: accepted invalid android_api';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.reports (client_report_id, install_id, kind, description, app_version_code, app_version_name, build_flavor, build_type, locale, client_created_at)
    values ('00000000-0000-4000-8000-000000000119', '00000000-0000-4000-8000-000000000219', 'problem', 'x', 6, '2.1.1', 'play', 'release', repeat('l', 17), now());
    raise exception 'FAIL: accepted oversized locale';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.reports (client_report_id, install_id, kind, description, admin_notes, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
    values ('00000000-0000-4000-8000-000000000120', '00000000-0000-4000-8000-000000000220', 'problem', 'x', repeat('n', 4001), 6, '2.1.1', 'play', 'release', now());
    raise exception 'FAIL: accepted oversized admin_notes';
  exception when check_violation then null;
  end;

  -- Valid rows and idempotency identity.
  insert into hmx.reports (client_report_id, install_id, kind, category, description, app_version_code, app_version_name, build_flavor, build_type, android_api, locale, client_created_at)
  values ('00000000-0000-4000-8000-000000000121', '00000000-0000-4000-8000-000000000221', 'problem', 'bug', 'valid problem', 6, '2.1.1', 'play', 'release', 35, 'en-US', now());

  insert into hmx.reports (client_report_id, install_id, kind, self_checks, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
  values ('00000000-0000-4000-8000-000000000122', '00000000-0000-4000-8000-000000000222', 'diagnostic_test', '{"storage_write":"ok","cache_write":"ok"}'::jsonb, 6, '2.1.1', 'play', 'release', now());

  begin
    insert into hmx.reports (client_report_id, install_id, kind, category, description, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
    values ('00000000-0000-4000-8000-000000000121', '00000000-0000-4000-8000-000000000221', 'problem', 'bug', 'duplicate', 6, '2.1.1', 'play', 'release', now());
    raise exception 'FAIL: duplicate (install_id, client_report_id) accepted';
  exception when unique_violation then null;
  end;

  -- Client roles cannot read or write reports.
  begin
    set local role anon;
    perform 1 from hmx.reports;
    raise exception 'FAIL: anon read hmx.reports';
  exception when insufficient_privilege then null;
  end;

  begin
    set local role anon;
    insert into hmx.reports (client_report_id, install_id, kind, description, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
    values ('00000000-0000-4000-8000-000000000123', '00000000-0000-4000-8000-000000000223', 'problem', 'x', 6, '2.1.1', 'play', 'release', now());
    raise exception 'FAIL: anon wrote hmx.reports';
  exception when insufficient_privilege then null;
  end;

  begin
    set local role authenticated;
    perform 1 from hmx.reports;
    raise exception 'FAIL: authenticated read hmx.reports';
  exception when insufficient_privilege then null;
  end;

  begin
    set local role authenticated;
    update hmx.reports set status = 'reviewing';
    raise exception 'FAIL: authenticated updated hmx.reports';
  exception when insufficient_privilege then null;
  end;

  begin
    set local role anon;
    perform hmx.purge_expired_reports(1000);
    raise exception 'FAIL: anon executed purge_expired_reports';
  exception when insufficient_privilege then null;
  end;

  begin
    set local role anon;
    perform hmx.purge_expired_rate_limits(1000);
    raise exception 'FAIL: anon executed purge_expired_rate_limits';
  exception when insufficient_privilege then null;
  end;

  -- service_role can perform the required operations.
  set local role service_role;
  insert into hmx.reports (client_report_id, install_id, kind, description, app_version_code, app_version_name, build_flavor, build_type, client_created_at)
  values ('00000000-0000-4000-8000-000000000124', '00000000-0000-4000-8000-000000000224', 'problem', 'service row', 6, '2.1.1', 'play', 'release', now());
  update hmx.reports set status = 'reviewing' where client_report_id = '00000000-0000-4000-8000-000000000124';
  select count(*) into v_count from hmx.reports where client_report_id = '00000000-0000-4000-8000-000000000124';
  if v_count <> 1 then raise exception 'FAIL: service_role insert/update/select'; end if;
  delete from hmx.reports where client_report_id = '00000000-0000-4000-8000-000000000124';
  reset role;

  -- Purge function is service-role-only.
  if has_function_privilege('anon', 'hmx.purge_expired_reports(integer)', 'execute') then
    raise exception 'FAIL: anon has purge execute';
  end if;
  if has_function_privilege('authenticated', 'hmx.purge_expired_reports(integer)', 'execute') then
    raise exception 'FAIL: authenticated has purge execute';
  end if;
  if not has_function_privilege('service_role', 'hmx.purge_expired_reports(integer)', 'execute') then
    raise exception 'FAIL: service_role lacks purge execute';
  end if;

  -- Rate-limit cleanup exists, is bounded by a 2-day window, and is
  -- service-role-only. The function body is checked in the retention suite.
  select count(*) into v_count
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'hmx' and p.proname = 'purge_expired_rate_limits' and p.prosecdef;
  if v_count <> 1 then raise exception 'FAIL: rate-limit purge missing or not security definer'; end if;

  if has_function_privilege('anon', 'hmx.purge_expired_rate_limits(integer)', 'execute') then
    raise exception 'FAIL: anon has rate-limit purge execute';
  end if;
  if has_function_privilege('authenticated', 'hmx.purge_expired_rate_limits(integer)', 'execute') then
    raise exception 'FAIL: authenticated has rate-limit purge execute';
  end if;
  if not has_function_privilege('service_role', 'hmx.purge_expired_rate_limits(integer)', 'execute') then
    raise exception 'FAIL: service_role lacks rate-limit purge execute';
  end if;

  -- Phase 3 flags exist. Their current enabled state is intentionally not
  -- asserted here because an admin may have changed it after the seed.
  select count(*) into v_count
  from hmx.feature_flags
  where key in ('diagnostics.enabled', 'problem_reports.enabled');
  if v_count <> 2 then raise exception 'FAIL: Phase 3 flags missing (%)', v_count; end if;

  raise notice 'reports_security_tests_passed';
end $$;

rollback;
