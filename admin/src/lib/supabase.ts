import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import { config, isBrowserConfigValid } from "./config";

// The publishable key is public by design. No service-role key is used in the
// browser. Sessions are tab-scoped in sessionStorage, never localStorage.
export const supabase: SupabaseClient | null = isBrowserConfigValid
  ? createClient(config.supabaseUrl, config.publishableKey, {
      auth: {
        persistSession: true,
        storage: window.sessionStorage,
        autoRefreshToken: true,
        detectSessionInUrl: false,
        flowType: "pkce",
      },
    })
  : null;
