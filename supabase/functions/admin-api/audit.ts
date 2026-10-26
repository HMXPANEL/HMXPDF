// Pure audit-list query validation. Admin identity and allowlist checks stay in
// the Edge Function; this module only bounds and validates client input.

const ISO_8601_PATTERN =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})$/;
const ACTION_PATTERN = /^[a-z][a-z0-9_.-]{0,119}$/;

export class AuditInputError extends Error {
  readonly code: string;

  constructor(code: string) {
    super(code);
    this.code = code;
  }
}

export interface AuditListQuery {
  action: string | null;
  before: string | null;
  limit: number;
}

export function parseAuditListQuery(body: Record<string, unknown>): AuditListQuery {
  let action: string | null = null;
  if (body.action !== undefined && body.action !== null) {
    if (typeof body.action !== "string" || !ACTION_PATTERN.test(body.action)) {
      throw new AuditInputError("invalid_action");
    }
    action = body.action;
  }

  let before: string | null = null;
  if (body.before !== undefined && body.before !== null) {
    if (
      typeof body.before !== "string" ||
      !ISO_8601_PATTERN.test(body.before) ||
      !Number.isFinite(Date.parse(body.before))
    ) {
      throw new AuditInputError("invalid_before");
    }
    before = new Date(body.before).toISOString();
  }

  let limit = 50;
  if (body.limit !== undefined) {
    if (
      !Number.isInteger(body.limit) ||
      (body.limit as number) < 1 ||
      (body.limit as number) > 100
    ) {
      throw new AuditInputError("invalid_limit");
    }
    limit = body.limit as number;
  }

  return { action, before, limit };
}
