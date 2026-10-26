import type { AuditRow } from "./types";

export const AUDIT_PAGE_SIZE = 50;

export const AUDIT_ACTIONS = [
  "report.update",
  "report.delete",
  "report.purge",
  "flag.upsert",
  "flag.delete",
  "system.kill_switch",
] as const;

export function buildAuditQuery(
  action: string,
  before: string | null,
  limit = AUDIT_PAGE_SIZE,
): Record<string, unknown> {
  const query: Record<string, unknown> = { limit };
  if (action !== "") query.action = action;
  if (before !== null) query.before = before;
  return query;
}

export function nextAuditCursor(
  rows: AuditRow[],
  limit = AUDIT_PAGE_SIZE,
): string | null {
  if (rows.length < limit) return null;
  return rows[rows.length - 1]?.created_at ?? null;
}
