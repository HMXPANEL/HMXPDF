import { afterEach, describe, expect, it, vi } from "vitest";
import { AdminApi, AdminApiError, describeError } from "./api";

function makeApi(overrides: Partial<ConstructorParameters<typeof AdminApi>[0]> = {}) {
  return new AdminApi({
    url: "https://staging.example/functions/v1/admin-api",
    publishableKey: "public-key",
    getAccessToken: async () => "access-token",
    ...overrides,
  });
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("AdminApi", () => {
  it("sends the action and bearer token and returns confirmed data", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ ok: true, data: { total: 3 } }));
    vi.stubGlobal("fetch", fetchMock);

    const data = await makeApi().request<{ total: number }>("reports.stats");

    expect(data).toEqual({ total: 3 });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain("/functions/v1/admin-api");
    expect(init.method).toBe("POST");
    expect((init.headers as Record<string, string>).authorization).toBe("Bearer access-token");
    expect((init.headers as Record<string, string>).apikey).toBe("public-key");
    expect(JSON.parse(String(init.body))).toEqual({ action: "reports.stats" });
  });

  it("fails closed on a backend error and preserves the error code", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse({ error: "forbidden" }, 403)));

    await expect(makeApi().request("reports.list")).rejects.toMatchObject({
      status: 403,
      code: "forbidden",
    });
  });

  it("rejects a success-shaped response without data", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse({ ok: true })));

    await expect(makeApi().request("reports.list")).rejects.toMatchObject({
      status: 502,
      code: "invalid_response",
    });
  });

  it("calls onUnauthorized and rejects on 401", async () => {
    const onUnauthorized = vi.fn();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse({ error: "unauthorized" }, 401)));

    await expect(makeApi({ onUnauthorized }).request("reports.list")).rejects.toMatchObject({
      status: 401,
    });
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  it("does not call fetch without a session", async () => {
    const onUnauthorized = vi.fn();
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      makeApi({ getAccessToken: async () => null, onUnauthorized }).request("reports.list"),
    ).rejects.toBeInstanceOf(AdminApiError);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  it("maps network and timeout failures to stable codes", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("offline")));
    await expect(makeApi().request("reports.list")).rejects.toMatchObject({
      code: "network_error",
    });

    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new DOMException("aborted", "AbortError")),
    );
    await expect(makeApi().request("reports.list")).rejects.toMatchObject({ code: "timeout" });
  });

  it("describes ADMIN_ORIGIN and origin failures without leaking details", () => {
    expect(describeError(new AdminApiError(503, "admin_origin_not_configured"))).toContain(
      "ADMIN_ORIGIN",
    );
    expect(describeError(new AdminApiError(403, "origin_not_allowed"))).toContain(
      "origin",
    );
  });
});
