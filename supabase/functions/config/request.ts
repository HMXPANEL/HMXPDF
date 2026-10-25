// Pure config-request validation. Reports are the only endpoint that may carry
// an install_id; config requests must not carry any persistent client identifier.

export const MAX_KEYS = 50;

const KEY_PATTERN = /^[a-z][a-z0-9_.-]{2,63}$/;
const TOKEN_PATTERN = /^[0-9a-f]{64}$/i;

const ALLOWED_FIELDS = new Set(["keys", "app_version_code", "tester_token"]);

export interface ConfigRequest {
  keys: string[];
  appVersionCode: number | null;
  testerToken: string | null;
}

export type ConfigRequestResult =
  | { ok: true; value: ConfigRequest }
  | { ok: false; error: string };

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function parseKeys(value: unknown): string[] | null {
  if (!Array.isArray(value) || value.length === 0 || value.length > MAX_KEYS) {
    return null;
  }
  const keys = new Set<string>();
  for (const item of value) {
    if (typeof item !== "string" || !KEY_PATTERN.test(item)) return null;
    keys.add(item);
  }
  return [...keys];
}

export function parseConfigRequest(body: unknown): ConfigRequestResult {
  if (!isPlainObject(body)) return { ok: false, error: "invalid_body" };

  // Explicit, stable error for the privacy boundary. This is checked before
  // the generic unknown-field rejection so clients get an actionable code.
  if ("install_id" in body) return { ok: false, error: "install_id_not_allowed" };

  if (Object.keys(body).some((key) => !ALLOWED_FIELDS.has(key))) {
    return { ok: false, error: "unknown_field" };
  }

  const keys = parseKeys(body.keys);
  if (keys === null) return { ok: false, error: "invalid_keys" };

  let appVersionCode: number | null = null;
  if (body.app_version_code !== undefined && body.app_version_code !== null) {
    if (
      !Number.isInteger(body.app_version_code) ||
      (body.app_version_code as number) < 0 ||
      (body.app_version_code as number) > 2_100_000_000
    ) {
      return { ok: false, error: "invalid_app_version_code" };
    }
    appVersionCode = body.app_version_code as number;
  }

  let testerToken: string | null = null;
  if (body.tester_token !== undefined && body.tester_token !== null) {
    if (typeof body.tester_token !== "string" || !TOKEN_PATTERN.test(body.tester_token)) {
      return { ok: false, error: "invalid_tester_token" };
    }
    testerToken = body.tester_token;
  }

  return { ok: true, value: { keys, appVersionCode, testerToken } };
}
