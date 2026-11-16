import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { SignInPage } from "../src/features/auth/AuthPages";
import { SignUpPage } from "../src/features/auth/SignUpPage";
const options = {
  enabled: true,
  minimumPasswordLength: 12,
  maximumPasswordBytes: 72,
};
afterEach(() => vi.unstubAllGlobals());
async function fillSignup(
  password = "A synthetic test password!",
  confirmation = password,
) {
  await userEvent.type(screen.getByLabelText("Full Name"), "Test User");
  await userEvent.type(screen.getByLabelText("Email"), "user@example.test");
  await userEvent.type(screen.getByLabelText("Set Password"), password);
  await userEvent.type(screen.getByLabelText("Confirm Password"), confirmation);
}
describe("identity feature interactions", () => {
  it("failed login stays on the sign-in form and shows the safe server error", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(
          new Response('{"headerName":"X-CSRF-TOKEN","token":"test"}'),
        )
        .mockResolvedValueOnce(
          new Response('{"message":"Incorrect email or password."}', {
            status: 401,
          }),
        ),
    );
    const done = vi.fn();
    render(<SignInPage onSignedIn={done} />);
    await userEvent.type(
      screen.getByLabelText("Email"),
      "unknown@example.test",
    );
    await userEvent.type(
      screen.getByLabelText("Password"),
      "Incorrect password",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Sign In", exact: true }),
    );
    expect((await screen.findByRole("alert")).textContent).toContain(
      "Incorrect email or password.",
    );
    expect(done).not.toHaveBeenCalled();
  });
  it("successful login reloads the server session", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(
          new Response('{"headerName":"X-CSRF-TOKEN","token":"test"}'),
        )
        .mockResolvedValueOnce(new Response('{"authenticated":true}')),
    );
    const done = vi.fn();
    render(<SignInPage onSignedIn={done} />);
    await userEvent.type(screen.getByLabelText("Email"), "user@example.test");
    await userEvent.type(screen.getByLabelText("Password"), "Test password!");
    await userEvent.click(
      screen.getByRole("button", { name: "Sign In", exact: true }),
    );
    await waitFor(() => expect(done).toHaveBeenCalledOnce());
  });
  it("signup loading, availability errors and disabled mode are explicit", () => {
    const view = render(<SignUpPage options={null} onCreated={vi.fn()} />);
    expect(screen.getByRole("status").textContent).toContain("Checking");
    view.rerender(
      <SignUpPage
        options={null}
        availabilityError={new Error("Backend unavailable")}
        onCreated={vi.fn()}
      />,
    );
    expect(screen.getByRole("alert").textContent).toContain(
      "Backend unavailable",
    );
    view.rerender(
      <SignUpPage
        options={{ ...options, enabled: false }}
        onCreated={vi.fn()}
      />,
    );
    expect(screen.getByRole("heading").textContent).toBe(
      "Sign up is unavailable",
    );
    expect(screen.queryByLabelText("Email")).toBeNull();
  });
  it("password mismatch prevents account creation", async () => {
    render(<SignUpPage options={options} onCreated={vi.fn()} />);
    await fillSignup("Synthetic password one", "Synthetic password two");
    expect(
      (
        screen.getByRole("button", {
          name: "Create Account",
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
    expect(
      screen.getByLabelText("Confirm Password").getAttribute("aria-invalid"),
    ).toBe("true");
  });
  it("multibyte passwords respect the server's UTF-8 byte limit", async () => {
    render(<SignUpPage options={options} onCreated={vi.fn()} />);
    await fillSignup("界".repeat(25));
    expect(
      (
        screen.getByRole("button", {
          name: "Create Account",
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
    expect(
      screen.getByText("This password exceeds the maximum size."),
    ).toBeTruthy();
  });
  it("registration reports actual completion and clears password inputs", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(
        new Response('{"headerName":"X-CSRF-TOKEN","token":"test"}'),
      )
      .mockResolvedValueOnce(
        new Response('{"message":"Account created. Sign in."}'),
      );
    vi.stubGlobal("fetch", fetch);
    const created = vi.fn();
    render(<SignUpPage options={options} onCreated={created} />);
    await fillSignup();
    await userEvent.click(
      screen.getByRole("button", { name: "Create Account" }),
    );
    await waitFor(() =>
      expect(created).toHaveBeenCalledWith("Account created. Sign in."),
    );
    expect(
      (screen.getByLabelText("Set Password") as HTMLInputElement).value,
    ).toBe("");
  });
});
