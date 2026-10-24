import { createClient } from "jsr:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const ADMIN_ORIGIN = (Deno.env.get("ADMIN_ORIGIN") ?? "").trim();

const MAX_BODY_BYTES = 32768;
const KEY_PATTERN = /^[a-z][a-z0-9_.-]{2,63}$/;
const SEGMENT_PATTERN = /^[a-z0-9][a-z0-9_-]{0,31}$/;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const db = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, {
  auth: { persistSession: false, autoRefreshToken: false },
});
const hmx = db.schema("hmx");

class ApiError extends Error {
  constructor(readonly status: number, readonly code: string) {
    super(code);
  }
}

function corsHeaders(): Record<string, string> {
  return {
    "access-control-allow-origin": ADMIN_ORIGIN,
    "access-control-allow-methods": "POST, OPTIONS",
    "access-control-allow-headers": "authorization, apikey, content-type, x-client-info",
    "access-control-max-age": "600",
    vary: "origin",
  };
}

function json(status: number, body: unknown, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
      ...corsHeaders(),
      ...extraHeaders,
    },
  });
}

async function readJson(req: Request): Promise<Record<string, unknown>> {
  const text = await req.text();
  if (new TextEncoder().encode(text).byteLength > MAX_BODY_BYTES) {
    throw new ApiError(413, "body_too_large");
  }
  try {
    const parsed = JSON.parse(text);
    if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
      throw new ApiError(400, "invalid_body");
    }
    return parsed as Record<string, unknown>;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError(400, "invalid_body");
  }
}

function requireString(value: unknown, name: string, min: number, max: number): string {
  if (typeof value !== "string" || value.length < min || value.length > max) {
    throw new ApiError(400, `invalid_${name}`);
  }
  return value;
}

function optionalString(value: unknown, name: string, max: number, fallback: string): string {
  if (value === undefined) return fallback;
  return requireString(value, name, 0, max);
}

function requireBoolean(value: unknown, name: string): boolean {
  if (typeof value !== "boolean") throw new ApiError(400, `invalid_${name}`);
  return value;
}

function optionalBoolean(value: unknown, name: string, fallback: boolean): boolean {
  if (value === undefined) return fallback;
  return requireBoolean(value, name);
}

function optionalInt(
  value: unknown,
  name: string,
  min: number,
  max: number,
  fallback: number | null,
): number | null {
  if (value === undefined || value === null) return fallback;
  if (!Number.isInteger(value) || (value as number) < min || (value as number) > max) {
    throw new ApiError(400, `invalid_${name}`);
  }
  return value as number;
}

function optionalTimestamp(value: unknown, name: string): string | null {
  if (value === undefined || value === null) return null;
  if (typeof value !== "string" || !Number.isFinite(Date.parse(value))) {
    throw new ApiError(400, `invalid_${name}`);
  }
  return new Date(value).toISOString();
}

function requireKey(value: unknown): string {
  if (typeof value !== "string" || !KEY_PATTERN.test(value)) {
    throw new ApiError(400, "invalid_flag_key");
  }
  return value;
}

function requireSegment(value: unknown): string {
  if (typeof value !== "string" || !SEGMENT_PATTERN.test(value)) {
    throw new ApiError(400, "invalid_segment");
  }
  return value;
}

function requireUuid(value: unknown, name: string): string {
  if (typeof value !== "string" || !UUID_PATTERN.test(value)) {
    throw new ApiError(400, `invalid_${name}`);
  }
  return value;
}

function requireObject(value: unknown, name: string): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new ApiError(400, `invalid_${name}`);
  }
  return value as Record<string, unknown>;
}

function decodeJwtPayload(token: string): Record<string, unknown> | null {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const base64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
    const parsed = JSON.parse(atob(padded));
    return parsed !== null && typeof parsed === "object" ? parsed : null;
  } catch {
    return null;
  }
}

