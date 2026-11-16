import { WorkspaceErrorBoundary } from "../components/WorkspaceErrorBoundary";
import {
  Suspense,
  lazy,
  useEffect,
  useState,
  useCallback,
  useRef,
} from "react";
import {
  request,
  mutate,
  clearOrganizationCache,
  ApiError,
  authApi,
} from "../services/api";
import type { Session, RegistrationOptions } from "../types/api";
import { Feedback, Loading } from "../components/Feedback";
import { EmptyState } from "../components/Design";
import { humanize } from "../components/format";
import { allowNavigation } from "./navigationGuard";
import { AuthShell, WorkspaceShell } from "./WorkspaceShell";
import {
  SignInPage,
  InvitationPage,
  ResetPage,
  ProfilePage,
} from "../features/auth/AuthPages";

const GovernancePage = lazy(() =>
  import("../features/governance/GovernancePage").then((m) => ({
    default: m.GovernancePage,
  })),
);
const ConnectionsPage = lazy(() =>
  import("../features/integration/ConnectionsPage").then((m) => ({
    default: m.ConnectionsPage,
  })),
);
const ImportCenter = lazy(() =>
  import("../features/integration/ImportCenter").then((m) => ({
    default: m.ImportCenter,
  })),
);
const AssetEditor = lazy(() =>
  import("../features/integration/AssetEditor").then((m) => ({
    default: m.AssetEditor,
  })),
);
const TransformationPage = lazy(() =>
  import("../features/integration/TransformationPage").then((m) => ({
    default: m.TransformationPage,
  })),
);
const DataFlowOverview = lazy(() =>
  import("../features/integration/DataFlowOverview").then((m) => ({
    default: m.DataFlowOverview,
  })),
);
const LineagePage = lazy(() =>
  import("../features/integration/LineagePage").then((m) => ({
    default: m.LineagePage,
  })),
);
const FormulaStudio = lazy(() =>
  import("../features/integration/FormulaStudio").then((m) => ({
    default: m.FormulaStudio,
  })),
);
const MetricsPage = lazy(() =>
  import("../features/integration/MetricsPage").then((m) => ({
    default: m.MetricsPage,
  })),
);
const OverviewPage = lazy(() =>
  import("./OverviewPage").then((m) => ({ default: m.OverviewPage })),
);
const CatalogPage = lazy(() =>
  import("../features/catalog/CatalogPage").then((m) => ({
    default: m.CatalogPage,
  })),
);
const AssetPage = lazy(() =>
  import("../features/catalog/CatalogPage").then((m) => ({
    default: m.AssetPage,
  })),
);
const SignUpPage = lazy(() =>
  import("../features/auth/SignUpPage").then((m) => ({
    default: m.SignUpPage,
  })),
);
const AccountHelpPage = lazy(() =>
  import("../features/auth/AccountHelpPages").then((m) => ({
    default: m.AccountHelpPage,
  })),
);
const OrganizationPage = lazy(() =>
  import("../features/admin/ManagementPages").then((m) => ({
    default: m.OrganizationPage,
  })),
);
const TeamsPage = lazy(() =>
  import("../features/admin/ManagementPages").then((m) => ({
    default: m.TeamsPage,
  })),
);
const PlatformPage = lazy(() =>
  import("../features/admin/ManagementPages").then((m) => ({
    default: m.PlatformPage,
  })),
);
export function TraceApp() {
  const [route, setRoute] = useState(
    window.location.hash.slice(1) || "overview",
  );
  const currentRoute = useRef(route);
  const hadSession = useRef(false);
  const [session, setSession] = useState<Session | null>(null),
    [loading, setLoading] = useState(true),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(null),
    [epoch, setEpoch] = useState(0);
  const [registrationOptions, setRegistrationOptions] =
    useState<RegistrationOptions | null>(null);
  const [authNotice, setAuthNotice] = useState("");
  const [registrationOptionsError, setRegistrationOptionsError] =
    useState<unknown>(null);
  const changingSession = useRef(false);
  const lastOrganization = useRef<string | null>(null);
  const reload = useCallback(async (force = false) => {
    if (changingSession.current && !force) return;
    try {
      const next = await request<Session>("/auth/me");
      if (lastOrganization.current !== next.selectedOrganization) {
        clearOrganizationCache();
        setEpoch((v) => v + 1);
        lastOrganization.current = next.selectedOrganization;
      }
      hadSession.current = true;
      setSession(next);
      setError(null);
    } catch (e) {
      if (e instanceof DOMException && e.name === "AbortError") return;
      if (e instanceof ApiError && e.status === 401) setSession(null);
      else setError(e);
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    Promise.resolve().then(() => reload());
    let active = true;
    authApi
      .registrationOptions()
      .then((options) => {
        if (active) {
          setRegistrationOptions(options);
          setRegistrationOptionsError(null);
        }
      })
      .catch((e) => {
        if (active && !(e instanceof DOMException && e.name === "AbortError"))
          setRegistrationOptionsError(e);
      });
    const change = () => {
      const next = window.location.hash.slice(1) || "overview";
      if (next === currentRoute.current) return;
      const commitRoute = () => {
        currentRoute.current = next;
        setRoute(next);
      };
      if (
        !allowNavigation(() => {
          window.history.replaceState(window.history.state, "", `#${next}`);
          commitRoute();
        })
      ) {
        window.history.replaceState(
          window.history.state,
          "",
          `#${currentRoute.current}`,
        );
        return;
      }
      commitRoute();
    };
    const expired = () => {
      if (hadSession.current) {
        setAuthNotice("Your session has expired. Please sign in again.");
      }
      hadSession.current = false;
      lastOrganization.current = null;
      clearOrganizationCache();
      setSession(null);
      setEpoch((v) => v + 1);
    };
    const invalidOrganization = () => {
      clearOrganizationCache();
      lastOrganization.current = null;
      setSession((s) =>
        s
          ? {
              ...s,
              selectedOrganization: null,
              organizationRole: null,
              teamRoles: {},
              permissions: { permissions: [], teamPermissions: [] },
            }
          : s,
      );
      setEpoch((v) => v + 1);
      void reload();
    };
    window.addEventListener("trace:organization-invalid", invalidOrganization);
    window.addEventListener("hashchange", change);
    window.addEventListener("trace:unauthenticated", expired);
    return () => {
      active = false;
      window.removeEventListener(
        "trace:organization-invalid",
        invalidOrganization,
      );
      window.removeEventListener("hashchange", change);
      window.removeEventListener("trace:unauthenticated", expired);
    };
  }, [reload]);
  const authenticatedUserId = session?.user.id;
  useEffect(() => {
    if (!authenticatedUserId) return;
    const refresh = () => void reload();
    window.addEventListener("focus", refresh);
    const timer = window.setInterval(refresh, 30000);
    return () => {
      window.removeEventListener("focus", refresh);
      window.clearInterval(timer);
    };
  }, [authenticatedUserId, reload]);
  const select = async (id: string) => {
    if (!id || changingSession.current) return;
    changingSession.current = true;
    setBusy(true);
    setError(null);
    clearOrganizationCache();
    setEpoch((v) => v + 1);
    try {
      const s = await mutate<Session>("/auth/organization", {
        organizationId: id,
      });
      clearOrganizationCache();
      lastOrganization.current = s.selectedOrganization;
      setSession(s);
      window.location.hash = "overview";
    } catch (e) {
      await reload(true);
      setError(e);
    } finally {
      changingSession.current = false;
      setBusy(false);
    }
  };
  const logout = async () => {
    if (changingSession.current) return;
    changingSession.current = true;
    setBusy(true);
    try {
      await mutate("/auth/logout");
      hadSession.current = false;
      clearOrganizationCache();
      setSession(null);
      setEpoch((v) => v + 1);
      window.location.hash = "login";
    } catch (e) {
      setError(e);
    } finally {
      changingSession.current = false;
      setBusy(false);
    }
  };
  const requestSelect = (id: string) => {
    const run = () => void select(id);
    if (allowNavigation(run)) run();
  };
  const requestLogout = () => {
    const run = () => void logout();
    if (allowNavigation(run)) run();
  };
  if (loading)
    return (
      <AuthShell>
        <Loading>Loading your session…</Loading>
      </AuthShell>
    );
  if (route.startsWith("reset-password/"))
    return (
      <AuthShell>
        <Suspense fallback={<Loading />}>
          <ResetPage token={route.slice(15)} onDone={reload} />
        </Suspense>
      </AuthShell>
    );
  if (route.startsWith("accept-invitation/"))
    return (
      <>
        <AuthShell>
          <Suspense fallback={<Loading />}>
            <InvitationPage
              token={route.slice(18)}
              session={session}
              reload={reload}
            />
            {session && <button onClick={requestLogout}>Sign out</button>}
          </Suspense>
        </AuthShell>
      </>
    );
  if (route === "join-organization")
    return (
      <AuthShell>
        <Suspense fallback={<Loading />}>
          <AccountHelpPage kind="invitation" signedIn={!!session} />
        </Suspense>
      </AuthShell>
    );
  if (!session && route === "forgot-password")
    return (
      <AuthShell>
        <Suspense fallback={<Loading />}>
          <AccountHelpPage kind="recovery" />
        </Suspense>
      </AuthShell>
    );
  if (!session && route === "signup")
    return (
      <AuthShell>
        <Suspense fallback={<Loading />}>
          <Feedback error={error} />
          <SignUpPage
            options={registrationOptions}
            availabilityError={registrationOptionsError}
            onCreated={(message) => {
              setAuthNotice(message);
              window.location.hash = "login";
            }}
          />
        </Suspense>
      </AuthShell>
    );
  if (!session)
    return (
      <AuthShell>
        <Suspense fallback={<Loading />}>
          <Feedback error={error} />
          <SignInPage
            signupEnabled={registrationOptions?.enabled}
            notice={authNotice}
            onSignedIn={async () => {
              await reload();
              setAuthNotice("");
            }}
          />
        </Suspense>
      </AuthShell>
    );
  const admin = ["OWNER", "ADMIN"].includes(session.organizationRole ?? "");
  const canImport =
    session.permissions?.permissions.includes("metadata:import") ||
    session.permissions?.teamPermissions.some((t) =>
      t.permissions.includes("metadata:import"),
    );
  const noOrg = !session.selectedOrganization;
  let content;
  if (route === "platform")
    content =
      session.user.platformRole === "PLATFORM_ADMIN" ? (
        <PlatformPage reload={reload} />
      ) : (
        <AccessDenied />
      );
  else if (route === "profile")
    content = <ProfilePage session={session} reload={reload} />;
  else if (noOrg)
    content = (
      <section className="auth-card">
        <p className="eyebrow">Workspace access</p>
        <h1>
          {session.organizations.length
            ? "Select an organization"
            : "Join an organization"}
        </h1>
        {session.organizations.length ? (
          <>
            <p>
              Choose the organization you want to work in,{" "}
              {session.user.displayName}.
            </p>
            {session.organizations.map((o) => (
              <button
                className="list-row team-choice"
                disabled={busy}
                key={o.id}
                onClick={() => requestSelect(o.id)}
              >
                {o.name}
                <small>{humanize(o.role)}</small>
              </button>
            ))}
          </>
        ) : (
          <EmptyState
            icon="building"
            title="No organization membership"
            description="Accept an invitation from an organization administrator to access its workspace."
            actions={
              <a className="button primary" href="#join-organization">
                Accept an invitation
              </a>
            }
          />
        )}
      </section>
    );
  else if (["organization", "members", "audit"].includes(route))
    content = admin ? (
      <OrganizationPage
        session={session}
        reload={reload}
        view={route as "organization" | "members" | "audit"}
      />
    ) : (
      <AccessDenied />
    );
  else if (route === "governance")
    content = <GovernancePage session={session} />;
  else if (route === "teams")
    content = <TeamsPage session={session} reload={reload} />;
  else if (
    route === "connections" ||
    route.startsWith("connections/") ||
    route === "discovery"
  )
    content = <ConnectionsPage session={session} route={route} />;
  else if (route === "register" || route.startsWith("edit-asset/"))
    content = (
      <AssetEditor
        key={route}
        session={session}
        id={route.startsWith("edit-asset/") ? route.slice(11) : undefined}
      />
    );
  else if (
    route === "lineage" ||
    route.startsWith("lineage/") ||
    route === "impact" ||
    route.startsWith("impact/")
  )
    content = (
      <LineagePage
        key={route}
        session={session}
        initialRoot={route.split("/")[1]}
        impact={route.startsWith("impact")}
      />
    );
  else if (route === "formulas") content = <FormulaStudio session={session} />;
  else if (route === "data-flow") content = <DataFlowOverview />;
  else if (route === "transformations" || route.startsWith("transformations/"))
    content = (
      <TransformationPage
        session={session}
        initialTarget={route.split("/")[1]}
      />
    );
  else if (route === "metrics" || route === "report-mappings")
    content = (
      <MetricsPage
        key={route}
        session={session}
        mappingsOnly={route === "report-mappings"}
      />
    );
  else if (
    route === "import" ||
    route.startsWith("import/") ||
    route.startsWith("import?")
  )
    content = canImport ? (
      <ImportCenter
        key={route}
        session={session}
        batchId={route.startsWith("import/") ? route.slice(7) : undefined}
        initialSource={
          new URLSearchParams(route.split("?")[1]).get("source") ?? undefined
        }
      />
    ) : (
      <AccessDenied />
    );
  else if (/^asset\/[a-f0-9-]{36}$/i.test(route))
    content = <AssetPage id={route.slice(6)} session={session} />;
  else if (route === "catalog" || route.startsWith("catalog?"))
    content = (
      <CatalogPage
        key={route}
        session={session}
        canImport={!!canImport}
        initialQuery={new URLSearchParams(route.split("?")[1]).get("q") ?? ""}
        initialSource={
          new URLSearchParams(route.split("?")[1]).get("source") ?? undefined
        }
        initialOwner={
          new URLSearchParams(route.split("?")[1]).get("owner") ?? ""
        }
        initialFreshness={
          new URLSearchParams(route.split("?")[1]).get("freshness") ?? ""
        }
      />
    );
  else content = <OverviewPage session={session} canImport={!!canImport} />;
  return (
    <WorkspaceShell
      session={session}
      route={route}
      busy={busy}
      canImport={!!canImport}
      onSelect={requestSelect}
      onSignOut={requestLogout}
    >
      <Feedback error={error} />
      {busy ? (
        <Loading>Updating session…</Loading>
      ) : (
        <WorkspaceErrorBoundary
          key={`${session.selectedOrganization}:${epoch}:${route}`}
        >
          {" "}
          <Suspense fallback={<Loading>Loading workspace…</Loading>}>
            <div
              key={`${session.selectedOrganization}:${epoch}:${route.split(/[/?]/)[0]}`}
            >
              {content}
            </div>
          </Suspense>
        </WorkspaceErrorBoundary>
      )}
    </WorkspaceShell>
  );
}
function AccessDenied() {
  return (
    <section className="panel" role="alert">
      <h1>Access denied</h1>
      <p>
        Your current role does not permit this action. Contact an organization
        administrator if your responsibilities have changed.
      </p>
      <a href="#catalog">Back to catalog</a>
    </section>
  );
}
