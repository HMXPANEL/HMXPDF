-- HMXPDF Phase 3: user-submitted diagnostic and problem reports.
-- Service-role-only storage. There are deliberately no RLS policies: the public
-- report-submit function and the authenticated admin-api are the only writers.
-- No document content, filenames, paths, annotations, form values, passwords,
-- tokens, location, screenshots, raw logs, or device model are stored here.
-- All statements are intentionally idempotent so a partially applied migration
-- can be re-run.

create table if not exists hmx.reports (
  id                 uuid primary key default gen_random_uuid(),
  client_report_id   uuid not null,
  install_id         uuid not null,
  kind               text not null check (kind in ('diagnostic_test', 'problem')),
  category           text,
  description        text,
  attached_error     jsonb,
  app_version_code   integer not null check (app_version_code between 0 and 2100000000),
  app_version_name   text not null check (char_length(app_version_name) between 1 and 64),
  build_flavor       text not null check (build_flavor in ('play', 'foss', 'fdroid')),
  build_type         text not null check (build_type in ('debug', 'release')),
  android_api        integer check (android_api between 1 and 100),
  locale             text check (locale is null or char_length(locale) between 1 and 16),
  self_checks        jsonb,
  status             text not null default 'received'
                       check (status in ('received', 'reviewing', 'resolved')),
  admin_notes        text check (admin_notes is null or char_length(admin_notes) <= 4000),
  client_created_at  timestamptz not null,
  received_at        timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  constraint reports_identity unique (install_id, client_report_id),
  constraint reports_category check (
    category is null or category in ('bug', 'crash', 'performance', 'other')
  ),
  constraint reports_kind_fields check (
    (kind = 'problem'
      and description is not null
      and char_length(description) between 1 and 4000
      and self_checks is null)
    or
    (kind = 'diagnostic_test'
      and description is null
      and attached_error is null
      and category is null
      and self_checks is not null)
  ),
  constraint reports_attached_error_object check (
    attached_error is null
    or (jsonb_typeof(attached_error) = 'object' and pg_column_size(attached_error) <= 4096)
  ),
  constraint reports_self_checks_object check (
    self_checks is null
    or (jsonb_typeof(self_checks) = 'object' and pg_column_size(self_checks) <= 2048)
  )
);

comment on table hmx.reports is
  'User-submitted diagnostic and problem reports. Service-role only. Retained 30 days.';

create index if not exists reports_received_idx
  on hmx.reports (received_at desc);
create index if not exists reports_kind_status_idx
  on hmx.reports (kind, status, received_at desc);
create index if not exists reports_install_idx
  on hmx.reports (install_id, received_at desc);

alter table hmx.reports enable row level security;
revoke all on table hmx.reports from public, anon, authenticated;
grant all on table hmx.reports to service_role;

-- Required for the 30-day retention rule. This deletes at most batch_size rows
-- per call so a scheduler can drain large backlogs without a long lock.
-- Retention is fixed at 30 days and runs from server received_at, never from the
-- client-supplied client_created_at.
create or replace function hmx.purge_expired_reports(batch_size integer default 1000)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  removed integer;
begin
  with expired as (
    select id
    from hmx.reports
    where received_at < now() - interval '30 days'
    order by received_at
    limit greatest(1, least(coalesce(batch_size, 1000), 5000))
  )
  delete from hmx.reports r
  using expired e
  where r.id = e.id;

  get diagnostics removed = row_count;
  return removed;
end;
$$;

revoke all on function hmx.purge_expired_reports(integer) from public, anon, authenticated;
grant execute on function hmx.purge_expired_reports(integer) to service_role;

-- Rate-limit buckets only need to outlive the longest window (24 hours).
-- Deleting rows older than 2 days keeps the table bounded without ever
-- resetting an active window. The batch cap prevents a long lock during the
-- first cleanup after a backlog.
create index if not exists rate_limits_window_idx
  on hmx.rate_limits (window_start);

create or replace function hmx.purge_expired_rate_limits(batch_size integer default 5000)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  removed integer;
begin
  with expired as (
    select bucket, window_start
    from hmx.rate_limits
    where window_start < now() - interval '2 days'
    order by window_start
    limit greatest(1, least(coalesce(batch_size, 5000), 20000))
  )
  delete from hmx.rate_limits r
  using expired e
  where r.bucket = e.bucket and r.window_start = e.window_start;

  get diagnostics removed = row_count;
  return removed;
end;
$$;

revoke all on function hmx.purge_expired_rate_limits(integer) from public, anon, authenticated;
grant execute on function hmx.purge_expired_rate_limits(integer) to service_role;

-- Phase 3 availability hints. Default OFF and 0% rollout. on conflict do nothing
-- preserves any deliberate admin change when this migration is re-run.
insert into hmx.feature_flags (key, name, description, enabled, rollout_percentage)
values
  (
    'diagnostics.enabled',
    'Diagnostic reports',
    'Lets the app offer an explicit, user-reviewed diagnostic test report. Local consent always wins.',
    false,
    0
  ),
  (
    'problem_reports.enabled',
    'Problem reports',
    'Lets the app offer an explicit, user-reviewed problem report. Local consent always wins.',
    false,
    0
  )
on conflict (key) do nothing;
