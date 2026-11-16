import "./lineage.css";
import { CodeEditor } from "./CodeEditor";
import { AssetRelationships } from "../catalog/AssetRelationships";
import { ContextNote, Steps } from "../../components/Workflow";
import { usePage } from "./paging";
import { Pager } from "./pagination";
import { EmptyState, TableRegion, Timestamp } from "../../components/Design";
import type { Asset } from "../../types/api";
import { useEffect, useState } from "react";
import { request, mutate } from "../../services/api";
import { useAction, useWorkspace } from "./hooks";
import { AssetSelect } from "./shared";
import { PageHeader } from "../../components/Design";
import { Feedback, Loading, Success } from "../../components/Feedback";
import { allowed } from "../../app/permissions";
import type { Session } from "../../types/api";
type Analysis = {
  status: string;
  definition: string;
  definitionHash: string;
  dialect: string;
  sourceRef: string;
  references: {
    identifier: string;
    status: string;
    matches: { id: string; name: string }[];
  }[];
  columnMappings: {
    sourceAssetId: string;
    sourceColumn: string;
    targetColumn: string;
    expression: string;
  }[];
  rules: { kind: string; expression: string }[];
  warnings: string[];
};
export function TransformationPage({
  session,
  initialTarget,
}: {
  session: Session;
  initialTarget?: string;
}) {
  const [create, setCreate] = useState(false);
  const [schema, setSchema] = useState("");
  const [tab, setTab] = useState("sql");
  const w = useWorkspace(0, { assets: true });
  const page = usePage<Asset>("assets", 0, {
    type: "SQL_TRANSFORMATION",
    schema,
  });
  const mappings = usePage<{
    id: string;
    source_asset_id: string;
    target_asset_id: string;
    provenance: string;
    evidence_ref: string;
    active: boolean;
  }>(
    "mappings",
    0,
    { type: "DATA_FLOW", provenance: "MANUAL_DECLARATION" },
    tab === "ingestion",
  );
  if (initialTarget || create)
    return (
      <>
        <button
          className="back"
          onClick={() => {
            setCreate(false);
            window.location.hash = "transformations";
          }}
        >
          ← Transformation library
        </button>
        <TransformationDetail
          key={initialTarget || "new"}
          session={session}
          initialTarget={initialTarget}
        />
      </>
    );
  return (
    <>
      <PageHeader
        area="Define & measure"
        title="Transformations"
        description="Explore the SQL and ingestion declarations that connect your datasets. Open a definition to inspect its inputs, output and supporting evidence."
        actions={
          <button className="primary" onClick={() => setCreate(true)}>
            Review new SQL
          </button>
        }
      />
      <Feedback error={w.error} />
      <nav className="workbench-tabs" aria-label="Transformation library">
        <button aria-pressed={tab === "sql"} onClick={() => setTab("sql")}>
          SQL definitions
        </button>
        <button
          aria-pressed={tab === "ingestion"}
          onClick={() => setTab("ingestion")}
        >
          Ingestion declarations
        </button>
      </nav>
      {tab === "sql" ? (
        <section className="panel table-panel">
          <div className="library-heading">
            <h2>
              Registered SQL <span>{page.total}</span>
            </h2>
          </div>
          <Pager
            page={page}
            label="transformations"
            filters={
              <label>
                <span className="sr-only">Target schema</span>
                <select
                  value={schema}
                  onChange={(e) => setSchema(e.target.value)}
                >
                  <option value="">All target schemas</option>
                  {[
                    ...new Set(
                      w.assets
                        .filter(
                          (a) =>
                            a.asset_type !== "SQL_TRANSFORMATION" &&
                            a.platform === "SNOWFLAKE",
                        )
                        .map((a) => a.external_key.split(".")[1]),
                    ),
                  ]
                    .filter(Boolean)
                    .map((k) => (
                      <option key={k}>{k}</option>
                    ))}
                </select>
              </label>
            }
          />
          <TableRegion label="Transformation library">
            <table>
              <thead>
                <tr>
                  <th>Definition</th>
                  <th>Output</th>
                  <th>Source</th>
                  <th>Owning team</th>
                  <th>Recorded</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((a) => {
                  const targetId = a.external_key.replace("trace:sql:", "");
                  const target = w.assets.find((x) => x.id === targetId);
                  return (
                    <tr key={a.id}>
                      <td>
                        <a href={`#transformations/${targetId}`}>
                          {a.name.replace(/^SQL · /, "")}
                        </a>
                        <small>
                          SQL dependency evidence · inspect revision
                        </small>
                      </td>
                      <td>{target?.external_key || "Open definition"}</td>
                      <td>
                        {a.connection_name}
                        <small>Parsed and explicitly confirmed</small>
                      </td>
                      <td>{a.owner_team_name || "Not recorded"}</td>
                      <td>
                        <Timestamp value={a.created_at} />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </TableRegion>
          {!page.loading && !page.error && !page.items.length && (
            <EmptyState
              icon="code"
              title="No registered SQL definitions"
              description="Select Review new SQL to analyze an existing output and confirm its dependencies."
            />
          )}
        </section>
      ) : (
        <section className="panel table-panel">
          <header>
            <h2>Source → target declarations</h2>
            <p className="muted">
              Declared ingestion relationships and their evidence. These do not
              prove execution or refresh.
            </p>
          </header>
          <Pager page={mappings} label="ingestion relationships" />
          <TableRegion label="Ingestion declarations">
            <table>
              <thead>
                <tr>
                  <th>Input</th>
                  <th>Output</th>
                  <th>Evidence</th>
                  <th>State</th>
                </tr>
              </thead>
              <tbody>
                {mappings.items
                  .filter(
                    (m) =>
                      m.provenance.includes("INGEST") ||
                      m.provenance.includes("MANUAL"),
                  )
                  .map((m) => (
                    <tr key={m.id}>
                      <td>
                        <a href={`#asset/${m.source_asset_id}`}>
                          {w.assets.find((a) => a.id === m.source_asset_id)
                            ?.name || "Open source"}
                        </a>
                      </td>
                      <td>
                        <a href={`#asset/${m.target_asset_id}`}>
                          {w.assets.find((a) => a.id === m.target_asset_id)
                            ?.external_key || "Open output"}
                        </a>
                      </td>
                      <td>
                        <details>
                          <summary>{m.provenance}</summary>
                          <p>{m.evidence_ref}</p>
                        </details>
                      </td>
                      <td>{m.active ? "Active declaration" : "Revoked"}</td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </TableRegion>
        </section>
      )}
    </>
  );
}
function TransformationDetail({
  session,
  initialTarget,
}: {
  session: Session;
  initialTarget?: string;
}) {
  const a = useAction(),
    w = useWorkspace(a.revision, { assets: true });
  const [target, setTarget] = useState(initialTarget || "");
  const [pane, setPane] = useState(initialTarget ? "definition" : "edit");
  const [loadedTarget, setLoadedTarget] = useState("");
  const [definition, setDefinition] = useState("");
  const [sourceRef, setSourceRef] = useState("");
  const [loadError, setLoadError] = useState<unknown>(null);
  const [analysis, setAnalysis] = useState<Analysis | null>(null);
  const [refs, setRefs] = useState<Record<string, string>>({});
  const [columns, setColumns] = useState<number[]>([]);
  const [historyPage, setHistoryPage] = useState({
    total: 0,
    offset: 0,
    hasMore: false,
  });
  const [history, setHistory] = useState<
    {
      id: string;
      definition: string;
      definition_hash: string;
      source_ref: string;
      created_at: string;
    }[]
  >([]);
  useEffect(() => {
    if (!target) return;
    let active = true;
    Promise.all([
      request<{ metadata: string; last_import_id: string }>(
        `/assets/${target}/curation`,
      ),
      request<{
        items: typeof history;
        total: number;
        offset: number;
        hasMore: boolean;
      }>(`/transformations/${target}/history/page?limit=20&offset=0`),
    ])
      .then(([asset, h]) => {
        if (!active) return;
        const m = JSON.parse(asset.metadata || "{}");
        setDefinition(
          String(m.viewDefinition || m.sql || h.items[0]?.definition || ""),
        );
        setSourceRef(
          String(
            m.sourceRef ||
              h.items[0]?.source_ref ||
              `Registry asset ${target} · import ${asset.last_import_id || "manual"}`,
          ),
        );
        setHistory(h.items);
        setHistoryPage({
          total: h.total,
          offset: h.offset,
          hasMore: h.hasMore,
        });
        setAnalysis(null);
        setRefs({});
        setColumns([]);
        setLoadError(null);
        setLoadedTarget(target);
      })
      .catch((e) => {
        if (active) setLoadError(e);
      });
    return () => {
      active = false;
    };
  }, [target]);
  async function loadHistory(offset: number) {
    const page = await request<{
      items: typeof history;
      total: number;
      offset: number;
      hasMore: boolean;
    }>(`/transformations/${target}/history/page?limit=20&offset=${offset}`);
    setHistory(page.items);
    setHistoryPage({
      total: page.total,
      offset: page.offset,
      hasMore: page.hasMore,
    });
  }
  const selected = w.assets.find((x) => x.id === target);
  const writable = selected && allowed(session, selected.owner_team_id);
  return (
    <>
      <PageHeader
        area="Definitions"
        title="Transformation workspace"
        description="Inspect what this definition reads, what it produces and why each relationship is recorded. Review edits before confirming new evidence."
        actions={
          <a className="button" href="#lineage">
            Open lineage workspace
          </a>
        }
      />
      <Feedback error={a.error || loadError || w.error} />
      {a.notice && <Success>{a.notice}</Success>}
      <div className="transformation-workspace">
        <aside className="panel">
          <p className="eyebrow">Definition source</p>
          <AssetSelect
            assets={w.assets.filter((x) =>
              ["DATASET", "VIEW", "TABLE"].includes(x.asset_type),
            )}
            name="target"
            label="Target dataset or view"
            value={target}
            onChange={setTarget}
          />
          <p className="hint">
            Choose the existing output identity. Supply the Snowflake definition
            or SQL from a versioned repository.
          </p>
          <h3>Evidence semantics</h3>
          <p>
            Parsing produces candidates. Confirm identities and column mappings
            before they contribute to the canonical graph.
          </p>
          <p className="hint">
            No SQL is executed. Confirmation does not deploy a view, grant
            approval or alter source data.
          </p>
          {target && (
            <>
              <a href={`#asset/${target}`}>Open target in catalog →</a>
              <div className="action-row">
                <a href={`#impact/${target}`}>Inspect downstream impact →</a>
              </div>
            </>
          )}
        </aside>
        <section className="panel transformation-editor">
          {!target ? (
            <p>Select a target to inspect its transformation and history.</p>
          ) : loadedTarget !== target ? (
            <Loading>Loading the recorded definition and evidence…</Loading>
          ) : (
            <>
              <div className="section-title">
                <h2>{selected?.name || "Selected target"}</h2>
                <span className="badge">SQL · Snowflake</span>
              </div>
              <nav
                className="workbench-tabs"
                aria-label="Transformation sections"
              >
                {[
                  ["definition", "Stored definition"],
                  ["relationships", "Inputs & outputs"],
                  ["edit", "Review new SQL"],
                ].map(([key, label]) => (
                  <button
                    key={key}
                    aria-pressed={pane === key}
                    onClick={() => setPane(key)}
                  >
                    {label}
                  </button>
                ))}
              </nav>
              {pane === "relationships" && <AssetRelationships id={target} />}
              {pane === "definition" && (
                <>
                  <ContextNote
                    title={
                      historyPage.total
                        ? `${historyPage.total} immutable evidence snapshots`
                        : "Stored metadata definition"
                    }
                  >
                    This is the definition recorded in Trace. Its evidence
                    identifies the source revision; it is not a live warehouse
                    query.
                  </ContextNote>
                  {definition ? (
                    <CodeEditor
                      value={definition}
                      language="SQL"
                      readOnly
                      onChange={() => {}}
                      assets={w.assets}
                      label="Stored SQL definition"
                    />
                  ) : (
                    <p>
                      No definition is recorded. Use Review new SQL to supply
                      one.
                    </p>
                  )}
                  <p className="hint">{sourceRef}</p>
                </>
              )}
              <div hidden={pane !== "edit"}>
                <Steps
                  labels={[
                    "Choose output",
                    "Edit SQL",
                    "Review dependencies",
                    "Confirm evidence",
                  ]}
                  current={analysis ? 2 : 1}
                />
                <label>
                  Definition evidence / revision
                  <input
                    value={sourceRef}
                    onChange={(e) => {
                      setSourceRef(e.target.value);
                      setAnalysis(null);
                    }}
                    placeholder="GitHub commit URL, view definition revision or source record"
                    maxLength={2000}
                  />
                </label>
                <CodeEditor
                  label="SQL definition"
                  value={definition}
                  language="SQL"
                  assets={w.assets}
                  readOnly={!writable || a.busy}
                  onChange={(value) => {
                    setDefinition(value);
                    setAnalysis(null);
                  }}
                />
                <button
                  className="primary"
                  disabled={a.busy || !definition.trim()}
                  onClick={() =>
                    void a.run(async () => {
                      const r = await mutate<Analysis>(
                        `/transformations/${target}/analyze`,
                        { definition, dialect: "snowflake", sourceRef },
                      );
                      setAnalysis(r);
                      setRefs(
                        Object.fromEntries(
                          r.references
                            .filter((x) => x.matches.length === 1)
                            .map((x) => [x.identifier, x.matches[0].id]),
                        ),
                      );
                      setColumns(r.columnMappings.map((_, i) => i));
                    })
                  }
                >
                  Analyze dependency candidates
                </button>
                {a.busy && (
                  <Loading>Checking SQL and registry identities…</Loading>
                )}
                {analysis && (
                  <section className="dependency-review">
                    <div className="section-title">
                      <h3>Dependency review</h3>
                      <span className="badge">
                        {analysis.status} · candidates
                      </span>
                    </div>
                    <code className="hash-label">
                      SHA-256 {analysis.definitionHash}
                    </code>
                    <div className="dependency-lanes">
                      <div>
                        <small>Reads from</small>
                        {analysis.references.map((ref) => (
                          <label key={ref.identifier}>
                            <strong>{ref.identifier}</strong>
                            <small>{ref.status}</small>
                            <select
                              aria-label={`Resolve ${ref.identifier}`}
                              value={refs[ref.identifier] || ""}
                              onChange={(e) =>
                                setRefs({
                                  ...refs,
                                  [ref.identifier]: e.target.value,
                                })
                              }
                            >
                              <option value="">Leave unresolved</option>
                              {(ref.matches.length
                                ? ref.matches
                                : w.assets.map((x) => ({
                                    id: x.id,
                                    name: x.external_key,
                                  }))
                              ).map((x) => (
                                <option key={x.id} value={x.id}>
                                  {x.name}
                                </option>
                              ))}
                            </select>
                          </label>
                        ))}
                      </div>
                      <span className="dependency-arrow">→</span>
                      <div>
                        <small>Produces</small>
                        <strong>{selected?.external_key}</strong>
                        <p>
                          {analysis.columnMappings.length} supported column
                          candidates
                        </p>
                      </div>
                    </div>
                    <h3>Input → output columns</h3>
                    {analysis.columnMappings.length ? (
                      <div className="mapping-review-list">
                        {analysis.columnMappings.map((m, i) => (
                          <label className="mapping-review" key={i}>
                            <input
                              type="checkbox"
                              checked={columns.includes(i)}
                              onChange={(e) =>
                                setColumns(
                                  e.target.checked
                                    ? [...columns, i]
                                    : columns.filter((x) => x !== i),
                                )
                              }
                            />
                            <span>
                              <strong>
                                {m.sourceColumn} → {m.targetColumn}
                              </strong>
                              <code>{m.expression}</code>
                            </span>
                          </label>
                        ))}
                      </div>
                    ) : (
                      <p className="hint">
                        No column mapping resolved uniquely. Dataset references
                        can be confirmed independently.
                      </p>
                    )}
                    <h3>SQL operations</h3>
                    {analysis.rules.map((r, i) => (
                      <details key={i}>
                        <summary>{r.kind}</summary>
                        <pre className="sql-viewer">{r.expression}</pre>
                      </details>
                    ))}
                    <details>
                      <summary>
                        Parser limitations and unresolved references
                      </summary>
                      <ul>
                        {analysis.warnings.map((warning, i) => (
                          <li key={i}>{warning}</li>
                        ))}
                      </ul>
                    </details>
                    <p className="notice">
                      Confirming stores the selected identities as SQL
                      dependencies with this exact immutable definition.
                      Unselected references remain unresolved in the evidence
                      snapshot.
                    </p>
                    <button
                      className="primary"
                      disabled={
                        !writable ||
                        a.busy ||
                        !Object.values(refs).some(Boolean) ||
                        analysis.status !== "PARSED"
                      }
                      onClick={() =>
                        void a.run(async () => {
                          await mutate(`/transformations/${target}/confirm`, {
                            definition: analysis.definition,
                            dialect: analysis.dialect,
                            sourceRef,
                            references: Object.fromEntries(
                              Object.entries(refs).filter(([, v]) => v),
                            ),
                            columnMappings: columns.filter((i) =>
                              Object.values(refs).includes(
                                analysis.columnMappings[i].sourceAssetId,
                              ),
                            ),
                          });
                          await loadHistory(0);
                          a.setNotice(
                            "Selected SQL dependencies confirmed. Prior evidence is preserved.",
                          );
                        })
                      }
                    >
                      Confirm selected dependencies
                    </button>
                    <a className="button" href={`#lineage/${target}`}>
                      Inspect resulting lineage
                    </a>
                  </section>
                )}
              </div>
              <details className="transformation-history">
                <summary>
                  Definition history · {historyPage.total} snapshots
                </summary>
                <div className="action-row">
                  <span className="hint">
                    Snapshots {history.length ? historyPage.offset + 1 : 0}–
                    {historyPage.offset + history.length} of {historyPage.total}
                  </span>
                  <button
                    disabled={!historyPage.offset || a.busy}
                    onClick={() =>
                      void a.run(() =>
                        loadHistory(Math.max(0, historyPage.offset - 20)),
                      )
                    }
                  >
                    Previous evidence
                  </button>
                  <button
                    disabled={!historyPage.hasMore || a.busy}
                    onClick={() =>
                      void a.run(() => loadHistory(historyPage.offset + 20))
                    }
                  >
                    Next evidence
                  </button>
                </div>
                {history.map((h) => (
                  <details key={h.id}>
                    <summary>
                      {new Date(h.created_at).toLocaleString()} ·{" "}
                      {h.definition_hash.slice(0, 12)}
                    </summary>
                    <p>{h.source_ref}</p>
                    <pre className="sql-viewer">{h.definition}</pre>
                  </details>
                ))}
              </details>
            </>
          )}
        </section>
      </div>
    </>
  );
}
