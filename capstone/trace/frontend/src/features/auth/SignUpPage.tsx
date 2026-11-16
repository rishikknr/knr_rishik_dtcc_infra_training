import { useId, useRef, useState } from "react";
import { authApi, ApiError } from "../../services/api";
import type { RegistrationOptions } from "../../types/api";
import { Feedback } from "../../components/Feedback";
import { PasswordField } from "./PasswordField";

export function SignUpPage({
  options,
  onCreated,
  availabilityError,
}: {
  options: RegistrationOptions | null;
  availabilityError?: unknown;
  onCreated: (message: string) => void;
}) {
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const submitting = useRef(false);
  const guidanceId = useId(),
    matchId = useId();
  if (!options)
    return (
      <section className="auth-card">
        {availabilityError ? (
          <>
            <h1>Sign up could not be loaded</h1>
            <Feedback error={availabilityError} />
            <p>Reload the page to try again.</p>
          </>
        ) : (
          <p role="status">Checking account availability…</p>
        )}
        <a href="#login">Back to Sign In</a>
      </section>
    );
  if (!options.enabled)
    return (
      <section className="auth-card">
        <div className="eyebrow">TRACE · ACCOUNT ACCESS</div>
        <h1>Sign up is unavailable</h1>
        <p>
          Contact your administrator or join an organization through an
          invitation.
        </p>
        <div className="auth-links">
          <a href="#login">Sign In</a>
          <a href="#join-organization">Accept an invitation</a>
        </div>
      </section>
    );
  const bytes = new TextEncoder().encode(password).length;
  const validLength =
    password.length >= options.minimumPasswordLength &&
    bytes <= options.maximumPasswordBytes;
  const mismatch = confirmation.length > 0 && password !== confirmation;
  const strength = !password
    ? "Choose a memorable passphrase."
    : bytes > options.maximumPasswordBytes
      ? "This password exceeds the maximum size."
      : !validLength
        ? "Add more characters to meet the password policy."
        : password.length >= 20
          ? "Long passphrase · meets the password policy."
          : "Meets the password policy. A longer passphrase is stronger.";
  return (
    <section className="auth-card">
      <div className="eyebrow">TRACE · METADATA REGISTRY</div>
      <h1>Create your account</h1>
      <p>
        Set up your identity, then join an organization through an invitation.
      </p>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          if (submitting.current) return;
          setError(null);
          if (!validLength) {
            setError(
              new ApiError(
                `Use at least ${options.minimumPasswordLength} characters and at most ${options.maximumPasswordBytes} UTF-8 bytes.`,
              ),
            );
            return;
          }
          if (password !== confirmation) {
            setError(new ApiError("Passwords do not match."));
            return;
          }
          const data = new FormData(e.currentTarget);
          submitting.current = true;
          setBusy(true);
          try {
            const response = await authApi.register({
              displayName: String(data.get("displayName")),
              email: String(data.get("email")),
              password,
              confirmPassword: confirmation,
            });
            setPassword("");
            setConfirmation("");
            onCreated(response.message);
          } catch (e) {
            setError(e);
          } finally {
            submitting.current = false;
            setBusy(false);
          }
        }}
      >
        <label>
          Full Name
          <input
            name="displayName"
            autoComplete="name"
            required
            maxLength={150}
            disabled={busy}
          />
        </label>
        <label>
          Email
          <input
            name="email"
            type="email"
            autoComplete="email"
            required
            maxLength={320}
            disabled={busy}
          />
        </label>
        <PasswordField
          label="Set Password"
          name="password"
          autoComplete="new-password"
          minimumLength={options.minimumPasswordLength}
          value={password}
          onChange={setPassword}
          describedBy={guidanceId}
        />
        <div className="password-guidance" id={guidanceId}>
          <p>
            At least {options.minimumPasswordLength} characters; at most{" "}
            {options.maximumPasswordBytes} UTF-8 bytes. Longer passphrases are
            stronger.
          </p>
          <p role="status">{strength}</p>
        </div>
        <PasswordField
          label="Confirm Password"
          name="confirmPassword"
          autoComplete="new-password"
          minimumLength={options.minimumPasswordLength}
          value={confirmation}
          onChange={setConfirmation}
          describedBy={matchId}
          invalid={mismatch}
        />
        <p
          className={mismatch ? "field-error" : "field-hint"}
          id={matchId}
          aria-live="polite"
        >
          {confirmation
            ? mismatch
              ? "Passwords do not match."
              : "Passwords match."
            : "Re-enter your password to confirm it."}
        </p>
        <Feedback error={error} />
        <button
          className="primary"
          disabled={busy || !validLength || !confirmation || mismatch}
        >
          {busy ? "Creating account…" : "Create Account"}
        </button>
      </form>
      <p className="auth-navigation">
        Already have an account? <a href="#login">Sign In</a>
      </p>
    </section>
  );
}
