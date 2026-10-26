import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { AuthContext } from "../auth/context";
import { ReportsPage } from "./ReportsPage";
import type { AdminApi } from "../lib/api";
import type { ReportListItem } from "../lib/types";
import { authValue } from "../test/helpers";

function row(index: number): ReportListItem {
  return {
    id: `11111111-1111-4111-8111-${String(index).padStart(12, "0")}`,
    kind: "problem",
    category: "bug",
    status: "received",
    app_version_code: 6,
    app_version_name: "2.1.1",
    android_api: 35,
    client_created_at: "2026-10-26T04:00:00Z",
    received_at: `2026-10-26T04:${String(index).padStart(2, "0")}:00Z`,
    updated_at: "2026-10-26T04:01:00Z",
  };
}

describe("ReportsPage", () => {
  it("paginates with the backend before cursor", async () => {
    const rows = Array.from({ length: 25 }, (_, index) => row(index));
    const request = vi.fn((action: string, body: Record<string, unknown>) => {
      if (action !== "reports.list") return Promise.resolve({});
      return Promise.resolve(body.before ? [] : rows);
    });
    const api = { request } as unknown as AdminApi;
    const user = userEvent.setup();

    render(
      <MemoryRouter>
        <AuthContext.Provider value={authValue("authorized", api)}>
          <ReportsPage />
        </AuthContext.Provider>
      </MemoryRouter>,
    );

    await screen.findByText("Report list");
    await user.click(screen.getByRole("button", { name: "Older" }));

    await waitFor(() => {
      expect(
        request.mock.calls.some(
          ([action, body]) =>
            action === "reports.list" && body.before === rows[24].received_at,
        ),
      ).toBe(true);
    });
  });

  it("sends the selected kind filter to the backend", async () => {
    const request = vi.fn().mockResolvedValue([]);
    const api = { request } as unknown as AdminApi;
    const user = userEvent.setup();

    render(
      <MemoryRouter>
        <AuthContext.Provider value={authValue("authorized", api)}>
          <ReportsPage />
        </AuthContext.Provider>
      </MemoryRouter>,
    );

    await user.selectOptions(screen.getByLabelText("Kind"), "problem");
    await waitFor(() => {
      expect(
        request.mock.calls.some(
          ([action, body]) => action === "reports.list" && body.kind === "problem",
        ),
      ).toBe(true);
    });
  });
});
