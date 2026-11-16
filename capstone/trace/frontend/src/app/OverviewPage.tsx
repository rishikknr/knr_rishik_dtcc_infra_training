import { useEffect, useState } from "react";
import {
  EmptyState,
  Icon,
  PageHeader,
  Status,
  Timestamp,
  type IconName,
} from "../components/Design";
import { Feedback, Loading } from "../components/Feedback";
import { request } from "../services/api";
import { requireCurrentBackend } from "../services/compatibility";
import type { Asset, Connection, Session, Team } from "../types/api";
import type { Page } from "../features/integration/paging";
import { allowed } from "./permissions";

type Activity = {
  id: string;
  filename: string;
  status: string;
  created_at: string;
};
type Dashboard = {
  assets?: Page<Asset>;
  connections?: Page<Connection>;
  formulas?: Page<unknown>;
  metrics?: Page<unknown>;
  imports?: Page<Activity>;
  teams?: Page<Team>;
  errors: unknown[];
};
const summaries: {
  key: "assets" | "connections" | "formulas" | "metrics";
  label: string;
  route: string;
  icon: IconName;
}[] = [
  { key: "assets", label: "Catalog assets", route: "catalog", icon: "catalog" },
  {
    key: "connections",
    label: "Connections",
    route: "connections",
    icon: "connections",
  },
  { key: "formulas", label: "Formulas", route: "formulas", icon: "formula" },
  { key: "metrics", label: "Metrics", route: "metrics", icon: "metrics" },
];

