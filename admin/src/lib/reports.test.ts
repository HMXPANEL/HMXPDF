import { describe, expect, it } from "vitest";
import {
  buildReportListQuery,
  buildReportUpdate,
  nextReportCursor,
  parsePurgeBatchSize,
} from "./reports";
import type { ReportDetail, ReportListItem } from "./types";

const report: ReportDetail = {
  id: "11111111-1111-4111-8111-111111111111",
  kind: "problem",
  category: "bug",
  status: "received",
  app_version_code: 6,
  app_version_name: "2.1.1",
  android_api: 35,
  client_created_at: "2026-10-26T04:00:00Z",
  received_at: "2026-10-26T04:01:00Z",
  updated_at: "2026-10-26T04:01:00Z",
  description: "example",
  attached_error: null,
  build_flavor: "play",
  build_type: "debug",
  locale: "en-US",
  self_checks: null,
  admin_notes: null,
};

function row(receivedAt: string): ReportListItem {
  return { ...report, received_at: receivedAt };
}

describe("report list helpers", () => {
  it("omits empty filters and includes a cursor only when present", () => {
    expect(buildReportListQuery({ kind: "", status: "" }, null, 25)).toEqual({
      limit: 25,
    });
    expect(
      buildReportListQuery({ kind: "problem", status: "reviewing" }, "2026-10-26T04:01:00Z"),
    ).toEqual({
      limit: 25,
      kind: "problem",
      status: "reviewing",
      before: "2026-10-26T04:01:00Z",
    });
  });

  it("only returns a next cursor for a full page", () => {
    const rows = Array.from({ length: 25 }, (_, index) =>
      row(`2026-10-26T04:${String(index).padStart(2, "0")}:00Z`),
    );
    expect(nextReportCursor(rows)).toBe(rows[24].received_at);
    expect(nextReportCursor(rows.slice(0, 24))).toBeNull();
  });
});

describe("report mutations", () => {
  it("sends only changed fields and clears notes as null", () => {
    expect(buildReportUpdate(report, "received", "")).toBeNull();
    expect(buildReportUpdate(report, "reviewing", "")).toEqual({ status: "reviewing" });
    expect(buildReportUpdate(report, "received", "checked")).toEqual({
      admin_notes: "checked",
    });
    expect(buildReportUpdate({ ...report, admin_notes: "old" }, "received", "")).toEqual({
      admin_notes: null,
    });
  });

  it("bounds the purge batch size", () => {
    expect(parsePurgeBatchSize("1000")).toBe(1000);
    expect(parsePurgeBatchSize("1")).toBe(1);
    expect(parsePurgeBatchSize("5000")).toBe(5000);
    expect(parsePurgeBatchSize("0")).toBeNull();
    expect(parsePurgeBatchSize("5001")).toBeNull();
    expect(parsePurgeBatchSize("1.5")).toBeNull();
    expect(parsePurgeBatchSize("abc")).toBeNull();
  });
});
