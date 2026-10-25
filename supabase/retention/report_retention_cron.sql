-- HMXPDF Phase 3 — deployment-time retention scheduler.
--
-- This is NOT a Supabase migration and is not applied by `supabase db push`.
-- Run it only after the reports migration is applied and pg_cron availability
-- has been verified in the target project. Production must not be declared
-- ready until the verification queries at the bottom show a successful run.
--
-- Prerequisites:
--   1. `hmx.reports`, `hmx.purge_expired_reports(integer)`, and
--      `hmx.purge_expired_rate_limits(integer)` exist (reports migration applied).
--   2. The pg_cron extension is installed and `cron.database_name` points at
--      this database. In Supabase this is normally enabled from the dashboard.
--   3. The SQL is run by the function owner (normally `postgres`) or by a role
--      with EXECUTE on both purge functions. pg_cron jobs run as their owner.
--
-- Each hourly run deletes at most 1000 expired reports and 5000 expired
-- rate-limit buckets. That is 24,000 reports/day, well above the expected
-- volume; raise a batch only after measuring lock impact. The rate-limit
-- window is 2 days, safely above the longest active window (24 hours).

do $$
begin
  if not exists (select 1 from pg_extension where extname = 'pg_cron') then
    raise exception
      'pg_cron is not installed; enable it in the Supabase dashboard or use a verified external scheduler';
  end if;
  if to_regprocedure('hmx.purge_expired_reports(integer)') is null then
    raise exception 'hmx.purge_expired_reports(integer) is missing; apply the reports migration first';
  end if;
  if to_regprocedure('hmx.purge_expired_rate_limits(integer)') is null then
    raise exception 'hmx.purge_expired_rate_limits(integer) is missing; apply the reports migration first';
  end if;
end;
$$;

do $$
begin
  if exists (select 1 from cron.job where jobname = 'hmx-purge-expired-reports') then
    perform cron.unschedule('hmx-purge-expired-reports');
  end if;
end;
$$;

select cron.schedule(
  'hmx-purge-expired-reports',
  '17 * * * *',
  $$select hmx.purge_expired_reports(1000), hmx.purge_expired_rate_limits(5000);$$
);

-- Verification (run after the first scheduled window):
--   select jobid, jobname, schedule, active from cron.job
--    where jobname = 'hmx-purge-expired-reports';
--   select status, return_message, start_time, end_time
--     from cron.job_run_details
--    where jobid = (select jobid from cron.job where jobname = 'hmx-purge-expired-reports')
--    order by start_time desc
--    limit 5;
--   select count(*) from hmx.reports where received_at < now() - interval '30 days';
--   select count(*) from hmx.rate_limits where window_start < now() - interval '2 days';
-- The last two queries must return 0 after the job has run (unless a new
-- backlog has arrived since the run).
