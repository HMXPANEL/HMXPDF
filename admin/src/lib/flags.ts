import type { FeatureFlag } from "./types";

export const PHASE3_FLAG_KEYS = [
  "diagnostics.enabled",
  "problem_reports.enabled",
] as const;

export interface FlagUpdate {
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
}

export interface FlagChanges {
  available?: boolean;
  killSwitch?: boolean;
}

/**
 * The Android config request deliberately sends no install ID. Fractional
 * rollout therefore cannot identify an eligible install and fails closed.
 * The dashboard only exposes 0% and 100%.
 */
export function buildFlagUpdate(flag: FeatureFlag, changes: FlagChanges): FlagUpdate {
  const available = changes.available;
  const enabled = available === undefined ? flag.enabled : available;
  const rollout = available === undefined
    ? flag.rollout_percentage
    : available
      ? 100
      : 0;

  return {
    key: flag.key,
    name: flag.name,
    description: flag.description,
    enabled,
    rollout_percentage: rollout,
    min_app_version_code: flag.min_app_version_code,
    max_app_version_code: flag.max_app_version_code,
    starts_at: flag.starts_at,
    ends_at: flag.ends_at,
    kill_switch: changes.killSwitch ?? flag.kill_switch,
    config: flag.config,
  };
}

export type FlagTone = "on" | "off" | "warn";

export interface FlagState {
  label: string;
  detail: string;
  tone: FlagTone;
}

export function flagState(flag: FeatureFlag): FlagState {
  if (flag.kill_switch) {
    return {
      label: "Killed",
      detail: "The per-flag kill switch forces this flag off.",
      tone: "warn",
    };
  }
  if (!flag.enabled) {
    return {
      label: "Off",
      detail: "The flag is disabled for every install.",
      tone: "off",
    };
  }
  if (flag.rollout_percentage === 100) {
    return {
      label: "Available",
      detail: "The flag is enabled for every eligible app version.",
      tone: "on",
    };
  }
  if (flag.rollout_percentage === 0) {
    return {
      label: "Enabled at 0%",
      detail: "Enabled but effectively off because rollout is 0%.",
      tone: "warn",
    };
  }
  return {
    label: "Fractional rollout",
    detail: "Android sends no install ID, so fractional rollout fails closed. Set 0% or 100%.",
    tone: "warn",
  };
}
