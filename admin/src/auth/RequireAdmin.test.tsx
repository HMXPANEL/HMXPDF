import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AuthContext } from "../auth/context";
import { RequireAdmin } from "../auth/RequireAdmin";
import { authValue } from "../test/helpers";
import type { AuthStatus } from "../auth/context";

function renderAt(status: AuthStatus) {
  return render(
    <MemoryRouter initialEntries={["/reports"]}>
      <AuthContext.Provider value={authValue(status)}>
        <Routes>
          <Route element={<RequireAdmin />}>
            <Route path="/reports" element={<div>protected reports</div>} />
          </Route>
          <Route path="/login" element={<div>login screen</div>} />
        </Routes>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

describe("RequireAdmin route protection", () => {
  it("renders protected content only for an authorized admin", () => {
    renderAt("authorized");
    expect(screen.getByText("protected reports")).toBeInTheDocument();
    expect(screen.queryByText("login screen")).not.toBeInTheDocument();
  });

  for (const status of ["signed_out", "unauthorized", "unavailable", "config_error"] as const) {
    it(`redirects ${status} to login without rendering report data`, () => {
      renderAt(status);
      expect(screen.getByText("login screen")).toBeInTheDocument();
      expect(screen.queryByText("protected reports")).not.toBeInTheDocument();
    });
  }

  it("shows a session check state instead of protected content while loading", () => {
    renderAt("loading");
    expect(screen.getByRole("status")).toHaveTextContent("Checking your admin session");
    expect(screen.queryByText("protected reports")).not.toBeInTheDocument();
  });
});
