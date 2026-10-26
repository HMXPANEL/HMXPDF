import { compactMetadata, formatTimestamp, shortId } from "../lib/format";
import type { AuditRow } from "../lib/types";
import { EmptyState } from "./States";

export function AuditTable({ rows }: { rows: AuditRow[] }) {
  if (rows.length === 0) {
    return (
      <EmptyState
        title="No audit rows"
        message="Successful privileged mutations appear here after the backend confirms them."
      />
    );
  }

  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th scope="col">Time</th>
            <th scope="col">Actor</th>
            <th scope="col">Action</th>
            <th scope="col">Target</th>
            <th scope="col">Metadata</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const metadata = compactMetadata(row.metadata);
            return (
              <tr key={row.id}>
                <td>{formatTimestamp(row.created_at)}</td>
                <td>
                  <code title={row.admin_user_id ?? "system"}>
                    {shortId(row.admin_user_id)}
                  </code>
                </td>
                <td>{row.action}</td>
                <td>
                  {row.target_type ?? "—"}
                  {row.target_id ? (
                    <span className="muted">
                      {" "}
                      / <code>{shortId(row.target_id)}</code>
                    </span>
                  ) : null}
                </td>
                <td className="metadata-cell">
                  {Object.keys(row.metadata).length === 0 ? (
                    "—"
                  ) : (
                    <details>
                      <summary>{metadata}</summary>
                      <pre>{JSON.stringify(row.metadata, null, 2)}</pre>
                    </details>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
