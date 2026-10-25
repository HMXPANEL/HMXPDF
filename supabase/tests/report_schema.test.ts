import assert from "node:assert/strict";
import test from "node:test";
import {
  parseReportSubmission,
  type ReportSubmission,
} from "../functions/_shared/report-schema.ts";

const NOW = new Date("2026-10-24T12:00:00Z");
const CLIENT_ID = "11111111-1111-4111-8111-111111111111";
const INSTALL_ID = "22222222-2222-4222-8222-222222222222";

function app(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    version_code: 6,
    version_name: "2.1.1",
    flavor: "play",
    build_type: "release",
    android_api: 35,
    locale: "en-US",
    ...overrides,
  };
}

function diagnostic(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    schema_version: 1,
    kind: "diagnostic_test",
    client_report_id: CLIENT_ID,
    install_id: INSTALL_ID,
    client_created_at: "2026-10-24T11:59:00Z",
    app: app(),
    self_checks: {
      storage_write: "ok",
      cache_write: "ok",
      pdf_engine_init: "ok",
      report_queue_init: "ok",
    },
    ...overrides,
  };
}

function problem(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    schema_version: 1,
    kind: "problem",
    client_report_id: CLIENT_ID,
    install_id: INSTALL_ID,
    client_created_at: "2026-10-24T11:59:00Z",
    category: "bug",
    description: "The export button did nothing after I rotated the screen.",
    app: app(),
    ...overrides,
  };
}

function parse(body: unknown) {
  return parseReportSubmission(body, NOW);
}

function expectError(body: unknown, error: string): void {
  const result = parse(body);
  assert.equal(result.ok, false, `expected ${error}`);
  if (!result.ok) assert.equal(result.error, error);
}

function expectValue(body: unknown): ReportSubmission {
  const result = parse(body);
  assert.equal(result.ok, true, result.ok ? "" : result.error);
  if (!result.ok) throw new Error(result.error);
  return result.value;
}

test("valid diagnostic report is accepted and identifiers are lowercased", () => {
  const value = expectValue(
    diagnostic({
      client_report_id: CLIENT_ID.toUpperCase(),
      install_id: INSTALL_ID.toUpperCase(),
    }),
  );
  assert.equal(value.kind, "diagnostic_test");
  assert.equal(value.client_report_id, CLIENT_ID);
  assert.equal(value.install_id, INSTALL_ID);
  assert.equal(value.category, null);
  assert.equal(value.description, null);
  assert.equal(value.attached_error, null);
  assert.deepEqual(value.self_checks, {
    storage_write: "ok",
    cache_write: "ok",
    pdf_engine_init: "ok",
    report_queue_init: "ok",
  });
});

test("valid problem report is accepted with optional attached error", () => {
  const value = expectValue(
    problem({
      attached_error: {
        summary: "NullPointerException while exporting",
        detail: "Sanitized detail reviewed by the user.",
      },
    }),
  );
  assert.equal(value.kind, "problem");
  assert.equal(value.category, "bug");
  assert.equal(value.self_checks, null);
  assert.deepEqual(value.attached_error, {
    summary: "NullPointerException while exporting",
    detail: "Sanitized detail reviewed by the user.",
  });
});

test("problem report accepts omitted category and omitted detail", () => {
  const value = expectValue(
    problem({
      category: undefined,
      attached_error: { summary: "Export failed" },
    }),
  );
  assert.equal(value.category, null);
  assert.deepEqual(value.attached_error, { summary: "Export failed", detail: null });
});

test("unknown top-level fields are rejected, including device_model and document data", () => {
  for (const field of [
    "device_model",
    "contact_email",
    "document_id",
    "file_name",
    "file_path",
    "uri",
    "raw_log",
    "location",
    "screenshot",
    "annotations",
    "form_values",
    "password",
    "token",
  ]) {
    expectError(diagnostic({ [field]: "forbidden" }), "unknown_field");
  }
});

test("diagnostic reports cannot carry problem-only fields", () => {
  expectError(diagnostic({ description: "text" }), "unknown_field");
  expectError(diagnostic({ category: "bug" }), "unknown_field");
  expectError(diagnostic({ attached_error: { summary: "x" } }), "unknown_field");
});

test("problem reports cannot carry diagnostic self-checks", () => {
  expectError(problem({ self_checks: { storage_write: "ok" } }), "unknown_field");
});

test("problem description is required and bounded", () => {
  expectError(problem({ description: undefined }), "invalid_description");
  expectError(problem({ description: "" }), "invalid_description");
  expectError(problem({ description: "   " }), "invalid_description");
  expectError(problem({ description: "a".repeat(4001) }), "invalid_description");
  expectError(problem({ description: "bad\u0007control" }), "invalid_description");

  const multiline = expectValue(problem({ description: "line one\nline two\tend" }));
  assert.equal(multiline.description, "line one\nline two\tend");
});

