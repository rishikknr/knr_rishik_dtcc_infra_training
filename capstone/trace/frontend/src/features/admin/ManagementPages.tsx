import { usePage } from "../integration/paging";
import { Pager } from "../integration/pagination";
import { useEffect, useState } from "react";
import { mutate, request } from "../../services/api";
import type {
  Session,
  Member,
  Team,
  TeamDetail,
  Invitation,
  Delivery,
} from "../../types/api";
import {
  ConfirmDialog,
  EmptyState,
  Icon,
  PageHeader,
  Status,
  TableRegion,
  Timestamp,
} from "../../components/Design";
import { humanize } from "../../components/format";
import { Feedback, Loading, Success } from "../../components/Feedback";
const orgRoles = ["OWNER", "ADMIN", "MEMBER", "VIEWER"],
  teamRoles = ["OWNER", "EDITOR", "VIEWER"];
function RoleOptions({ roles }: { roles: string[] }) {
  return roles.map((r) => <option key={r}>{r}</option>);
}
function LinkDelivery({ delivery }: { delivery: Delivery | null }) {
  return (
    delivery && (
      <div className="notice" role="status">
        <strong>One-time delivery link</strong>
        <p>
          Share privately with the intended recipient. Expires{" "}
          {new Date(delivery.expiresAt).toLocaleString()}.
        </p>
        <a href={delivery.link}>
          {window.location.origin}/{delivery.link}
        </a>
      </div>
    )
  );
}

