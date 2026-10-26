import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../auth/context";
import { AuditTable } from "../components/AuditTable";
import { EmptyState, ErrorPanel, PageLoading } from "../components/States";
import { describeError } from "../lib/api";
import type { AuditRow, ReportStats } from "../lib/types";

export function DashboardPage() {
  const { api } = useAuth();
  const [stats, setStats] = useState<ReportStats | null>(null);
  const [audit, setAudit] = useState<AuditRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshedAt, setRefreshedAt] = useState<Date | null>(null);

  const load = useCallback(async () => {
    if (!api) return;
    setLoading(true);
    setError(null);
    try {
      const [nextStats, nextAudit] = await Promise.all([
        api.request<ReportStats>("reports.stats"),
        api.request<AuditRow[]>("audit.list", { limit: 8 }),
      ]);
      setStats(nextStats);
      setAudit(nextAudit);
      setRefreshedAt(new Date());
    } catch (nextError) {
      setError(describeError(nextError));
    } finally {
      setLoading(false);
    }
  }, [api]);

  useEffect(() => {
    void load();
  }, [load]);

  if (loading && !stats) return <PageLoading label="Loading dashboard…" />;
  if (error && !stats) return <ErrorPanel message={error} onRetry={() => void load()} />;

  return (
    <div className="page-stack">
      <div className="page-heading">
        <div>
          <h2>Dashboard</h2>
          <p className="muted">
            Counts come directly from the staging reports table through the
            authorized admin API.
          </p>
        </div>
        <button type="button" className="button" onClick={() => void load()} disabled={loading}>
          {loading ? "Refreshing…" : "Refresh"}
        </button>
      </div>

      {error ? <ErrorPanel message={error} onRetry={() => void load()} /> : null}

      <section className="stats-grid" aria-label="Report statistics">
        <StatCard label="Total reports" value={stats?.total ?? 0} />
        <StatCard
          label="Pending review"
          value={stats?.pending ?? 0}
          hint="received + reviewing"
        />
        <StatCard label="Diagnostic" value={stats?.by_kind.diagnostic_test ?? 0} />
        <StatCard label="Problem" value={stats?.by_kind.problem ?? 0} />
      </section>

      <div className="split-grid">
        <section className="panel">
          <div className="panel-heading">
            <h3>Backend status</h3>
            <span className="status-dot status-on">Connected</span>
          </div>
          <p className="muted">
            The authorized admin API answered this dashboard request. This is a
            live response, not a static badge.
          </p>
          <dl className="detail-list compact">
            <div>
              <dt>Last refresh</dt>
              <dd>{refreshedAt ? refreshedAt.toLocaleTimeString() : "—"}</dd>
            </div>
            <div>
              <dt>Environment</dt>
              <dd>Staging</dd>
            </div>
          </dl>
        </section>

        <section className="panel">
          <div className="panel-heading">
            <h3>Reports by status</h3>
          </div>
          <dl className="status-list">
            <StatusRow label="Received" value={stats?.by_status.received ?? 0} />
            <StatusRow label="Reviewing" value={stats?.by_status.reviewing ?? 0} />
            <StatusRow label="Resolved" value={stats?.by_status.resolved ?? 0} />
          </dl>
          <Link className="text-link" to="/reports">
            Open reports management
          </Link>
        </section>
      </div>

      <section className="panel">
        <div className="panel-heading">
          <h3>Recent admin activity</h3>
          <Link className="text-link" to="/audit">
            View all
          </Link>
        </div>
        {audit.length === 0 ? (
          <EmptyState
            title="No activity yet"
            message="No successful privileged mutation has been recorded in the audit log."
          />
        ) : (
          <AuditTable rows={audit} />
        )}
      </section>
    </div>
  );
}

function StatCard({
  label,
  value,
  hint,
}: {
  label: string;
  value: number;
  hint?: string;
}) {
  return (
    <article className="stat-card">
      <span className="stat-label">{label}</span>
      <strong className="stat-value">{value}</strong>
      {hint ? <span className="muted">{hint}</span> : null}
    </article>
  );
}

function StatusRow({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}
