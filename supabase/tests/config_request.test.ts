import assert from "node:assert/strict";
import test from "node:test";
import { parseConfigRequest } from "../functions/config/request.ts";

test("config request accepts only understood keys and version code", () => {
  const result = parseConfigRequest({
    keys: ["diagnostics.enabled", "problem_reports.enabled"],
    app_version_code: 6,
  });
  assert.deepEqual(result, {
    ok: true,
    value: {
      keys: ["diagnostics.enabled", "problem_reports.enabled"],
      appVersionCode: 6,
      testerToken: null,
    },
  });
});

test("config request rejects install_id and other persistent identifiers", () => {
  const base = { keys: ["diagnostics.enabled"] };
  assert.deepEqual(parseConfigRequest({ ...base, install_id: "uuid" }), {
    ok: false,
    error: "install_id_not_allowed",
  });
  assert.deepEqual(parseConfigRequest({ ...base, installId: "uuid" }), {
    ok: false,
    error: "unknown_field",
  });
  for (const field of ["device_model", "ANDROID_ID", "advertising_id", "document_id"]) {
    assert.deepEqual(parseConfigRequest({ ...base, [field]: "value" }), {
      ok: false,
      error: "unknown_field",
    });
  }
});

test("config request deduplicates keys and validates key syntax", () => {
  const result = parseConfigRequest({
    keys: ["diagnostics.enabled", "diagnostics.enabled", "problem_reports.enabled"],
  });
  assert.equal(result.ok, true);
  if (result.ok) {
    assert.deepEqual(result.value.keys, [
      "diagnostics.enabled",
      "problem_reports.enabled",
    ]);
  }

  for (const keys of [
    [],
    ["Bad Key"],
    ["a"],
    Array.from({ length: 51 }, (_, i) => `flag.key.${i}`),
    ["diagnostics.enabled", 1],
    "diagnostics.enabled",
  ]) {
    assert.deepEqual(parseConfigRequest({ keys }), {
      ok: false,
      error: "invalid_keys",
    });
  }
});

test("config request validates the optional app version code", () => {
  for (const value of [undefined, null, 0, 2_100_000_000]) {
    const result = parseConfigRequest({ keys: ["diagnostics.enabled"], app_version_code: value });
    assert.equal(result.ok, true);
  }
  for (const value of [-1, 2_100_000_001, 1.5, "6", Number.NaN]) {
    assert.deepEqual(
      parseConfigRequest({ keys: ["diagnostics.enabled"], app_version_code: value }),
      { ok: false, error: "invalid_app_version_code" },
    );
  }
});

test("config request keeps the backend-only tester token path", () => {
  const token = "a".repeat(64);
  const result = parseConfigRequest({
    keys: ["diagnostics.enabled"],
    tester_token: token,
  });
  assert.equal(result.ok, true);
  if (result.ok) assert.equal(result.value.testerToken, token);

  for (const value of ["short", "g".repeat(64), 123]) {
    assert.deepEqual(
      parseConfigRequest({ keys: ["diagnostics.enabled"], tester_token: value }),
      { ok: false, error: "invalid_tester_token" },
    );
  }
});

test("config request rejects non-object bodies", () => {
  for (const body of [null, undefined, [], "config", 1, true]) {
    assert.deepEqual(parseConfigRequest(body), { ok: false, error: "invalid_body" });
  }
});
