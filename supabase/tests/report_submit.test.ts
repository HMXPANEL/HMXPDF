import assert from "node:assert/strict";
import test from "node:test";
import {
  handleReportRequest,
  MAX_BODY_BYTES,
  RATE_LIMITS,
  type FindResult,
  type InsertResult,
  type RateLimitResult,
  type ReportAck,
  type ReportRecord,
  type ReportStore,
} from "../functions/report-submit/handler.ts";

const CLIENT_ID = "11111111-1111-4111-8111-111111111111";
const INSTALL_ID = "22222222-2222-4222-8222-222222222222";
const REPORT_ID = "33333333-3333-4333-8333-333333333333";
const RECEIVED_AT = "2026-10-24T12:00:01.000Z";

function body(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    schema_version: 1,
    kind: "problem",
    client_report_id: CLIENT_ID,
    install_id: INSTALL_ID,
    client_created_at: "2026-10-24T11:59:00Z",
    category: "bug",
    description: "The export button did nothing.",
    app: {
      version_code: 6,
      version_name: "2.1.1",
      flavor: "play",
      build_type: "release",
      android_api: 35,
      locale: "en-US",
    },
    ...overrides,
  };
}

function request(payload: unknown = body(), headers: Record<string, string> = {}): Request {
  return new Request("https://example.test/report-submit", {
    method: "POST",
    headers: { "content-type": "application/json", ...headers },
    body: typeof payload === "string" ? payload : JSON.stringify(payload),
  });
}

class FakeStore implements ReportStore {
  readonly reports = new Map<string, ReportAck>();
  readonly buckets: string[] = [];
  insertCalls = 0;
  findCalls = 0;
  failFind = false;
  failInsert = false;
  limited = false;
  raceOnInsert = false;

  private key(installId: string, clientReportId: string): string {
    return `${installId}:${clientReportId}`;
  }

  async consumeRateLimit(bucket: string): Promise<RateLimitResult> {
    this.buckets.push(bucket);
    return this.limited ? "limited" : "allowed";
  }

  async find(installId: string, clientReportId: string): Promise<FindResult> {
    this.findCalls++;
    if (this.failFind) return { status: "error" };
    const row = this.reports.get(this.key(installId, clientReportId));
    return row ? { status: "found", row } : { status: "missing" };
  }

  async insert(record: ReportRecord): Promise<InsertResult> {
    this.insertCalls++;
    if (this.failInsert) return { status: "error" };
    const key = this.key(record.install_id, record.client_report_id);
    if (this.raceOnInsert || this.reports.has(key)) {
      this.reports.set(key, {
        id: REPORT_ID,
        status: "received",
        received_at: RECEIVED_AT,
      });
      return { status: "duplicate" };
    }
    const row = { id: REPORT_ID, status: "received", received_at: RECEIVED_AT };
    this.reports.set(key, row);
    return { status: "inserted", row };
  }
}

test("valid submission returns a minimal 201 acknowledgement", async () => {
  const store = new FakeStore();
  const response = await handleReportRequest(request(), { store });

  assert.equal(response.status, 201);
  const payload = await response.json();
  assert.deepEqual(payload, {
    report_id: REPORT_ID,
    status: "received",
    received_at: RECEIVED_AT,
  });
  assert.equal(store.insertCalls, 1);
  assert.equal(store.reports.size, 1);
});

test("duplicate retry returns 200 duplicate without another insert or rate-limit charge", async () => {
  const store = new FakeStore();
  await handleReportRequest(request(), { store });
  const bucketsAfterFirst = store.buckets.length;

  const retry = await handleReportRequest(request(), { store });
  assert.equal(retry.status, 200);
  assert.deepEqual(await retry.json(), {
    report_id: REPORT_ID,
    status: "received",
    received_at: RECEIVED_AT,
    duplicate: true,
  });
  assert.equal(store.insertCalls, 1, "retry must not insert a second row");
  assert.equal(store.reports.size, 1);
  assert.equal(store.buckets.length, bucketsAfterFirst, "retry must not consume limits");
});

