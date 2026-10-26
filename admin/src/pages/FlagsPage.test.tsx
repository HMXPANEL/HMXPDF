import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { AuthContext } from "../auth/context";
import { FlagsPage } from "./FlagsPage";
import type { AdminApi } from "../lib/api";
import type { FeatureFlag } from "../lib/types";
import { authValue } from "../test/helpers";

const flag: FeatureFlag = {
  key: "diagnostics.enabled",
  name: "Diagnostic reports",
  description: "Explicit diagnostic reports.",
  enabled: false,
  rollout_percentage: 0,
  min_app_version_code: null,
  max_app_version_code: null,
  starts_at: null,
  ends_at: null,
  kill_switch: false,
  config: { note: "keep" },
  config_version: 4,
  updated_at: "2026-10-26T05:00:00Z",
};

describe("FlagsPage", () => {
  it("confirms before sending a 0%/100% availability change", async () => {
    const request = vi.fn((action: string, body: Record<string, unknown>) => {
      if (action === "flags.list") return Promise.resolve([flag]);
      if (action === "flags.upsert") {
        return Promise.resolve({
          ...(body.flag as FeatureFlag),
          config_version: 5,
          updated_at: "2026-10-26T05:05:00Z",
        });
      }
      return Promise.resolve({});
    });
    const api = { request } as unknown as AdminApi;
    const user = userEvent.setup();

    render(
      <MemoryRouter>
        <AuthContext.Provider value={authValue("authorized", api)}>
          <FlagsPage />
        </AuthContext.Provider>
      </MemoryRouter>,
    );

    await user.click(await screen.findByRole("button", { name: "Make available" }));
    expect(screen.getByRole("dialog")).toHaveTextContent("Make this flag available?");
    await user.click(screen.getByRole("button", { name: "Apply change" }));

    await waitFor(() => {
      const call = request.mock.calls.find(([action]) => action === "flags.upsert");
      expect(call).toBeDefined();
      expect(call?.[1]).toMatchObject({
        flag: {
          key: "diagnostics.enabled",
          enabled: true,
          rollout_percentage: 100,
          kill_switch: false,
          config: { note: "keep" },
        },
      });
    });
    expect(await screen.findByText(/server confirmed/i)).toBeInTheDocument();
  });
});
