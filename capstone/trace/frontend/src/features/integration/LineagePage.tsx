import { ContextNote, Steps } from "../../components/Workflow";
import { usePage, loadOptions } from "./paging";
import { Pager } from "./pagination";
import { useEffect, useState } from "react";
import { mutate, request } from "../../services/api";
import type { AssetColumn, Session } from "../../types/api";
import { ConfirmDialog, Icon, PageHeader } from "../../components/Design";
import { humanize } from "../../components/format";
import { Feedback, Loading, Success } from "../../components/Feedback";
import { AssetSelect, JsonDetails } from "./shared";
import { useAction, useWorkspace } from "./hooks";
import { allowed } from "../../app/permissions";
import { LineageGraph, type Node, type Edge } from "./LineageGraph";
import { DataFlowOverview } from "./DataFlowOverview";

type Graph = {
  nodes: Node[];
  edges: Edge[];
  columnEdges: {
    id: string;
    source_asset_id: string;
    target_asset_id: string;
    source_column: string;
    target_column: string;
    provenance: string;
    evidence_ref: string;
  }[];
  history: Edge[];
  truncated: boolean;
  label: string;
  totalNodes: number;
  totalEdges: number;
  hasMore: boolean;
  nextOffset: number;
  snapshot: string;
  limits?: { maximumNodes: number; maximumEdges: number };
};
export function LineagePage({
  session,
  initialRoot,
  impact = false,
}: {
  session: Session;
  initialRoot?: string;
  impact?: boolean;
}) {
  const [scenario, setScenario] = useState(
    new URLSearchParams(initialRoot?.split("?")[1]).get("change") ||
      "Modify SQL transformation",
  );
  const [impactView, setImpactView] = useState("results");
  const a = useAction(),
    w = useWorkspace(a.revision, { assets: true });
  const [root, setRoot] = useState(initialRoot?.split("?")[0] ?? ""),
    [source, setSource] = useState(""),
    [target, setTarget] = useState(""),
    [kind, setKind] = useState("DATA_FLOW"),
    [resource, setResource] = useState<{
      key: string;
      graph: Graph | null;
      error: unknown;
    } | null>(null),
    [offset, setOffset] = useState(0),
    [snapshot, setSnapshot] = useState(""),
    [explorerQuery, setExplorerQuery] = useState(""),
    [assetTypeFilter, setAssetTypeFilter] = useState(""),
    [columnId, setColumnId] = useState(
      new URLSearchParams(initialRoot?.split("?")[1]).get("column") ?? "",
    ),
    [mode, setMode] = useState(
      impact
        ? "impact"
        : new URLSearchParams(initialRoot?.split("?")[1]).has("column")
          ? "column"
          : "dataset",
    ),
    [rootColumns, setRootColumns] = useState<AssetColumn[]>([]),
    [direction, setDirection] = useState(
      impact
        ? "downstream"
        : new URLSearchParams(initialRoot?.split("?")[1]).get("direction") ||
            "both",
    ),
    [sourceColumns, setSourceColumns] = useState<AssetColumn[]>([]),
    [targetColumns, setTargetColumns] = useState<AssetColumn[]>([]),
    [error, setError] = useState<unknown>(null);
  const [confirm, setConfirm] = useState<{
    id: string;
    column: boolean;
  } | null>(null);
  const graphKey = `${root}:${offset}:${direction}:${columnId}:${a.revision}`;
  const graph = root && resource?.key === graphKey ? resource.graph : null;
  const graphError = root && resource?.key === graphKey ? resource.error : null;
  useEffect(() => {
    if (!root) return;
    const controller = new AbortController();
    request<Graph>(
      `/lineage/${root}?direction=${direction}&offset=${offset}&limit=80${snapshot ? `&snapshot=${snapshot}` : ""}${columnId ? `&columnId=${columnId}` : ""}`,
      {
        signal: controller.signal,
      },
    )
      .then((value) => {
        if (!controller.signal.aborted)
          setResource({ key: graphKey, graph: value, error: null });
      })
      .catch((error) => {
        if (!controller.signal.aborted)
          setResource({ key: graphKey, graph: null, error });
      });
    return () => controller.abort();
  }, [root, offset, direction, graphKey, snapshot, columnId]);
  useEffect(() => {
    let active = true;
    if (root && w.assets.some((x) => x.id === root))
      loadOptions<AssetColumn>("columns", { parent: root })
        .then((c) => {
          if (active) setRootColumns(c);
        })
        .catch((e) => {
          if (active) setError(e);
        });
    return () => {
      active = false;
    };
  }, [root, w.assets]);
  function explore(id: string, dir = direction, column = "") {
    setRoot(id);
    setRootColumns([]);
    setDirection(dir);
    setOffset(0);
    setSnapshot("");
    setColumnId(column);
    window.location.hash = `${impact || mode === "impact" ? "impact" : "lineage"}/${id}?direction=${dir}${column ? `&column=${column}` : ""}${impact ? `&change=${encodeURIComponent(scenario)}` : ""}`;
  }

  useEffect(() => {
    let active = true;
    if (source && target)
      Promise.all([
        loadOptions<AssetColumn>("columns", { parent: source }),
        loadOptions<AssetColumn>("columns", { parent: target }),
      ])
        .then(([s, t]) => {
          if (active) {
            setSourceColumns(s);
            setTargetColumns(t);
          }
        })
        .catch((e) => {
          if (active) setError(e);
        });
    return () => {
      active = false;
    };
  }, [source, target]);
  const explorerAssets = w.assets.filter(
    (x) => !assetTypeFilter || x.asset_type === assetTypeFilter,
  );
  const writable = w.assets.filter((x) => allowed(session, x.owner_team_id));
  return (
    <>
      <PageHeader
        area="Workspace"
        title={impact ? "Impact Analysis" : "Data Flow"}
        description={
          impact
            ? "Trace the potential effects of a hypothetical change through recorded dependencies."
            : "Follow your data across systems, transformations and business definitions."
        }
        actions={
          !impact &&
          writable.length > 0 && (
            <a
              className="button primary"
              href="#lineage"
              onClick={(event) => {
                event.preventDefault();
                const form = document.getElementById("manual-mapping");
                const disclosure = form?.closest("details");
                if (disclosure) disclosure.open = true;
                form?.focus();
              }}
            >
              <Icon name="plus" size={16} />
              Declare mapping
            </a>
          )
        }
      />
      <Feedback error={a.error ?? w.error ?? error ?? graphError} />
      {a.notice && <Success>{a.notice}</Success>}
      {impact && !root ? (
        <ImpactStart
          assets={w.assets}
          loading={w.loading}
          scenario={scenario}
          onScenario={setScenario}
          onAnalyze={(id, column) => explore(id, "downstream", column)}
        />
      ) : (
        <>
          {!impact && (
            <nav className="lineage-modes" aria-label="Lineage modes">
              <a className="button" href="#data-flow">
                System lineage
              </a>
              {["dataset", "column", "metric", "impact"].map((m) => (
                <button
                  key={m}
                  aria-pressed={mode === m}
                  onClick={() => {
                    setMode(m);
                    setOffset(0);
                    setSnapshot("");
                    setColumnId("");
                    if (m === "impact") setDirection("downstream");
                  }}
                >
                  {humanize(m)} lineage
                </button>
              ))}
            </nav>
          )}
          {impact && root && (
            <>
              <Steps
                labels={["Choose change", "Select resource", "Inspect impact"]}
                current={2}
              />
              <ContextNote
                title={`${scenario} · ${w.assets.find((a) => a.id === root)?.external_key || "Selected registered definition"}`}
              >
                This is a read-only dependency assessment. The proposed change
                labels the scenario; Trace does not simulate SQL execution or
                guarantee breakage.{" "}
                <a href="#impact">Start another analysis →</a>
              </ContextNote>
            </>
          )}
          <div
            className={`lineage-three-panel${impact ? " impact-workspace" : ""}`}
          >
            <aside
              hidden={impact}
              className="lineage-explorer"
              aria-label="Source and asset explorer"
            >
              <p className="eyebrow">Explorer</p>
              <h2>Registered environment</h2>
              <label>
                Find an asset
                <input
                  type="search"
                  value={explorerQuery}
                  onChange={(e) => setExplorerQuery(e.target.value)}
                  placeholder="Name, schema or source…"
                />
              </label>
              <label>
                Direction
                <select
                  value={direction}
                  onChange={(e) => {
                    if (root) explore(root, e.target.value, columnId);
                    else setDirection(e.target.value);
                  }}
                >
                  <option value="both">Upstream + downstream</option>
                  <option value="upstream">Upstream only</option>
                  <option value="downstream">
                    Downstream potential impact
                  </option>
                </select>
              </label>
              <label>
                Asset type filter
                <select
                  value={assetTypeFilter}
                  onChange={(e) => setAssetTypeFilter(e.target.value)}
                >
                  <option value="">All asset types</option>
                  {[...new Set(w.assets.map((x) => x.asset_type))]
                    .sort()
                    .map((type) => (
                      <option key={type} value={type}>
                        {humanize(type)}
                      </option>
                    ))}
                </select>
              </label>
              <p className="hint">
                All reachable levels · up to 80 relationships per page
              </p>
              {w.loading && <Loading>Loading sources…</Loading>}
              {[...new Set(explorerAssets.map((x) => x.connection_name))].map(
                (connection) => (
                  <details key={connection} open>
                    <summary>
                      {connection}
                      <span className="badge">
                        {
                          explorerAssets.filter(
                            (x) => x.connection_name === connection,
                          ).length
                        }
                      </span>
                    </summary>
                    {[
                      ...new Set(
                        explorerAssets
                          .filter((x) => x.connection_name === connection)
                          .map((x) =>
                            x.asset_type === "SQL_TRANSFORMATION"
                              ? "SQL transformations"
                              : x.platform === "SNOWFLAKE"
                                ? x.external_key
                                    .split(".")
                                    .slice(0, 2)
                                    .join(".")
                                : humanize(x.asset_type),
                          ),
                      ),
                    ].map((schema) => (
                      <details
                        key={schema}
                        open={!!explorerQuery || schema.includes("ANALYTICS")}
                      >
                        <summary>{schema}</summary>
                        {explorerAssets
                          .filter(
                            (x) =>
                              x.connection_name === connection &&
                              (x.asset_type === "SQL_TRANSFORMATION"
                                ? "SQL transformations"
                                : x.platform === "SNOWFLAKE"
                                  ? x.external_key
                                      .split(".")
                                      .slice(0, 2)
                                      .join(".")
                                  : humanize(x.asset_type)) === schema &&
                              `${x.name} ${x.external_key}`
                                .toLowerCase()
                                .includes(explorerQuery.toLowerCase()),
                          )
                          .map((asset) => (
                            <button
                              className="explorer-asset"
                              aria-pressed={root === asset.id}
                              key={asset.id}
                              onClick={() => explore(asset.id)}
                              title={asset.external_key}
                            >
                              {asset.name}
                            </button>
                          ))}
                      </details>
                    ))}
                  </details>
                ),
              )}
            </aside>
            <section className="lineage-main">
              {!root ? (
                <DataFlowOverview onExplore={explore} />
              ) : (
                <>
                  <header className="graph-scope">
                    <div>
                      <p className="eyebrow">
                        {mode === "impact"
                          ? "Hypothetical change"
                          : "Recorded dependency graph"}
                      </p>
                      <h2>
                        {w.assets.find((x) => x.id === root)?.name ||
                          graph?.nodes.find((x) => x.id === root)?.name ||
                          "Selected registry resource"}
                      </h2>
                    </div>
                    <button
                      onClick={() => {
                        setOffset(0);
                        setSnapshot("");
                        a.setNotice("");
                        setResource(null);
                        void request<Graph>(
                          `/lineage/${root}?direction=${direction}&limit=80${columnId ? `&columnId=${columnId}` : ""}`,
                        )
                          .then((value) =>
                            setResource({
                              key: graphKey,
                              graph: value,
                              error: null,
                            }),
                          )
                          .catch((e) => setError(e));
                      }}
                    >
                      Refresh graph
                    </button>
                  </header>
                  {(mode === "column" || mode === "impact" || columnId) && (
                    <div className="graph-scenario">
                      <label>
                        Column scope
                        <select
                          value={columnId}
                          onChange={(e) => {
                            setColumnId(e.target.value);
                            setOffset(0);
                            setSnapshot("");
                          }}
                        >
                          <option value="">Entire asset</option>
                          {rootColumns.map((c) => (
                            <option key={c.id} value={c.id}>
                              {c.name} · {c.data_type}
                            </option>
                          ))}
                        </select>
                      </label>
                      {mode === "impact" && (
                        <label>
                          Proposed change
                          <select
                            value={scenario}
                            onChange={(e) => setScenario(e.target.value)}
                          >
                            {changeScenarios.map((s) => (
                              <option key={s[0]}>{s[0]}</option>
                            ))}
                          </select>
                        </label>
                      )}
                      <p className="hint">
                        {mode === "impact"
                          ? "Read-only scenario. Dependencies indicate potential effects; external systems are unchanged."
                          : "Only recorded column dependencies are shown. Missing mappings remain unknown."}
                      </p>
                    </div>
                  )}
                  {!graph && !graphError && (
                    <Loading>Traversing recorded dependencies…</Loading>
                  )}
                  {graph && (
                    <>
                      <div className="graph-statbar" role="status">
                        <strong>{graph.totalNodes} reachable nodes</strong>
                        <span>{graph.totalEdges} recorded relationships</span>
                        <span>No depth limit</span>
                      </div>
                      {mode === "metric" &&
                        !graph.nodes.some(
                          (n) => n.asset_type === "METRIC_VERSION",
                        ) && (
                          <p className="notice">
                            No registered metric version is reachable in this
                            scope. <a href="#metrics">Open metric registry</a>
                          </p>
                        )}
                      {mode === "impact" && (
                        <>
                          <nav
                            className="workbench-tabs impact-view-tabs"
                            aria-label="Impact views"
                          >
                            <button
                              aria-pressed={impactView === "results"}
                              onClick={() => setImpactView("results")}
                            >
                              Affected resources & paths
                            </button>
                            <button
                              aria-pressed={impactView === "graph"}
                              onClick={() => setImpactView("graph")}
                            >
                              Dependency graph
                            </button>
                          </nav>
                          {impactView === "results" && (
                            <ImpactSummary
                              graph={graph}
                              root={columnId || root}
                              assetRoot={root}
                              columnId={columnId}
                            />
                          )}
                        </>
                      )}
                      {(mode !== "impact" || impactView === "graph") && (
                        <LineageGraph
                          key={mode}
                          root={
                            mode === "metric"
                              ? graph.nodes.find(
                                  (n) => n.asset_type === "METRIC_VERSION",
                                )?.id || root
                              : columnId || root
                          }
                          nodes={graph.nodes}
                          edges={graph.edges}
                          onExplore={(id, dir) => {
                            const n = graph.nodes.find((x) => x.id === id);
                            if (n?.asset_type === "COLUMN") {
                              const m = JSON.parse(n.metadata || "{}");
                              explore(String(m.assetId), dir);
                              setColumnId(id);
                            } else explore(id, dir);
                          }}
                        />
                      )}
                      <div className="graph-pagination">
                        <p>
                          Showing relationships{" "}
                          {graph.edges.length ? offset + 1 : 0}–
                          {offset + graph.edges.length} of {graph.totalEdges}.{" "}
                          {graph.truncated
                            ? "This is a portion of the complete reachable graph; continue to inspect the remaining relationships."
                            : "Complete reachable graph shown."}
                        </p>
                        <button
                          disabled={offset === 0}
                          onClick={() => setOffset(Math.max(0, offset - 80))}
                        >
                          Previous graph page
                        </button>
                        <button
                          disabled={!graph.hasMore}
                          onClick={() => {
                            setSnapshot(graph.snapshot);
                            setOffset(graph.nextOffset);
                          }}
                        >
                          Continue exploration
                        </button>
                      </div>
                      <details className="panel">
                        <summary>
                          Relationship evidence and mapping history
                        </summary>
                        {graph.edges.map((e) => (
                          <details key={e.id}>
                            <summary>
                              {
                                graph.nodes.find(
                                  (n) => n.id === e.source_asset_id,
                                )?.name
                              }{" "}
                              →{" "}
                              {
                                graph.nodes.find(
                                  (n) => n.id === e.target_asset_id,
                                )?.name
                              }
                            </summary>
                            <p>
                              {humanize(e.provenance)} · {e.evidence_ref}
                            </p>
                            <p>{e.description}</p>
                            {!e.relationship_type.startsWith("METRIC_") &&
                              !e.relationship_type.startsWith("FORMULA_") &&
                              writable.some(
                                (x) => x.id === e.source_asset_id,
                              ) &&
                              writable.some(
                                (x) => x.id === e.target_asset_id,
                              ) && (
                                <button
                                  onClick={() =>
                                    setConfirm({
                                      id: e.id,
                                      column:
                                        e.relationship_type ===
                                        "COLUMN_DERIVATION",
                                    })
                                  }
                                >
                                  Revoke mapping
                                </button>
                              )}
                          </details>
                        ))}
                        <MappingHistory root={root} revision={a.revision} />
                      </details>
                    </>
                  )}
                </>
              )}
            </section>
          </div>
        </>
      )}
      {!impact && writable.length > 0 && (
        <details className="manual-mapping-disclosure">
          <summary>Declare an evidence-backed relationship</summary>
          <form
            className="panel"
            id="manual-mapping"
            tabIndex={-1}
            onSubmit={(e) => {
              e.preventDefault();
              const f = e.currentTarget,
                d = new FormData(f);
              void a.run(async () => {
                await mutate("/lineage/mappings", {
                  sourceAssetId: source,
                  targetAssetId: target,
                  sourceColumnId: d.get("sourceColumnId") || null,
                  targetColumnId: d.get("targetColumnId") || null,
                  relationshipType: kind,
                  evidenceRef: d.get("evidenceRef"),
                  description: d.get("description"),
                  supersedes: d.get("supersedes") || null,
                });
                a.setNotice("Manual relationship stored with provenance.");
                f.querySelector<HTMLTextAreaElement>("textarea")?.blur();
              });
            }}
          >
            <h2>Declare a mapping</h2>
            <AssetSelect
              assets={writable}
              name="source"
              label="Upstream asset"
              value={source}
              onChange={setSource}
            />
            <AssetSelect
              assets={writable}
              name="target"
              label="Downstream asset"
              value={target}
              onChange={setTarget}
            />
            <label>
              Relationship type
              <select value={kind} onChange={(e) => setKind(e.target.value)}>
                {[
                  "DATA_FLOW",
                  "COLUMN_DERIVATION",
                  "TRANSFORMATION_INPUT",
                  "TRANSFORMATION_OUTPUT",
                  "MODEL_SOURCE",
                  "REPORT_USES_MODEL",
                  "VISUAL_USES_MEASURE",
                  "REPORT_CONTAINS_VISUAL",
                  "MODEL_CONTAINS_MEASURE",
                ].map((k) => (
                  <option key={k} value={k}>
                    {humanize(k)}
                  </option>
                ))}
              </select>
            </label>
            {kind === "COLUMN_DERIVATION" && (
              <>
                <label>
                  Source column
                  <select name="sourceColumnId" required>
                    <option value="">Select source column</option>
                    {sourceColumns.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Target column
                  <select name="targetColumnId" required>
                    <option value="">Select target column</option>
                    {targetColumns.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.name}
                      </option>
                    ))}
                  </select>
                </label>
                <p className="hint">
                  Add another mapping to express multiple source columns
                  deriving one target.
                </p>
              </>
            )}
            <label>
              Evidence / source reference
              <input name="evidenceRef" required maxLength={2000} />
            </label>
            <label>
              Description / derivation expression
              <textarea name="description" maxLength={4000} />
            </label>
            <details>
              <summary>Supersede an existing asset mapping</summary>
              <label>
                Previous mapping ID
                <input name="supersedes" pattern="[a-fA-F0-9-]{36}" />
              </label>
            </details>
            <button className="primary" disabled={a.busy}>
              Save manual mapping
            </button>
          </form>
        </details>
      )}
      {confirm && (
        <ConfirmDialog
          title="Revoke this mapping?"
          description="This relationship will be removed from the active graph. Its evidence will remain in mapping history."
          action="Revoke mapping"
          onClose={() => setConfirm(null)}
          onConfirm={() =>
            void a.run(async () => {
              await mutate(`/lineage/mappings/${confirm.id}/revoke`);
              a.setNotice(
                confirm.column
                  ? "Column mapping revoked; history retained."
                  : "Mapping revoked; history retained.",
              );
            })
          }
        />
      )}
    </>
  );
}

