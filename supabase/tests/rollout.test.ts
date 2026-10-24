import assert from "node:assert/strict";
import test from "node:test";
import {
  bucketFor,
  evaluateFlag,
  evaluateFlags,
  isValidRolloutPercentage,
  revisionFor,
  type FlagContext,
  type FlagRow,
} from "../functions/config/rollout.ts";

function flag(overrides: Partial<FlagRow> = {}): FlagRow {
  return {
    key: "feature.test",
    enabled: true,
    rollout_percentage: 0,
    min_app_version_code: null,
    max_app_version_code: null,
    starts_at: null,
    ends_at: null,
    kill_switch: false,
    config: { mode: "safe" },
    config_version: 1,
    ...overrides,
  };
}

function context(overrides: Partial<FlagContext> = {}): FlagContext {
  return {
    installId: "00000000-0000-4000-8000-000000000001",
    appVersionCode: 100,
    now: new Date("2026-10-24T12:00:00Z"),
    globalKillSwitch: false,
    testerId: null,
    testerSegment: null,
    testerFlagKeys: new Set(),
    segmentFlagKeys: new Set(),
    ...overrides,
  };
}

function installId(index: number): string {
  return `00000000-0000-4000-8000-${index.toString(16).padStart(12, "0")}`;
}

test("rollout percentage validation accepts only integers from 0 to 100", () => {
  for (const value of [0, 1, 5, 50, 100]) assert.equal(isValidRolloutPercentage(value), true);
  for (const value of [-1, 101, 1.5, Number.NaN, Number.POSITIVE_INFINITY]) {
    assert.equal(isValidRolloutPercentage(value), false);
  }
});

test("bucket assignment is deterministic and ignores install-id casing", async () => {
  const a = await bucketFor("00000000-0000-4000-8000-0000000000aa", "feature.test");
  const b = await bucketFor("00000000-0000-4000-8000-0000000000aa", "feature.test");
  const c = await bucketFor("00000000-0000-4000-8000-0000000000AA", "feature.test");
  assert.equal(a, b);
  assert.equal(a, c);
  assert.ok(a >= 0 && a < 10000);
});

test("0% is off and 100% is on without an install id", async () => {
  const off = await evaluateFlag(flag({ rollout_percentage: 0 }), context());
  assert.equal(off.enabled, false);
  assert.equal(off.source, "rollout");

  const on = await evaluateFlag(
    flag({ rollout_percentage: 100 }),
    context({ installId: null }),
  );
  assert.equal(on.enabled, true);
  assert.equal(on.source, "rollout");
  assert.deepEqual(on.config, { mode: "safe" });
});

test("percentage rollout matches the stable bucket at 1%, 5% and 50%", async () => {
  for (const percentage of [1, 5, 50]) {
    for (let i = 0; i < 200; i++) {
      const id = installId(i);
      const bucket = await bucketFor(id, "feature.test");
      const result = await evaluateFlag(
        flag({ rollout_percentage: percentage }),
        context({ installId: id }),
      );
      assert.equal(
        result.enabled,
        bucket < percentage * 100,
        `percentage=${percentage} id=${id} bucket=${bucket}`,
      );
    }
  }
});

test("50% cohort is reasonably distributed across fixed ids", async () => {
  let enabled = 0;
  for (let i = 0; i < 1000; i++) {
    const result = await evaluateFlag(
      flag({ rollout_percentage: 50 }),
      context({ installId: installId(i) }),
    );
    if (result.enabled) enabled++;
  }
  assert.ok(enabled > 400 && enabled < 600, `unexpected 50% cohort size: ${enabled}`);
});

test("missing install id disables fractional rollout", async () => {
  const result = await evaluateFlag(
    flag({ rollout_percentage: 50 }),
    context({ installId: null }),
  );
  assert.equal(result.enabled, false);
  assert.equal(result.source, "missing_install_id");
});

test("invalid rollout percentage fails closed", async () => {
  const result = await evaluateFlag(
    flag({ rollout_percentage: 101 }),
    context({ installId: installId(1) }),
  );
  assert.equal(result.enabled, false);
  assert.equal(result.source, "default_off");
});

test("global and per-flag kill switches override testers and 100% rollout", async () => {
  const tester = context({
    testerId: "tester-1",
    testerFlagKeys: new Set(["feature.test"]),
    segmentFlagKeys: new Set(["feature.test"]),
  });

  const global = await evaluateFlag(
    flag({ rollout_percentage: 100 }),
    { ...tester, globalKillSwitch: true },
  );
  assert.equal(global.enabled, false);
  assert.equal(global.source, "global_kill_switch");

  const perFlag = await evaluateFlag(
    flag({ rollout_percentage: 100, kill_switch: true }),
    tester,
  );
  assert.equal(perFlag.enabled, false);
  assert.equal(perFlag.source, "flag_kill_switch");
});

