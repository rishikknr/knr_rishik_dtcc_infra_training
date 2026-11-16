import { AssetDependencies, ColumnDependencies } from "./DependencyPicker";
import { SubmitReview } from "../governance/GovernancePage";
import { ContextNote, GuidedFields } from "../../components/Workflow";
import { DefinitionDependencies } from "./DefinitionDependencies";
import { beforeLeave } from "../../app/navigationGuard";
import { lazy, Suspense } from "react";
const CodeEditor = lazy(() =>
  import("./CodeEditor").then((m) => ({ default: m.CodeEditor })),
);
import { VersionComparison } from "./VersionComparison";
import { usePage } from "./paging";
import { Pager } from "./pagination";
import { useEffect, useState } from "react";
import { mutate, request } from "../../services/api";
import type { Session } from "../../types/api";
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
import { AssetSelect, TeamSelect } from "./shared";
import { useAction, useWorkspace } from "./hooks";
import { allowed } from "../../app/permissions";
export type FormulaVersion = {
  id: string;
  revision: number;
  referenced_columns: string;
  version: number;
  expression: string;
  state: string;
  dialect: string;
  validation: unknown;
  created_at: string;
  created_by?: string;
  referenced_assets: string;
};
export type Formula = {
  id: string;
  name: string;
  description: string;
  category: string;
  language: string;
  owner_team_id: string;
  asset_id?: string;
  versions?: FormulaVersion[];
  latest_version?: number;
  latest_state?: string;
  latest_expression?: string;
  version_count?: number;
  owner_team_name?: string;
  latest_created_at?: string;
};
type Validation = {
  status: string;
  inputs?: string[];
  outputs?: string[];
  columns?: string[];
  warnings?: string[];
};

function ValidationResult({ result }: { result: Validation }) {
  return (
    <section className="validation-result" aria-live="polite">
      <div className="section-title">
        <h3>Validation result</h3>
        <Status value={result.status} />
      </div>
      {!!result.inputs?.length && (
        <p>
          <strong>Candidate inputs</strong> ·{" "}
          <code>{result.inputs.join(", ")}</code>
        </p>
      )}
      {!!result.outputs?.length && (
        <p>
          <strong>Candidate outputs</strong> ·{" "}
          <code>{result.outputs.join(", ")}</code>
        </p>
      )}
      {!!result.columns?.length && (
        <details>
          <summary>Candidate columns ({result.columns.length})</summary>
          <p>
            <code>{result.columns.join(", ")}</code>
          </p>
        </details>
      )}
      {!!result.warnings?.length && (
        <ul>
          {result.warnings.map((warning) => (
            <li key={warning}>{warning}</li>
          ))}
        </ul>
      )}
    </section>
  );
}

