import { SuggestedMappings } from "./SuggestedMappings";
import { useEffect, useState } from "react";
import type { Connection, Session } from "../../types/api";
import { mutate, request } from "../../services/api";
import {
  ConfirmDialog,
  EmptyState,
  Icon,
  PageHeader,
  Status,
  Timestamp,
} from "../../components/Design";
import { Feedback, Loading, Success } from "../../components/Feedback";
import { humanize } from "../../components/format";
import { JsonDetails, NoTeams, TeamSelect } from "./shared";
import { useAction, useWorkspace } from "./hooks";
import { allowed } from "../../app/permissions";
import { usePage } from "./paging";
import { Pager, PageNavigation } from "./pagination";
import {
  ConnectionWizard,
  ConnectionFailure,
  RunWarnings,
  type DiscoveryRun,
} from "./ConnectionWizard";
import { providers, outcome } from "./connectionModel";
import "./connections.css";
type Run = DiscoveryRun;
export function ConnectionsPage({
  session,
  route,
}: {
  session: Session;
  route: string;
}) {
  const action = useAction();
  const [revision, setRevision] = useState(0);
  const workspace = useWorkspace(action.revision + revision, { teams: true });
  const [status, setStatus] = useState("");
  const [sourceType, setSourceType] = useState("");
  const sourcePage = usePage<Connection>(
    "connections",
    action.revision + revision,
    { status, type: sourceType },
  );
  const [selection, setSelection] = useState<{
    id: string;
    data: Connection | null;
    error: unknown;
  } | null>(null);
  const [retry, setRetry] = useState(0);
  const [confirm, setConfirm] = useState(false);
  const routeId = route.startsWith("connections/") ? route.split("/")[1] : "";
  const creating = routeId === "new",
    detailId = creating ? "" : routeId;
  const selected = selection?.id === detailId ? selection.data : null;
  const discovery = route === "discovery" || route.endsWith("/history");
  const setup = route.endsWith("/setup");
  const canCreate = workspace.teams.some(
    (t) => t.active && allowed(session, t.id),
  );
  const writable = !!selected && allowed(session, selected.owner_team_id);
  const teamName = (id: string) =>
    workspace.teams.find((t) => t.id === id)?.name ?? "Unavailable team";
  const orgName = session.organizations.find(
    (o) => o.id === session.selectedOrganization,
  )?.name;
  useEffect(() => {
    if (!detailId) return;
    const controller = new AbortController();
    request<Connection>(`/connections/${detailId}`, {
      signal: controller.signal,
    })
      .then((data) => {
        if (!controller.signal.aborted)
          setSelection({ id: detailId, data, error: null });
      })
      .catch((error) => {
        if (!controller.signal.aborted)
          setSelection({ id: detailId, data: null, error });
      });
    return () => controller.abort();
  }, [detailId, action.revision, retry, revision]);
  const refresh = () => setRevision((v) => v + 1);
  const enable = () =>
    void action.run(async () => {
      await mutate(
        `/connections/${selected!.id}/status`,
        { enabled: selected!.status === "DISABLED" },
        "PATCH",
      );
      action.setNotice(
        "Connection status updated. Catalog records are retained.",
      );
    });
  return (
    <div className="connections-experience">
      <PageHeader
        area={orgName ? `${orgName} / Data sources` : "Data sources"}
        title={
          creating
            ? "Connect a source"
            : discovery
              ? "Discovery history"
              : (selected?.name ?? "Connections")
        }
        description={
          creating
            ? "A clear path from secure access to trusted metadata."
            : detailId
              ? "Manage access, verify your source and review discovery evidence."
              : "Your metadata starts here. Connect a source, verify access and decide what enters your catalog."
        }
        actions={
          creating || detailId ? (
            <a className="button" href="#connections">
              All connections
            </a>
          ) : canCreate ? (
            <a className="button primary" href="#connections/new">
              <Icon name="plus" size={16} />
              Add connection
            </a>
          ) : undefined
        }
      />
      <Feedback
        error={
          action.error ??
          workspace.error ??
          (selection?.id === detailId ? selection.error : null)
        }
      />
      {action.notice && <Success>{action.notice}</Success>}
      {workspace.loading ? (
        <Loading>Loading source permissions…</Loading>
      ) : creating ? (
        canCreate ? (
          <ConnectionWizard
            key={`new-${session.selectedOrganization}`}
            session={session}
            teams={workspace.teams}
            onChanged={refresh}
          />
        ) : (
          <NoTeams />
        )
      ) : detailId ? (
        !selected ? (
          selection?.error ? (
            <button onClick={() => setRetry((v) => v + 1)}>
              Retry connection
            </button>
          ) : (
            <Loading>Loading connection…</Loading>
          )
        ) : (
          <>
            {setup && writable && selected.status !== "DISABLED" ? (
              <ConnectionWizard
                key={selected.id}
                initial={selected}
                session={session}
                teams={workspace.teams}
                onChanged={refresh}
              />
            ) : (
              <>
                <section className="connection-summary">
                  <div className="connection-identity">
                    <span
                      className={`provider-emblem provider-${selected.platform.toLowerCase()}`}
                    >
                      <Icon
                        name={
                          providers[selected.platform]?.icon ?? "connections"
                        }
                        size={26}
                      />
                    </span>
                    <div>
                      <p className="eyebrow">
                        {providers[selected.platform]?.category}
                      </p>
                      <h2>
                        {providers[selected.platform]?.name ??
                          selected.platform}
                      </h2>
                      <p>
                        {teamName(selected.owner_team_id)} · {orgName}
                      </p>
                    </div>
                    <Status value={selected.status} />
                  </div>
                  <dl className="connection-facts">
                    <div>
                      <dt>Last successful test</dt>
                      <dd>
                        <Timestamp
                          value={selected.last_successful_test_at ?? null}
                          fallback="No recorded success"
                        />
                      </dd>
                    </div>
                    <div>
                      <dt>Last successful discovery</dt>
                      <dd>
                        <Timestamp
                          value={selected.last_successful_sync_at}
                          fallback="No successful run"
                        />
                      </dd>
                    </div>
                    <div>
                      <dt>Discovered / cataloged</dt>
                      <dd>
                        {selected.discovered_asset_count ?? "—"} /{" "}
                        {selected.registered_asset_count ?? "—"}
                      </dd>
                    </div>
                    <div>
                      <dt>Credentials</dt>
                      <dd>
                        {selected.has_credentials
                          ? "Encrypted and stored"
                          : "Not stored"}
                      </dd>
                    </div>
                  </dl>
                  {selected.reason_code &&
                    selected.reason_code !== "CONNECTED" && (
                      <ConnectionFailure
                        code={selected.reason_code}
                        reference={selected.last_test_diagnostic_id}
                      />
                    )}
                  <div className="action-row">
                    {writable && selected.status !== "DISABLED" && (
                      <a
                        className="button primary"
                        href={`#connections/${selected.id}/setup`}
                      >
                        {["MANUAL", "FILE_IMPORT"].includes(selected.platform)
                          ? "Edit source settings"
                          : "Configure, test & discover"}
                      </a>
                    )}
                    <a
                      className="button"
                      href={`#catalog?${new URLSearchParams({ source: selected.id })}`}
                    >
                      Browse catalog assets
                    </a>
                    {writable && (
                      <button
                        disabled={action.busy}
                        onClick={() =>
                          selected.status === "DISABLED"
                            ? enable()
                            : setConfirm(true)
                        }
                      >
                        {selected.status === "DISABLED"
                          ? "Enable connection"
                          : "Disable connection"}
                      </button>
                    )}
                    {writable && (
                      <a href={`#import?source=${selected.id}`}>
                        Import metadata
                      </a>
                    )}
                  </div>
                </section>
                {!discovery && (
                  <details className="panel">
                    <summary>Settings and ownership</summary>
                    <p>
                      {selected.description ||
                        providers[selected.platform]?.description}
                    </p>
                    <JsonDetails value={selected.configuration} />
                    {(["OWNER", "ADMIN"].includes(
                      session.organizationRole ?? "",
                    ) ||
                      session.teamRoles[selected.owner_team_id] ===
                        "OWNER") && (
                      <form
                        onSubmit={(e) => {
                          e.preventDefault();
                          const data = new FormData(e.currentTarget);
                          void action.run(async () => {
                            await mutate(
                              `/connections/${selected.id}/owner-team`,
                              { ownerTeamId: data.get("transferTeam") },
                              "PATCH",
                            );
                            action.setNotice(
                              "Ownership transferred. Existing asset ownership is preserved.",
                            );
                          });
                        }}
                      >
                        <TeamSelect
                          teams={workspace.teams}
                          session={session}
                          name="transferTeam"
                          value={selected.owner_team_id}
                        />
                        <button disabled={action.busy}>
                          Transfer ownership
                        </button>
                      </form>
                    )}
                  </details>
                )}
                {!discovery && Number(selected.registered_asset_count) > 0 && <SuggestedMappings session={session} source={selected.id}/>}
                <DiscoveryHistory
                  connection={selected.id}
                  revision={action.revision + revision}
                  writable={writable}
                  userId={session.user.id}
                />
              </>
            )}
          </>
        )
      ) : (
        <section className="connections-overview">
          <div className="source-principles">
            <div>
              <Icon name="shield" />
              <span>
                Private by design
                <small>Encrypted credentials, scoped permissions</small>
              </span>
            </div>
            <div>
              <Icon name="search" />
              <span>
                Verify, then discover
                <small>Real access tests and persistent run history</small>
              </span>
            </div>
            <div>
              <Icon name="catalog" />
              <span>
                You control the catalog
                <small>Review every inventory before commitment</small>
              </span>
            </div>
          </div>
          <Pager
            page={sourcePage}
            label="connections"
            separateNavigation
            filters={
              <>
                <label className="toolbar-filter">
                  <span className="sr-only">Source type</span>
                  <select
                    value={sourceType}
                    onChange={(e) => setSourceType(e.target.value)}
                  >
                    <option value="">All source types</option>
                    {Object.entries(providers).map(([value, p]) => (
                      <option key={value} value={value}>
                        {p.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="toolbar-filter">
                  <span className="sr-only">Connection status</span>
                  <select
                    value={status}
                    onChange={(e) => setStatus(e.target.value)}
                  >
                    <option value="">All statuses</option>
                    {[
                      "NOT_TESTED",
                      "CONNECTED",
                      "AUTHENTICATION_FAILED",
                      "CONFIGURATION_ERROR",
                      "PERMISSION_DENIED",
                      "UNAVAILABLE",
                      "DISABLED",
                    ].map((s) => (
                      <option key={s} value={s}>
                        {humanize(s)}
                      </option>
                    ))}
                  </select>
                </label>
              </>
            }
          />
          {sourcePage.loading ? (
            <Loading>Loading registered sources…</Loading>
          ) : (
            !sourcePage.error &&
            (sourcePage.items.length ? (
              <div className="connection-cards">
                {sourcePage.items.map((c) => {
                  const provider = providers[c.platform],
                    error =
                      c.reason_code && c.reason_code !== "CONNECTED"
                        ? c.reason_code
                        : c.recent_run_error_code;
                  return (
                    <article className="connection-card" key={c.id}>
                      <div className="connection-card-top">
                        <span
                          className={`provider-emblem provider-${c.platform.toLowerCase()}`}
                        >
                          <Icon
                            name={provider?.icon ?? "connections"}
                            size={23}
                          />
                        </span>
                        <Status value={c.status} />
                      </div>
                      <small className="eyebrow">{provider?.category}</small>
                      <h2>
                        <a
                          href={`#connections/${c.id}${discovery ? "/history" : ""}`}
                        >
                          {c.name}
                        </a>
                      </h2>
                      <p className="connection-card-owner">
                        {provider?.name} ·{" "}
                        {c.owner_team_name ?? teamName(c.owner_team_id)}
                      </p>
                      <dl>
                        <div>
                          <dt>Last successful test</dt>
                          <dd>
                            <Timestamp
                              value={c.last_successful_test_at ?? null}
                              fallback="No recorded success"
                            />
                          </dd>
                        </div>
                        <div>
                          <dt>Last discovery</dt>
                          <dd>
                            <Timestamp
                              value={c.last_successful_sync_at}
                              fallback="No successful run"
                            />
                          </dd>
                        </div>
                        <div>
                          <dt>Discovered assets</dt>
                          <dd>{c.discovered_asset_count ?? "—"}</dd>
                        </div>
                        <div>
                          <dt>Catalog assets</dt>
                          <dd>{c.registered_asset_count ?? "—"}</dd>
                        </div>
                      </dl>
                      {error ? (
                        <p className="connection-card-error">
                          <Icon name="alert" size={14} />
                          {outcome(error)[0]}
                        </p>
                      ) : (
                        <p className="connection-card-note">
                          {c.recent_run_status
                            ? `Latest discovery: ${humanize(c.recent_run_status)}`
                            : "No discovery run yet"}
                        </p>
                      )}
                      <footer>
                        <a
                          href={`#connections/${c.id}${allowed(session, c.owner_team_id) && c.status !== "DISABLED" ? "/setup" : ""}`}
                        >
                          {allowed(session, c.owner_team_id)
                            ? "Manage source"
                            : "View source"}
                          <Icon name="arrow" size={15} />
                        </a>
                        <a
                          href={`#connections/${c.id}/history`}
                          aria-label={`Discovery history for ${c.name}`}
                        >
                          <Icon name="history" size={16} />
                        </a>
                      </footer>
                    </article>
                  );
                })}
              </div>
            ) : (
              <div className="connections-empty">
                <EmptyState
                  icon="connections"
                  title={
                    sourcePage.query || status || sourceType
                      ? "No sources match this view"
                      : "Bring your metadata into focus"
                  }
                  description={
                    sourcePage.query || status || sourceType
                      ? "Adjust your search or source filters."
                      : "Connect cloud storage, a warehouse, a repository or an analytics workspace. Start with one source and review what it returns."
                  }
                  actions={
                    canCreate ? (
                      <a className="button primary" href="#connections/new">
                        <Icon name="plus" size={16} />
                        Add your first connection
                      </a>
                    ) : (
                      <a className="button" href="#teams">
                        Set up an owning team
                      </a>
                    )
                  }
                />
              </div>
            ))
          )}
          <PageNavigation page={sourcePage} label="connections" />
        </section>
      )}
      {confirm && selected && (
        <ConfirmDialog
          title="Disable connection?"
          description="Discovery and imports will be unavailable. Existing catalog records are retained."
          action="Disable connection"
          onClose={() => setConfirm(false)}
          onConfirm={enable}
        />
      )}
    </div>
  );
}
function DiscoveryHistory({
  connection,
  revision,
  writable,
  userId,
}: {
  connection: string;
  revision: number;
  writable: boolean;
  userId: string;
}) {
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const a = useAction(),
    page = usePage<
      Run & {
        phase: string;
        attempt_count: number;
        cancel_requested_at: string | null;
        warnings: string;
        next_attempt_at: string;
      }
    >("sync-runs", revision + a.revision, { parent: connection });
  useEffect(() => {
    const timer = window.setInterval(page.reload, 2000);
    return () => window.clearInterval(timer);
  }, [connection, page.reload]);
  return (
    <section>
      <h3>Discovery history</h3>
      <Pager page={page} label="discovery runs" />
      <Feedback error={a.error} />
      {!page.loading && !page.error && page.total === 0 && (
        <EmptyState
          icon="history"
          title="No discovery runs yet"
          description="Runs appear here after discovery is requested for this source."
        />
      )}
      {page.items.map((r) => (
        <div className="panel" key={r.id}>
          <strong>
            <Status value={r.status} /> {humanize(r.phase)}
          </strong>
          <p>
            Run {r.id} · attempt {r.attempt_count}/3 · {r.records_discovered}{" "}
            discovered objects
          </p>
          <p className="hint">
            Queued: <Timestamp value={r.queued_at} /> · Started:{" "}
            <Timestamp
              value={r.attempt_count > 0 ? r.started_at : null}
              fallback="Not started"
            />{" "}
            · Completed:{" "}
            <Timestamp value={r.finished_at} fallback="Not completed" />
          </p>
          {r.cancel_requested_at && (
            <p>
              Cancellation requested. An in-flight provider request may require
              its timeout to stop; cancelled results cannot be published.
            </p>
          )}
          {r.error_code && (
            <ConnectionFailure code={r.error_code} reference={r.id} />
          )}
          {r.status === "RETRY_WAIT" && (
            <p>Next attempt: {r.next_attempt_at}</p>
          )}
          <RunWarnings value={r.warnings} />
          <details
            onToggle={(event) => {
              const open = event.currentTarget.open;
              setExpanded((previous) => {
                const next = new Set(previous);
                if (open) next.add(r.id);
                else next.delete(r.id);
                return next;
              });
            }}
          >
            <summary>Attempt history</summary>
            {expanded.has(r.id) && (
              <AttemptHistory
                run={r.id}
                revision={
                  r.attempt_count * 10 +
                  [
                    "PENDING",
                    "RUNNING",
                    "RETRY_WAIT",
                    "COMPLETED",
                    "PARTIALLY_COMPLETED",
                    "FAILED",
                    "CANCELLED",
                  ].indexOf(r.status)
                }
              />
            )}
          </details>
          {r.import_id &&
            (writable && r.initiated_by === userId ? (
              <a href={`#import/${r.import_id}`}>Review preview</a>
            ) : (
              <small>
                Preview review is available to the authorized run initiator.
              </small>
            ))}
          {writable &&
            ["PENDING", "RUNNING", "RETRY_WAIT"].includes(r.status) && (
              <button
                disabled={a.busy || !!r.cancel_requested_at}
                onClick={() =>
                  void a.run(async () => {
                    await mutate(
                      `/connections/${connection}/sync-runs/${r.id}/cancel`,
                    );
                  })
                }
              >
                Cancel discovery
              </button>
            )}
          {writable && ["FAILED", "CANCELLED"].includes(r.status) && (
            <button
              disabled={a.busy}
              onClick={() =>
                void a.run(async () => {
                  await mutate(
                    `/connections/${connection}/sync-runs/${r.id}/retry`,
                  );
                })
              }
            >
              Retry discovery
            </button>
          )}
        </div>
      ))}
    </section>
  );
}

function AttemptHistory({ run, revision }: { run: string; revision: number }) {
  const page = usePage<Record<string, unknown>>("sync-attempts", revision, {
    parent: run,
  });
  return (
    <>
      <Pager page={page} label="attempts" />
      {page.items.map((item, i) => (
        <JsonDetails key={i} value={item} />
      ))}
    </>
  );
}
