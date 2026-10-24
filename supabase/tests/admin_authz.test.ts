import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { isAuthorizedAdmin } from "../functions/admin-api/authz.ts";

test("active admin allowlist row is authorized", () => {
  assert.equal(isAuthorizedAdmin({ role: "admin", is_active: true }), true);
});

test("inactive admin is rejected", () => {
  assert.equal(isAuthorizedAdmin({ role: "admin", is_active: false }), false);
});

test("non-admin active row is rejected", () => {
  assert.equal(isAuthorizedAdmin({ role: "readonly", is_active: true }), false);
});

test("missing allowlist row is rejected", () => {
  assert.equal(isAuthorizedAdmin(null), false);
  assert.equal(isAuthorizedAdmin(undefined), false);
});

test("admin-api no longer requires AAL2 but still verifies the user and allowlist", async () => {
  const source = await readFile(
    new URL("../functions/admin-api/index.ts", import.meta.url),
    "utf8",
  );
  assert.doesNotMatch(source, /\baal2\b|\bmfa_required\b/);
  assert.match(source, /auth\.getUser\(token\)/);
  assert.match(source, /isAuthorizedAdmin\(admin\)/);
});
