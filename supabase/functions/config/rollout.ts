export type FlagSource =
  | "global_kill_switch"
  | "flag_kill_switch"
  | "disabled"
  | "not_started"
  | "expired"
  | "version_mismatch"
  | "tester"
  | "segment"
  | "rollout"
  | "missing_install_id"
  | "default_off";

export interface FlagRow {
  key: string;
  enabled: boolean;
  rollout_percentage: number;
  min_app_version_code: number | null;
  max_app_version_code: number | null;
  starts_at: string | null;
  ends_at: string | null;
  kill_switch: boolean;
  config: Record<string, unknown>;
  config_version: number;
}

export interface FlagContext {
  installId: string | null;
  appVersionCode: number | null;
  now: Date;
  globalKillSwitch: boolean;
  testerId: string | null;
  testerSegment: string | null;
  testerFlagKeys: Set<string>;
  segmentFlagKeys: Set<string>;
}

export interface EffectiveFlag {
  enabled: boolean;
  config: Record<string, unknown>;
  version: number;
  source: FlagSource;
}

export function isValidRolloutPercentage(value: number): boolean {
  return Number.isInteger(value) && value >= 0 && value <= 100;
}

/**
 * Stable 0..9999 bucket for an installation/flag pair.
 * The install id is a random UUID generated on-device; it is not an email,
 * phone number, advertising id, or other personal identifier.
 */
export async function bucketFor(installId: string, flagKey: string): Promise<number> {
  const bytes = new TextEncoder().encode(`${installId.toLowerCase()}:${flagKey}`);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return new DataView(digest).getUint32(0, false) % 10000;
}

function off(source: FlagSource, flag?: FlagRow): EffectiveFlag {
  return { enabled: false, config: {}, version: flag?.config_version ?? 0, source };
}

function on(flag: FlagRow, source: FlagSource): EffectiveFlag {
  return {
    enabled: true,
    config: flag.config ?? {},
    version: flag.config_version,
    source,
  };
}

/**
 * Precedence:
 *   global kill switch > per-flag kill switch > disabled > schedule
 *   > app-version compatibility > explicit tester > tester segment
 *   > percentage rollout > off.
 *
 * Explicit tester/segment grants are server-side records keyed by the tester
 * token, never by a client-supplied "I am a tester" flag.
 */
export async function evaluateFlag(flag: FlagRow, ctx: FlagContext): Promise<EffectiveFlag> {
  if (ctx.globalKillSwitch) return off("global_kill_switch", flag);
  if (flag.kill_switch) return off("flag_kill_switch", flag);
  if (!flag.enabled) return off("disabled", flag);

  const now = ctx.now.getTime();
  const startsAt = flag.starts_at === null ? null : Date.parse(flag.starts_at);
  const endsAt = flag.ends_at === null ? null : Date.parse(flag.ends_at);
  if (startsAt !== null && (!Number.isFinite(startsAt) || now < startsAt)) {
    return off("not_started", flag);
  }
  if (endsAt !== null && (!Number.isFinite(endsAt) || now >= endsAt)) {
    return off("expired", flag);
  }

  if (flag.min_app_version_code !== null) {
    if (ctx.appVersionCode === null || ctx.appVersionCode < flag.min_app_version_code) {
      return off("version_mismatch", flag);
    }
  }
  if (flag.max_app_version_code !== null) {
    if (ctx.appVersionCode === null || ctx.appVersionCode > flag.max_app_version_code) {
      return off("version_mismatch", flag);
    }
  }

  if (ctx.testerId !== null && ctx.testerFlagKeys.has(flag.key)) {
    return on(flag, "tester");
  }
  if (ctx.testerSegment !== null && ctx.segmentFlagKeys.has(flag.key)) {
    return on(flag, "segment");
  }

  if (!isValidRolloutPercentage(flag.rollout_percentage)) return off("default_off", flag);
  if (flag.rollout_percentage === 0) return off("rollout", flag);
  if (flag.rollout_percentage === 100) return on(flag, "rollout");
  if (ctx.installId === null || ctx.installId.length === 0) return off("missing_install_id", flag);

  const bucket = await bucketFor(ctx.installId, flag.key);
  return bucket < flag.rollout_percentage * 100 ? on(flag, "rollout") : off("rollout", flag);
}

export async function evaluateFlags(
  flags: FlagRow[],
  ctx: FlagContext,
): Promise<Record<string, EffectiveFlag>> {
  const result: Record<string, EffectiveFlag> = {};
  for (const flag of flags) {
    result[flag.key] = await evaluateFlag(flag, ctx);
  }
  return result;
}

/** Stable revision over the effective response, so clients can detect changes. */
export async function revisionFor(
  effective: Record<string, EffectiveFlag>,
  globalKillSwitch = false,
): Promise<string> {
  const ordered = Object.keys(effective)
    .sort()
    .map((key) => [key, effective[key]]);
  const bytes = new TextEncoder().encode(JSON.stringify([globalKillSwitch, ordered]));
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
