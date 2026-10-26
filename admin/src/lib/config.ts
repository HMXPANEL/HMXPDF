function clean(value: string | undefined): string {
  return (value ?? "").trim().replace(/\/+$/, "");
}

function isSafeUrl(value: string): boolean {
  if (value === "") return false;
  try {
    const url = new URL(value);
    return url.protocol === "https:" || url.hostname === "localhost" || url.hostname === "127.0.0.1";
  } catch {
    return false;
  }
}

const supabaseUrl = clean(import.meta.env.VITE_SUPABASE_URL);
const publishableKey = clean(import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY);
const adminApiUrl = clean(import.meta.env.VITE_ADMIN_API_URL) ||
  (supabaseUrl ? `${supabaseUrl}/functions/v1/admin-api` : "");

export const config = {
  supabaseUrl,
  publishableKey,
  adminApiUrl,
  environment: clean(import.meta.env.VITE_ENVIRONMENT) || "staging",
} as const;

export const configProblems: string[] = [];
if (!isSafeUrl(config.supabaseUrl)) {
  configProblems.push("VITE_SUPABASE_URL must be an HTTPS Supabase project URL.");
}
if (config.publishableKey === "") {
  configProblems.push("VITE_SUPABASE_PUBLISHABLE_KEY is missing.");
}
if (!isSafeUrl(config.adminApiUrl)) {
  configProblems.push("VITE_ADMIN_API_URL must be an HTTPS URL.");
}

export const isBrowserConfigValid = configProblems.length === 0;
