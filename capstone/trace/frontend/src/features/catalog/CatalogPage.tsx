import { AssetRelationships } from "./AssetRelationships";
import { ContextNote } from "../../components/Workflow";
import { useWorkspace } from "../integration/hooks";
import { AssetActivity } from "./AssetActivity";
import { request } from "../../services/api";
import { JsonDetails } from "../integration/shared";
import { useEffect, useState } from "react";
import { api } from "../../services/api";
import type { Asset, AssetColumn, Session, Team } from "../../types/api";
import { Feedback, Loading } from "../../components/Feedback";

import {
  EmptyState,
  Icon,
  PageHeader,
  Status,
  TableRegion,
  Timestamp,
} from "../../components/Design";
import { humanize } from "../../components/format";
import { PageNavigation, Pager } from "../integration/pagination";
import { usePage } from "../integration/paging";
import { allowed } from "../../app/permissions";

export function CatalogPage({
  session,
  canImport,
  initialQuery = "",
  initialSource,
  initialOwner = "",
  initialFreshness = "",
  transformationsOnly = false,
}: {
  session: Session;
  canImport: boolean;
  initialQuery?: string;
  initialSource?: string;
  initialOwner?: string;
  initialFreshness?: string;
  transformationsOnly?: boolean;
}) {
  const [type, setType] = useState("");
  const [sort, setSort] = useState("");
  const [connection, setConnection] = useState(initialSource || "");
  const [database, setDatabase] = useState("");
  const [schema, setSchema] = useState("");
  const [owner, setOwner] = useState(initialOwner);
  const [freshness, setFreshness] = useState(initialFreshness);
  const [lineage, setLineage] = useState("");
  const workspace = useWorkspace(0, { connections: true, assets: true });
  const source =
    connection && /^[a-f0-9-]{36}$/i.test(connection) ? connection : undefined;
  const effectiveType = transformationsOnly ? "SQL_TRANSFORMATION" : type;
  const assets = usePage<Asset>(
    "assets",
    0,
    {
      type: effectiveType,
      sort,
      database,
      schema,
      owner,
      freshness,
      lineage,
      ...(source ? { source } : {}),
    },
    true,
    initialQuery,
  );
  const teams = usePage<Team>(
    "teams",
    0,
    {},
    session.organizationRole === "OWNER" ||
      session.organizationRole === "ADMIN",
  );
  const canRegister =
    teams.items.some((team) => team.active) ||
    session.permissions.teamPermissions.some((team) =>
      team.permissions.includes("asset:edit"),
    );
  return (
    <>
      <PageHeader
        area={transformationsOnly ? "Definitions" : "Workspace"}
        title={transformationsOnly ? "Transformations" : "Dataset catalog"}
        description={
          transformationsOnly
            ? "Browse registered SQL transformations and their source evidence."
            : "Find the datasets, files, and reports registered in this organization."
        }
        actions={
          transformationsOnly ? (
            <a className="button" href="#formulas">
              <Icon name="formula" size={16} />
              Open Formula Studio
            </a>
          ) : (
            <>
              {canImport && (
                <a className="button" href="#import">
                  <Icon name="upload" size={16} />
                  Import metadata
                </a>
              )}
              {canRegister && (
                <a className="button primary" href="#register">
                  <Icon name="plus" size={16} />
                  Register asset
                </a>
              )}
            </>
          )
        }
      />
      {source && (
        <div className="notice">
          Showing assets from the selected connection.{" "}
          <a href="#catalog">Clear source filter</a>
        </div>
      )}
      <div className="library-filters" aria-label="Catalog filters">
        <label>
          Source
          <select
            value={connection}
            onChange={(e) => setConnection(e.target.value)}
          >
            <option value="">All sources</option>
            {workspace.connections.map((c) => (
              <option value={c.id} key={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          Database
          <select
            value={database}
            onChange={(e) => {
              setDatabase(e.target.value);
              setSchema("");
            }}
          >
            <option value="">All databases</option>
            {[
              ...new Set(
                workspace.assets
                  .filter(
                    (a) =>
                      a.platform === "SNOWFLAKE" &&
                      a.asset_type !== "SQL_TRANSFORMATION" &&
                      (!connection || a.connection_id === connection),
                  )
                  .map((a) => a.external_key.split(".")[0]),
              ),
            ]
              .sort()
              .map((d) => (
                <option key={d}>{d}</option>
              ))}
          </select>
        </label>
        <label>
          Schema
          <select value={schema} onChange={(e) => setSchema(e.target.value)}>
            <option value="">All schemas</option>
            {[
              ...new Set(
                workspace.assets
                  .filter(
                    (a) =>
                      a.platform === "SNOWFLAKE" &&
                      a.asset_type !== "SQL_TRANSFORMATION" &&
                      (!database || a.external_key.split(".")[0] === database),
                  )
                  .map((a) => a.external_key.split(".")[1]),
              ),
            ]
              .sort()
              .map((d) => (
                <option key={d}>{d}</option>
              ))}
          </select>
        </label>
        <label>
          Ownership
          <select value={owner} onChange={(e) => setOwner(e.target.value)}>
            <option value="">All owners</option>
            <option value="missing">Technical owner missing</option>
            {[
              ...new Map(
                workspace.assets.map((a) => [
                  a.owner_team_id,
                  a.owner_team_name,
                ]),
              ).entries(),
            ].map(([id, name]) => (
              <option key={id} value={id}>
                {name || "Unnamed team"}
              </option>
            ))}
          </select>
        </label>
        <label>
          Metadata observation
          <select
            value={freshness}
            onChange={(e) => setFreshness(e.target.value)}
          >
            <option value="">Any observation date</option>
            <option value="recent-7d">Observed in last 7 days</option>
            <option value="older-7d">Not observed for 7 days</option>
            <option value="unknown">Observation unknown</option>
          </select>
        </label>
        <label>
          Lineage
          <select value={lineage} onChange={(e) => setLineage(e.target.value)}>
            <option value="">Any lineage state</option>
            <option value="recorded">Has recorded relationships</option>
            <option value="missing">No asset relationships recorded</option>
          </select>
        </label>
      </div>
      <p className="hint">
        Observation dates describe catalog metadata. They do not establish when
        business data was refreshed.
      </p>
      <Feedback error={workspace.error} />
      <section className="panel table-panel">
        <Pager
          page={assets}
          label={transformationsOnly ? "transformations" : "assets"}
          separateNavigation
          filters={
            <>
              {!transformationsOnly && (
                <label className="toolbar-filter">
                  <span className="sr-only">Asset type</span>
                  <select
                    value={type}
                    onChange={(event) => setType(event.target.value)}
                  >
                    <option value="">All asset types</option>
                    {[
                      "FILE",
                      "DATASET",
                      "TABLE",
                      "VIEW",
                      "SQL_TRANSFORMATION",
                      "SEMANTIC_MODEL",
                      "REPORT",
                      "VISUAL",
                      "MEASURE",
                      "WORKSPACE",
                    ].map((value) => (
                      <option key={value} value={value}>
                        {humanize(value)}
                      </option>
                    ))}
                  </select>
                </label>
              )}
              <label className="toolbar-filter">
                <span className="sr-only">Sort assets</span>
                <select
                  value={sort}
                  onChange={(event) => setSort(event.target.value)}
                >
                  <option value="">Name A–Z</option>
                  <option value="name-desc">Name Z–A</option>
                  <option value="last-seen">Last seen, newest</option>
                </select>
              </label>
            </>
          }
        />
        {assets.loading ? (
          <Loading>Loading catalog…</Loading>
        ) : (
          !assets.error &&
          (assets.items.length ? (
            <TableRegion label="Catalog assets">
              <table className="wide-table">
                <thead>
                  <tr>
                    <th scope="col">Asset</th>
                    <th scope="col">Type</th>
                    <th scope="col">Source</th>
                    <th scope="col">Owning team</th>
                    <th scope="col">Metadata</th>
                    <th scope="col">Columns</th>
                    <th scope="col">Last seen</th>
                  </tr>
                </thead>
                <tbody>
                  {assets.items.map((asset) => (
                    <tr key={asset.id}>
                      <td>
                        <div className="provider-line">
                          <span className="resource-icon">
                            <Icon
                              name={
                                asset.asset_type === "FILE"
                                  ? "file"
                                  : asset.asset_type === "SQL_TRANSFORMATION"
                                    ? "code"
                                    : "catalog"
                              }
                              size={16}
                            />
                          </span>
                          <div>
                            <a href={`#asset/${asset.id}`}>{asset.name}</a>
                            <small className="qualified">
                              {asset.external_key}
                            </small>
                            {!!asset.tags?.length && (
                              <div className="tag-list">
                                {asset.tags.slice(0, 3).map((tag) => (
                                  <span className="badge" key={tag}>
                                    {tag}
                                  </span>
                                ))}
                                {asset.tags.length > 3 && (
                                  <span
                                    className="badge"
                                    title={asset.tags.slice(3).join(", ")}
                                  >
                                    +{asset.tags.length - 3}
                                  </span>
                                )}
                              </div>
                            )}
                          </div>
                        </div>
                      </td>
                      <td>
                        <span className="badge">
                          {asset.object_type === "VIEW"
                            ? "View"
                            : asset.object_type === "BASE TABLE"
                              ? "Table"
                              : humanize(asset.asset_type)}
                        </span>
                      </td>
                      <td>
                        {asset.connection_name}
                        <small>{asset.platform}</small>
                      </td>
                      <td>
                        {asset.owner_team_name ?? "Unassigned"}
                        {asset.owner && (
                          <small>Technical · {asset.owner}</small>
                        )}
                        {asset.business_owner && (
                          <small>Business · {asset.business_owner}</small>
                        )}
                      </td>
                      <td>
                        {asset.inventory_state && (
                          <Status
                            value={
                              asset.inventory_state ===
                              "NOT_SEEN_IN_LATEST_COMPLETE_INVENTORY"
                                ? "NOT_SEEN"
                                : asset.inventory_state
                            }
                          />
                        )}
                      </td>
                      <td className="numeric">{asset.column_count}</td>
                      <td>
                        <Timestamp
                          value={asset.last_seen_at}
                          fallback="Not recorded"
                        />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </TableRegion>
          ) : (
            <EmptyState
              title={
                assets.query || type
                  ? "No matching assets"
                  : transformationsOnly
                    ? "No transformations yet"
                    : "No assets yet"
              }
              description={
                assets.query || type
                  ? "Change the search or asset type filter to find a record."
                  : transformationsOnly
                    ? "Registered SQL transformation assets will appear here."
                    : "Import metadata or register an asset to give your team a shared catalog."
              }
              actions={
                canImport &&
                !transformationsOnly && (
                  <a className="button" href="#import">
                    Import metadata
                  </a>
                )
              }
            />
          ))
        )}
        <PageNavigation
          page={assets}
          label={transformationsOnly ? "transformations" : "assets"}
        />
      </section>
    </>
  );
}
export function AssetPage({ id, session }: { id: string; session: Session }) {
  const [retry, setRetry] = useState(0);
  const [section, setSection] = useState("overview");
  const key = `${id}:${retry}`;
  const [resource, setResource] = useState<{
    key: string;
    data: { asset: Asset; columns: AssetColumn[] } | null;
    error: unknown;
  } | null>(null);
  const data = resource?.key === key ? resource.data : null;
  const error = resource?.key === key ? resource.error : null;
  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      api.asset(id, controller.signal),
      api.columns(id, controller.signal),
    ])
      .then(([asset, columns]) => {
        if (!controller.signal.aborted)
          setResource({ key, data: { asset, columns }, error: null });
      })
      .catch((error) => {
        if (!controller.signal.aborted) setResource({ key, data: null, error });
      });
    return () => controller.abort();
  }, [id, retry, key]);
  return (
    <>
      <a className="back" href="#catalog">
        ← Dataset catalog
      </a>
      <Feedback error={error} />
      {error ? (
        <button onClick={() => setRetry((v) => v + 1)}>Retry</button>
      ) : !data ? (
        <Loading>Loading dataset and columns…</Loading>
      ) : (
        <>
          <PageHeader
            area={`Catalog / ${humanize(data.asset.asset_type)}`}
            title={data.asset.name}
            description={<code>{data.asset.external_key}</code>}
            actions={
              <>
                {allowed(session, data.asset.owner_team_id) && (
                  <a className="button" href={`#edit-asset/${data.asset.id}`}>
                    Edit metadata
                  </a>
                )}
                <a className="button" href={`#impact/${data.asset.id}`}>
                  Analyze potential impact
                </a>
                <a
                  className="button primary"
                  href={`#lineage/${data.asset.id}`}
                >
                  <Icon name="lineage" size={16} />
                  Explore lineage
                </a>
              </>
            }
          />
          <nav className="workbench-tabs" aria-label="Dataset sections">
            {[
              ["overview", "Overview"],
              ["schema", "Schema"],
              ["lineage", "Lineage"],
              ["transformations", "Transformations"],
              ["definitions", "Formulas & metrics"],
              ["consumers", "Consumers"],
              ["activity", "Activity"],
            ].map(([key, label]) => (
              <button
                key={key}
                aria-pressed={section === key}
                onClick={() => setSection(key)}
              >
                {label}
              </button>
            ))}
          </nav>
          {!["overview", "schema", "activity"].includes(section) && (
            <section className="panel">
              <AssetRelationships
                id={data.asset.id}
                section={
                  section as
                    | "lineage"
                    | "transformations"
                    | "definitions"
                    | "consumers"
                }
              />
            </section>
          )}
          <section className="panel" hidden={section !== "overview"}>
            <h2>Asset overview</h2>
            <ContextNote title="Understand this dataset">
              Start with its source and ownership below. Schema lists the
              registered fields; Lineage shows recorded inputs and outputs;
              Transformations explains SQL evidence; Formulas & metrics and
              Consumers show registered usage.
            </ContextNote>
            <dl className="details">
              <div>
                <dt>Connection</dt>
                <dd>
                  <a href={`#connections/${data.asset.connection_id}`}>
                    {data.asset.connection_name}
                  </a>
                </dd>
              </div>
              <div>
                <dt>Owner team</dt>
                <dd>{data.asset.owner_team_name ?? "Unassigned"}</dd>
              </div>
              <div>
                <dt>Owner metadata</dt>
                <dd>{data.asset.owner ?? "Not recorded"}</dd>
              </div>
              <div>
                <dt>Metadata last observed</dt>
                <dd>
                  {data.asset.last_seen_at
                    ? new Date(data.asset.last_seen_at).toLocaleString()
                    : "Not recorded"}
                </dd>
              </div>
            </dl>
            <p className="hint">
              Actual business-data refresh: not available from the catalog
              observation timestamp.
            </p>
            <SourceEvidence key={data.asset.id} id={data.asset.id} />
          </section>
          <section className="panel" hidden={section !== "schema"}>
            <div className="section-title">
              <h2>Registered columns</h2>
              <span className="badge">{data.columns.length} columns</span>
            </div>
            {data.columns.length === 0 ? (
              <p>No columns registered.</p>
            ) : (
              <TableRegion label="Asset columns">
                <table>
                  <thead>
                    <tr>
                      <th>Column</th>
                      <th>Inventory</th>
                      <th>Data type</th>
                      <th>Nullable</th>
                      <th>Description</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.columns.map((c) => (
                      <tr key={c.id}>
                        <td>
                          <strong>{c.name}</strong>
                          <small>
                            <a
                              href={`#lineage/${data.asset.id}?column=${c.id}`}
                            >
                              Trace field
                            </a>{" "}
                            ·{" "}
                            <a href={`#impact/${data.asset.id}?column=${c.id}`}>
                              Plan a change
                            </a>
                          </small>
                        </td>
                        <td>
                          <Status
                            value={
                              c.inventory_state ===
                              "NOT_SEEN_IN_LATEST_COMPLETE_INVENTORY"
                                ? "NOT_SEEN"
                                : (c.inventory_state ?? "CURRENT")
                            }
                          />
                        </td>
                        <td>
                          <code>{c.data_type}</code>
                        </td>
                        <td>
                          {c.nullable == null
                            ? "Unknown"
                            : c.nullable
                              ? "Yes"
                              : "No"}
                        </td>
                        <td>{c.description ?? "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </TableRegion>
            )}
          </section>
          <div hidden={section !== "activity"}>
            <AssetActivity key={data.asset.id} id={data.asset.id} />
            <AssetActivity
              key={`metadata:${data.asset.id}`}
              id={data.asset.id}
              sourceHistory
            />
          </div>
        </>
      )}
    </>
  );
}

function SourceEvidence({ id }: { id: string }) {
  const [metadata, setMetadata] = useState<Record<string, unknown> | null>(
      null,
    ),
    [error, setError] = useState<unknown>(null);
  useEffect(() => {
    let active = true;
    request<Record<string, unknown>>(`/assets/${id}/curation`)
      .then((data) => {
        if (active) setMetadata(data);
      })
      .catch((e) => {
        if (active) setError(e);
      });
    return () => {
      active = false;
    };
  }, [id]);
  return (
    <>
      <p className="record-description">
        {metadata
          ? String(
              metadata.description ||
                "No business description has been recorded for this asset.",
            )
          : "Reading source description…"}
      </p>
      {metadata && (
        <p className="hint">
          Metadata provenance:{" "}
          {humanize(String(metadata.provenance || "Not recorded"))}. Ownership
          is displayed as recorded; it is not inferred from repository authors.
        </p>
      )}
      <details>
        <summary>Source metadata and provenance</summary>
        <Feedback error={error} />
        {metadata ? (
          <>
            <dl className="details">
              {(
                [
                  "description",
                  "business_owner",
                  "technical_owner",
                  "inventory_state",
                ] as const
              ).map((key) => (
                <div key={key}>
                  <dt>{humanize(key)}</dt>
                  <dd>{String(metadata[key] || "Not recorded")}</dd>
                </div>
              ))}
            </dl>
            <JsonDetails
              value={{
                ...metadata,
                metadata:
                  typeof metadata.metadata === "string"
                    ? JSON.parse(metadata.metadata)
                    : metadata.metadata,
              }}
            />
          </>
        ) : (
          !error && <p role="status">Loading source evidence…</p>
        )}
      </details>
    </>
  );
}
