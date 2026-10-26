import { useCallback, useEffect, useState } from "react";
import { useAuth } from "../auth/context";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { EmptyState, ErrorPanel, Notice, PageLoading } from "../components/States";
import { describeError } from "../lib/api";
import { formatTimestamp } from "../lib/format";
import {
  buildFlagUpdate,
  flagState,
  PHASE3_FLAG_KEYS,
  type FlagUpdate,
} from "../lib/flags";
import type { FeatureFlag } from "../lib/types";

type PendingChange = {
  flag: FeatureFlag;
  kind: "availability" | "kill";
  next: boolean;
};

export function FlagsPage() {
  const { api } = useAuth();
  const [flags, setFlags] = useState<FeatureFlag[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pending, setPending] = useState<PendingChange | null>(null);

  const load = useCallback(async () => {
    if (!api) return;
    setLoading(true);
    setError(null);
    try {
      const all = await api.request<FeatureFlag[]>("flags.list");
      setFlags(
        all.filter((flag) =>
          (PHASE3_FLAG_KEYS as readonly string[]).includes(flag.key),
        ),
      );
    } catch (nextError) {
      setError(describeError(nextError));
    } finally {
      setLoading(false);
    }
  }, [api]);

  useEffect(() => {
    void load();
  }, [load]);

  async function applyChange() {
    if (!api || !pending) return;
    const changes = pending.kind === "availability"
      ? { available: pending.next }
      : { killSwitch: pending.next };
    const update: FlagUpdate = buildFlagUpdate(pending.flag, changes);
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      const saved = await api.request<FeatureFlag>("flags.upsert", {
        flag: update,
      });
      setFlags((current) =>
        current.map((flag) => (flag.key === saved.key ? saved : flag)),
      );
      setNotice(
        `${saved.name} saved at ${formatTimestamp(saved.updated_at)}. The server confirmed the new value.`,
      );
      setPending(null);
    } catch (nextError) {
      setError(describeError(nextError));
    } finally {
      setSaving(false);
    }
  }

  if (loading && flags.length === 0) return <PageLoading label="Loading feature flags…" />;

  return (
    <div className="page-stack">
      <div className="page-heading">
        <div>
          <h2>Feature controls</h2>
          <p className="muted">
            Staging-only server availability for the two Phase 3 flags. Changes
            are authorized and audited by <code>admin-api</code>.
          </p>
        </div>
        <button type="button" className="button" onClick={() => void load()} disabled={loading}>
          {loading ? "Refreshing…" : "Refresh"}
        </button>
      </div>

      <Notice tone="info">
        Remote flags cannot override local user consent. If a user chose Off
        during onboarding, turning a flag on here does not enable reports for
        that install. Android config requests send no install ID, so this panel
        only uses 0% or 100% rollout.
      </Notice>

      {notice ? <Notice tone="success">{notice}</Notice> : null}
      {error ? <ErrorPanel message={error} onRetry={() => void load()} /> : null}

      {flags.length === 0 ? (
        <EmptyState
          title="Phase 3 flags not found"
          message="The staging backend did not return diagnostics.enabled or problem_reports.enabled. No flag was changed."
        />
      ) : (
        <div className="flag-grid">
          {flags.map((flag) => {
            const state = flagState(flag);
            const available = flag.enabled && flag.rollout_percentage === 100;
            return (
              <article className="panel flag-card" key={flag.key}>
                <div className="panel-heading">
                  <div>
                    <h3>{flag.name}</h3>
                    <code>{flag.key}</code>
                  </div>
                  <span className={`status-pill status-${state.tone}`}>{state.label}</span>
                </div>
                <p>{flag.description || "No description."}</p>
                <p className="muted">{state.detail}</p>
                <dl className="detail-list compact">
                  <div>
                    <dt>Enabled</dt>
                    <dd>{flag.enabled ? "Yes" : "No"}</dd>
                  </div>
                  <div>
                    <dt>Rollout</dt>
                    <dd>{flag.rollout_percentage}%</dd>
                  </div>
                  <div>
                    <dt>Kill switch</dt>
                    <dd>{flag.kill_switch ? "On" : "Off"}</dd>
                  </div>
                  <div>
                    <dt>Config version</dt>
                    <dd>{flag.config_version}</dd>
                  </div>
                  <div>
                    <dt>Updated</dt>
                    <dd>{formatTimestamp(flag.updated_at)}</dd>
                  </div>
                </dl>
                <div className="button-row">
                  <button
                    type="button"
                    className={available ? "button button-danger" : "button button-primary"}
                    onClick={() =>
                      setPending({ flag, kind: "availability", next: !available })
                    }
                  >
                    {available ? "Turn off" : "Make available"}
                  </button>
                  <button
                    type="button"
                    className="button button-ghost"
                    onClick={() =>
                      setPending({ flag, kind: "kill", next: !flag.kill_switch })
                    }
                  >
                    {flag.kill_switch ? "Disable kill switch" : "Enable kill switch"}
                  </button>
                </div>
              </article>
            );
          })}
        </div>
      )}

      {pending ? (
        <ConfirmDialog
          title={
            pending.kind === "availability"
              ? pending.next
                ? "Make this flag available?"
                : "Turn this flag off?"
              : pending.next
                ? "Enable the kill switch?"
                : "Disable the kill switch?"
          }
          description={changeDescription(pending)}
          confirmLabel="Apply change"
          busy={saving}
          danger={pending.kind === "kill" ? pending.next : !pending.next}
          onConfirm={() => void applyChange()}
          onCancel={() => setPending(null)}
        >
          <p className="muted">
            This changes staging only. Local consent still wins on every install.
          </p>
        </ConfirmDialog>
      ) : null}
    </div>
  );
}

function changeDescription(pending: PendingChange): string {
  if (pending.kind === "availability") {
    return pending.next
      ? `Set ${pending.flag.key} to enabled with 100% rollout. Existing local consent remains authoritative.`
      : `Set ${pending.flag.key} to disabled with 0% rollout.`;
  }
  return pending.next
    ? `Force ${pending.flag.key} off for every install until the kill switch is disabled.`
    : `Remove the forced-off state from ${pending.flag.key}. The flag's normal enabled and rollout values apply again.`;
}
