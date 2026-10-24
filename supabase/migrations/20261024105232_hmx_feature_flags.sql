-- HMXPDF Phase 2: feature flags, tester targeting, admin allowlist, audit.
-- The schema is reachable through PostgREST only by service_role (Edge Functions).
-- anon/authenticated have no USAGE on the schema and no table grants, so direct
-- PostgREST access is denied even though the schema is in the exposed list.
-- All statements are intentionally idempotent so a partially applied migration can be re-run.

create schema if not exists hmx;

revoke all on schema hmx from public, anon, authenticated;
grant usage on schema hmx to service_role;

-- PostgREST only resolves schemas listed in db-schemas. Preserve the platform
-- defaults and append hmx. Re-apply with the dashboard "Exposed schemas" setting
-- if the platform ever rewrites this role configuration.
alter role authenticator
  set pgrst.db_schemas = 'public, storage, graphql_public, hmx';
notify pgrst, 'reload config';

create table if not exists hmx.admin_users (
  user_id    uuid primary key references auth.users(id) on delete cascade,
  role       text not null default 'admin' check (role in ('admin', 'readonly')),
  is_active  boolean not null default true,
  created_at timestamptz not null default now()
);

create table if not exists hmx.system_settings (
  id                boolean primary key default true check (id),
  kill_switch_all   boolean not null default false,
  updated_at        timestamptz not null default now(),
  updated_by        uuid references auth.users(id) on delete set null
);

insert into hmx.system_settings (id, kill_switch_all)
values (true, false)
on conflict (id) do nothing;

create table if not exists hmx.feature_flags (
  key                    text primary key
                           check (key ~ '^[a-z][a-z0-9_.-]{2,63}$'),
  name                   text not null
                           check (length(name) between 1 and 120),
  description            text not null default ''
                           check (length(description) <= 2000),
  enabled                boolean not null default false,
  rollout_percentage     int not null default 0
                           check (rollout_percentage between 0 and 100),
  min_app_version_code   int
                           check (min_app_version_code is null or min_app_version_code >= 0),
  max_app_version_code   int
                           check (max_app_version_code is null or max_app_version_code >= 0),
  starts_at              timestamptz,
  ends_at                timestamptz,
  kill_switch            boolean not null default false,
  config                 jsonb not null default '{}'::jsonb
                           check (jsonb_typeof(config) = 'object')
                           check (pg_column_size(config) <= 16384),
  config_version         bigint not null default 1 check (config_version > 0),
  created_at             timestamptz not null default now(),
  updated_at             timestamptz not null default now(),
  updated_by             uuid references auth.users(id) on delete set null,
  check (max_app_version_code is null or min_app_version_code is null
         or max_app_version_code >= min_app_version_code),
  check (ends_at is null or starts_at is null or ends_at > starts_at)
);

create table if not exists hmx.testers (
  id                 uuid primary key default gen_random_uuid(),
  install_id         uuid not null unique,
  token_hash         text not null unique
                       check (token_hash ~ '^[0-9a-f]{64}$'),
  status             text not null default 'active'
                       check (status in ('active', 'revoked')),
  label              text check (label is null or length(label) <= 120),
  segment            text not null default 'default'
                       check (segment ~ '^[a-z0-9][a-z0-9_-]{0,31}$'),
  app_version_code   int check (app_version_code is null or app_version_code >= 0),
  app_version_name   text check (app_version_name is null or length(app_version_name) <= 64),
  android_api        int check (android_api is null or android_api between 1 and 100),
  device_model       text check (device_model is null or length(device_model) <= 120),
  created_at         timestamptz not null default now(),
  last_seen_at       timestamptz,
  revoked_at         timestamptz
);

create table if not exists hmx.tester_invites (
  id          uuid primary key default gen_random_uuid(),
  code_hash   text not null unique check (code_hash ~ '^[0-9a-f]{64}$'),
  label       text check (label is null or length(label) <= 120),
  max_uses    int not null default 1 check (max_uses between 1 and 100),
  used_count  int not null default 0 check (used_count >= 0),
  expires_at  timestamptz not null,
  created_by  uuid references auth.users(id) on delete set null,
  created_at  timestamptz not null default now(),
  revoked_at  timestamptz,
  check (used_count <= max_uses)
);

create table if not exists hmx.feature_flag_testers (
  flag_key   text not null references hmx.feature_flags(key) on delete cascade,
  tester_id  uuid not null references hmx.testers(id) on delete cascade,
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  primary key (flag_key, tester_id)
);

create table if not exists hmx.feature_flag_segments (
  flag_key   text not null references hmx.feature_flags(key) on delete cascade,
  segment    text not null check (segment ~ '^[a-z0-9][a-z0-9_-]{0,31}$'),
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  primary key (flag_key, segment)
);

create table if not exists hmx.admin_audit_log (
  id             bigserial primary key,
  admin_user_id  uuid references auth.users(id) on delete set null,
  action         text not null check (length(action) between 1 and 120),
  target_type    text check (target_type is null or length(target_type) <= 64),
  target_id      text check (target_id is null or length(target_id) <= 200),
  metadata       jsonb not null default '{}'::jsonb
                   check (jsonb_typeof(metadata) = 'object')
                   check (pg_column_size(metadata) <= 8192),
  created_at     timestamptz not null default now()
);

