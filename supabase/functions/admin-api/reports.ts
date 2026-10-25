import { REPORT_KINDS, type ReportKind } from "../_shared/report-schema.ts";

export const REPORT_STATUSES = ["received", "reviewing", "resolved"] as const;
export type ReportStatus = (typeof REPORT_STATUSES)[number];

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ISO_8601_PATTERN =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})$/;
const CONTROL_CHARS = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/;

export class ReportInputError extends Error {
  readonly code: string;

  constructor(code: string) {
    super(code);
    this.code = code;
  }
}

export interface ReportListQuery {
  kind: ReportKind | null;
  status: ReportStatus | null;
  before: string | null;
  limit: number;
}

export interface ReportUpdate {
  id: string;
  status?: ReportStatus;
  admin_notes?: string | null;
  changed_fields: string[];
}

export interface ReportPurge {
  older_than_days: 30;
  batch_size: number;
}

export function parseReportId(body: Record<string, unknown>): string {
  const id = body.id;
  if (typeof id !== "string" || !UUID_PATTERN.test(id)) {
    throw new ReportInputError("invalid_id");
  }
  return id;
}

export function parseReportListQuery(body: Record<string, unknown>): ReportListQuery {
  let kind: ReportKind | null = null;
  if (body.kind !== undefined && body.kind !== null) {
    if (
      typeof body.kind !== "string" ||
      !REPORT_KINDS.includes(body.kind as ReportKind)
    ) {
      throw new ReportInputError("invalid_kind");
    }
    kind = body.kind as ReportKind;
  }

  let status: ReportStatus | null = null;
  if (body.status !== undefined && body.status !== null) {
    if (
      typeof body.status !== "string" ||
      !REPORT_STATUSES.includes(body.status as ReportStatus)
    ) {
      throw new ReportInputError("invalid_status");
    }
    status = body.status as ReportStatus;
  }

  let before: string | null = null;
  if (body.before !== undefined && body.before !== null) {
    if (
      typeof body.before !== "string" ||
      !ISO_8601_PATTERN.test(body.before) ||
      !Number.isFinite(Date.parse(body.before))
    ) {
      throw new ReportInputError("invalid_before");
    }
    before = new Date(body.before).toISOString();
  }

  let limit = 50;
  if (body.limit !== undefined) {
    if (
      !Number.isInteger(body.limit) ||
      (body.limit as number) < 1 ||
      (body.limit as number) > 50
    ) {
      throw new ReportInputError("invalid_limit");
    }
    limit = body.limit as number;
  }

  return { kind, status, before, limit };
}

export function parseReportUpdate(body: Record<string, unknown>): ReportUpdate {
  const id = parseReportId(body);
  const changedFields: string[] = [];

  let status: ReportStatus | undefined;
  if (body.status !== undefined) {
    if (
      typeof body.status !== "string" ||
      !REPORT_STATUSES.includes(body.status as ReportStatus)
    ) {
      throw new ReportInputError("invalid_status");
    }
    status = body.status as ReportStatus;
    changedFields.push("status");
  }

  let adminNotes: string | null | undefined;
  if (body.admin_notes !== undefined) {
    if (body.admin_notes === null) {
      adminNotes = null;
    } else if (
      typeof body.admin_notes === "string" &&
      body.admin_notes.length <= 4000 &&
      !CONTROL_CHARS.test(body.admin_notes)
    ) {
      adminNotes = body.admin_notes;
    } else {
      throw new ReportInputError("invalid_admin_notes");
    }
    changedFields.push("admin_notes");
  }

  if (changedFields.length === 0) {
    throw new ReportInputError("no_changes");
  }

  return { id, status, admin_notes: adminNotes, changed_fields: changedFields };
}

export function parseReportPurge(body: Record<string, unknown>): ReportPurge {
  if (body.older_than_days !== undefined && body.older_than_days !== 30) {
    throw new ReportInputError("invalid_older_than_days");
  }

  let batchSize = 1000;
  if (body.batch_size !== undefined) {
    if (
      !Number.isInteger(body.batch_size) ||
      (body.batch_size as number) < 1 ||
      (body.batch_size as number) > 5000
    ) {
      throw new ReportInputError("invalid_batch_size");
    }
    batchSize = body.batch_size as number;
  }

  return { older_than_days: 30, batch_size: batchSize };
}
