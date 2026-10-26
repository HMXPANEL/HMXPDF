import type { AuthContextValue, AuthStatus } from "../auth/context";
import type { AdminApi } from "../lib/api";

export function authValue(
  status: AuthStatus,
  api: AdminApi | null = null,
  message: string | null = null,
): AuthContextValue {
  return {
    status,
    session: null,
    admin: status === "authorized"
      ? {
          user_id: "22222222-2222-4222-8222-222222222222",
          role: "admin",
          email: "owner@example.test",
        }
      : null,
    message,
    api,
    signIn: async () => {},
    signOut: async () => {},
    retry: async () => {},
  };
}
