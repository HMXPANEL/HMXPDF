import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import type { Session } from "@supabase/supabase-js";
import { AdminApi, AdminApiError, describeError } from "../lib/api";
import { config, isBrowserConfigValid } from "../lib/config";
import { supabase } from "../lib/supabase";
import type { AdminIdentity } from "../lib/types";
import { AuthContext, type AuthStatus } from "./context";

function authMessage(message: string): string {
  const lower = message.toLowerCase();
  if (lower.includes("invalid login credentials")) return "Invalid email or password.";
  if (lower.includes("email not confirmed")) return "This email has not been confirmed.";
  if (lower.includes("rate limit") || lower.includes("too many")) {
    return "Too many sign-in attempts. Wait and try again.";
  }
  return message;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>(
    isBrowserConfigValid ? "loading" : "config_error",
  );
  const [session, setSession] = useState<Session | null>(null);
  const [admin, setAdmin] = useState<AdminIdentity | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const api = useMemo(() => {
    const client = supabase;
    if (!client) return null;
    return new AdminApi({
      url: config.adminApiUrl,
      publishableKey: config.publishableKey,
      getAccessToken: async () => {
        const { data } = await client.auth.getSession();
        return data.session?.access_token ?? null;
      },
      onUnauthorized: () => {
        setAdmin(null);
        setStatus("signed_out");
        setMessage("Your session expired. Sign in again.");
      },
    });
  }, []);

  const verify = useCallback(
    async (nextSession: Session | null) => {
      if (!api || !nextSession) {
        setAdmin(null);
        setStatus("signed_out");
        return;
      }
      try {
        const identity = await api.request<{ user_id: string; role: string }>(
          "session.whoami",
        );
        setAdmin({
          user_id: identity.user_id,
          role: identity.role,
          email: nextSession.user.email ?? null,
        });
        setStatus("authorized");
        setMessage(null);
      } catch (error) {
        setAdmin(null);
        if (error instanceof AdminApiError) {
          if (error.status === 401) {
            setStatus("signed_out");
            setMessage("Your session expired. Sign in again.");
            void supabase?.auth.signOut({ scope: "local" });
            return;
          }
          if (error.status === 403) {
            setStatus("unauthorized");
            setMessage("This account is signed in but is not an active HMX PDF admin.");
            return;
          }
        }
        setStatus("unavailable");
        setMessage(describeError(error));
      }
    },
    [api],
  );

  useEffect(() => {
    if (!supabase) return;
    let active = true;
    void supabase.auth.getSession().then(({ data }) => {
      if (!active) return;
      setSession(data.session);
      void verify(data.session);
    });
    const { data } = supabase.auth.onAuthStateChange((event, nextSession) => {
      if (!active) return;
      setSession(nextSession);
      if (event === "SIGNED_OUT") {
        setAdmin(null);
        setStatus("signed_out");
      }
    });
    return () => {
      active = false;
      data.subscription.unsubscribe();
    };
  }, [verify]);

  const signIn = useCallback(
    async (email: string, password: string) => {
      if (!supabase) {
        setStatus("config_error");
        return;
      }
      setMessage(null);
      const { data, error } = await supabase.auth.signInWithPassword({
        email,
        password,
      });
      if (error) {
        setStatus("signed_out");
        setMessage(authMessage(error.message));
        return;
      }
      setSession(data.session);
      await verify(data.session);
    },
    [verify],
  );

  const signOut = useCallback(async () => {
    await supabase?.auth.signOut();
    setAdmin(null);
    setSession(null);
    setStatus("signed_out");
    setMessage(null);
  }, []);

  const retry = useCallback(async () => {
    if (!session) {
      setStatus("signed_out");
      return;
    }
    setStatus("loading");
    await verify(session);
  }, [session, verify]);

  return (
    <AuthContext.Provider
      value={{ status, session, admin, message, api, signIn, signOut, retry }}
    >
      {children}
    </AuthContext.Provider>
  );
}