test("schema version and kind are strict", () => {
  expectError(diagnostic({ schema_version: 2 }), "invalid_schema_version");
  expectError(diagnostic({ schema_version: "1" }), "invalid_schema_version");
  expectError(diagnostic({ kind: "support" }), "invalid_kind");
  expectError(diagnostic({ kind: undefined }), "invalid_kind");
});

test("client_report_id and install_id must be UUIDs", () => {
  expectError(diagnostic({ client_report_id: "not-a-uuid" }), "invalid_client_report_id");
  expectError(diagnostic({ install_id: "not-a-uuid" }), "invalid_install_id");
  expectError(diagnostic({ install_id: undefined }), "invalid_install_id");
});

test("client_created_at accepts the 30-day boundary and rejects future/older values", () => {
  const exactlyThirtyDays = expectValue(
    diagnostic({ client_created_at: "2026-09-24T12:00:00Z" }),
  );
  assert.equal(exactlyThirtyDays.client_created_at, "2026-09-24T12:00:00.000Z");

  expectError(
    diagnostic({ client_created_at: "2026-10-24T12:05:01Z" }),
    "invalid_client_created_at",
  );
  expectError(
    diagnostic({ client_created_at: "2026-09-24T11:59:59Z" }),
    "invalid_client_created_at",
  );
  expectError(
    diagnostic({ client_created_at: "2026-10-24 11:59:00" }),
    "invalid_client_created_at",
  );
  expectError(
    diagnostic({ client_created_at: "not-a-date" }),
    "invalid_client_created_at",
  );
});

test("app metadata is exact and bounded", () => {
  expectError(diagnostic({ app: app({ device_model: "Pixel" }) }), "invalid_app");
  expectError(diagnostic({ app: app({ version_name: "" }) }), "invalid_app");
  expectError(diagnostic({ app: app({ version_name: "v".repeat(65) }) }), "invalid_app");
  expectError(diagnostic({ app: app({ version_code: -1 }) }), "invalid_app");
  expectError(diagnostic({ app: app({ version_code: 2_100_000_001 }) }), "invalid_app");
  expectError(diagnostic({ app: app({ flavor: "amazon" }) }), "invalid_app");
  expectError(diagnostic({ app: app({ build_type: "beta" }) }), "invalid_app");
  expectError(diagnostic({ app: app({ android_api: 0 }) }), "invalid_app");
  expectError(diagnostic({ app: app({ android_api: 101 }) }), "invalid_app");
  expectError(diagnostic({ app: app({ locale: "en_US" }) }), "invalid_app");
  expectError(diagnostic({ app: app({ locale: "e".repeat(17) }) }), "invalid_app");

  const noOptional = expectValue(
    diagnostic({ app: app({ android_api: null, locale: null }) }),
  );
  assert.equal(noOptional.app.android_api, null);
  assert.equal(noOptional.app.locale, null);
});

test("attached_error is a bounded two-field object", () => {
  expectError(problem({ attached_error: "text" }), "invalid_attached_error");
  expectError(problem({ attached_error: { summary: "x", extra: "y" } }), "invalid_attached_error");
  expectError(problem({ attached_error: { summary: "" } }), "invalid_attached_error");
  expectError(
    problem({ attached_error: { summary: "s".repeat(201) } }),
    "invalid_attached_error",
  );
  expectError(
    problem({ attached_error: { summary: "ok", detail: "d".repeat(4001) } }),
    "invalid_attached_error",
  );
  expectError(
    problem({ attached_error: { summary: "ok", detail: "d".repeat(4000) } }),
    "invalid_attached_error",
  );
});

test("self_checks accept only the fixed allowlist and values", () => {
  expectError(diagnostic({ self_checks: {} }), "invalid_self_checks");
  expectError(
    diagnostic({ self_checks: { storage_write: "ok", device_model: "Pixel" } }),
    "invalid_self_checks",
  );
  expectError(
    diagnostic({ self_checks: { storage_write: "maybe" } }),
    "invalid_self_checks",
  );
  expectError(diagnostic({ self_checks: "ok" }), "invalid_self_checks");

  const subset = expectValue(
    diagnostic({ self_checks: { storage_write: "failed", cache_write: "skipped" } }),
  );
  assert.deepEqual(subset.self_checks, {
    storage_write: "failed",
    cache_write: "skipped",
  });
});

test("problem category is optional but must be in the allowlist", () => {
  expectError(problem({ category: "security" }), "invalid_category");
  expectError(problem({ category: 1 }), "invalid_category");
  assert.equal(expectValue(problem({ category: "performance" })).category, "performance");
});

test("non-object bodies are rejected", () => {
  for (const body of [null, undefined, [], "report", 1, true]) {
    expectError(body, "invalid_body");
  }
});
