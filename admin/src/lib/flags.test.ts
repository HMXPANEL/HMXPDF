import { describe, expect, it } from "vitest";
import { buildFlagUpdate, flagState } from "./flags";
import type { FeatureFlag } from "./types";

const flag: FeatureFlag = {
  key: "diagnostics.enabled",
  name: "Diagnostic reports",
  description: "Lets the app offer an explicit diagnostic report.",
  enabled: false,
  rollout_percentage: 0,
  min_app_version_code: null,
  max_app_version_code: null,
  starts_at: null,
  ends_at: null,
  kill_switch: false,
  config: { note: "preserve" },
  config_version: 4,
  updated_at: "2026-10-26T05:00:00Z",
};

describe("feature flag controls", () => {
  it("uses 100% for available and 0% for off while preserving metadata", () => {
    const on = buildFlagUpdate(flag, { available: true });
    expect(on).toMatchObject({
      key: flag.key,
      name: flag.name,
      enabled: true,
      rollout_percentage: 100,
      config: flag.config,
      min_app_version_code: null,
    });

    const off = buildFlagUpdate({ ...flag, enabled: true, rollout_percentage: 100 }, {
      available: false,
    });
    expect(off.enabled).toBe(false);
    expect(off.rollout_percentage).toBe(0);
  });

  it("preserves availability when only the kill switch changes", () => {
    const on = buildFlagUpdate({ ...flag, enabled: true, rollout_percentage: 100 }, {
      killSwitch: true,
    });
    expect(on.enabled).toBe(true);
    expect(on.rollout_percentage).toBe(100);
    expect(on.kill_switch).toBe(true);
  });

  it("labels kill switch, off, available, and fractional states", () => {
    expect(flagState({ ...flag, kill_switch: true }).label).toBe("Killed");
    expect(flagState(flag).label).toBe("Off");
    expect(flagState({ ...flag, enabled: true, rollout_percentage: 100 }).label).toBe(
      "Available",
    );
    expect(flagState({ ...flag, enabled: true, rollout_percentage: 50 }).label).toBe(
      "Fractional rollout",
    );
  });
});