test("concurrent insert race resolves to the existing row", async () => {
  const store = new FakeStore();
  store.raceOnInsert = true;
  const response = await handleReportRequest(request(), { store });

  assert.equal(response.status, 200);
  const payload = (await response.json()) as Record<string, unknown>;
  assert.equal(payload.duplicate, true);
  assert.equal(payload.report_id, REPORT_ID);
  assert.equal(store.reports.size, 1);
});

test("rate-limited submission returns 429 and never inserts", async () => {
  const store = new FakeStore();
  store.limited = true;
  const response = await handleReportRequest(request(), { store });

  assert.equal(response.status, 429);
  assert.equal(response.headers.get("retry-after"), "3600");
  assert.deepEqual(await response.json(), { error: "rate_limited" });
  assert.equal(store.insertCalls, 0);
});

test("rate-limit buckets contain hashed IP and install identifiers only", async () => {
  const store = new FakeStore();
  const response = await handleReportRequest(
    request(body(), { "x-forwarded-for": "203.0.113.7" }),
    { store },
  );
  assert.equal(response.status, 201);

  assert.equal(store.buckets.length, 3);
  assert.ok(store.buckets.every((bucket) => !bucket.includes(INSTALL_ID)));
  assert.ok(store.buckets.every((bucket) => !bucket.includes("203.0.113.7")));

  const installBuckets = store.buckets.filter((bucket) =>
    bucket.startsWith("report-install-")
  );
  assert.equal(installBuckets.length, 2);
  for (const bucket of installBuckets) {
    assert.match(bucket, /^report-install-(hour|day):[0-9a-f]{64}$/);
  }
  const ipBucket = store.buckets.find((bucket) => bucket.startsWith("report-ip:"));
  assert.match(ipBucket ?? "", /^report-ip:[0-9a-f]{64}$/);
});

test("privacy-sensitive fields are rejected before limits or storage", async () => {
  for (const field of ["device_model", "contact_email", "document_id", "file_path", "raw_log"]) {
    const store = new FakeStore();
    const response = await handleReportRequest(request(body({ [field]: "forbidden" })), {
      store,
    });
    assert.equal(response.status, 400);
    assert.deepEqual(await response.json(), { error: "unknown_field" });
    assert.equal(store.buckets.length, 0);
    assert.equal(store.insertCalls, 0);
  }
});

test("invalid payload returns 400 without consuming limits", async () => {
  const store = new FakeStore();
  const response = await handleReportRequest(request({ kind: "support" }), { store });
  assert.equal(response.status, 400);
  assert.equal(store.buckets.length, 0);
  assert.equal(store.insertCalls, 0);
});

test("oversized body is rejected with 413", async () => {
  const store = new FakeStore();
  const oversized = "x".repeat(MAX_BODY_BYTES + 1);
  const response = await handleReportRequest(request(oversized), { store });
  assert.equal(response.status, 413);
  assert.deepEqual(await response.json(), { error: "body_too_large" });
});

test("store failures fail closed with 503", async () => {
  const findFailure = new FakeStore();
  findFailure.failFind = true;
  assert.equal((await handleReportRequest(request(), { store: findFailure })).status, 503);

  const insertFailure = new FakeStore();
  insertFailure.failInsert = true;
  assert.equal((await handleReportRequest(request(), { store: insertFailure })).status, 503);
});

test("database check violations map to 400 invalid_field", async () => {
  const store = new FakeStore();
  store.insert = async () => ({ status: "invalid" });
  const response = await handleReportRequest(request(), { store });
  assert.equal(response.status, 400);
  assert.deepEqual(await response.json(), { error: "invalid_field" });
});

test("method and configuration guards are preserved", async () => {
  const store = new FakeStore();
  const options = await handleReportRequest(
    new Request("https://example.test/report-submit", { method: "OPTIONS" }),
    { store },
  );
  assert.equal(options.status, 204);

  const get = await handleReportRequest(
    new Request("https://example.test/report-submit", { method: "GET" }),
    { store },
  );
  assert.equal(get.status, 405);

  const unconfigured = await handleReportRequest(request(), { store, configured: false });
  assert.equal(unconfigured.status, 503);
});

test("rate limits keep their configured values", () => {
  assert.deepEqual(RATE_LIMITS, {
    ipPerHour: 10,
    installPerHour: 5,
    installPerDay: 20,
  });
});
