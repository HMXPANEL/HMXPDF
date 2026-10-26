import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AuthContext } from "../auth/context";
import { LoginPage } from "./LoginPage";
import { authValue } from "../test/helpers";

function renderLogin(status: Parameters<typeof authValue>[0], message: string | null = null) {
  return render(
    <MemoryRouter>
      <AuthContext.Provider value={authValue(status, null, message)}>
        <LoginPage />
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

describe("LoginPage", () => {
  it("shows an explicit unauthorized state after a backend 403", () => {
    renderLogin("unauthorized", "Not an admin");
    expect(screen.getByRole("heading", { name: "Not authorized" })).toBeInTheDocument();
    expect(screen.queryByLabelText("Password")).not.toBeInTheDocument();
  });

  it("shows the deployment configuration state without a login form", () => {
    renderLogin("config_error");
    expect(
      screen.getByRole("heading", { name: "Deployment configuration required" }),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText("Password")).not.toBeInTheDocument();
  });

  it("renders the sign-in form only for a signed-out visitor", () => {
    renderLogin("signed_out");
    expect(screen.getByRole("heading", { name: "Admin sign in" })).toBeInTheDocument();
    expect(screen.getByLabelText("Email")).toBeInTheDocument();
    expect(screen.getByLabelText("Password")).toBeInTheDocument();
  });
});
