import { NavLink, Outlet } from "react-router-dom";
import { useAuth } from "../auth/context";
import { config } from "../lib/config";

const navigation = [
  { to: "/dashboard", label: "Dashboard" },
  { to: "/reports", label: "Reports" },
  { to: "/flags", label: "Feature controls" },
  { to: "/audit", label: "Audit logs" },
];

export function AppShell() {
  const { admin, signOut } = useAuth();

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            HMX
          </span>
          <div>
            <strong>HMX PDF</strong>
            <span>Admin panel</span>
          </div>
        </div>
        <nav aria-label="Admin sections">
          {navigation.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) => (isActive ? "nav-link active" : "nav-link")}
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-foot">
          <span className={`env-badge env-${config.environment}`}>
            {config.environment}
          </span>
          <span className="muted">Private staging dashboard</span>
        </div>
      </aside>

      <div className="main-column">
        <header className="topbar">
          <div>
            <h1>HMX PDF administration</h1>
            <p className="muted">
              Reports, feature availability, and privileged audit history.
            </p>
          </div>
          <div className="identity">
            <div>
              <strong>{admin?.email ?? "Signed-in admin"}</strong>
              <span className="muted">
                {admin ? `${admin.role} · ${admin.user_id.slice(0, 8)}…` : ""}
              </span>
            </div>
            <button type="button" className="button button-ghost" onClick={() => void signOut()}>
              Sign out
            </button>
          </div>
        </header>
        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
