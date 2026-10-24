import { createClient } from "jsr:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

const MAX_BODY_BYTES = 4096;
const CODE_PATTERN = /^[0-9a-f]{64}$/i;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

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

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
      ...CORS_HEADERS,
    },
  });
}

function fail(status: number, error: string, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify({ error }), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      ...CORS_HEADERS,
      ...extraHeaders,
    },
  });
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

function randomHex(bytes: number): string {
  const buffer = new Uint8Array(bytes);
  crypto.getRandomValues(buffer);
  return [...buffer].map((b) => b.toString(16).padStart(2, "0")).join("");
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

function optionalString(value: unknown, maxLength: number): string | null | undefined {
  if (value === undefined || value === null) return null;
  if (typeof value !== "string" || value.length > maxLength) return undefined;
  return value;
}

function optionalVersionCode(value: unknown): number | null | undefined {
  if (value === undefined || value === null) return null;
  if (!Number.isInteger(value) || (value as number) < 0 || (value as number) > 2_100_000_000) {
    return undefined;
  }
  return value as number;
}

function optionalAndroidApi(value: unknown): number | null | undefined {
  if (value === undefined || value === null) return null;
  if (!Number.isInteger(value) || (value as number) < 1 || (value as number) > 100) {
    return undefined;
  }
  return value as number;
}

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS_HEADERS });
  if (req.method !== "POST") return fail(405, "method_not_allowed", { allow: "POST, OPTIONS" });

  if (!SUPABASE_URL || !SERVICE_ROLE_KEY) {
    console.error("tester-enroll: missing runtime environment");
    return fail(503, "service_unavailable");
  }

  const parsed = await readJson(req);
  if (parsed.tooLarge) return fail(413, "body_too_large");
  if (parsed.body === null) return fail(400, "invalid_body");
  const body = parsed.body;

  const inviteCode = body.invite_code;
  if (typeof inviteCode !== "string" || !CODE_PATTERN.test(inviteCode)) {
    return fail(400, "invalid_invite_code");
  }
  const installId = body.install_id;
  if (typeof installId !== "string" || !UUID_PATTERN.test(installId)) {
    return fail(400, "invalid_install_id");
  }

  const appVersionCode = optionalVersionCode(body.app_version_code);
  const appVersionName = optionalString(body.app_version_name, 64);
  const androidApi = optionalAndroidApi(body.android_api);
  const deviceModel = optionalString(body.device_model, 120);
  if (
    appVersionCode === undefined ||
    appVersionName === undefined ||
    androidApi === undefined ||
    deviceModel === undefined
  ) {
    return fail(400, "invalid_metadata");
  }

  const ip = (req.headers.get("x-forwarded-for") ?? "").split(",")[0].trim() || "unknown";
  const codeHash = await sha256Hex(inviteCode.toLowerCase());

  const [ipLimit, codeLimit] = await Promise.all([
    hmx.rpc("consume_rate_limit", {
      p_bucket: `enroll-ip:${ip}`,
      p_limit: 10,
      p_window_seconds: 60,
    }),
    hmx.rpc("consume_rate_limit", {
      p_bucket: `enroll-code:${codeHash}`,
      p_limit: 5,
      p_window_seconds: 3600,
    }),
  ]);
  if (ipLimit.error || codeLimit.error) {
    console.error("tester-enroll: rate limiter failed");
    return fail(503, "service_unavailable");
  }
  if (ipLimit.data !== true || codeLimit.data !== true) {
    return fail(429, "rate_limited", { "retry-after": "60" });
  }

  const testerToken = randomHex(32);
  const tokenHash = await sha256Hex(testerToken);

  const { data, error } = await hmx.rpc("enroll_tester", {
    p_code_hash: codeHash,
    p_install_id: installId,
    p_token_hash: tokenHash,
    p_app_version_code: appVersionCode,
    p_app_version_name: appVersionName,
    p_android_api: androidApi,
    p_device_model: deviceModel,
  });

  if (error) {
    if (error.code === "23505") return fail(409, "already_enrolled");
    console.error("tester-enroll: enroll failed", error.code);
    return fail(503, "service_unavailable");
  }

  const tester = Array.isArray(data) ? data[0] : null;
  if (!tester) return fail(404, "invalid_invite");

  return json(201, {
    tester_token: testerToken,
    segment: tester.segment,
    label: tester.label,
  });
});
