import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "./context";
import { PageLoading } from "../components/States";

export function RequireAdmin() {
  const { status } = useAuth();
  const location = useLocation();

  if (status === "loading") {
    return <PageLoading label="Checking your admin session…" />;
  }
  if (status !== "authorized") {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }
  return <Outlet />;
}
