export interface AdminRow {
  role: string;
  is_active: boolean;
}

// MFA is deliberately not part of this decision. Authentication is enforced by
// auth.getUser(); this function enforces the active allowlist and admin role.
export function isAuthorizedAdmin(
  row: Pick<AdminRow, "role" | "is_active"> | null | undefined,
): row is Pick<AdminRow, "role" | "is_active"> {
  return row !== null && row !== undefined && row.is_active === true && row.role === "admin";
}
