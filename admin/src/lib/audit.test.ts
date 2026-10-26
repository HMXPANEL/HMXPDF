import { describe, expect, it } from "vitest";
import { buildAuditQuery, nextAuditCursor } from "./audit";
import type { AuditRow } from "./types";

function row(createdAt: string): AuditRow {
  return {
    id: 1,
    admin_user_id: "22222222-2222-4222-8222-222222222222",
    action: "report.update",
    target_type: "report",
    target_id: "11111111-1111-4111-8111-111111111111",
    metadata: { changed_fields: ["status"] },
    created_at: createdAt,
  };
}

describe("audit list helpers", () => {
  it("builds bounded queries with optional action and cursor", () => {
    expect(buildAuditQuery("", null, 50)).toEqual({ limit: 50 });
    expect(buildAuditQuery("report.update", "2026-10-26T05:00:00Z", 10)).toEqual({
      limit: 10,
      action: "report.update",
      before: "2026-10-26T05:00:00Z",
    });
  });

  it("only returns a next cursor for a full page", () => {
    const rows = Array.from({ length: 50 }, (_, index) =>
      row(`2026-10-26T05:${String(index).padStart(2, "0")}:00Z`),
    );
    expect(nextAuditCursor(rows)).toBe(rows[49].created_at);
    expect(nextAuditCursor(rows.slice(0, 49))).toBeNull();
  });
});
