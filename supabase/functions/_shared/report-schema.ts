// Pure report-submission schema shared by the Edge Function and Node tests.
// This module deliberately has no Deno or Supabase imports.

export const REPORT_SCHEMA_VERSION = 1 as const;

export const REPORT_KINDS = ["diagnostic_test", "problem"] as const;
export type ReportKind = (typeof REPORT_KINDS)[number];

export const PROBLEM_CATEGORIES = ["bug", "crash", "performance", "other"] as const;
export type ProblemCategory = (typeof PROBLEM_CATEGORIES)[number];

export const BUILD_FLAVORS = ["play", "foss", "fdroid"] as const;
export type BuildFlavor = (typeof BUILD_FLAVORS)[number];

export const BUILD_TYPES = ["debug", "release"] as const;
export type BuildType = (typeof BUILD_TYPES)[number];

export const SELF_CHECK_KEYS = [
  "storage_write",
  "cache_write",
  "pdf_engine_init",
  "report_queue_init",
] as const;
export type SelfCheckKey = (typeof SELF_CHECK_KEYS)[number];

export const SELF_CHECK_VALUES = ["ok", "failed", "skipped"] as const;
export type SelfCheckValue = (typeof SELF_CHECK_VALUES)[number];

export interface ReportApp {
  version_code: number;
  version_name: string;
  flavor: BuildFlavor;
  build_type: BuildType;
  android_api: number | null;
  locale: string | null;
}

export interface AttachedError {
  summary: string;
  detail: string | null;
}

export interface ReportSubmission {
  schema_version: typeof REPORT_SCHEMA_VERSION;
  kind: ReportKind;
  client_report_id: string;
  install_id: string;
  client_created_at: string;
  app: ReportApp;
  category: ProblemCategory | null;
  description: string | null;
  attached_error: AttachedError | null;
  self_checks: Partial<Record<SelfCheckKey, SelfCheckValue>> | null;
}

export type ReportParseResult =
  | { ok: true; value: ReportSubmission }
  | { ok: false; error: string };

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ISO_8601_PATTERN =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})$/;
const LOCALE_PATTERN = /^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/;
// Allow tab, newline, and carriage return in user-authored text; reject the
// remaining C0 controls and DEL.
const CONTROL_CHARS = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/;
// Stay below the database jsonb caps (4096 and 2048) to leave room for jsonb
// container overhead.
const ATTACHED_ERROR_MAX_BYTES = 4000;
const SELF_CHECKS_MAX_BYTES = 2000;

const COMMON_FIELDS = new Set([
  "schema_version",
  "kind",
  "client_report_id",
  "install_id",
  "client_created_at",
  "app",
]);
const DIAGNOSTIC_FIELDS = new Set([...COMMON_FIELDS, "self_checks"]);
const PROBLEM_FIELDS = new Set([
  ...COMMON_FIELDS,
  "category",
  "description",
  "attached_error",
]);
const APP_FIELDS = new Set([
  "version_code",
  "version_name",
  "flavor",
  "build_type",
  "android_api",
  "locale",
]);
const ATTACHED_ERROR_FIELDS = new Set(["summary", "detail"]);

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function fail(error: string): ReportParseResult {
  return { ok: false, error };
}

function unknownField(
  value: Record<string, unknown>,
  allowed: Set<string>,
): boolean {
  return Object.keys(value).some((key) => !allowed.has(key));
}

function parseUuid(value: unknown): string | null {
  if (typeof value !== "string" || !UUID_PATTERN.test(value)) return null;
  return value.toLowerCase();
}

function parseText(
  value: unknown,
  min: number,
  max: number,
  allowWhitespaceOnly = false,
): string | null {
  if (typeof value !== "string") return null;
  if (value.length < min || value.length > max) return null;
  if (CONTROL_CHARS.test(value)) return null;
  if (!allowWhitespaceOnly && value.trim().length === 0) return null;
  return value;
}

function parseOptionalText(
  value: unknown,
  min: number,
  max: number,
): string | null | undefined {
  if (value === undefined || value === null) return null;
  const parsed = parseText(value, min, max);
  return parsed === null ? undefined : parsed;
}

function parseInteger(
  value: unknown,
  min: number,
  max: number,
): number | null | undefined {
  if (value === undefined || value === null) return null;
  if (!Number.isInteger(value) || (value as number) < min || (value as number) > max) {
    return undefined;
  }
  return value as number;
}

function parseClientCreatedAt(value: unknown, now: Date): string | null {
  if (typeof value !== "string" || !ISO_8601_PATTERN.test(value)) return null;
  const millis = Date.parse(value);
  if (!Number.isFinite(millis)) return null;
  if (millis > now.getTime() + 5 * 60 * 1000) return null;
  if (millis < now.getTime() - 30 * 24 * 60 * 60 * 1000) return null;
  return new Date(millis).toISOString();
}