export function OverviewPage({
  session,
  canImport,
}: {
  session: Session;
  canImport: boolean;
}) {
  const [data, setData] = useState<Dashboard | null>(null);
  const [retry, setRetry] = useState(0);
  const [summary, setSummary] = useState<{
    counts: Record<string, number>;
    attention: {
      count: number;
      title: string;
      reason: string;
      route: string;
    }[];
  } | null>(null);
  const [summaryError, setSummaryError] = useState<unknown>(null);
  useEffect(() => {
    if (!data?.assets) return;
    const c = new AbortController();
    request<NonNullable<typeof summary>>("/workspace/summary", {
      signal: c.signal,
    })
      .then((s) => {
        if (s.counts && Array.isArray(s.attention)) setSummary(s);
      })
      .catch((e) => {
        if (!c.signal.aborted) setSummaryError(e);
      });
    return () => c.abort();
  }, [retry, data?.assets]);
  const org = session.organizations.find(
    (item) => item.id === session.selectedOrganization,
  );
  useEffect(() => {
    const controller = new AbortController();
    const ready = requireCurrentBackend(controller.signal);
    const read = async <T,>(kind: string, limit: number) => {
      await ready;
      return request<Page<T>>(`/browse/${kind}?limit=${limit}`, {
        signal: controller.signal,
      });
    };
    void Promise.allSettled([
      read<Asset>("assets", 5),
      read<Connection>("connections", 5),
      read("formulas", 1),
      read("metrics", 1),
      canImport ? read<Activity>("imports", 5) : Promise.resolve(undefined),
      read<Team>("teams", 25),
    ]).then(([assets, connections, formulas, metrics, imports, teams]) => {
      if (controller.signal.aborted) return;
      const value = <T,>(result: PromiseSettledResult<T>) =>
        result.status === "fulfilled" ? result.value : undefined;
      setData({
        assets: value(assets),
        connections: value(connections),
        formulas: value(formulas),
        metrics: value(metrics),
        imports: value(imports),
        teams: value(teams),
        errors: [
          assets,
          connections,
          formulas,
          metrics,
          imports,
          teams,
        ].flatMap((result) =>
          result.status === "rejected" ? [result.reason] : [],
        ),
      });
    });
    return () => controller.abort();
  }, [retry, canImport]);
  const canWrite = data?.teams?.items.some(
    (team) => team.active && allowed(session, team.id),
  );
  return (
    <>
      <PageHeader
        area="Workspace"
        title="Overview"
        description={`Explore the sources, definitions, and metadata registered in ${org?.name ?? "this organization"}.`}
        actions={
          <>
            {canImport && (
              <a className="button" href="#import">
                <Icon name="upload" size={16} />
                Import metadata
              </a>
            )}
            {canWrite && (
              <a className="button primary" href="#connections/new">
                <Icon name="plus" size={16} />
                Add connection
              </a>
            )}
          </>
        }
      />
      <div className="journey-list" aria-label="Explore your workspace">
        <a href="#catalog">
          <span>
            <Icon name="catalog" size={16} />
            Understand a dataset <Icon name="arrow" size={14} />
          </span>
          <small>
            Find its fields, source evidence, calculations and consumers in one
            place.
          </small>
        </a>
        <a href="#impact">
          <span>
            <Icon name="lineage" size={16} />
            Plan a data change <Icon name="arrow" size={14} />
          </span>
          <small>
            Choose a scenario and inspect the recorded paths to affected
            resources.
          </small>
        </a>
        <a href="#governance">
          <span>
            <Icon name="shield" size={16} />
            Review a definition <Icon name="arrow" size={14} />
          </span>
          <small>
            Compare versioned changes and record an independent governance
            decision.
          </small>
        </a>
      </div>
      <Feedback error={summaryError} />
      {summary && (
        <section className="panel">
          <div className="section-title">
            <h2>Needs attention</h2>
            <span className="hint">Calculated from the current registry</span>
          </div>
          {summary.attention.length ? (
            summary.attention.map((item) => (
              <div className="suggestion-row" key={item.title}>
                <div>
                  <strong>
                    {item.count}{" "}
                    {item.count === 1
                      ? item.title
                          .toLowerCase()
                          .replace(
                            /^(datasets|metrics|versions|sources)/,
                            (word) => word.slice(0, -1),
                          )
                      : item.title.toLowerCase()}
                  </strong>
                  <p>{item.reason}</p>
                </div>
                <a className="button" href={`#${item.route}`}>
                  Review →
                </a>
              </div>
            ))
          ) : (
            <p className="muted">
              No missing ownership, pending reviews, failed source statuses or
              seven-day metadata observations were found by these checks.
            </p>
          )}
        </section>
      )}
      {!data ? (
        <Loading>Loading organization overview…</Loading>
      ) : (
        <>
          {data.errors.length > 0 && (
            <>
              <Feedback error={data.errors[0]} />
              <div className="action-row">
                <button
                  onClick={() => {
                    setData(null);
                    setRetry((value) => value + 1);
                  }}
                >
                  Retry overview
                </button>
              </div>
            </>
          )}
          <section
            className="panel overview-summary"
            aria-label="Registry totals"
          >
            {summaries.map(({ key, label, route, icon }) => (
              <a key={key} href={`#${route}`}>
                <span>
                  <Icon name={icon} size={16} />
                  {label}
                  <Icon name="arrow" size={13} />
                </span>
                <strong>{data[key]?.total.toLocaleString() ?? "—"}</strong>
              </a>
            ))}
          </section>
          {summary && (
            <div className="summary-strip" aria-label="Data estate">
              {Object.entries(summary.counts).map(([key, count]) => (
                <div key={key}>
                  <strong>{count}</strong>
                  <small>
                    {
                      (
                        {
                          datasets: "Datasets",
                          columns: "Known fields",
                          sqlDefinitions: "SQL definitions",
                          formulaVersions: "Formula versions",
                          reportingAssets: "Reporting resources",
                        } as Record<string, string>
                      )[key]
                    }
                  </small>
                </div>
              ))}
            </div>
          )}
          <div className="overview-grid">
            <section className="panel">
              <div className="section-title">
                <h2>Source status</h2>
                <a href="#connections">
                  All connections <span aria-hidden="true">→</span>
                </a>
              </div>
              {data.connections ? (
                data.connections.items.length ? (
                  data.connections.items.map((item) => (
                    <div className="list-row" key={item.id}>
                      <div className="provider-line">
                        <span className="resource-icon">
                          <Icon name="connections" size={16} />
                        </span>
                        <div>
                          <a href={`#connections/${item.id}`}>{item.name}</a>
                          <small>
                            {item.platform === "AMAZON_S3"
                              ? "Amazon S3"
                              : item.platform === "GITHUB_SQL"
                                ? "GitHub SQL"
                                : item.platform === "POWER_BI"
                                  ? "Power BI"
                                  : item.platform === "FILE_IMPORT"
                                    ? "File import"
                                    : item.platform === "SNOWFLAKE"
                                      ? "Snowflake"
                                      : "Manual source"}
                          </small>
                        </div>
                      </div>
                      <Status value={item.status} />
                    </div>
                  ))
                ) : (
                  <EmptyState
                    icon="connections"
                    title="No connections yet"
                    description="Source connections identify where catalog metadata comes from."
                    actions={
                      canWrite ? (
                        <a href="#connections/new">Add a connection</a>
                      ) : data.teams &&
                        session.permissions.permissions.includes(
                          "team:create",
                        ) ? (
                        <a href="#teams">Set up an owning team</a>
                      ) : undefined
                    }
                  />
                )
              ) : (
                <p className="muted">Source status unavailable.</p>
              )}
            </section>
            <section className="panel">
              <div className="section-title">
                <h2>{canImport ? "Recent imports" : "Explore the registry"}</h2>
                {canImport && (
                  <a href="#import">
                    Import Center <span aria-hidden="true">→</span>
                  </a>
                )}
              </div>
              {canImport ? (
                data.imports ? (
                  data.imports.items.length ? (
                    data.imports.items.map((item) => (
                      <div className="list-row" key={item.id}>
                        <div>
                          <a href={`#import/${item.id}`}>{item.filename}</a>
                          <small>
                            <Timestamp value={item.created_at} />
                          </small>
                        </div>
                        <Status value={item.status} />
                      </div>
                    ))
                  ) : (
                    <EmptyState
                      icon="upload"
                      title="No recent imports"
                      description="Your previews and committed imports will appear here."
                    />
                  )
                ) : (
                  <p className="muted">Import history unavailable.</p>
                )
              ) : (
                <div className="quick-links">
                  <a href="#catalog">
                    <Icon name="catalog" />
                    Browse catalog
                  </a>
                  <a href="#lineage">
                    <Icon name="lineage" />
                    Explore lineage
                  </a>
                  <a href="#metrics">
                    <Icon name="metrics" />
                    Browse metric definitions
                  </a>
                </div>
              )}
            </section>
            <section className="panel">
              <div className="section-title">
                <h2>Catalog snapshot</h2>
                <a href="#catalog">
                  Open catalog <span aria-hidden="true">→</span>
                </a>
              </div>
              {data.assets ? (
                data.assets.items.length ? (
                  data.assets.items.map((item) => (
                    <div className="list-row" key={item.id}>
                      <div>
                        <a href={`#asset/${item.id}`}>{item.name}</a>
                        <small className="qualified">{item.external_key}</small>
                      </div>
                      <span className="badge">{item.asset_type}</span>
                    </div>
                  ))
                ) : (
                  <EmptyState
                    title="No assets yet"
                    description="Registered datasets, files, and reports are listed in the catalog."
                  />
                )
              ) : (
                <p className="muted">Catalog snapshot unavailable.</p>
              )}
            </section>
            <section className="panel">
              <h2>Quick links</h2>
              <div className="quick-links">
                <a href="#lineage">
                  <Icon name="lineage" />
                  Explore lineage
                </a>
                {canWrite && (
                  <>
                    <a href="#register">
                      <Icon name="catalog" />
                      Register an asset
                    </a>
                    <a href="#metrics">
                      <Icon name="metrics" />
                      Browse metric definitions
                    </a>
                  </>
                )}
                <a href="#discovery">
                  <Icon name="history" />
                  Review discovery history
                </a>
              </div>
            </section>
          </div>
        </>
      )}
    </>
  );
}
