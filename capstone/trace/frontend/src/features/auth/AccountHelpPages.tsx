import { useState } from "react";
import { Feedback } from "../../components/Feedback";

function tokenFromLink(
  value: string,
  kind: "reset-password" | "accept-invitation",
) {
  const trimmed = value.trim();
  if (/^[A-Za-z0-9_-]{43}$/.test(trimmed)) return trimmed;
  const hash = new URL(trimmed, window.location.origin).hash;
  const match = hash.match(new RegExp(`^#${kind}/([A-Za-z0-9_-]{43})$`));
  if (!match)
    throw new Error(
      "Paste the complete link or token supplied by your administrator.",
    );
  return match[1];
}
export function AccountHelpPage({
  kind,
  signedIn = false,
}: {
  kind: "recovery" | "invitation";
  signedIn?: boolean;
}) {
  const [error, setError] = useState<unknown>(null);
  const recovery = kind === "recovery";
  return (
    <section className="auth-card">
      <div className="eyebrow">TRACE · ACCOUNT ACCESS</div>
      <h1>{recovery ? "Forgot password?" : "Join an organization"}</h1>
      <p>
        {recovery
          ? "Ask your platform administrator for a one-time password recovery link. You can open that link directly or paste it below."
          : "Ask an organization administrator for an invitation. Open the invitation link or paste it below to continue."}
      </p>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          setError(null);
          try {
            const token = tokenFromLink(
              String(new FormData(e.currentTarget).get("link")),
              recovery ? "reset-password" : "accept-invitation",
            );
            window.location.hash = `${recovery ? "reset-password" : "accept-invitation"}/${token}`;
          } catch (e) {
            setError(e);
          }
        }}
      >
        <label>
          {recovery ? "Recovery link or token" : "Invitation link or token"}
          <input name="link" autoComplete="off" required maxLength={2048} />
        </label>
        <Feedback error={error} />
        <button className="primary">
          {recovery ? "Continue to password reset" : "Continue to invitation"}
        </button>
      </form>
      <p className="auth-navigation">
        <a href={signedIn ? "#catalog" : "#login"}>
          {signedIn ? "Back to Trace" : "Back to Sign In"}
        </a>
      </p>
    </section>
  );
}
