// Pure report-submission handler. The Deno entrypoint injects a Supabase-backed
// store; Node tests inject an in-memory store. No Deno or Supabase imports here.
import {
  parseReportSubmission,
  type AttachedError,
  type BuildFlavor,
  type BuildType,
  type ProblemCategory,
  type ReportKind,
  type SelfCheckKey,
  type SelfCheckValue,
} from "../_shared/report-schema.ts";

export const MAX_BODY_BYTES = 16384;
export const RATE_LIMITS = {
  ipPerHour: 10,
  installPerHour: 5,
  installPerDay: 20,
} as const;

const CORS_HEADERS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "POST, OPTIONS",
  "access-control-allow-headers": "content-type",
  "access-control-max-age": "86400",
};

export interface ReportAck {
  id: string;
  status: string;
  received_at: string;
}

export interface ReportRecord {
  client_report_id: string;
  install_id: string;
  kind: ReportKind;
  category: ProblemCategory | null;
  description: string | null;
  attached_error: AttachedError | null;
  app_version_code: number;
  app_version_name: string;
  build_flavor: BuildFlavor;
  build_type: BuildType;
  android_api: number | null;
  locale: string | null;
  self_checks: Partial<Record<SelfCheckKey, SelfCheckValue>> | null;
  client_created_at: string;
}

export type RateLimitResult = "allowed" | "limited" | "error";
export type FindResult =
  | { status: "found"; row: ReportAck }
  | { status: "missing" }
  | { status: "error" };
export type InsertResult =
  | { status: "inserted"; row: ReportAck }
  | { status: "duplicate" }
  | { status: "invalid" }
  | { status: "error" };

export interface ReportStore {
  consumeRateLimit(
    bucket: string,
    limit: number,
    windowSeconds: number,
  ): Promise<RateLimitResult>;
  find(installId: string, clientReportId: string): Promise<FindResult>;
  insert(record: ReportRecord): Promise<InsertResult>;
}

export interface ReportDeps {
  store: ReportStore;
  configured?: boolean;
}

function json(
  status: number,
  body: unknown,
  extraHeaders: Record<string, string> = {},
): Response {
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

function fail(
  status: number,
  error: string,
  extraHeaders: Record<string, string> = {},
): Response {
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

async function readJson(req: Request): Promise<{ body: unknown; tooLarge: boolean }> {
  const text = await req.text();
  if (new TextEncoder().encode(text).byteLength > MAX_BODY_BYTES) {
    return { body: null, tooLarge: true };
  }
  try {
    return { body: JSON.parse(text), tooLarge: false };
  } catch {
    return { body: null, tooLarge: false };
  }
}

function acknowledgement(row: ReportAck, duplicate: boolean): Record<string, unknown> {
  const body: Record<string, unknown> = {
    report_id: row.id,
    status: row.status,
    received_at: row.received_at,
  };
  if (duplicate) body.duplicate = true;
  return body;
}

export async function handleReportRequest(
  req: Request,
  deps: ReportDeps,
): Promise<Response> {
  if (req.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: CORS_HEADERS });
  }
  if (req.method !== "POST") {
    return fail(405, "method_not_allowed", { allow: "POST, OPTIONS" });
  }
  if (deps.configured === false) {
    console.error("report-submit: missing runtime environment");
    return fail(503, "service_unavailable");
  }

  const parsed = await readJson(req);
  if (parsed.tooLarge) return fail(413, "body_too_large");

  const validated = parseReportSubmission(parsed.body);
  if (!validated.ok) return fail(400, validated.error);
  const report = validated.value;

  // Resolve duplicates before consuming rate limits. A lost 201 followed by a
  // retry must return the existing report, not a 429. The caller already knows
  // both UUIDs, so the acknowledgement reveals nothing new.
  const existing = await deps.store.find(report.install_id, report.client_report_id);
  if (existing.status === "error") {
    return fail(503, "service_unavailable");
  }
  if (existing.status === "found") {
    return json(200, acknowledgement(existing.row, true));
  }

  const ip = (req.headers.get("x-forwarded-for") ?? "").split(",")[0].trim() || "unknown";
  // Store only pseudonymous bucket keys, never the raw IP or install UUID.
  const ipKey = await sha256Hex(ip);
  const installKey = await sha256Hex(`report-install:${report.install_id}`);

  const [ipLimit, installHourLimit, installDayLimit] = await Promise.all([
    deps.store.consumeRateLimit(`report-ip:${ipKey}`, RATE_LIMITS.ipPerHour, 3600),
    deps.store.consumeRateLimit(
      `report-install-hour:${installKey}`,
      RATE_LIMITS.installPerHour,
      3600,
    ),
    deps.store.consumeRateLimit(
      `report-install-day:${installKey}`,
      RATE_LIMITS.installPerDay,
      86400,
    ),
  ]);

  if (
    ipLimit === "error" ||
    installHourLimit === "error" ||
    installDayLimit === "error"
  ) {
    return fail(503, "service_unavailable");
  }
  if (
    ipLimit !== "allowed" ||
    installHourLimit !== "allowed" ||
    installDayLimit !== "allowed"
  ) {
    return fail(429, "rate_limited", { "retry-after": "3600" });
  }

  const record: ReportRecord = {
    client_report_id: report.client_report_id,
    install_id: report.install_id,
    kind: report.kind,
    category: report.category,
    description: report.description,
    attached_error: report.attached_error,
    app_version_code: report.app.version_code,
    app_version_name: report.app.version_name,
    build_flavor: report.app.flavor,
    build_type: report.app.build_type,
    android_api: report.app.android_api,
    locale: report.app.locale,
    self_checks: report.self_checks,
    client_created_at: report.client_created_at,
  };

  // (install_id, client_report_id) is unique. A concurrent retry is ignored by
  // the insert and resolved to the existing row below.
  const inserted = await deps.store.insert(record);
  if (inserted.status === "inserted") {
    return json(201, acknowledgement(inserted.row, false));
  }
  if (inserted.status === "invalid") {
    return fail(400, "invalid_field");
  }
  if (inserted.status === "error") {
    return fail(503, "service_unavailable");
  }

  const raced = await deps.store.find(report.install_id, report.client_report_id);
  if (raced.status === "error" || raced.status === "missing") {
    console.error("report-submit: insert was ignored but no row exists");
    return fail(503, "service_unavailable");
  }
  return json(200, acknowledgement(raced.row, true));
}
