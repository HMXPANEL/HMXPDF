import { useCallback, useEffect, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { useAuth } from "../auth/context";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { EmptyState, ErrorPanel, Notice, PageLoading } from "../components/States";
import { describeError } from "../lib/api";
import { formatTimestamp, humanCategory, humanKind, humanStatus } from "../lib/format";
import {
  buildReportListQuery,
  nextReportCursor,
  parsePurgeBatchSize,
  REPORT_PAGE_SIZE,
  type ReportFilters,
} from "../lib/reports";
import type { PurgeResult, ReportListItem } from "../lib/types";

const initialFilters: ReportFilters = { kind: "", status: "" };

export function ReportsPage() {
  const { api } = useAuth();
  const location = useLocation();
  const [filters, setFilters] = useState<ReportFilters>(initialFilters);
  const [cursor, setCursor] = useState<string | null>(null);
  const [history, setHistory] = useState<Array<string | null>>([]);
  const [rows, setRows] = useState<ReportListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [purgeOpen, setPurgeOpen] = useState(false);
  const [purgeBatch, setPurgeBatch] = useState("1000");
  const [purgeError, setPurgeError] = useState<string | null>(null);
  const [purging, setPurging] = useState(false);

  useEffect(() => {
    const state = location.state as { notice?: string } | null;
    if (state?.notice) setNotice(state.notice);
  }, [location.state]);

  const load = useCallback(
    async (at: string | null = cursor) => {
      if (!api) return;
      setLoading(true);
      setError(null);
      try {
        const page = await api.request<ReportListItem[]>(
          "reports.list",
          buildReportListQuery(filters, at),
        );
        setRows(page);
      } catch (nextError) {
        setError(describeError(nextError));
      } finally {
        setLoading(false);
      }
    },
    [api, cursor, filters],
  );

  useEffect(() => {
    void load();
  }, [load]);

  const nextCursor = nextReportCursor(rows);

  function changeFilter(key: keyof ReportFilters, value: string) {
    setFilters((current) => ({ ...current, [key]: value }));
    setCursor(null);
    setHistory([]);
    setNotice(null);
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

  async function confirmPurge() {
    if (!api) return;
    const batchSize = parsePurgeBatchSize(purgeBatch);
    if (batchSize === null) {
      setPurgeError("Batch size must be an integer from 1 to 5000.");
      return;
    }
    setPurging(true);
    setPurgeError(null);
    try {
      const result = await api.request<PurgeResult>("reports.purge", {
        older_than_days: 30,
        batch_size: batchSize,
      });
      setPurgeOpen(false);
      setNotice(`Purge completed. Removed ${result.removed} expired report(s).`);
      setCursor(null);
      setHistory([]);
      await load(null);
    } catch (nextError) {
      setPurgeError(describeError(nextError));
    } finally {
      setPurging(false);
    }
  }

  return (
    <div className="page-stack">
      <div className="page-heading">
        <div>
          <h2>Reports</h2>
          <p className="muted">
            User-submitted diagnostic and problem reports. List rows omit report
            content; open a report for the approved detail fields.
          </p>
        </div>
        <button type="button" className="button" onClick={() => void load()} disabled={loading}>
          {loading ? "Refreshing…" : "Refresh"}
        </button>
      </div>

      {notice ? <Notice tone="success">{notice}</Notice> : null}

      <section className="panel filter-bar" aria-label="Report filters">
        <label>
          Kind
          <select
            value={filters.kind}
            onChange={(event) => changeFilter("kind", event.target.value)}
          >
            <option value="">All kinds</option>
            <option value="diagnostic_test">Diagnostic test</option>
            <option value="problem">Problem</option>
          </select>
        </label>
        <label>
          Status
          <select
            value={filters.status}
            onChange={(event) => changeFilter("status", event.target.value)}
          >
            <option value="">All statuses</option>
            <option value="received">Received</option>
            <option value="reviewing">Reviewing</option>
            <option value="resolved">Resolved</option>
          </select>
        </label>
        <p className="muted filter-note">
          Search is not offered because the backend does not expose a supported
          search field.
        </p>
      </section>

      {error ? <ErrorPanel message={error} onRetry={() => void load()} /> : null}

      <section className="panel">
        <div className="panel-heading">
          <h3>Report list</h3>
          <span className="muted">{rows.length} row(s) on this page</span>
        </div>
        {loading && rows.length === 0 ? (
          <PageLoading label="Loading reports…" />
        ) : rows.length === 0 ? (
          <EmptyState
            title="No reports match"
            message="The staging reports table has no rows for these filters."
          />
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th scope="col">Received</th>
                  <th scope="col">Kind</th>
                  <th scope="col">Status</th>
                  <th scope="col">Category</th>
                  <th scope="col">App</th>
                  <th scope="col">Android API</th>
                  <th scope="col">
                    <span className="sr-only">Open</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id}>
                    <td>{formatTimestamp(row.received_at)}</td>
                    <td>{humanKind(row.kind)}</td>
                    <td>
                      <span className={`status-pill status-${row.status}`}>
                        {humanStatus(row.status)}
                      </span>
                    </td>
                    <td>{humanCategory(row.category)}</td>
                    <td>
                      {row.app_version_name} ({row.app_version_code})
                    </td>
                    <td>{row.android_api ?? "—"}</td>
                    <td>
                      <Link className="text-link" to={`/reports/${row.id}`}>
                        Open
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
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
            Page {history.length + 1} · {REPORT_PAGE_SIZE} per page
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

      <section className="panel danger-zone">
        <div className="panel-heading">
          <h3>Retention maintenance</h3>
        </div>
        <p className="muted">
          Purge reports older than 30 days. The backend fixes the age at 30 days
          and bounds the batch. This is a destructive bulk action and requires
          confirmation.
        </p>
        <div className="inline-form">
          <label>
            Batch size
            <input
              type="number"
              min={1}
              max={5000}
              value={purgeBatch}
              onChange={(event) => setPurgeBatch(event.target.value)}
            />
          </label>
          <button
            type="button"
            className="button button-danger"
            onClick={() => {
              setPurgeError(null);
              setPurgeOpen(true);
            }}
          >
            Purge expired reports
          </button>
        </div>
        {purgeError ? (
          <p className="form-error" role="alert">
            {purgeError}
          </p>
        ) : null}
      </section>

      {purgeOpen ? (
        <ConfirmDialog
          title="Purge expired reports?"
          description={`This permanently deletes up to ${purgeBatch} reports received more than 30 days ago from staging. It cannot be undone.`}
          confirmLabel="Purge reports"
          busy={purging}
          danger
          onConfirm={() => void confirmPurge()}
          onCancel={() => setPurgeOpen(false)}
        />
      ) : null}
    </div>
  );
}