export function OrganizationPage({
  session,
  reload,
  view,
}: {
  session: Session;
  reload: () => Promise<void>;
  view: "organization" | "members" | "audit";
}) {
  const [members, setMembers] = useState<Member[]>([]),
    [invitations, setInvitations] = useState<Invitation[]>([]),
    [error, setError] = useState<unknown>(null),
    [busy, setBusy] = useState(false),
    [delivery, setDelivery] = useState<Delivery | null>(null),
    [revision, setRevision] = useState(0),
    [audit, setAudit] = useState<
      { id: string; event_type: string; created_at: string }[]
    >([]);
  const [loaded, setLoaded] = useState(false);
  const [notice, setNotice] = useState("");
  const [confirmation, setConfirmation] = useState<{
    title: string;
    description: string;
    action: string;
    run: () => void;
  } | null>(null);
  const current = session.organizations.find(
    (organization) => organization.id === session.selectedOrganization,
  );
  const owner = session.organizationRole === "OWNER";
  useEffect(() => {
    if (view === "organization") return;
    const controller = new AbortController();
    const read = <T,>(path: string) =>
      request<T>(`/organization/${path}`, { signal: controller.signal });
    const load =
      view === "members"
        ? Promise.all([
            read<Member[]>("members"),
            read<Invitation[]>("invitations"),
          ]).then(([people, invites]) => {
            if (!controller.signal.aborted) {
              setMembers(people);
              setInvitations(invites);
            }
          })
        : read<typeof audit>("audit").then((events) => {
            if (!controller.signal.aborted) setAudit(events);
          });
    void load
      .catch((failure) => {
        if (!controller.signal.aborted) setError(failure);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoaded(true);
      });
    return () => controller.abort();
  }, [revision, view]);
  const action = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
      setRevision((v) => v + 1);
      await reload();
      setNotice("Organization changes saved.");
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <PageHeader
        area="Management"
        title={
          view === "members"
            ? "Members"
            : view === "audit"
              ? "Audit History"
              : "Organization Settings"
        }
        description={
          view === "members"
            ? "Manage organization access, ownership, and invitations."
            : view === "audit"
              ? "Review recent events recorded in the selected organization."
              : `Manage settings for ${current?.name ?? "this organization"}.`
        }
      />
      <Feedback error={error} />
      {notice && <Success>{notice}</Success>}
      {view !== "organization" && !loaded && !error && (
        <Loading>Loading organization records…</Loading>
      )}
      {view === "organization" && (
        <section className="panel workflow-panel">
          <h2>Organization information</h2>
          <p>Slug: {current?.slug}</p>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const data = new FormData(e.currentTarget);
              void action(() =>
                mutate("/organization", { name: data.get("name") }, "PATCH"),
              );
            }}
          >
            <label>
              Name
              <input
                name="name"
                defaultValue={current?.name}
                required
                maxLength={150}
              />
            </label>
            <button className="primary" disabled={busy}>
              Save organization
            </button>
          </form>
        </section>
      )}
      {view === "members" && loaded && (
        <>
          <section className="panel table-panel">
            <header>
              <h2>Organization members</h2>
              <p className="muted">
                Role changes take effect across the organization.
              </p>
            </header>
            <TableRegion label="Organization members">
              <table className="wide-table">
                <thead>
                  <tr>
                    <th>Member</th>
                    <th>Role</th>
                    <th>Status</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {members.map((m) => (
                    <tr key={m.user_id}>
                      <td>
                        {m.display_name}
                        <small>{m.email}</small>
                      </td>
                      <td>
                        <span className="badge">{humanize(m.role)}</span>
                      </td>
                      <td>
                        <Status value={m.status} />
                        {!m.active && " · Account disabled"}
                      </td>
                      <td>
                        <form
                          className="inline-form"
                          onSubmit={(e) => {
                            e.preventDefault();
                            const data = new FormData(e.currentTarget);
                            setConfirmation({
                              title: "Update member access?",
                              description: `${m.email} will have the ${humanize(String(data.get("role")))} role and ${humanize(String(data.get("status")))} status.`,
                              action: "Update access",
                              run: () =>
                                void action(() =>
                                  mutate(
                                    `/organization/members/${m.user_id}`,
                                    {
                                      role: data.get("role"),
                                      status: data.get("status"),
                                    },
                                    "PATCH",
                                  ),
                                ),
                            });
                          }}
                        >
                          <select
                            aria-label={`Role for ${m.email}`}
                            name="role"
                            defaultValue={m.role}
                            disabled={!owner && m.role === "OWNER"}
                          >
                            <RoleOptions
                              roles={
                                owner
                                  ? orgRoles
                                  : orgRoles.filter((r) => r !== "OWNER")
                              }
                            />
                          </select>
                          <select
                            aria-label={`Status for ${m.email}`}
                            name="status"
                            defaultValue={m.status}
                          >
                            <RoleOptions
                              roles={["ACTIVE", "SUSPENDED", "REVOKED"]}
                            />
                          </select>
                          <button
                            disabled={busy || (!owner && m.role === "OWNER")}
                          >
                            Save access
                          </button>
                          {owner &&
                            m.user_id !== session.user.id &&
                            m.status === "ACTIVE" && (
                              <button
                                type="button"
                                disabled={busy}
                                onClick={() => {
                                  setConfirmation({
                                    title: "Transfer ownership?",
                                    description: `${m.email} will become an owner. Your role will change to administrator.`,
                                    action: "Transfer ownership",
                                    run: () =>
                                      void action(() =>
                                        mutate(
                                          `/organization/transfer/${m.user_id}`,
                                        ),
                                      ),
                                  });
                                }}
                              >
                                Transfer ownership
                              </button>
                            )}
                        </form>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </TableRegion>
          </section>
          <section className="panel">
            <h2>Invite a member</h2>
            <form
              className="inline-form"
              onSubmit={(e) => {
                e.preventDefault();
                const data = new FormData(e.currentTarget);
                void action(async () => {
                  const d = await mutate<Delivery>(
                    "/organization/invitations",
                    {
                      email: data.get("email"),
                      role: data.get("role"),
                    },
                  );
                  setDelivery(d);
                });
              }}
            >
              <label>
                Email
                <input name="email" type="email" required maxLength={320} />
              </label>
              <label>
                Organization role
                <select
                  aria-label="Organization role"
                  name="role"
                  defaultValue="MEMBER"
                >
                  <RoleOptions
                    roles={
                      owner ? orgRoles : orgRoles.filter((r) => r !== "OWNER")
                    }
                  />
                </select>
              </label>
              <button disabled={busy}>Create invitation</button>
            </form>
            <LinkDelivery delivery={delivery} />
            <h3>Invitations</h3>
            {!invitations.length && (
              <EmptyState
                icon="user"
                title="No invitations"
                description="Pending and previous invitations appear here after you invite a member."
              />
            )}
            {invitations.map((i) => (
              <div className="list-row" key={i.id}>
                <span>
                  {i.email}
                  <small>
                    {humanize(i.role)} · Expires{" "}
                    <Timestamp value={i.expires_at} />
                  </small>
                </span>
                <div className="heading-actions">
                  <Status value={i.status} />
                  {i.status === "PENDING" && (
                    <button
                      disabled={busy}
                      onClick={() => {
                        setConfirmation({
                          title: "Revoke invitation?",
                          description: `The invitation link for ${i.email} will stop working.`,
                          action: "Revoke invitation",
                          run: () =>
                            void action(() =>
                              mutate(
                                `/organization/invitations/${i.id}`,
                                undefined,
                                "DELETE",
                              ),
                            ),
                        });
                      }}
                    >
                      Revoke
                    </button>
                  )}
                </div>
              </div>
            ))}
          </section>
        </>
      )}
      {view === "audit" && loaded && (
        <section className="panel table-panel">
          <header>
            <h2>Recent audit events</h2>
            <p className="muted">
              Up to 200 recent events returned by the audit service.
            </p>
          </header>
          {audit.length ? (
            <TableRegion label="Organization audit events">
              <table>
                <thead>
                  <tr>
                    <th scope="col">Event</th>
                    <th scope="col">Recorded</th>
                  </tr>
                </thead>
                <tbody>
                  {audit.map((event) => (
                    <tr key={event.id}>
                      <td>{humanize(event.event_type)}</td>
                      <td>
                        <Timestamp value={event.created_at} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </TableRegion>
          ) : (
            <EmptyState
              icon="history"
              title="No audit events"
              description="Recorded organization events will appear here."
            />
          )}
        </section>
      )}
      {confirmation && (
        <ConfirmDialog
          {...confirmation}
          onConfirm={confirmation.run}
          onClose={() => setConfirmation(null)}
        />
      )}
    </>
  );
}

export function TeamsPage({
  session,
  reload,
}: {
  session: Session;
  reload: () => Promise<void>;
}) {
  const teamPage = usePage<Team>("teams");
  const [detail, setDetail] = useState<TeamDetail | null>(null),
    [candidates, setCandidates] = useState<Member[]>([]),
    [error, setError] = useState<unknown>(null),
    [busy, setBusy] = useState(false),
    [revision, setRevision] = useState(0);
  const admin = ["OWNER", "ADMIN"].includes(session.organizationRole ?? "");
  const canManage =
    admin ||
    (session.organizationRole !== "VIEWER" &&
      detail &&
      session.teamRoles[detail.id] === "OWNER");
  const open = async (id: string) => {
    setError(null);
    try {
      const d = await request<TeamDetail>(`/teams/${id}`);
      setDetail(d);
      if (
        admin ||
        (session.organizationRole !== "VIEWER" &&
          session.teamRoles[id] === "OWNER")
      )
        setCandidates(await request<Member[]>(`/teams/${id}/candidates`));
    } catch (e) {
      setError(e);
    }
  };
  const action = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
      setRevision((v) => v + 1);
      teamPage.reload();
      await reload();
      if (detail) await open(detail.id);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <PageHeader
        area="Management"
        title="Teams"
        description="Manage team membership and ownership of connections and catalog assets."
        actions={
          admin && (
            <a
              className="button primary"
              href="#teams"
              onClick={(event) => {
                event.preventDefault();
                document.getElementById("team-create")?.focus();
              }}
            >
              <Icon name="plus" size={16} />
              New team
            </a>
          )
        }
      />
      <Feedback error={error} />
      {admin && (
        <section className="panel">
          <h2 id="team-create" tabIndex={-1}>
            Create team
          </h2>
          <form
            className="inline-form"
            onSubmit={(e) => {
              e.preventDefault();
              const data = new FormData(e.currentTarget);
              void action(() =>
                mutate("/teams", {
                  name: data.get("name"),
                  slug: data.get("slug"),
                  description: "",
                }),
              );
            }}
          >
            <label>
              Name
              <input name="name" required maxLength={150} />
            </label>
            <label>
              Slug
              <input
                name="slug"
                pattern="[a-z0-9]+(-[a-z0-9]+)*"
                required
                maxLength={100}
              />
            </label>
            <button className="primary" disabled={busy}>
              Create team
            </button>
          </form>
        </section>
      )}
      <div className="management-grid">
        <section className="panel">
          <h2>Organization teams</h2>
          <Pager page={teamPage} label="teams" />
          {teamPage.items.map((t) => (
            <button
              className="list-row team-choice"
              key={t.id}
              aria-pressed={detail?.id === t.id}
              onClick={() => void open(t.id)}
            >
              {t.name}
              <small>{session.teamRoles[t.id] ?? "Organization access"}</small>
            </button>
          ))}
          {!teamPage.loading && !teamPage.error && teamPage.total === 0 && (
            <EmptyState
              icon="teams"
              title="No teams found"
              description="Teams assign ownership and determine who can manage sources and metadata."
            />
          )}
        </section>
        {detail ? (
          <section className="panel">
            <div className="section-title">
              <h2>{detail.name}</h2>
              <Status value={detail.active ? "ACTIVE" : "INACTIVE"} />
            </div>
            <p>{detail.description}</p>
            {canManage && (
              <form
                key={detail.id + detail.name}
                onSubmit={(e) => {
                  e.preventDefault();
                  const data = new FormData(e.currentTarget);
                  void action(() =>
                    mutate(
                      `/teams/${detail.id}`,
                      {
                        name: data.get("name"),
                        slug: data.get("slug"),
                        description: data.get("description"),
                      },
                      "PATCH",
                    ),
                  );
                }}
              >
                <label>
                  Team name
                  <input
                    name="name"
                    defaultValue={detail.name}
                    required
                    maxLength={150}
                  />
                </label>
                <label>
                  Slug
                  <input
                    name="slug"
                    defaultValue={detail.slug}
                    required
                    maxLength={100}
                  />
                </label>
                <label>
                  Description
                  <textarea
                    name="description"
                    defaultValue={detail.description}
                    maxLength={2000}
                  />
                </label>
                <button disabled={busy}>Save team</button>
              </form>
            )}
            <h3>Members</h3>
            {detail.members.map((m) => (
              <div key={m.user_id} className="list-row">
                <span>
                  {m.display_name}
                  <small>
                    {m.email} · {m.role}
                  </small>
                </span>
                {canManage && (
                  <form
                    className="inline-form"
                    onSubmit={(e) => {
                      e.preventDefault();
                      const role = new FormData(e.currentTarget).get("role");
                      void action(() =>
                        mutate(
                          `/teams/${detail.id}/members/${m.user_id}`,
                          { role },
                          "PATCH",
                        ),
                      );
                    }}
                  >
                    <select
                      aria-label={`Team role for ${m.email}`}
                      name="role"
                      defaultValue={m.role}
                    >
                      <RoleOptions roles={teamRoles} />
                    </select>
                    <button disabled={busy}>Save role</button>
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => {
                        if (window.confirm(`Remove ${m.email} from the team?`))
                          void action(() =>
                            mutate(
                              `/teams/${detail.id}/members/${m.user_id}`,
                              undefined,
                              "DELETE",
                            ),
                          );
                      }}
                    >
                      Remove
                    </button>
                  </form>
                )}
              </div>
            ))}
            {canManage && (
              <form
                className="inline-form"
                onSubmit={(e) => {
                  e.preventDefault();
                  const data = new FormData(e.currentTarget);
                  void action(() =>
                    mutate(`/teams/${detail.id}/members`, {
                      userId: data.get("user"),
                      role: data.get("role"),
                    }),
                  );
                }}
              >
                <label>
                  Organization member
                  <select aria-label="Organization member" name="user" required>
                    <option value="">Choose member</option>
                    {candidates
                      .filter((m) => m.status === "ACTIVE" && m.active)
                      .map((m) => (
                        <option value={m.user_id} key={m.user_id}>
                          {m.email}
                        </option>
                      ))}
                  </select>
                </label>
                <label>
                  Team role
                  <select
                    aria-label="Team role"
                    name="role"
                    defaultValue="VIEWER"
                  >
                    <RoleOptions roles={teamRoles} />
                  </select>
                </label>
                <button disabled={busy}>Add member</button>
              </form>
            )}
            <TeamResources id={detail.id} revision={revision} />
          </section>
        ) : (
          <section className="panel">
            <EmptyState
              icon="teams"
              title="Select a team"
              description="Open a team to see its members, role assignments, and owned resources."
            />
          </section>
        )}
      </div>
    </>
  );
}

export function PlatformPage({ reload }: { reload: () => Promise<void> }) {
  const [organizations, setOrganizations] = useState<
      { id: string; name: string; slug: string; active: boolean }[]
    >([]),
    [error, setError] = useState<unknown>(null),
    [revision, setRevision] = useState(0),
    [busy, setBusy] = useState(false),
    [delivery, setDelivery] = useState<Delivery | null>(null);
  useEffect(() => {
    let active = true;
    request<typeof organizations>("/platform/organizations")
      .then((o) => {
        if (active) setOrganizations(o);
      })
      .catch((e) => {
        if (active) setError(e);
      });
    return () => {
      active = false;
    };
  }, [revision]);
  const action = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
      setRevision((v) => v + 1);
      await reload();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <PageHeader
        area="Administration"
        title="Platform Management"
        description="Provision organizations, manage account status, and issue recovery links."
      />
      <Feedback error={error} />
      <section className="panel">
        <h2>Organizations</h2>
        {organizations.map((o) => (
          <div className="list-row" key={o.id}>
            <span>
              <strong>{o.name}</strong>
              <small>{o.slug}</small>
            </span>
            <div className="heading-actions">
              <Status value={o.active ? "ACTIVE" : "SUSPENDED"} />
              <button
                disabled={busy}
                onClick={() => {
                  if (
                    window.confirm(
                      `${o.active ? "Suspend" : "Activate"} ${o.name}?`,
                    )
                  )
                    void action(() =>
                      mutate(
                        `/platform/organizations/${o.id}/status`,
                        { active: !o.active },
                        "PATCH",
                      ),
                    );
                }}
              >
                {o.active ? "Suspend" : "Activate"}
              </button>
            </div>
          </div>
        ))}
      </section>
      <section className="panel">
        <h2>Create organization</h2>
        <form
          className="inline-form"
          onSubmit={(e) => {
            e.preventDefault();
            const data = new FormData(e.currentTarget);
            void action(() =>
              mutate("/platform/organizations", {
                name: data.get("name"),
                slug: data.get("slug"),
              }),
            );
          }}
        >
          <label>
            Name
            <input name="name" required maxLength={150} />
          </label>
          <label>
            Slug
            <input
              name="slug"
              pattern="[a-z0-9]+(-[a-z0-9]+)*"
              required
              maxLength={100}
            />
          </label>
          <button disabled={busy}>Create organization</button>
        </form>
      </section>
      <section className="panel">
        <h2>Account recovery and status</h2>
        <p className="muted">
          Recovery links are issued by an administrator for private delivery.
        </p>
        <form
          className="inline-form"
          onSubmit={(e) => {
            e.preventDefault();
            const id = String(new FormData(e.currentTarget).get("user"));
            void action(async () =>
              setDelivery(
                await mutate<Delivery>(
                  `/platform/users/${id}/password-recovery`,
                ),
              ),
            );
          }}
        >
          <label>
            User UUID
            <input name="user" required pattern="[a-fA-F0-9-]{36}" />
          </label>
          <button disabled={busy}>Issue recovery link</button>
        </form>
        <LinkDelivery delivery={delivery} />
        <form
          className="inline-form"
          onSubmit={(e) => {
            e.preventDefault();
            const data = new FormData(e.currentTarget);
            if (
              window.confirm(
                "Change this global account status? Existing sessions will be revoked.",
              )
            )
              void action(() =>
                mutate(
                  `/platform/users/${data.get("user")}/status`,
                  { active: data.get("active") === "true" },
                  "PATCH",
                ),
              );
          }}
        >
          <label>
            User UUID
            <input name="user" required pattern="[a-fA-F0-9-]{36}" />
          </label>
          <label>
            Status
            <select name="active">
              <option value="false">DISABLED</option>
              <option value="true">ACTIVE</option>
            </select>
          </label>
          <button disabled={busy}>Save account status</button>
        </form>
      </section>
    </>
  );
}

function TeamResources({ id, revision }: { id: string; revision: number }) {
  const connections = usePage<{ id: string; name: string }>(
      "connections",
      revision,
      { parent: id },
    ),
    assets = usePage<{ id: string; name: string }>("assets", revision, {
      parent: id,
    });
  return (
    <>
      <h3>Owned connections</h3>
      <Pager page={connections} label="owned connections" />
      {connections.items.map((c) => (
        <p key={c.id}>{c.name}</p>
      ))}
      <h3>Owned assets</h3>
      <Pager page={assets} label="owned assets" />
      {assets.items.map((a) => (
        <p key={a.id}>
          <a href={`#asset/${a.id}`}>{a.name}</a>
        </p>
      ))}
    </>
  );
}
