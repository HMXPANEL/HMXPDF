import assert from "node:assert/strict";
import test from "node:test";
import {
  AuditInputError,
  parseAuditListQuery,
} from "../functions/admin-api/audit.ts";

function expectInputError(fn: () => unknown, code: string): void {
  assert.throws(fn, (error: unknown) => {
    assert.ok(error instanceof AuditInputError);
    assert.equal(error.code, code);
    return true;
  });
}

test("audit list defaults to 50 rows with no filter", () => {
  assert.deepEqual(parseAuditListQuery({}), {
    action: null,
    before: null,
    limit: 50,
  });
});

test("audit list accepts an exact action, cursor, and bounded limit", () => {
  assert.deepEqual(
    parseAuditListQuery({
      action: "report.update",
      before: "2026-10-26T05:00:00Z",
      limit: 10,
    }),
    {
      action: "report.update",
      before: "2026-10-26T05:00:00.000Z",
      limit: 10,
    },
  );
});

test("audit list rejects invalid filters and limits", () => {
  expectInputError(() => parseAuditListQuery({ action: "Report Update" }), "invalid_action");
  expectInputError(() => parseAuditListQuery({ action: "" }), "invalid_action");
  expectInputError(() => parseAuditListQuery({ before: "yesterday" }), "invalid_before");
  expectInputError(() => parseAuditListQuery({ limit: 0 }), "invalid_limit");
  expectInputError(() => parseAuditListQuery({ limit: 101 }), "invalid_limit");
  expectInputError(() => parseAuditListQuery({ limit: 1.5 }), "invalid_limit");
});
