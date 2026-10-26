import { useCallback, useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useAuth } from "../auth/context";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { ErrorPanel, Notice, PageLoading } from "../components/States";
import { describeError } from "../lib/api";
import {
  formatTimestamp,
  humanCategory,
  humanKind,
  humanStatus,
} from "../lib/format";
import { buildReportUpdate } from "../lib/reports";
import type { ReportDetail, ReportStatus } from "../lib/types";

const statuses: ReportStatus[] = ["received", "reviewing", "resolved"];

export function ReportDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { api } = useAuth();
  const navigate = useNavigate();
  const [report, setReport] = useState<ReportDetail | null>(null);
  const [status, setStatus] = useState<ReportStatus>("received");
  const [notes, setNotes] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!api || !id) {
      setError("Missing report id.");
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const next = await api.request<ReportDetail>("reports.get", { id });
      setReport(next);
      setStatus(next.status);
      setNotes(next.admin_notes ?? "");
    } catch (nextError) {
      setError(describeError(nextError));
    } finally {
      setLoading(false);
    }
  }, [api, id]);

  useEffect(() => {
    void load();
  }, [load]);

  if (loading && !report) return <PageLoading label="Loading report…" />;
  if (error && !report) return <ErrorPanel message={error} onRetry={() => void load()} />;
  if (!report) return <ErrorPanel message="Report not found." />;

  const patch = buildReportUpdate(report, status, notes);
  const reportId = report.id;

  async function save() {
    if (!api || !patch) return;
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      const updated = await api.request<ReportDetail>("reports.update", {
        id: reportId,
        ...patch,
      });
      setReport(updated);
      setStatus(updated.status);
      setNotes(updated.admin_notes ?? "");
      setNotice("Report updated and the change was audited.");
    } catch (nextError) {
      setError(describeError(nextError));
    } finally {
      setSaving(false);
    }
  }

  async function remove() {
    if (!api) return;
    setDeleting(true);
    setError(null);
    try {
      await api.request<{ id: string }>("reports.delete", { id: reportId });
      navigate("/reports", {
        replace: true,
        state: { notice: "Report deleted. The deletion was audited." },
      });
    } catch (nextError) {
      setError(describeError(nextError));
      setDeleteOpen(false);
    } finally {
      setDeleting(false);
    }
  }

  return (
    <div className="page-stack">
      <div className="page-heading">
        <div>
          <Link className="text-link" to="/reports">
            ← Back to reports
          </Link>
          <h2>Report detail</h2>
          <p className="muted">
            Server report <code>{report.id}</code>
          </p>
        </div>
        <button type="button" className="button" onClick={() => void load()} disabled={loading}>
          {loading ? "Refreshing…" : "Refresh"}
        </button>
      </div>

      {notice ? <Notice tone="success">{notice}</Notice> : null}
      {error ? <ErrorPanel message={error} onRetry={() => void load()} /> : null}

      <div className="split-grid">
        <section className="panel">
          <h3>Report fields</h3>
          <dl className="detail-list">
            <Row label="Kind" value={humanKind(report.kind)} />
            <Row label="Status" value={humanStatus(report.status)} />
            <Row label="Category" value={humanCategory(report.category)} />
            <Row label="Received" value={formatTimestamp(report.received_at)} />
            <Row label="Client created" value={formatTimestamp(report.client_created_at)} />
            <Row label="Updated" value={formatTimestamp(report.updated_at)} />
            <Row
              label="App"
              value={`${report.app_version_name} (${report.app_version_code})`}
            />
            <Row label="Build" value={`${report.build_flavor} / ${report.build_type}`} />
            <Row label="Android API" value={report.android_api?.toString() ?? "—"} />
            <Row label="Locale" value={report.locale ?? "—"} />
          </dl>
        </section>

        <section className="panel">
          <h3>Review</h3>
          <div className="form-stack">
            <label>
              Status
              <select
                value={status}
                onChange={(event) => setStatus(event.target.value as ReportStatus)}
              >
                {statuses.map((value) => (
                  <option key={value} value={value}>
                    {humanStatus(value)}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Admin notes
              <textarea
                value={notes}
                maxLength={4000}
                rows={6}
                onChange={(event) => setNotes(event.target.value)}
                placeholder="Internal review notes. Do not paste document content or secrets."
              />
              <span className="muted">{notes.length} / 4000</span>
            </label>
            <button
              type="button"
              className="button button-primary"
              onClick={() => void save()}
              disabled={!patch || saving}
            >
              {saving ? "Saving…" : "Save changes"}
            </button>
          </div>
        </section>
      </div>

      <section className="panel">
        <h3>{report.kind === "diagnostic_test" ? "Self-checks" : "Description"}</h3>
        {report.kind === "diagnostic_test" ? (
          report.self_checks && Object.keys(report.self_checks).length > 0 ? (
            <dl className="detail-list compact">
              {Object.entries(report.self_checks).map(([key, value]) => (
                <Row key={key} label={key} value={String(value)} />
              ))}
            </dl>
          ) : (
            <p className="muted">No self-check values were stored.</p>
          )
        ) : (
          <pre className="report-text">{report.description ?? "—"}</pre>
        )}
      </section>

      {report.attached_error ? (
        <section className="panel">
          <h3>Attached error</h3>
          <dl className="detail-list compact">
            {Object.entries(report.attached_error).map(([key, value]) => (
              <Row
                key={key}
                label={key}
                value={typeof value === "string" ? value : JSON.stringify(value)}
              />
            ))}
          </dl>
        </section>
      ) : null}

      <section className="panel danger-zone">
        <div className="panel-heading">
          <h3>Delete report</h3>
        </div>
        <p className="muted">
          Deletion is permanent and audited. It does not affect any other report.
        </p>
        <button
          type="button"
          className="button button-danger"
          onClick={() => setDeleteOpen(true)}
        >
          Delete report
        </button>
      </section>

      {deleteOpen ? (
        <ConfirmDialog
          title="Delete this report?"
          description={`This permanently deletes server report ${report.id} from staging. It cannot be undone.`}
          confirmLabel="Delete report"
          busy={deleting}
          danger
          onConfirm={() => void remove()}
          onCancel={() => setDeleteOpen(false)}
        />
      ) : null}
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}