function parseApp(value: unknown): ReportApp | null {
  if (!isPlainObject(value) || unknownField(value, APP_FIELDS)) return null;

  const versionCode = value.version_code;
  if (
    !Number.isInteger(versionCode) ||
    (versionCode as number) < 0 ||
    (versionCode as number) > 2_100_000_000
  ) {
    return null;
  }

  const versionName = parseText(value.version_name, 1, 64);
  if (versionName === null) return null;

  const flavor = value.flavor;
  if (typeof flavor !== "string" || !BUILD_FLAVORS.includes(flavor as BuildFlavor)) {
    return null;
  }

  const buildType = value.build_type;
  if (typeof buildType !== "string" || !BUILD_TYPES.includes(buildType as BuildType)) {
    return null;
  }

  const androidApi = parseInteger(value.android_api, 1, 100);
  if (androidApi === undefined) return null;

  const locale = parseOptionalText(value.locale, 1, 16);
  if (locale === undefined) return null;
  if (locale !== null && !LOCALE_PATTERN.test(locale)) return null;

  return {
    version_code: versionCode as number,
    version_name: versionName,
    flavor: flavor as BuildFlavor,
    build_type: buildType as BuildType,
    android_api: androidApi,
    locale,
  };
}

function parseSelfChecks(
  value: unknown,
): Partial<Record<SelfCheckKey, SelfCheckValue>> | null {
  if (!isPlainObject(value) || Object.keys(value).length === 0) return null;
  if (
    new TextEncoder().encode(JSON.stringify(value)).byteLength >
      SELF_CHECKS_MAX_BYTES
  ) {
    return null;
  }

  const result: Partial<Record<SelfCheckKey, SelfCheckValue>> = {};
  for (const [key, checkValue] of Object.entries(value)) {
    if (!SELF_CHECK_KEYS.includes(key as SelfCheckKey)) return null;
    if (
      typeof checkValue !== "string" ||
      !SELF_CHECK_VALUES.includes(checkValue as SelfCheckValue)
    ) {
      return null;
    }
    result[key as SelfCheckKey] = checkValue as SelfCheckValue;
  }
  return result;
}

function parseAttachedError(value: unknown): AttachedError | null | undefined {
  if (value === undefined || value === null) return null;
  if (!isPlainObject(value) || unknownField(value, ATTACHED_ERROR_FIELDS)) {
    return undefined;
  }
  const summary = parseText(value.summary, 1, 200);
  if (summary === null) return undefined;
  const detail = parseOptionalText(value.detail, 1, 4000);
  if (detail === undefined) return undefined;

  const parsed: AttachedError = { summary, detail };
  if (
    new TextEncoder().encode(JSON.stringify(parsed)).byteLength >
      ATTACHED_ERROR_MAX_BYTES
  ) {
    return undefined;
  }
  return parsed;
}

/**
 * Validates an untrusted report-submission body. Unknown fields are rejected,
 * including device_model and any document/file field. `now` is injectable so the
 * timestamp window can be tested deterministically.
 */
export function parseReportSubmission(
  body: unknown,
  now: Date = new Date(),
): ReportParseResult {
  if (!isPlainObject(body)) return fail("invalid_body");

  if (body.schema_version !== REPORT_SCHEMA_VERSION) {
    return fail("invalid_schema_version");
  }

  const kind = body.kind;
  if (typeof kind !== "string" || !REPORT_KINDS.includes(kind as ReportKind)) {
    return fail("invalid_kind");
  }

  const allowed = kind === "diagnostic_test" ? DIAGNOSTIC_FIELDS : PROBLEM_FIELDS;
  if (unknownField(body, allowed)) return fail("unknown_field");

  const clientReportId = parseUuid(body.client_report_id);
  if (clientReportId === null) return fail("invalid_client_report_id");

  const installId = parseUuid(body.install_id);
  if (installId === null) return fail("invalid_install_id");

  const clientCreatedAt = parseClientCreatedAt(body.client_created_at, now);
  if (clientCreatedAt === null) return fail("invalid_client_created_at");

  const app = parseApp(body.app);
  if (app === null) return fail("invalid_app");

  if (kind === "diagnostic_test") {
    const selfChecks = parseSelfChecks(body.self_checks);
    if (selfChecks === null) return fail("invalid_self_checks");
    return {
      ok: true,
      value: {
        schema_version: REPORT_SCHEMA_VERSION,
        kind: kind as ReportKind,
        client_report_id: clientReportId,
        install_id: installId,
        client_created_at: clientCreatedAt,
        app,
        category: null,
        description: null,
        attached_error: null,
        self_checks: selfChecks,
      },
    };
  }

  const description = parseText(body.description, 1, 4000);
  if (description === null) return fail("invalid_description");

  let category: ProblemCategory | null = null;
  if (body.category !== undefined && body.category !== null) {
    if (
      typeof body.category !== "string" ||
      !PROBLEM_CATEGORIES.includes(body.category as ProblemCategory)
    ) {
      return fail("invalid_category");
    }
    category = body.category as ProblemCategory;
  }

  const attachedError = parseAttachedError(body.attached_error);
  if (attachedError === undefined) return fail("invalid_attached_error");

  return {
    ok: true,
    value: {
      schema_version: REPORT_SCHEMA_VERSION,
      kind: kind as ReportKind,
      client_report_id: clientReportId,
      install_id: installId,
      client_created_at: clientCreatedAt,
      app,
      category,
      description,
      attached_error: attachedError,
      self_checks: null,
    },
  };
}
