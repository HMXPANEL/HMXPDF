export type ReportKind = "diagnostic_test" | "problem";
export type ReportStatus = "received" | "reviewing" | "resolved";
export type ProblemCategory = "bug" | "crash" | "performance" | "other";

export interface ReportListItem {
  id: string;
  kind: ReportKind;
  category: ProblemCategory | null;
  status: ReportStatus;
  app_version_code: number;
  app_version_name: string;
  android_api: number | null;
  client_created_at: string;
  received_at: string;
  updated_at: string;
}

export interface ReportDetail extends ReportListItem {
  description: string | null;
  attached_error: Record<string, unknown> | null;
  build_flavor: "play" | "foss" | "fdroid";
  build_type: "debug" | "release";
  locale: string | null;
  self_checks: Record<string, unknown> | null;
  admin_notes: string | null;
}

export interface ReportStats {
  total: number;
  pending: number;
  by_kind: Record<ReportKind, number>;
  by_status: Record<ReportStatus, number>;
}

export interface FeatureFlag {
  key: string;
  name: string;
  description: string;
  enabled: boolean;
  rollout_percentage: number;
  min_app_version_code: number | null;
  max_app_version_code: number | null;
  starts_at: string | null;
  ends_at: string | null;
  kill_switch: boolean;
  config: Record<string, unknown>;
  config_version: number;
  created_at?: string;
  updated_at: string;
  updated_by?: string | null;
}

export interface AuditRow {
  id: number;
  admin_user_id: string | null;
  action: string;
  target_type: string | null;
  target_id: string | null;
  metadata: Record<string, unknown>;
  created_at: string;
}

export interface AdminIdentity {
  user_id: string;
  role: string;
  email: string | null;
}

export interface PurgeResult {
  removed: number;
  older_than_days: 30;
  batch_size: number;
}
