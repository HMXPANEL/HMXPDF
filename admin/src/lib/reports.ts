import type { ReportDetail, ReportListItem, ReportStatus } from "./types";

export const REPORT_PAGE_SIZE = 25;

export interface ReportFilters {
  kind: string;
  status: string;
}

export function buildReportListQuery(
  filters: ReportFilters,
  before: string | null,
  limit = REPORT_PAGE_SIZE,
): Record<string, unknown> {
  const query: Record<string, unknown> = { limit };
  if (filters.kind !== "") query.kind = filters.kind;
  if (filters.status !== "") query.status = filters.status;
  if (before !== null) query.before = before;
  return query;
}

export function nextReportCursor(
  rows: ReportListItem[],
  limit = REPORT_PAGE_SIZE,
): string | null {
  if (rows.length < limit) return null;
  return rows[rows.length - 1]?.received_at ?? null;
}

export interface ReportPatch {
  status?: ReportStatus;
  admin_notes?: string | null;
}

export function buildReportUpdate(
  report: ReportDetail,
  status: ReportStatus,
  notes: string,
): ReportPatch | null {
  const patch: ReportPatch = {};
  if (status !== report.status) patch.status = status;

  const originalNotes = report.admin_notes ?? "";
  if (notes !== originalNotes) {
    patch.admin_notes = notes.length === 0 ? null : notes;
  }

  return Object.keys(patch).length === 0 ? null : patch;
}

export function parsePurgeBatchSize(value: string): number | null {
  if (!/^\d+$/.test(value.trim())) return null;
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 5000) return null;
  return parsed;
}
