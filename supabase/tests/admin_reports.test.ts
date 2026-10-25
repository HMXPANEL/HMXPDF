import assert from "node:assert/strict";
import test from "node:test";
import {
  parseReportId,
  parseReportListQuery,
  parseReportPurge,
  parseReportUpdate,
  ReportInputError,
} from "../functions/admin-api/reports.ts";

const ID = "11111111-1111-4111-8111-111111111111";

function expectInputError(fn: () => unknown, code: string): void {
  assert.throws(fn, (error: unknown) => {
    assert.ok(error instanceof ReportInputError);
    assert.equal(error.code, code);
    return true;
  });
}

test("report id must be a UUID", () => {
  assert.equal(parseReportId({ id: ID }), ID);
  expectInputError(() => parseReportId({ id: "not-a-uuid" }), "invalid_id");
  expectInputError(() => parseReportId({}), "invalid_id");
});

test("list query defaults and filters are validated", () => {
  assert.deepEqual(parseReportListQuery({}), {
    kind: null,
    status: null,
    before: null,
    limit: 50,
  });
  assert.deepEqual(
    parseReportListQuery({
      kind: "problem",
      status: "reviewing",
      before: "2026-10-24T12:00:00Z",
      limit: 10,
    }),
    {
      kind: "problem",
      status: "reviewing",
      before: "2026-10-24T12:00:00.000Z",
      limit: 10,
    },
  );

  expectInputError(() => parseReportListQuery({ kind: "support" }), "invalid_kind");
  expectInputError(() => parseReportListQuery({ status: "closed" }), "invalid_status");
  expectInputError(() => parseReportListQuery({ before: "yesterday" }), "invalid_before");
  expectInputError(() => parseReportListQuery({ limit: 0 }), "invalid_limit");
  expectInputError(() => parseReportListQuery({ limit: 51 }), "invalid_limit");
  expectInputError(() => parseReportListQuery({ limit: 1.5 }), "invalid_limit");
});

test("update accepts status and notes, and reports changed field names only", () => {
  assert.deepEqual(parseReportUpdate({ id: ID, status: "reviewing" }), {
    id: ID,
    status: "reviewing",
    admin_notes: undefined,
    changed_fields: ["status"],
  });
  assert.deepEqual(parseReportUpdate({ id: ID, admin_notes: "checked" }), {
    id: ID,
    status: undefined,
    admin_notes: "checked",
    changed_fields: ["admin_notes"],
  });
  assert.deepEqual(parseReportUpdate({ id: ID, admin_notes: null }), {
    id: ID,
    status: undefined,
    admin_notes: null,
    changed_fields: ["admin_notes"],
  });
  assert.deepEqual(
    parseReportUpdate({ id: ID, status: "resolved", admin_notes: "done" }).changed_fields,
    ["status", "admin_notes"],
  );
});

test("update rejects invalid status, notes, and empty changes", () => {
  expectInputError(() => parseReportUpdate({ id: ID }), "no_changes");
  expectInputError(
    () => parseReportUpdate({ id: ID, status: "closed" }),
    "invalid_status",
  );
  expectInputError(
    () => parseReportUpdate({ id: ID, admin_notes: "n".repeat(4001) }),
    "invalid_admin_notes",
  );
  expectInputError(
    () => parseReportUpdate({ id: ID, admin_notes: "bad\u0007note" }),
    "invalid_admin_notes",
  );
  expectInputError(
    () => parseReportUpdate({ id: ID, admin_notes: 42 }),
    "invalid_admin_notes",
  );
});

test("purge is fixed at 30 days with a bounded batch size", () => {
  assert.deepEqual(parseReportPurge({}), { older_than_days: 30, batch_size: 1000 });
  assert.deepEqual(parseReportPurge({ older_than_days: 30, batch_size: 25 }), {
    older_than_days: 30,
    batch_size: 25,
  });
  expectInputError(
    () => parseReportPurge({ older_than_days: 29 }),
    "invalid_older_than_days",
  );
  expectInputError(
    () => parseReportPurge({ older_than_days: 31 }),
    "invalid_older_than_days",
  );
  expectInputError(() => parseReportPurge({ batch_size: 0 }), "invalid_batch_size");
  expectInputError(() => parseReportPurge({ batch_size: 5001 }), "invalid_batch_size");
  expectInputError(() => parseReportPurge({ batch_size: 1.5 }), "invalid_batch_size");
});
