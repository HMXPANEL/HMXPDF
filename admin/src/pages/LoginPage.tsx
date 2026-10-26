import { useState, type FormEvent } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { useAuth } from "../auth/context";
import { PageLoading } from "../components/States";
import { config, configProblems } from "../lib/config";

export function LoginPage() {
  const { status, message, signIn, signOut, retry } = useAuth();
  const location = useLocation();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);

  const from = (location.state as { from?: { pathname?: string } } | null)?.from
    ?.pathname ?? "/dashboard";

  if (status === "authorized") {
    return <Navigate to={from} replace />;
  }
  if (status === "loading") {
    return <PageLoading label="Checking your session…" />;
  }

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    try {
      await signIn(email, password);
    } finally {
      setBusy(false);
    }
  }

  if (status === "config_error") {
    return (
      <main className="login-page">
        <section className="login-card">
          <Brand />
          <h1>Deployment configuration required</h1>
          <p>
            This build is missing public Supabase configuration. No admin request
            was attempted.
          </p>
          <ul className="problem-list">
            {configProblems.map((problem) => (
              <li key={problem}>{problem}</li>
            ))}
          </ul>
          <p className="muted">
            Set these Vercel environment variables and redeploy:{" "}
            <code>VITE_SUPABASE_URL</code> and{" "}
            <code>VITE_SUPABASE_PUBLISHABLE_KEY</code>.
          </p>
        </section>
      </main>
    );
  }

  if (status === "unauthorized") {
    return (
      <main className="login-page">
        <section className="login-card">
          <Brand />
          <h1>Not authorized</h1>
          <p>{message}</p>
          <p className="muted">
            The backend checked your Supabase session against the active
            <code> hmx.admin_users </code> allowlist. Signing in alone does not
            grant access.
          </p>
          <button type="button" className="button" onClick={() => void signOut()}>
            Sign out
          </button>
        </section>
      </main>
    );
  }

  if (status === "unavailable") {
    return (
      <main className="login-page">
        <section className="login-card">
          <Brand />
          <h1>Admin backend unavailable</h1>
          <p>{message}</p>
          <div className="button-row">
            <button type="button" className="button button-primary" onClick={() => void retry()}>
              Retry authorization
            </button>
            <button type="button" className="button button-ghost" onClick={() => void signOut()}>
              Sign out
            </button>
          </div>
        </section>
      </main>
    );
  }

  return (
    <main className="login-page">
      <section className="login-card">
        <Brand />
        <div className="login-heading">
          <h1>Admin sign in</h1>
          <span className={`env-badge env-${config.environment}`}>
            {config.environment}
          </span>
        </div>
        <p className="muted">
          Use the owner Supabase account. Access is checked again by the
          <code> admin-api </code> allowlist after sign-in.
        </p>
        <form onSubmit={onSubmit} className="form-stack">
          <label>
            Email
            <input
              type="email"
              autoComplete="username"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              required
              disabled={busy}
            />
          </label>
          <label>
            Password
            <input
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
              disabled={busy}
            />
          </label>
          {message ? (
            <p className="form-error" role="alert">
              {message}
            </p>
          ) : null}
          <button type="submit" className="button button-primary" disabled={busy}>
            {busy ? "Signing in…" : "Sign in"}
          </button>
        </form>
        <p className="login-foot muted">
          Session tokens are tab-scoped and kept in sessionStorage. No
          service-role key is present in this app.
        </p>
      </section>
    </main>
  );
}

function Brand() {
  return (
    <div className="brand login-brand">
      <span className="brand-mark" aria-hidden="true">
        HMX
      </span>
      <div>
        <strong>HMX PDF</strong>
        <span>Private administration</span>
      </div>
    </div>
  );
}
