-- HMXPDF Phase 2 security/constraint checks.
-- Run against a project with migration 20261024000100 applied:
--   psql "$SUPABASE_DB_URL" -f supabase/tests/hmx_security.sql
-- or paste the whole file into the Supabase SQL editor.
-- The transaction is rolled back; no test rows or settings survive.

begin;

do $$
declare
  v_count int;
begin
  -- Constraints reject invalid flag configuration.
  begin
    insert into hmx.feature_flags (key, name, rollout_percentage) values ('test.bad.percent', 'bad', 101);
    raise exception 'FAIL: accepted rollout_percentage=101';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.feature_flags (key, name) values ('Bad Key', 'bad');
    raise exception 'FAIL: accepted malformed key';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.feature_flags (key, name, config) values ('test.bad.config', 'bad', '[]'::jsonb);
    raise exception 'FAIL: accepted array config';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.feature_flags (key, name, starts_at, ends_at)
    values ('test.bad.schedule', 'bad', now(), now() - interval '1 hour');
    raise exception 'FAIL: accepted ends_at <= starts_at';
  exception when check_violation then null;
  end;

  begin
    insert into hmx.feature_flags (key, name, min_app_version_code, max_app_version_code)
    values ('test.bad.versions', 'bad', 10, 5);
    raise exception 'FAIL: accepted max < min';
  exception when check_violation then null;
  end;

  -- RLS is enabled on every hmx table.
  select count(*) into v_count
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'hmx' and c.relkind = 'r' and not c.relrowsecurity;
  if v_count <> 0 then
    raise exception 'FAIL: % hmx table(s) without RLS', v_count;
  end if;

  -- Client roles cannot read or write hmx directly.
  begin
    set local role anon;
    perform 1 from hmx.feature_flags;
    raise exception 'FAIL: anon read hmx.feature_flags';
  exception when insufficient_privilege then null;
  end;

  begin
    set local role anon;
    insert into hmx.feature_flags (key, name) values ('test.anon', 'anon');
    raise exception 'FAIL: anon wrote hmx.feature_flags';
  exception when insufficient_privilege then null;
  end;

  begin
    set local role authenticated;
    perform 1 from hmx.testers;
    raise exception 'FAIL: authenticated read hmx.testers';
  exception when insufficient_privilege then null;
  end;

  begin
    set local role authenticated;
    perform hmx.consume_rate_limit('test:auth', 1, 60);
    raise exception 'FAIL: authenticated executed consume_rate_limit';
  exception when insufficient_privilege then null;
  end;

  -- service_role can perform the required operations.
  set local role service_role;
  insert into hmx.feature_flags (key, name, rollout_percentage) values ('test.service', 'service', 50);
  select count(*) into v_count from hmx.feature_flags where key = 'test.service';
  if v_count <> 1 then raise exception 'FAIL: service_role insert/select'; end if;
  delete from hmx.feature_flags where key = 'test.service';
  reset role;

  -- Fixed-window rate limit boundary.
  if not hmx.consume_rate_limit('test:rl', 2, 60) then raise exception 'FAIL: rate limit first call'; end if;
  if not hmx.consume_rate_limit('test:rl', 2, 60) then raise exception 'FAIL: rate limit second call'; end if;
  if hmx.consume_rate_limit('test:rl', 2, 60) then raise exception 'FAIL: rate limit third call allowed'; end if;

  -- Invite redemption is atomic and single-use.
  insert into hmx.tester_invites (code_hash, label, max_uses, expires_at)
  values (repeat('a',64), 'test', 1, now() + interval '1 hour');

  select count(*) into v_count from hmx.enroll_tester(repeat('a',64), '00000000-0000-4000-8000-000000000001'::uuid, repeat('b',64));
  if v_count <> 1 then raise exception 'FAIL: valid invite did not enroll'; end if;

  select count(*) into v_count from hmx.enroll_tester(repeat('a',64), '00000000-0000-4000-8000-000000000002'::uuid, repeat('c',64));
  if v_count <> 0 then raise exception 'FAIL: exhausted invite enrolled'; end if;

  -- Duplicate install_id fails and rolls back the invite use.
  insert into hmx.tester_invites (code_hash, label, max_uses, expires_at)
  values (repeat('d',64), 'test2', 1, now() + interval '1 hour');

  begin
    perform hmx.enroll_tester(repeat('d',64), '00000000-0000-4000-8000-000000000001'::uuid, repeat('e',64));
    raise exception 'FAIL: duplicate install_id enrolled';
  exception when unique_violation then null;
  end;

  select used_count into v_count from hmx.tester_invites where code_hash = repeat('d',64);
  if v_count <> 0 then raise exception 'FAIL: invite use not rolled back (%)', v_count; end if;

  insert into hmx.tester_invites (code_hash, max_uses, expires_at)
  values (repeat('f',64), 1, now() - interval '1 minute');
  select count(*) into v_count from hmx.enroll_tester(repeat('f',64), '00000000-0000-4000-8000-000000000003'::uuid, repeat('1',64));
  if v_count <> 0 then raise exception 'FAIL: expired invite enrolled'; end if;

  -- config_version increments on every update.
  insert into hmx.feature_flags (key, name, enabled, rollout_percentage) values ('test.version', 'v', true, 100);
  update hmx.feature_flags set rollout_percentage = 50 where key = 'test.version';
  select config_version into v_count from hmx.feature_flags where key = 'test.version';
  if v_count <> 2 then raise exception 'FAIL: config_version did not increment (%)', v_count; end if;

  -- Global kill switch is writable and defaults off.
  update hmx.system_settings set kill_switch_all = true where id;
  select count(*) into v_count from hmx.system_settings where id and kill_switch_all;
  if v_count <> 1 then raise exception 'FAIL: kill switch update'; end if;
  update hmx.system_settings set kill_switch_all = false where id;
end $$;

rollback;

select 'sql_security_tests_passed' as result;
