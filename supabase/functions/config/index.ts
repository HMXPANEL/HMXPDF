import { createClient } from "jsr:@supabase/supabase-js@2";
import {
  evaluateFlags,
  revisionFor,
  type FlagRow,
} from "./rollout.ts";
import { parseConfigRequest } from "./request.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

const MAX_BODY_BYTES = 4096;
// Keep emergency changes responsive without forcing a request per app launch.
const MAX_AGE_SECONDS = 60;
const RATE_LIMIT_PER_MINUTE = 120;

const CORS_HEADERS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "POST, OPTIONS",
  "access-control-allow-headers": "content-type",
  "access-control-max-age": "86400",
};

const db = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, {
  auth: { persistSession: false, autoRefreshToken: false },
});
const hmx = db.schema("hmx");

function json(status: number, body: unknown, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
      ...CORS_HEADERS,
      ...extraHeaders,
    },
  });
}

function fail(status: number, error: string, extraHeaders: Record<string, string> = {}): Response {
  return json(status, { error }, extraHeaders);
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

async function readJson(req: Request): Promise<{ body: Record<string, unknown> | null; tooLarge: boolean }> {
  const text = await req.text();
  if (new TextEncoder().encode(text).byteLength > MAX_BODY_BYTES) {
    return { body: null, tooLarge: true };
  }
  try {
    const parsed = JSON.parse(text);
    if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
      return { body: null, tooLarge: false };
    }
    return { body: parsed as Record<string, unknown>, tooLarge: false };
  } catch {
    return { body: null, tooLarge: false };
  }
}

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS_HEADERS });
  if (req.method !== "POST") return fail(405, "method_not_allowed", { allow: "POST, OPTIONS" });

  if (!SUPABASE_URL || !SERVICE_ROLE_KEY) {
    console.error("config: missing runtime environment");
    return fail(503, "service_unavailable");
  }

  const parsed = await readJson(req);
  if (parsed.tooLarge) return fail(413, "body_too_large");
  if (parsed.body === null) return fail(400, "invalid_body");
  const body = parsed.body;

  const parsedRequest = parseConfigRequest(body);
  if (!parsedRequest.ok) return fail(400, parsedRequest.error);
  const { keys, appVersionCode, testerToken } = parsedRequest.value;

  const ip = (req.headers.get("x-forwarded-for") ?? "").split(",")[0].trim() || "unknown";
  // Store only a pseudonymous bucket key, never the raw client IP.
  const ipKey = await sha256Hex(ip);
  const { data: allowed, error: limitError } = await hmx.rpc("consume_rate_limit", {
    p_bucket: `config:${ipKey}`,
    p_limit: RATE_LIMIT_PER_MINUTE,
    p_window_seconds: 60,
  });
  if (limitError) {
    console.error("config: rate limiter failed", limitError.code);
    return fail(503, "service_unavailable");
  }
  if (allowed !== true) return fail(429, "rate_limited", { "retry-after": "60" });

  const { data: settings, error: settingsError } = await hmx
    .from("system_settings")
    .select("kill_switch_all")
    .eq("id", true)
    .maybeSingle();
  if (settingsError) {
    console.error("config: settings query failed", settingsError.code);
    return fail(503, "service_unavailable");
  }
  // Missing settings row is a fail-safe condition: disable every flag.
  const globalKillSwitch = settings === null ? true : settings.kill_switch_all !== false;

  const { data: flagRows, error: flagsError } = await hmx
    .from("feature_flags")
    .select(
      "key,enabled,rollout_percentage,min_app_version_code,max_app_version_code,starts_at,ends_at,kill_switch,config,config_version",
    )
    .in("key", keys);
  if (flagsError) {
    console.error("config: flags query failed", flagsError.code);
    return fail(503, "service_unavailable");
  }

  let testerId: string | null = null;
  let testerSegment: string | null = null;
  const testerFlagKeys = new Set<string>();
  const segmentFlagKeys = new Set<string>();

  if (typeof testerToken === "string") {
    const tokenHash = await sha256Hex(testerToken.toLowerCase());
    const { data: tester, error: testerError } = await hmx
      .from("testers")
      .select("id,segment,status")
      .eq("token_hash", tokenHash)
      .maybeSingle();
    if (testerError) {
      console.error("config: tester query failed", testerError.code);
      return fail(503, "service_unavailable");
    }
    if (tester !== null && tester.status === "active") {
      testerId = tester.id;
      testerSegment = tester.segment;

      const [flagAssignments, segmentAssignments] = await Promise.all([
        hmx.from("feature_flag_testers").select("flag_key").eq("tester_id", tester.id),
        hmx.from("feature_flag_segments").select("flag_key").eq("segment", tester.segment),
      ]);
      if (flagAssignments.error || segmentAssignments.error) {
        console.error("config: assignment query failed");
        return fail(503, "service_unavailable");
      }
      for (const row of flagAssignments.data ?? []) testerFlagKeys.add(row.flag_key);
      for (const row of segmentAssignments.data ?? []) segmentFlagKeys.add(row.flag_key);

      const { error: seenError } = await hmx
        .from("testers")
        .update({ last_seen_at: new Date().toISOString() })
        .eq("id", tester.id);
      if (seenError) console.error("config: last_seen update failed", seenError.code);
    }
  }

  const effective = await evaluateFlags((flagRows ?? []) as FlagRow[], {
    // Install IDs are accepted only by report-submit. Config never receives one,
    // so fractional rollouts fail closed with missing_install_id.
    installId: null,
    appVersionCode,
    now: new Date(),
    globalKillSwitch,
    testerId,
    testerSegment,
    testerFlagKeys,
    segmentFlagKeys,
  });

  const revision = await revisionFor(effective, globalKillSwitch);

  return json(200, {
    revision,
    generated_at: new Date().toISOString(),
    max_age_seconds: MAX_AGE_SECONDS,
    kill_switch: globalKillSwitch,
    flags: effective,
  });
});