function MappingHistory({
  root,
  revision,
}: {
  root: string;
  revision: number;
}) {
  const edges = usePage<Record<string, unknown>>("mappings", revision, {
      parent: root,
    }),
    columns = usePage<Record<string, unknown>>("column-mappings", revision, {
      parent: root,
    });
  return (
    <>
      <h4>Asset mapping history</h4>
      <Pager page={edges} label="asset mappings" />
      {edges.items.map((e, i) => (
        <JsonDetails key={i} value={e} />
      ))}
      <h4>Column mapping history</h4>
      <Pager page={columns} label="column mappings" />
      {columns.items.map((e, i) => (
        <JsonDetails key={i} value={e} />
      ))}
    </>
  );
}

const changeScenarios = [
  [
    "Remove a column",
    "Find calculations and consumers that use a recorded field.",
  ],
  ["Rename a column", "Review references that may need a new field name."],
  [
    "Change a data type",
    "Inspect downstream expressions for compatibility review.",
  ],
  [
    "Modify SQL transformation",
    "See the datasets and definitions downstream of an output.",
  ],
  [
    "Change a formula",
    "Inspect dependencies of a registered calculation version.",
  ],
  [
    "Register metric version",
    "Review consumers of an existing metric definition.",
  ],
  [
    "Remove a source dataset",
    "Follow all recorded downstream dependency paths.",
  ],
  ["Change view definition", "Inspect the consumers of a registered view."],
];
function ImpactStart({
  assets,
  loading,
  scenario,
  onScenario,
  onAnalyze,
}: {
  assets: import("../../types/api").Asset[];
  loading: boolean;
  scenario: string;
  onScenario: (s: string) => void;
  onAnalyze: (id: string, column: string) => void;
}) {
  const [asset, setAsset] = useState(""),
    [column, setColumn] = useState(""),
    [columns, setColumns] = useState<AssetColumn[]>([]),
    [error, setError] = useState<unknown>(null);
  const columnChange =
    scenario.toLowerCase().includes("column") ||
    scenario === "Change a data type";
  useEffect(() => {
    if (!asset) return;
    let active = true;
    loadOptions<AssetColumn>("columns", { parent: asset })
      .then((c) => {
        if (active) {
          setColumns(c);
          setError(null);
        }
      })
      .catch((e) => {
        if (active) setError(e);
      });
    return () => {
      active = false;
    };
  }, [asset]);
  return (
    <div className="scenario-start">
      <Steps
        labels={["Choose change", "Select resource", "Inspect impact"]}
        current={asset ? 1 : 0}
      />
      <h2>What are you planning to change?</h2>
      <p className="muted">
        Choose a scenario, then a registered dataset or field. Trace follows
        recorded dependencies to help you decide what needs review.
      </p>
      <div className="scenario-grid">
        {changeScenarios.map(([name, description]) => (
          <button
            key={name}
            className="scenario-option"
            aria-pressed={scenario === name}
            onClick={() => {
              onScenario(name);
              setColumn("");
            }}
          >
            <Icon
              name={name.includes("column") ? "catalog" : "code"}
              size={18}
            />
            <span>
              {name}
              <small>{description}</small>
            </span>
          </button>
        ))}
      </div>
      <section className="panel">
        <h2>Choose the affected resource</h2>
        {loading ? (
          <Loading>Loading registered datasets…</Loading>
        ) : (
          <>
            <AssetSelect
              assets={assets.filter(
                (a) => a.asset_type !== "SQL_TRANSFORMATION",
              )}
              name="impactAsset"
              label="Dataset or reporting resource"
              value={asset}
              onChange={(id) => {
                setAsset(id);
                setColumn("");
              }}
            />
            <small>
              For a formula or metric version, open its detail page and follow
              its lineage or impact link.
            </small>
            {columnChange && (
              <label>
                Column
                <select
                  value={column}
                  onChange={(e) => setColumn(e.target.value)}
                  disabled={!asset}
                >
                  <option value="">Select a registered field</option>
                  {columns.map((c) => (
                    <option value={c.id} key={c.id}>
                      {c.name} · {c.data_type}
                    </option>
                  ))}
                </select>
              </label>
            )}
            <Feedback error={error} />
            <ContextNote title="What this analysis can tell you">
              Direct dependencies reference the selected resource. Transitive
              dependencies are reachable through those links. Missing lineage
              and unregistered reporting consumers remain unknown.
            </ContextNote>
            <button
              className="primary"
              disabled={!asset || (columnChange && !column)}
              onClick={() => onAnalyze(asset, columnChange ? column : "")}
            >
              Analyze recorded impact →
            </button>
          </>
        )}
      </section>
    </div>
  );
}

