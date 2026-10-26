export function formatTimestamp(value: string | null | undefined): string {
  if (!value) return "—";
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return "—";
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date);
}

export function shortId(value: string | null | undefined): string {
  if (!value) return "—";
  return value.length > 12 ? `${value.slice(0, 8)}…` : value;
}

export function humanKind(value: string): string {
  if (value === "diagnostic_test") return "Diagnostic";
  if (value === "problem") return "Problem";
  return value;
}

export function humanStatus(value: string): string {
  if (value === "received") return "Received";
  if (value === "reviewing") return "Reviewing";
  if (value === "resolved") return "Resolved";
  return value;
}

export function humanCategory(value: string | null): string {
  if (!value) return "—";
  return value.charAt(0).toUpperCase() + value.slice(1);
}

export function compactMetadata(value: Record<string, unknown>): string {
  const entries = Object.entries(value);
  if (entries.length === 0) return "—";
  return entries
    .map(([key, item]) => `${key}: ${typeof item === "string" ? item : JSON.stringify(item)}`)
    .join(" · ");
}
