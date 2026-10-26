import { createContext, useContext } from "react";
import type { Session } from "@supabase/supabase-js";
import type { AdminApi } from "../lib/api";
import type { AdminIdentity } from "../lib/types";

export type AuthStatus =
  | "loading"
  | "signed_out"
  | "authorized"
  | "unauthorized"
  | "unavailable"
  | "config_error";

export interface AuthContextValue {
  status: AuthStatus;
  session: Session | null;
  admin: AdminIdentity | null;
  message: string | null;
  api: AdminApi | null;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
  retry: () => Promise<void>;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside AuthProvider");
  return value;
}