function ImpactSummary({
  graph,
  root,
  assetRoot,
  columnId,
}: {
  graph: Graph;
  root: string;
  assetRoot: string;
  columnId: string;
}) {
  const [path, setPath] = useState<{
    nodes: Node[];
    totalNodes: number;
    hasMore: boolean;
    nextOffset: number;
  } | null>(null);
  const [category, setCategory] = useState("all");
  const [pathTarget, setPathTarget] = useState("");
  const [error, setError] = useState<unknown>(null);
  async function showPath(target: string, offset = 0) {
    try {
      setPathTarget(target);
      setPath(
        await request(
          `/lineage/${assetRoot}/path?targetId=${target}&offset=${offset}${columnId ? `&columnId=${columnId}` : ""}`,
        ),
      );
    } catch (e) {
      setError(e);
    }
  }
  const paths = new Map<string, string[]>([[root, [root]]]);
  const q = [root];
  for (const id of q)
    for (const e of graph.edges)
      if (e.source_asset_id === id && !paths.has(e.target_asset_id)) {
        paths.set(e.target_asset_id, [...paths.get(id)!, e.target_asset_id]);
        q.push(e.target_asset_id);
      }
  const name = (id: string) => {
    const n = graph.nodes.find((n) => n.id === id);
    return n?.asset_type === "COLUMN" ? n.external_key : n?.name || id;
  };
  return (
    <section className="panel impact-results">
      <p className="eyebrow">Explainable potential impact</p>
      <h2>
        {graph.totalNodes > 1
          ? `${graph.totalNodes - 1} recorded downstream dependencies`
          : "No known registered dependency"}
      </h2>
      <Feedback error={error} />
      {path && (
        <div className="definition-summary">
          <strong>Complete recorded path · {path.totalNodes} nodes</strong>
          <ol className="recorded-path">
            {path.nodes.map((n, i) => (
              <li key={n.id}>
                <span>{i + 1}</span>
                <div>
                  <strong>
                    {n.asset_type === "COLUMN" ? n.external_key : n.name}
                  </strong>
                  <small>{humanize(n.asset_type)}</small>
                </div>
              </li>
            ))}
          </ol>
          <button
            disabled={!path.hasMore}
            onClick={() => void showPath(pathTarget, path.nextOffset)}
          >
            Continue dependency path
          </button>
        </div>
      )}
      <p>
        Unknown or unregistered consumers cannot be assessed. A recorded path
        establishes a dependency, not a guarantee of failure.
      </p>
      <div className="impact-categories">
        {[
          columnId ? "COLUMN" : "SQL_TRANSFORMATION",
          "FORMULA_VERSION",
          "METRIC_VERSION",
          "REPORT",
          "MEASURE",
        ].map((type) => (
          <div key={type}>
            <strong>
              {graph.nodes.filter((n) => n.asset_type === type).length}
            </strong>
            <span>{humanize(type)} in this page</span>
          </div>
        ))}
      </div>
      <nav className="workbench-tabs" aria-label="Affected resource categories">
        {[
          ["all", "All dependencies"],
          ["direct", "Direct"],
          ["transitive", "Transitive"],
          ["definitions", "Formulas & metrics"],
          ["reporting", "Reporting"],
        ].map(([key, label]) => (
          <button
            key={key}
            aria-pressed={category === key}
            onClick={() => setCategory(key)}
          >
            {label}
          </button>
        ))}
      </nav>
      {[...paths.entries()]
        .filter(
          ([id, p]) =>
            id !== root &&
            (category === "all" ||
              (category === "direct" && p.length === 2) ||
              (category === "transitive" && p.length > 2) ||
              (category === "definitions" &&
                ["FORMULA_VERSION", "METRIC_VERSION"].includes(
                  graph.nodes.find((n) => n.id === id)?.asset_type || "",
                )) ||
              (category === "reporting" &&
                ["REPORT", "VISUAL", "SEMANTIC_MODEL", "MEASURE"].includes(
                  graph.nodes.find((n) => n.id === id)?.asset_type || "",
                ))),
        )
        .map(([id, path]) => (
          <div className="impact-path" key={id}>
            <strong>{name(id)}</strong>
            <span>
              {path.length === 2
                ? "Direct recorded dependency"
                : "Potential transitive effect"}
            </span>
            <details>
              <summary>
                Why this resource appears · {path.length - 1} recorded steps
              </summary>
              <p>{path.map(name).join(" → ")}</p>
            </details>
            <button onClick={() => void showPath(id)}>
              Inspect complete recorded path
            </button>
          </div>
        ))}
      {graph.nodes
        .filter((n) => n.id !== root && !paths.has(n.id))
        .map((n) => (
          <div className="impact-path" key={n.id}>
            <strong>{n.name}</strong>
            <button onClick={() => void showPath(n.id)}>
              Inspect complete recorded path
            </button>
          </div>
        ))}
      {graph.truncated && (
        <p className="notice warning">
          Paths above use relationships in the current page. Continue
          exploration to inspect other recorded dependencies.
        </p>
      )}
    </section>
  );
}
