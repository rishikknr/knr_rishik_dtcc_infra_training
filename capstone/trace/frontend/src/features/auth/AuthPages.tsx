import { useState } from "react";
import { mutate, request } from "../../services/api";
import type { Session } from "../../types/api";
import { EmptyState, PageHeader, Status } from "../../components/Design";
import { Feedback, Success } from "../../components/Feedback";
import { PasswordField } from "./PasswordField";

export function SignInPage({
  onSignedIn,
  signupEnabled = false,
  notice,
}: {
  onSignedIn: () => Promise<void>;
  signupEnabled?: boolean;
  notice?: string;
}) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(null);
  return (
    <section className="auth-card">
      <div className="eyebrow">TRACE · METADATA REGISTRY</div>
      <h1>Sign in to Trace</h1>
      <p>Use your work account to access your organizations.</p>
      {notice && (
        <p className="notice" role="status">
          {notice}
        </p>
      )}
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          const data = new FormData(e.currentTarget);
          setBusy(true);
          setError(null);
          try {
            await request("/auth/login", {
              method: "POST",
              headers: { "Content-Type": "application/x-www-form-urlencoded" },
              body: new URLSearchParams({
                email: String(data.get("email")),
                password: String(data.get("password")),
              }),
            });
            await onSignedIn();
          } catch (e) {
            setError(e);
          } finally {
            setBusy(false);
          }
        }}
      >
        <label>
          Email
          <input
            name="email"
            type="email"
            autoComplete="username"
            required
            maxLength={320}
          />
        </label>
        <PasswordField
          label="Password"
          name="password"
          autoComplete="current-password"
        />
        <a className="forgot-password" href="#forgot-password">
          Forgot password?
        </a>
        <Feedback error={error} />
        <button className="primary" disabled={busy}>
          {busy ? "Signing in…" : "Sign In"}
        </button>
      </form>
      {signupEnabled && (
        <p className="auth-navigation">
          Don't have an account? <a href="#signup">Sign Up</a>
        </p>
      )}
      <p className="auth-navigation">
        <a href="#join-organization">Accept an organization invitation</a>
      </p>
    </section>
  );
}

export function InvitationPage({
  token,
  session,
  reload,
}: {
  token: string;
  session: Session | null;
  reload: () => Promise<void>;
}) {
  const [existing, setExisting] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(null);
  return (
    <section className="auth-card">
      <div className="eyebrow">TRACE · INVITATION</div>
      <h1>Join your organization</h1>
      <p>
        This invitation activates only the email identity chosen by your
        administrator.
      </p>
      {session ? (
        <p>
          Accept as <strong>{session.user.email}</strong>. If this is a
          different identity, sign out first.
        </p>
      ) : (
        <>
          <div className="actions">
            <button onClick={() => setExisting(false)}>
              Create invited account
            </button>
            <button onClick={() => setExisting(true)}>
              I already have an account
            </button>
          </div>
          {existing && <SignInPage onSignedIn={reload} />}
        </>
      )}
      {(session || !existing) && (
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            const data = new FormData(e.currentTarget);
            setBusy(true);
            setError(null);
            try {
              await mutate("/auth/invitations/accept", {
                token,
                displayName: data.get("displayName"),
                password: data.get("password"),
              });
              window.location.hash = "catalog";
              await reload();
            } catch (e) {
              setError(e);
            } finally {
              setBusy(false);
            }
          }}
        >
          {!session && (
            <>
              <label>
                Display name
                <input
                  name="displayName"
                  required
                  maxLength={150}
                  autoComplete="name"
                />
              </label>
              <label>
                Create password
                <input
                  name="password"
                  type="password"
                  required
                  minLength={12}
                  maxLength={72}
                  autoComplete="new-password"
                />
              </label>
              <p className="muted">
                At least 12 characters; at most 72 UTF-8 bytes.
              </p>
            </>
          )}
          <Feedback error={error} />
          <button className="primary" disabled={busy}>
            {busy ? "Accepting…" : "Accept invitation"}
          </button>
        </form>
      )}
    </section>
  );
}

export function ResetPage({
  token,
  onDone,
}: {
  token: string;
  onDone: () => Promise<void>;
}) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(null),
    [done, setDone] = useState(false);
  return (
    <section className="auth-card">
      <h1>Reset password</h1>
      {done ? (
        <>
          <p>Password changed. Previous sessions have been revoked.</p>
          <a href="#login">Return to sign in</a>
        </>
      ) : (
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            const data = new FormData(e.currentTarget);
            setBusy(true);
            setError(null);
            try {
              await mutate("/auth/password/reset", {
                token,
                newPassword: data.get("password"),
              });
              setDone(true);
              await onDone();
            } catch (e) {
              setError(e);
            } finally {
              setBusy(false);
            }
          }}
        >
          <label>
            New password
            <input
              name="password"
              type="password"
              autoComplete="new-password"
              minLength={12}
              maxLength={72}
              required
            />
          </label>
          <Feedback error={error} />
          <button className="primary" disabled={busy}>
            Reset password
          </button>
        </form>
      )}
    </section>
  );
}

export function ProfilePage({
  session,
  reload,
}: {
  session: Session;
  reload: () => Promise<void>;
}) {
  const [error, setError] = useState<unknown>(null),
    [message, setMessage] = useState(""),
    [busy, setBusy] = useState(false);
  return (
    <>
      <PageHeader
        area="Your account"
        title="Account settings"
        description={session.user.email}
      />
      <Feedback error={error} />
      {message && <Success>{message}</Success>}
      <section className="panel workflow-panel">
        <h2>Profile</h2>
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            const data = new FormData(e.currentTarget);
            setBusy(true);
            setError(null);
            try {
              await mutate(
                "/auth/profile",
                { displayName: data.get("name") },
                "PATCH",
              );
              await reload();
              setMessage("Profile saved.");
            } catch (e) {
              setError(e);
            } finally {
              setBusy(false);
            }
          }}
        >
          <label>
            Display name
            <input
              name="name"
              defaultValue={session.user.displayName}
              maxLength={150}
              required
            />
          </label>
          <button className="primary" disabled={busy}>
            Save profile
          </button>
        </form>
      </section>
      <section className="panel workflow-panel">
        <h2>Memberships</h2>
        {session.organizations.length ? (
          session.organizations.map((org) => (
            <div className="list-row" key={org.id}>
              <strong>{org.name}</strong>
              <Status value={org.role} />
            </div>
          ))
        ) : (
          <EmptyState
            icon="building"
            title="No organization membership"
            description="Your organization administrator can send you an invitation."
          />
        )}
      </section>
      <section className="panel workflow-panel">
        <h2>Change password</h2>
        <p>Changing your password signs out all existing sessions.</p>
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            const data = new FormData(e.currentTarget);
            setBusy(true);
            setError(null);
            try {
              await mutate("/auth/password/change", {
                currentPassword: data.get("current"),
                newPassword: data.get("new"),
              });
              await reload();
            } catch (e) {
              setError(e);
            } finally {
              setBusy(false);
            }
          }}
        >
          <PasswordField
            label="Current password"
            name="current"
            autoComplete="current-password"
          />
          <PasswordField
            label="New password"
            name="new"
            autoComplete="new-password"
            minimumLength={12}
          />
          <button disabled={busy}>Change password</button>
        </form>
      </section>
    </>
  );
}
