import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { AuthContext } from "../auth/context";
import { AuditPage } from "./AuditPage";
import type { AdminApi } from "../lib/api";
import type { AuditRow } from "../lib/types";
import { authValue } from "../test/helpers";

const row: AuditRow = {
  id: 7,
  admin_user_id: "22222222-2222-4222-8222-222222222222",
  action: "report.update",
  target_type: "report",
  target_id: "11111111-1111-4111-8111-111111111111",
  metadata: { changed_fields: ["status"] },
  created_at: "2026-10-26T05:00:00Z",
};

describe("AuditPage", () => {
  it("renders authorized audit rows without an edit or delete control", async () => {
    const request = vi.fn().mockResolvedValue([row]);
    const api = { request } as unknown as AdminApi;

    render(
      <MemoryRouter>
        <AuthContext.Provider value={authValue("authorized", api)}>
          <AuditPage />
        </AuthContext.Provider>
      </MemoryRouter>,
    );

    expect(await screen.findByText("report.update")).toBeInTheDocument();
    expect(screen.getByText(/Read-only history/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /delete/i })).not.toBeInTheDocument();
    expect(request).toHaveBeenCalledWith("audit.list", { limit: 50 });
  });

  it("renders an empty state when no audit rows exist", async () => {
    const request = vi.fn().mockResolvedValue([]);
    const api = { request } as unknown as AdminApi;

    render(
      <MemoryRouter>
        <AuthContext.Provider value={authValue("authorized", api)}>
          <AuditPage />
        </AuthContext.Provider>
      </MemoryRouter>,
    );

    expect(await screen.findByText("No audit rows")).toBeInTheDocument();
  });
});
