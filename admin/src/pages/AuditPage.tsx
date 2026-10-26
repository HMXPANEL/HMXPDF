import { useCallback, useEffect, useState } from "react";
import { useAuth } from "../auth/context";
import { AuditTable } from "../components/AuditTable";
import { ErrorPanel, PageLoading } from "../components/States";
import { describeError } from "../lib/api";
import {
  AUDIT_ACTIONS,
  AUDIT_PAGE_SIZE,
  buildAuditQuery,
  nextAuditCursor,
} from "../lib/audit";
import type { AuditRow } from "../lib/types";

export function AuditPage() {
  const { api } = useAuth();
  const [action, setAction] = useState("");
  const [cursor, setCursor] = useState<string | null>(null);
  const [history, setHistory] = useState<Array<string | null>>([]);
  const [rows, setRows] = useState<AuditRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    async (at: string | null = cursor) => {
      if (!api) return;
      setLoading(true);
      setError(null);
      try {
        const page = await api.request<AuditRow[]>(
          "audit.list",
          buildAuditQuery(action, at),
        );
        setRows(page);
      } catch (nextError) {
        setError(describeError(nextError));
      } finally {
        setLoading(false);
      }
    },
    [action, api, cursor],
  );

  useEffect(() => {
    void load();
  }, [load]);

  const nextCursor = nextAuditCursor(rows);

  function changeAction(value: string) {
    setAction(value);
    setCursor(null);
    setHistory([]);
  }

  function older() {
    if (!nextCursor) return;
    setHistory((current) => [...current, cursor]);
    setCursor(nextCursor);
  }

  function newer() {
    if (history.length === 0) return;
    setCursor(history[history.length - 1] ?? null);
    setHistory((current) => current.slice(0, -1));
  }

  return (
    <div className="page-stack">
      <div className="page-heading">
        <div>
          <h2>Audit logs</h2>
          <p className="muted">
            Read-only history of successful privileged mutations. The backend
            writes a row after the mutation succeeds; there is no edit or delete
            action here.
          </p>
        </div>
        <button type="button" className="button" onClick={() => void load()} disabled={loading}>
          {loading ? "Refreshing…" : "Refresh"}
        </button>
      </div>

      <section className="panel filter-bar" aria-label="Audit filters">
        <label>
          Action
          <select value={action} onChange={(event) => changeAction(event.target.value)}>
            <option value="">All actions</option>
            {AUDIT_ACTIONS.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </label>
        <p className="muted filter-note">
          The backend records successful mutations only. Failed requests do not
          create audit rows.
        </p>
      </section>

      {error ? <ErrorPanel message={error} onRetry={() => void load()} /> : null}

      <section className="panel">
        {loading && rows.length === 0 ? (
          <PageLoading label="Loading audit history…" />
        ) : (
          <AuditTable rows={rows} />
        )}
        <div className="pagination">
          <button
            type="button"
            className="button button-ghost"
            onClick={newer}
            disabled={history.length === 0 || loading}
          >
            Newer
          </button>
          <span className="muted">
            Page {history.length + 1} · {AUDIT_PAGE_SIZE} per page
          </span>
          <button
            type="button"
            className="button button-ghost"
            onClick={older}
            disabled={!nextCursor || loading}
          >
            Older
          </button>
        </div>
      </section>
    </div>
  );
}