create table if not exists hmx.rate_limits (
  bucket        text not null check (length(bucket) between 1 and 200),
  window_start  timestamptz not null,
  count         int not null default 0 check (count >= 0),
  primary key (bucket, window_start)
);

create index if not exists feature_flags_updated_at_idx
  on hmx.feature_flags (updated_at desc);
create index if not exists testers_segment_idx
  on hmx.testers (segment) where status = 'active';
create index if not exists feature_flag_testers_tester_idx
  on hmx.feature_flag_testers (tester_id);
create index if not exists tester_invites_expires_idx
  on hmx.tester_invites (expires_at);
create index if not exists admin_audit_log_created_idx
  on hmx.admin_audit_log (created_at desc);

create or replace function hmx.is_admin(p_user uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from hmx.admin_users
    where user_id = p_user
      and is_active
      and role = 'admin'
  );
$$;

create or replace function hmx.admin_role(p_user uuid)
returns text
language sql
stable
security definer
set search_path = ''
as $$
  select role
  from hmx.admin_users
  where user_id = p_user
    and is_active
  limit 1;
$$;

create or replace function hmx.consume_rate_limit(
  p_bucket text,
  p_limit int,
  p_window_seconds int
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  w timestamptz;
  c int;
begin
  if p_limit <= 0 or p_window_seconds <= 0 then
    return false;
  end if;

  w := to_timestamp(
    floor(extract(epoch from clock_timestamp()) / p_window_seconds) * p_window_seconds
  );

  insert into hmx.rate_limits (bucket, window_start, count)
  values (p_bucket, w, 1)
  on conflict (bucket, window_start)
  do update set count = hmx.rate_limits.count + 1
  returning count into c;

  return c <= p_limit;
end;
$$;

-- Atomically consumes an invite and creates the tester record. If the install_id
-- is already enrolled the insert raises 23505 and the invite use is rolled back.
create or replace function hmx.enroll_tester(
  p_code_hash text,
  p_install_id uuid,
  p_token_hash text,
  p_app_version_code int default null,
  p_app_version_name text default null,
  p_android_api int default null,
  p_device_model text default null
)
returns table (tester_id uuid, segment text, label text)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_invite hmx.tester_invites%rowtype;
begin
  update hmx.tester_invites
  set used_count = used_count + 1
  where code_hash = p_code_hash
    and revoked_at is null
    and expires_at > now()
    and used_count < max_uses
  returning * into v_invite;

  if not found then
    return;
  end if;

  return query
  insert into hmx.testers as t (
    install_id, token_hash, label, app_version_code, app_version_name,
    android_api, device_model
  )
  values (
    p_install_id, p_token_hash, v_invite.label, p_app_version_code,
    p_app_version_name, p_android_api, p_device_model
  )
  returning t.id, t.segment, t.label;
end;
$$;

create or replace function hmx.touch_feature_flag()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  new.updated_at = now();
  new.config_version = old.config_version + 1;
  return new;
end;
$$;

create or replace function hmx.touch_system_settings()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists feature_flags_touch on hmx.feature_flags;
create trigger feature_flags_touch
  before update on hmx.feature_flags
  for each row execute function hmx.touch_feature_flag();

drop trigger if exists system_settings_touch on hmx.system_settings;
create trigger system_settings_touch
  before update on hmx.system_settings
  for each row execute function hmx.touch_system_settings();

alter table hmx.admin_users enable row level security;
alter table hmx.system_settings enable row level security;
alter table hmx.feature_flags enable row level security;
alter table hmx.testers enable row level security;
alter table hmx.tester_invites enable row level security;
alter table hmx.feature_flag_testers enable row level security;
alter table hmx.feature_flag_segments enable row level security;
alter table hmx.admin_audit_log enable row level security;
alter table hmx.rate_limits enable row level security;

revoke all on all tables in schema hmx from public, anon, authenticated;
revoke all on all sequences in schema hmx from public, anon, authenticated;
grant all on all tables in schema hmx to service_role;
grant all on all sequences in schema hmx to service_role;

revoke all on function hmx.is_admin(uuid) from public, anon, authenticated;
revoke all on function hmx.admin_role(uuid) from public, anon, authenticated;
revoke all on function hmx.consume_rate_limit(text, int, int) from public, anon, authenticated;
revoke all on function hmx.enroll_tester(text, uuid, text, int, text, int, text) from public, anon, authenticated;
revoke all on function hmx.touch_feature_flag() from public, anon, authenticated;
revoke all on function hmx.touch_system_settings() from public, anon, authenticated;

grant execute on function hmx.is_admin(uuid) to service_role;
grant execute on function hmx.admin_role(uuid) to service_role;
grant execute on function hmx.consume_rate_limit(text, int, int) to service_role;
grant execute on function hmx.enroll_tester(text, uuid, text, int, text, int, text) to service_role;
grant execute on function hmx.touch_feature_flag() to service_role;
grant execute on function hmx.touch_system_settings() to service_role;