function randomHex(bytes: number): string {
  const buffer = new Uint8Array(bytes);
  crypto.getRandomValues(buffer);
  return [...buffer].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

async function audit(
  adminUserId: string,
  action: string,
  targetType: string | null,
  targetId: string | null,
  metadata: Record<string, unknown> = {},
): Promise<void> {
  const { error } = await hmx.from("admin_audit_log").insert({
    admin_user_id: adminUserId,
    action,
    target_type: targetType,
    target_id: targetId,
    metadata,
  });
  if (error) console.error("admin-api: audit write failed", error.code);
}

interface Admin {
  userId: string;
  role: string;
}

async function authenticate(req: Request): Promise<Admin> {
  const header = req.headers.get("authorization") ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (!token) throw new ApiError(401, "unauthorized");

  const claims = decodeJwtPayload(token);
  if (claims === null || claims.aal !== "aal2") {
    throw new ApiError(403, "mfa_required");
  }

  const { data: userData, error } = await db.auth.getUser(token);
  if (error || !userData.user) throw new ApiError(401, "unauthorized");

  const { data: admin, error: adminError } = await hmx
    .from("admin_users")
    .select("role,is_active")
    .eq("user_id", userData.user.id)
    .maybeSingle();
  if (adminError) {
    console.error("admin-api: allowlist query failed", adminError.code);
    throw new ApiError(503, "service_unavailable");
  }
  if (!admin || admin.is_active !== true || admin.role !== "admin") {
    throw new ApiError(403, "forbidden");
  }

  return { userId: userData.user.id, role: admin.role };
}

async function handle(action: string, body: Record<string, unknown>, admin: Admin): Promise<unknown> {
  switch (action) {
    case "flags.list": {
      const { data, error } = await hmx
        .from("feature_flags")
        .select(
          "key,name,description,enabled,rollout_percentage,min_app_version_code,max_app_version_code,starts_at,ends_at,kill_switch,config,config_version,created_at,updated_at,updated_by",
        )
        .order("key");
      if (error) throw new ApiError(503, "service_unavailable");
      return data;
    }

    case "flags.upsert": {
      const flag = requireObject(body.flag, "flag");
      const key = requireKey(flag.key);
      const name = requireString(flag.name, "name", 1, 120);
      const description = optionalString(flag.description, "description", 2000, "");
      const enabled = optionalBoolean(flag.enabled, "enabled", false);
      const rolloutPercentage = optionalInt(flag.rollout_percentage, "rollout_percentage", 0, 100, 0) ?? 0;
      const minVersion = optionalInt(flag.min_app_version_code, "min_app_version_code", 0, 2_100_000_000, null);
      const maxVersion = optionalInt(flag.max_app_version_code, "max_app_version_code", 0, 2_100_000_000, null);
      if (minVersion !== null && maxVersion !== null && maxVersion < minVersion) {
        throw new ApiError(400, "invalid_version_range");
      }
      const startsAt = optionalTimestamp(flag.starts_at, "starts_at");
      const endsAt = optionalTimestamp(flag.ends_at, "ends_at");
      if (startsAt !== null && endsAt !== null && Date.parse(endsAt) <= Date.parse(startsAt)) {
        throw new ApiError(400, "invalid_schedule");
      }
      const killSwitch = optionalBoolean(flag.kill_switch, "kill_switch", false);
      const config = flag.config === undefined ? {} : requireObject(flag.config, "config");
      if (new TextEncoder().encode(JSON.stringify(config)).byteLength > 16384) {
        throw new ApiError(400, "config_too_large");
      }

      const record = {
        key,
        name,
        description,
        enabled,
        rollout_percentage: rolloutPercentage,
        min_app_version_code: minVersion,
        max_app_version_code: maxVersion,
        starts_at: startsAt,
        ends_at: endsAt,
        kill_switch: killSwitch,
        config,
        updated_by: admin.userId,
      };

      const { data, error } = await hmx
        .from("feature_flags")
        .upsert(record, { onConflict: "key" })
        .select(
          "key,name,description,enabled,rollout_percentage,min_app_version_code,max_app_version_code,starts_at,ends_at,kill_switch,config,config_version,created_at,updated_at",
        )
        .single();
      if (error) {
        console.error("admin-api: flag upsert failed", error.code);
        throw new ApiError(400, "flag_upsert_failed");
      }
      await audit(admin.userId, "flag.upsert", "feature_flag", key, {
        enabled,
        rollout_percentage: rolloutPercentage,
        kill_switch: killSwitch,
      });
      return data;
    }

    case "flags.delete": {
      const key = requireKey(body.key);
      const { data, error } = await hmx.from("feature_flags").delete().eq("key", key).select("key");
      if (error) throw new ApiError(503, "service_unavailable");
      if (!data || data.length === 0) throw new ApiError(404, "not_found");
      await audit(admin.userId, "flag.delete", "feature_flag", key);
      return { key };
    }

    case "flags.assign-tester": {
      const key = requireKey(body.flag_key);
      const testerId = requireUuid(body.tester_id, "tester_id");
      const { error } = await hmx.from("feature_flag_testers").upsert(
        { flag_key: key, tester_id: testerId, created_by: admin.userId },
        { onConflict: "flag_key,tester_id", ignoreDuplicates: true },
      );
      if (error) {
        if (error.code === "23503") throw new ApiError(404, "not_found");
        throw new ApiError(503, "service_unavailable");
      }
      await audit(admin.userId, "flag.assign_tester", "feature_flag", key, { tester_id: testerId });
      return { flag_key: key, tester_id: testerId };
    }

    case "flags.unassign-tester": {
      const key = requireKey(body.flag_key);
      const testerId = requireUuid(body.tester_id, "tester_id");
      const { error } = await hmx
        .from("feature_flag_testers")
        .delete()
        .eq("flag_key", key)
        .eq("tester_id", testerId);
      if (error) throw new ApiError(503, "service_unavailable");
      await audit(admin.userId, "flag.unassign_tester", "feature_flag", key, { tester_id: testerId });
      return { flag_key: key, tester_id: testerId };
    }

    case "flags.assign-segment": {
      const key = requireKey(body.flag_key);
      const segment = requireSegment(body.segment);
      const { error } = await hmx.from("feature_flag_segments").upsert(
        { flag_key: key, segment, created_by: admin.userId },
        { onConflict: "flag_key,segment", ignoreDuplicates: true },
      );
      if (error) {
        if (error.code === "23503") throw new ApiError(404, "not_found");
        throw new ApiError(503, "service_unavailable");
      }
      await audit(admin.userId, "flag.assign_segment", "feature_flag", key, { segment });
      return { flag_key: key, segment };
    }

    case "flags.unassign-segment": {
      const key = requireKey(body.flag_key);
      const segment = requireSegment(body.segment);
      const { error } = await hmx
        .from("feature_flag_segments")
        .delete()
        .eq("flag_key", key)
        .eq("segment", segment);
      if (error) throw new ApiError(503, "service_unavailable");
      await audit(admin.userId, "flag.unassign_segment", "feature_flag", key, { segment });
      return { flag_key: key, segment };
    }

    case "flags.assignments": {
      const [testers, segments] = await Promise.all([
        hmx.from("feature_flag_testers").select("flag_key,tester_id,created_at"),
        hmx.from("feature_flag_segments").select("flag_key,segment,created_at"),
      ]);
      if (testers.error || segments.error) throw new ApiError(503, "service_unavailable");
      return { testers: testers.data, segments: segments.data };
    }

    case "settings.get": {
      const { data, error } = await hmx
        .from("system_settings")
        .select("kill_switch_all,updated_at,updated_by")
        .eq("id", true)
        .maybeSingle();
      if (error) throw new ApiError(503, "service_unavailable");
      return data;
    }

    case "settings.kill-switch": {
      const enabled = requireBoolean(body.enabled, "enabled");
      const { data, error } = await hmx
        .from("system_settings")
        .update({ kill_switch_all: enabled, updated_by: admin.userId })
        .eq("id", true)
        .select("kill_switch_all,updated_at,updated_by")
        .single();
      if (error) throw new ApiError(503, "service_unavailable");
      await audit(admin.userId, "system.kill_switch", "system_settings", "global", { enabled });
      return data;
    }

    case "testers.list": {
      const { data, error } = await hmx
        .from("testers")
        .select(
          "id,install_id,label,segment,status,app_version_code,app_version_name,android_api,device_model,created_at,last_seen_at,revoked_at",
        )
        .order("created_at", { ascending: false });
      if (error) throw new ApiError(503, "service_unavailable");
      return data;
    }

    case "testers.revoke": {
      const testerId = requireUuid(body.tester_id, "tester_id");
      const { data, error } = await hmx
        .from("testers")
        .update({ status: "revoked", revoked_at: new Date().toISOString() })
        .eq("id", testerId)
        .select("id,status,revoked_at")
        .maybeSingle();
      if (error) throw new ApiError(503, "service_unavailable");
      if (!data) throw new ApiError(404, "not_found");
      await audit(admin.userId, "tester.revoke", "tester", testerId);
      return data;
    }

    case "invites.list": {
      const { data, error } = await hmx
        .from("tester_invites")
        .select("id,label,max_uses,used_count,expires_at,created_by,created_at,revoked_at")
        .order("created_at", { ascending: false });
      if (error) throw new ApiError(503, "service_unavailable");
      return data;
    }

    case "invites.create": {
      const label = body.label === undefined || body.label === null
        ? null
        : requireString(body.label, "label", 1, 120);
      const maxUses = optionalInt(body.max_uses, "max_uses", 1, 100, 1) ?? 1;
      const expiresInHours = optionalInt(body.expires_in_hours, "expires_in_hours", 1, 720, 24) ?? 24;
      const code = randomHex(32);
      const codeHash = await sha256Hex(code);
      const expiresAt = new Date(Date.now() + expiresInHours * 3_600_000).toISOString();

      const { data, error } = await hmx
        .from("tester_invites")
        .insert({
          code_hash: codeHash,
          label,
          max_uses: maxUses,
          expires_at: expiresAt,
          created_by: admin.userId,
        })
        .select("id,label,max_uses,expires_at,created_at")
        .single();
      if (error) throw new ApiError(503, "service_unavailable");
      await audit(admin.userId, "invite.create", "tester_invite", data.id, { max_uses: maxUses, expires_at: expiresAt });
      return { ...data, code };
    }

    case "invites.revoke": {
      const inviteId = requireUuid(body.invite_id, "invite_id");
      const { data, error } = await hmx
        .from("tester_invites")
        .update({ revoked_at: new Date().toISOString() })
        .eq("id", inviteId)
        .select("id,revoked_at")
        .maybeSingle();
      if (error) throw new ApiError(503, "service_unavailable");
      if (!data) throw new ApiError(404, "not_found");
      await audit(admin.userId, "invite.revoke", "tester_invite", inviteId);
      return data;
    }

    case "audit.list": {
      const { data, error } = await hmx
        .from("admin_audit_log")
        .select("id,admin_user_id,action,target_type,target_id,metadata,created_at")
        .order("created_at", { ascending: false })
        .limit(100);
      if (error) throw new ApiError(503, "service_unavailable");
      return data;
    }

    default:
      throw new ApiError(400, "unknown_action");
  }
}

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method === "OPTIONS") {
    if (!ADMIN_ORIGIN) return new Response(null, { status: 503 });
    const origin = req.headers.get("origin");
    if (origin !== null && origin !== ADMIN_ORIGIN) return new Response(null, { status: 403 });
    return new Response(null, { status: 204, headers: corsHeaders() });
  }
  if (req.method !== "POST") {
    return json(405, { error: "method_not_allowed" }, { allow: "POST, OPTIONS" });
  }
  if (!SUPABASE_URL || !SERVICE_ROLE_KEY) {
    console.error("admin-api: missing runtime environment");
    return json(503, { error: "service_unavailable" });
  }
  if (!ADMIN_ORIGIN) {
    console.error("admin-api: ADMIN_ORIGIN is not configured");
    return json(503, { error: "admin_origin_not_configured" });
  }

  const origin = req.headers.get("origin");
  if (origin !== null && origin !== ADMIN_ORIGIN) {
    return json(403, { error: "origin_not_allowed" });
  }

  try {
    const admin = await authenticate(req);
    const body = await readJson(req);
    const action = requireString(body.action, "action", 1, 64);
    const data = await handle(action, body, admin);
    return json(200, { ok: true, data });
  } catch (error) {
    if (error instanceof ApiError) return json(error.status, { error: error.code });
    console.error("admin-api: unhandled error", error instanceof Error ? error.name : "unknown");
    return json(500, { error: "internal_error" });
  }
});