test("disabled flag overrides tester targeting", async () => {
  const result = await evaluateFlag(
    flag({ enabled: false }),
    context({
      testerId: "tester-1",
      testerFlagKeys: new Set(["feature.test"]),
    }),
  );
  assert.equal(result.enabled, false);
  assert.equal(result.source, "disabled");
});

test("schedule gates override tester targeting", async () => {
  const tester = context({
    testerId: "tester-1",
    testerFlagKeys: new Set(["feature.test"]),
  });

  const notStarted = await evaluateFlag(
    flag({ starts_at: "2026-10-25T00:00:00Z" }),
    tester,
  );
  assert.equal(notStarted.enabled, false);
  assert.equal(notStarted.source, "not_started");

  const expired = await evaluateFlag(
    flag({ ends_at: "2026-10-24T12:00:00Z" }),
    tester,
  );
  assert.equal(expired.enabled, false);
  assert.equal(expired.source, "expired");
});

test("malformed schedule timestamps fail closed", async () => {
  const started = await evaluateFlag(flag({ starts_at: "not-a-date" }), context());
  assert.equal(started.source, "not_started");
  const ended = await evaluateFlag(flag({ ends_at: "not-a-date" }), context());
  assert.equal(ended.source, "expired");
});

test("app-version gates override tester targeting", async () => {
  const tester = context({
    appVersionCode: 99,
    testerId: "tester-1",
    testerFlagKeys: new Set(["feature.test"]),
  });

  const tooOld = await evaluateFlag(flag({ min_app_version_code: 100 }), tester);
  assert.equal(tooOld.enabled, false);
  assert.equal(tooOld.source, "version_mismatch");

  const tooNew = await evaluateFlag(
    flag({ max_app_version_code: 50 }),
    context({ appVersionCode: 99 }),
  );
  assert.equal(tooNew.enabled, false);
  assert.equal(tooNew.source, "version_mismatch");

  const unknownVersion = await evaluateFlag(
    flag({ min_app_version_code: 1 }),
    context({ appVersionCode: null }),
  );
  assert.equal(unknownVersion.enabled, false);
  assert.equal(unknownVersion.source, "version_mismatch");
});

test("explicit tester grants enable a flag at 0%", async () => {
  const result = await evaluateFlag(
    flag({ rollout_percentage: 0 }),
    context({
      testerId: "tester-1",
      testerFlagKeys: new Set(["feature.test"]),
    }),
  );
  assert.equal(result.enabled, true);
  assert.equal(result.source, "tester");
});

test("segment grants enable a flag and explicit tester wins", async () => {
  const segment = await evaluateFlag(
    flag({ rollout_percentage: 0 }),
    context({
      testerId: "tester-1",
      testerSegment: "beta",
      segmentFlagKeys: new Set(["feature.test"]),
    }),
  );
  assert.equal(segment.enabled, true);
  assert.equal(segment.source, "segment");

  const explicit = await evaluateFlag(
    flag({ rollout_percentage: 0 }),
    context({
      testerId: "tester-1",
      testerSegment: "beta",
      testerFlagKeys: new Set(["feature.test"]),
      segmentFlagKeys: new Set(["feature.test"]),
    }),
  );
  assert.equal(explicit.source, "tester");
});

test("a tester grant for another flag does not leak across flags", async () => {
  const result = await evaluateFlag(
    flag({ key: "feature.other", rollout_percentage: 0 }),
    context({
      testerId: "tester-1",
      testerFlagKeys: new Set(["feature.test"]),
    }),
  );
  assert.equal(result.enabled, false);
});

test("unknown flags are absent and therefore off", async () => {
  const result = await evaluateFlags([], context());
  assert.deepEqual(result, {});
});

test("evaluateFlags preserves per-flag results", async () => {
  const result = await evaluateFlags(
    [
      flag({ key: "feature.a", rollout_percentage: 100 }),
      flag({ key: "feature.b", rollout_percentage: 0 }),
    ],
    context(),
  );
  assert.equal(result["feature.a"].enabled, true);
  assert.equal(result["feature.b"].enabled, false);
});

test("revision is stable and changes with kill switch or flag state", async () => {
  const first = await evaluateFlags([flag({ rollout_percentage: 100 })], context());
  const second = await evaluateFlags([flag({ rollout_percentage: 100 })], context());
  assert.equal(await revisionFor(first), await revisionFor(second));
  assert.notEqual(await revisionFor(first), await revisionFor(first, true));

  const changed = await evaluateFlags(
    [flag({ rollout_percentage: 100, config_version: 2, config: { mode: "new" } })],
    context(),
  );
  assert.notEqual(await revisionFor(first), await revisionFor(changed));
});