export function FormulaStudio({ session }: { session: Session }) {
  const a = useAction(),
    w = useWorkspace(a.revision, { teams: true, assets: true });

  const [editing, setEditing] = useState<FormulaVersion | null>(null);
  const [assetRefs, setAssetRefs] = useState<string[]>([]),
    [columnRefs, setColumnRefs] = useState<string[]>([]);
  const [selected, setSelected] = useState<Formula | null>(null),
    [expression, setExpression] = useState(""),
    [dialect, setDialect] = useState("snowflake"),
    [language, setLanguage] = useState("SQL"),
    [validation, setValidation] = useState<Validation | null>(null),
    [dirty, setDirty] = useState(false);
  const [pane, setPane] = useState<"overview" | "editor" | "versions">(
    "overview",
  );
  const [creating, setCreating] = useState(false);
  const [languageFilter, setLanguageFilter] = useState("");
  const [stateFilter, setStateFilter] = useState("");
  const formulaPage = usePage<Formula>("formulas", a.revision, {
    type: languageFilter,
    status: stateFilter,
  });
  const [confirmDiscard, setConfirmDiscard] = useState<(() => void) | null>(
    null,
  );
  useEffect(() => {
    if (!dirty) return;
    const handler = (e: BeforeUnloadEvent) => e.preventDefault();
    const sessionLeave = (event: Event) => {
      const resume = (event as CustomEvent<() => void>).detail;
      if (typeof resume !== "function") return;
      event.preventDefault();
      setConfirmDiscard(() => resume);
    };
    window.addEventListener(beforeLeave, sessionLeave);
    window.addEventListener("beforeunload", handler);
    return () => {
      window.removeEventListener("beforeunload", handler);
      window.removeEventListener(beforeLeave, sessionLeave);
    };
  }, [dirty]);
  function guard(run: () => void) {
    if (dirty) setConfirmDiscard(() => run);
    else run();
  }
  async function open(id: string) {
    await a.run(async () => {
      const f = await request<Formula>(`/formulas/${id}`);
      setSelected(f);
      setPane("overview");
      setCreating(false);
      setEditing(null);
      setAssetRefs(JSON.parse(f.versions?.[0]?.referenced_assets ?? "[]"));
      setColumnRefs(JSON.parse(f.versions?.[0]?.referenced_columns ?? "[]"));
      setLanguage(f.language);
      setExpression(f.versions?.[0]?.expression ?? "");
      setDialect(f.versions?.[0]?.dialect ?? "snowflake");
      setDirty(false);
      setValidation(null);
    });
  }
  const canWrite = selected
    ? allowed(session, selected.owner_team_id)
    : w.teams.some((t) => allowed(session, t.id));
  return (
    <>
      <PageHeader
        area="Definitions"
        title="Formula Studio"
        description="Your reusable calculations, their inputs, and every recorded version. Open a formula to understand how it works and where it is used."
        actions={
          w.teams.some((t) => t.active && allowed(session, t.id)) && (
            <button
              className="primary"
              onClick={() =>
                guard(() => {
                  setSelected(null);
                  setCreating(true);
                  setAssetRefs([]);
                  setColumnRefs([]);
                  setExpression("");
                  setDirty(false);
                })
              }
            >
              <Icon name="plus" size={16} />
              Create formula
            </button>
          )
        }
      />
      <Feedback error={a.error ?? w.error} />
      {a.notice && <Success>{a.notice}</Success>}
      {w.loading && (
        <Loading>Loading formula permissions and references…</Loading>
      )}
      {!selected && !creating && (
        <section className="panel table-panel">
          <div className="library-heading">
            <h2>
              All formulas <span>{formulaPage.total}</span>
            </h2>
            <span className="hint">
              Select a calculation to inspect its definition
            </span>
          </div>
          <Pager
            page={formulaPage}
            label="formulas"
            filters={
              <>
                <label>
                  <span className="sr-only">Formula language</span>
                  <select
                    value={languageFilter}
                    onChange={(e) => setLanguageFilter(e.target.value)}
                  >
                    <option value="">All languages</option>
                    <option>SQL</option>
                    <option>DAX</option>
                    <option>BUSINESS_RULE</option>
                  </select>
                </label>
                <label>
                  <span className="sr-only">Formula state</span>
                  <select
                    value={stateFilter}
                    onChange={(e) => setStateFilter(e.target.value)}
                  >
                    <option value="">All version states</option>
                    <option value="REGISTERED">Registered</option>
                    <option value="DRAFT">Draft</option>
                    <option value="NO_VERSION">No expression</option>
                  </select>
                </label>
              </>
            }
          />
          {!formulaPage.loading && formulaPage.items.length === 0 && (
            <EmptyState
              icon="formula"
              title="No formulas found"
              description="Reusable calculations and their version history will appear here. Create a formula to document a calculation."
            />
          )}
          <TableRegion label="Formula library">
            <table>
              <thead>
                <tr>
                  <th>Formula</th>
                  <th>Language / purpose</th>
                  <th>Latest version</th>
                  <th>Owning team</th>
                  <th>Updated</th>
                </tr>
              </thead>
              <tbody>
                {formulaPage.items
                  .filter(
                    (f) => !languageFilter || f.language === languageFilter,
                  )
                  .map((f) => (
                    <tr key={f.id}>
                      <td>
                        <button
                          className="record-name"
                          onClick={() => guard(() => void open(f.id))}
                        >
                          {f.name}
                        </button>
                        <small className="record-description">
                          {f.description?.split(
                            /\s+Source:\s*https?:\/\//,
                          )[0] || "No description recorded"}
                        </small>
                      </td>
                      <td>
                        {f.language}
                        <small>{humanize(f.category)}</small>
                      </td>
                      <td>
                        {f.latest_version ? (
                          <>
                            <span>v{f.latest_version}</span>{" "}
                            <Status value={f.latest_state || "DRAFT"} />
                            <small>
                              {f.version_count} recorded{" "}
                              {f.version_count === 1 ? "version" : "versions"}
                            </small>
                          </>
                        ) : (
                          <span className="muted">No expression yet</span>
                        )}
                      </td>
                      <td>
                        {f.owner_team_name ||
                          w.teams.find((t) => t.id === f.owner_team_id)?.name ||
                          "Not recorded"}
                      </td>
                      <td>
                        <Timestamp value={f.latest_created_at} />
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </TableRegion>
        </section>
      )}
      {(selected || creating) && (
        <div className="record-detail">
          <div className="detail-topline">
            <button
              onClick={() =>
                guard(() => {
                  setSelected(null);
                  setCreating(false);
                  setDirty(false);
                })
              }
            >
              ← All formulas
            </button>
            <span className="hint">
              {creating
                ? "New calculation · metadata first, then expression"
                : "Calculation details · changes require an explicit save"}
            </span>
          </div>
          <section className="panel">
            {!selected && !canWrite ? (
              <EmptyState
                icon="formula"
                title="Select a formula"
                description="Open a formula to view its expression, dependencies, and registered versions."
              />
            ) : !selected ? (
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  const d = new FormData(e.currentTarget);
                  void a.run(async () => {
                    const r = await mutate<{ id: string }>("/formulas", {
                      name: d.get("name"),
                      description: d.get("description"),
                      category: d.get("category"),
                      language,
                      ownerTeamId: d.get("ownerTeamId"),
                      assetId: d.get("assetId") || null,
                    });
                    setSelected(await request<Formula>(`/formulas/${r.id}`));
                    setPane("editor");
                    setCreating(false);
                    a.setNotice("Formula created. Add an expression version.");
                  });
                }}
              >
                <h2>Create formula</h2>
                <fieldset disabled={a.busy || !canWrite}>
                  <ContextNote title="Start with the calculation's purpose">
                    Create a library entry first. You will then choose source
                    fields, build the expression, validate it and save a draft
                    or immutable version.
                  </ContextNote>
                  <GuidedFields
                    submitLabel="Create formula & continue"
                    busy={a.busy}
                    steps={[
                      {
                        title: "Purpose",
                        description:
                          "Give this calculation a useful name and explain what it calculates.",
                        fields: (
                          <>
                            {" "}
                            <label>
                              Formula name
                              <input name="name" required maxLength={255} />
                            </label>
                            <label>
                              Description
                              <textarea name="description" maxLength={4000} />
                            </label>
                            <label>
                              Category
                              <select name="category" key={language}>
                                {[
                                  "SQL_TRANSFORMATION",
                                  "COLUMN_FORMULA",
                                  "BUSINESS_METRIC",
                                  "DAX_MEASURE",
                                  "BUSINESS_RULE",
                                ]
                                  .filter((c) =>
                                    language === "SQL"
                                      ? ![
                                          "DAX_MEASURE",
                                          "BUSINESS_RULE",
                                        ].includes(c)
                                      : language === "DAX"
                                        ? ![
                                            "SQL_TRANSFORMATION",
                                            "BUSINESS_RULE",
                                          ].includes(c)
                                        : c === "BUSINESS_RULE",
                                  )
                                  .map((c) => (
                                    <option key={c} value={c}>
                                      {humanize(c)}
                                    </option>
                                  ))}
                              </select>
                            </label>
                            <label>
                              Expression language
                              <select
                                value={language}
                                onChange={(e) => setLanguage(e.target.value)}
                              >
                                <option>SQL</option>
                                <option>DAX</option>
                                <option>BUSINESS_RULE</option>
                              </select>
                            </label>
                          </>
                        ),
                      },
                      {
                        title: "Ownership & output",
                        description:
                          "Choose the team responsible for maintaining it. Link an existing output only when the calculation produces that asset.",
                        fields: (
                          <>
                            {" "}
                            <TeamSelect teams={w.teams} session={session} />
                            <AssetSelect
                              assets={w.assets.filter((x) =>
                                allowed(session, x.owner_team_id),
                              )}
                              name="assetId"
                              label="Linked transformation or asset"
                              optional
                            />
                          </>
                        ),
                      },
                    ]}
                  />
                </fieldset>
              </form>
            ) : (
              <>
                <div className="section-title">
                  <h2>{selected.name}</h2>
                  <span className="badge">{selected.language}</span>
                </div>
                <p className="muted">
                  {selected.description?.split(/\s+Source:\s*https?:\/\//)[0] ||
                    humanize(selected.category)}
                </p>
                {selected.description?.match(/https?:\/\/\S+/)?.[0] && (
                  <p className="hint">
                    <a
                      href={selected.description.match(/https?:\/\/\S+/)![0]}
                      target="_blank"
                      rel="noreferrer"
                    >
                      Open recorded definition source ↗
                    </a>
                  </p>
                )}
                <nav
                  className="workbench-tabs"
                  aria-label="Formula workspace sections"
                >
                  <button
                    type="button"
                    aria-pressed={pane === "overview"}
                    onClick={() => setPane("overview")}
                  >
                    Overview & dependencies
                  </button>
                  <button
                    type="button"
                    aria-pressed={pane === "editor"}
                    onClick={() => setPane("editor")}
                  >
                    Edit expression
                  </button>
                  <button
                    type="button"
                    aria-pressed={pane === "versions"}
                    onClick={() => setPane("versions")}
                  >
                    Version history and comparison
                  </button>
                  {dirty && (
                    <small role="status">Unsaved expression changes</small>
                  )}
                </nav>
                {pane !== "versions" && (
                  <>
                    {" "}
                    <DefinitionDependencies
                      key={selected.id}
                      name={selected.name}
                      assets={w.assets}
                      refs={assetRefs}
                      columnRefs={columnRefs}
                      target={selected.asset_id}
                      expression={expression}
                      previous={selected.versions?.[0]?.expression ?? ""}
                      previousRefs={JSON.parse(
                        selected.versions?.[0]?.referenced_assets ?? "[]",
                      )}
                      previousColumns={JSON.parse(
                        selected.versions?.[0]?.referenced_columns ?? "[]",
                      )}
                      registeredId={
                        selected.versions?.find((v) => v.state === "REGISTERED")
                          ?.id
                      }
                    />
                  </>
                )}
                {pane === "overview" && (
                  <div>
                    <ContextNote
                      title={
                        selected.versions?.[0]
                          ? `Latest recorded definition · v${selected.versions[0].version}`
                          : "Expression not added yet"
                      }
                    >
                      {selected.versions?.[0]
                        ? "Inspect the stored calculation and declared inputs above. Registered means an immutable registry snapshot; approval is reviewed separately."
                        : "This formula has a library entry. Use Edit expression to add its first calculation and source dependencies."}
                    </ContextNote>
                    {expression && (
                      <pre className="sql-viewer">{expression}</pre>
                    )}
                    <div className="action-row">
                      <button onClick={() => setPane("versions")}>
                        Compare versions
                      </button>
                      {canWrite && (
                        <button onClick={() => setPane("editor")}>
                          Edit expression
                        </button>
                      )}
                      <a className="button" href="#governance">
                        Open governance reviews
                      </a>
                      {canWrite &&
                        selected.versions?.[0]?.state === "REGISTERED" && (
                          <SubmitReview
                            targetType="FORMULA_VERSION"
                            targetId={selected.versions[0].id}
                            name={`${selected.name} · v${selected.versions[0].version}`}
                          />
                        )}
                    </div>
                  </div>
                )}
                <form
                  hidden={pane !== "editor"}
                  onSubmit={(e) => {
                    e.preventDefault();
                    const d = new FormData(e.currentTarget);
                    void a.run(async () => {
                      const definition = {
                        expression,
                        dialect,
                        state: d.get("state"),
                        referencedAssets: assetRefs,
                        referencedColumns: columnRefs,
                      };
                      if (editing) {
                        const saved = await mutate<FormulaVersion>(
                          `/formulas/${selected.id}/versions/${editing.id}`,
                          { definition, expectedRevision: editing.revision },
                          "PATCH",
                        );
                        setEditing(saved.state === "DRAFT" ? saved : null);
                        a.setNotice(
                          saved.state === "DRAFT"
                            ? "Draft changes saved."
                            : "Formula version registered.",
                        );
                      } else {
                        await mutate(
                          `/formulas/${selected.id}/versions`,
                          definition,
                        );
                        a.setNotice("New expression version saved.");
                      }
                      setSelected(
                        await request<Formula>(`/formulas/${selected.id}`),
                      );
                      setValidation(null);
                      setDirty(false);
                    });
                  }}
                >
                  <fieldset
                    className="formula-workbench"
                    disabled={a.busy || !canWrite}
                  >
                    <div className="editor-layout">
                      <div className="editor-main">
                        {language === "SQL" && (
                          <label>
                            SQL dialect
                            <select
                              value={dialect}
                              onChange={(e) => {
                                setDialect(e.target.value);
                                setDirty(true);
                              }}
                            >
                              {[
                                "snowflake",
                                "postgres",
                                "tsql",
                                "bigquery",
                                "mysql",
                                "sqlite",
                                "spark",
                                "duckdb",
                              ].map((d) => (
                                <option key={d}>{d}</option>
                              ))}
                            </select>
                          </label>
                        )}
                        <Suspense fallback={<p>Loading formula editor…</p>}>
                          <CodeEditor
                            key={editing?.id ?? selected.id}
                            value={expression}
                            language={language}
                            assets={w.assets}
                            dialect={dialect}
                            readOnly={a.busy || !canWrite}
                            onChange={(value) => {
                              setExpression(value);
                              setDirty(true);
                              setValidation(null);
                            }}
                          />
                        </Suspense>
                        {validation && <ValidationResult result={validation} />}
                      </div>
                      <aside className="editor-context">
                        <h3>Formula context</h3>
                        <dl>
                          <div>
                            <dt>Owning team</dt>
                            <dd>
                              {w.teams.find(
                                (team) => team.id === selected.owner_team_id,
                              )?.name ?? "Unavailable"}
                            </dd>
                          </div>
                          {selected.asset_id && (
                            <div>
                              <dt>Linked asset</dt>
                              <dd>
                                <a href={`#asset/${selected.asset_id}`}>
                                  {w.assets.find(
                                    (asset) => asset.id === selected.asset_id,
                                  )?.name ?? "Open catalog asset"}
                                </a>
                              </dd>
                            </div>
                          )}
                        </dl>
                        <AssetDependencies
                          assets={w.assets}
                          selected={assetRefs}
                          onChange={(ids) => {
                            setAssetRefs(ids);
                            setDirty(true);
                          }}
                        />
                        <ColumnDependencies
                          assets={w.assets}
                          selected={columnRefs}
                          onChange={(ids) => {
                            setColumnRefs(ids);
                            setDirty(true);
                          }}
                        />
                      </aside>
                    </div>
                    {editing && (
                      <p role="status">
                        Editing draft version {editing.version} · revision{" "}
                        {editing.revision}
                      </p>
                    )}
                    <label>
                      Save state
                      <select
                        name="state"
                        key={editing?.id ?? "next"}
                        defaultValue="DRAFT"
                        onChange={() => setDirty(true)}
                      >
                        <option>DRAFT</option>
                        <option>REGISTERED</option>
                      </select>
                    </label>
                    <p className="hint">
                      Registered versions are immutable registry records, not
                      business approval or deployment. DAX expressions receive
                      text and size checks.
                    </p>
                    <div className="action-row">
                      <button
                        type="button"
                        disabled={!expression}
                        onClick={() =>
                          void a.run(async () =>
                            setValidation(
                              await mutate<Validation>("/formulas/validate", {
                                expression,
                                language,
                                dialect,
                              }),
                            ),
                          )
                        }
                      >
                        Validate expression
                      </button>
                      <button className="primary" disabled={!expression}>
                        {editing ? "Save draft changes" : "Save new version"}
                      </button>
                    </div>
                  </fieldset>
                </form>
                <div hidden={pane !== "versions"}>
                  <VersionComparison
                    key={selected.id}
                    formula={selected.id}
                    assets={w.assets}
                    revision={a.revision}
                    writable={canWrite}
                    onEdit={(v) => {
                      if (!canWrite) return;
                      guard(() => {
                        setPane("editor");
                        setEditing(v);
                        setExpression(v.expression);
                        setDialect(v.dialect);
                        setAssetRefs(JSON.parse(v.referenced_assets ?? "[]"));
                        setColumnRefs(JSON.parse(v.referenced_columns ?? "[]"));
                        setDirty(false);
                      });
                    }}
                    onUse={(v) => {
                      if (!canWrite) return;
                      guard(() => {
                        setPane("editor");
                        setEditing(null);
                        setExpression(v.expression);
                        setDialect(v.dialect);
                        setAssetRefs(JSON.parse(v.referenced_assets ?? "[]"));
                        setColumnRefs(JSON.parse(v.referenced_columns ?? "[]"));
                        setDirty(true);
                      });
                    }}
                  />
                </div>
              </>
            )}
          </section>
        </div>
      )}
      {confirmDiscard && (
        <ConfirmDialog
          title="Discard unsaved changes?"
          description="Your edits to this formula version will be lost."
          action="Discard changes"
          onClose={() => setConfirmDiscard(null)}
          onConfirm={confirmDiscard}
        />
      )}
    </>
  );
}
