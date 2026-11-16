import { OwnerField } from "../../components/OwnerField";
import { SubmitReview } from "../governance/GovernancePage";
import { GuidedFields, ContextNote } from "../../components/Workflow";
import { DefinitionDependencies } from "./DefinitionDependencies";
import { usePage, loadOptions } from "./paging";
import { Pager } from "./pagination";
import { useEffect, useState } from "react";
import { mutate, request } from "../../services/api";
import type { Session } from "../../types/api";
import type { Formula, FormulaVersion } from "./FormulaStudio";
import {
  ConfirmDialog,
  EmptyState,
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
type Version = {
  id: string;
  version: number;
  formula_version_id: string;
  created_at: string;
  created_by: string;
  formula_id?: string;
  formula_name?: string;
  expression?: string;
  formula_version?: number;
  definition_snapshot?: unknown;
};
type Metric = {
  id: string;
  name: string;
  business_key: string;
  description: string;
  owner_team_id: string;
  unit?: string;
  aggregation?: string;
  grain?: string;
  business_owner?: string;
  technical_owner?: string;
  revision: number;
  latest_version?: number;
  latest_created_at?: string;
  owner_team_name?: string;
  formula_name?: string;
  consumer_count?: number;
};
type Mapping = {
  id: string;
  metric_name: string;
  version: number;
  asset_name: string;
  asset_id: string;
  asset_type: string;
  provenance: string;
  evidence_ref: string;
  metric_team_id: string;
  asset_team_id: string;
  active: boolean;
};
export function MetricsPage({
  session,
  mappingsOnly,
}: {
  session: Session;
  mappingsOnly: boolean;
}) {
  const [creating, setCreating] = useState(false);
  const [mapping, setMapping] = useState(false);
  const [formulaQuery, setFormulaQuery] = useState("");
  const [ownerFilter, setOwnerFilter] = useState("");
  const [proposedVersion, setProposedVersion] = useState("");
  const [revoke, setRevoke] = useState<string | null>(null);
  const a = useAction(),
    w = useWorkspace(a.revision, { teams: true, assets: true });
  const metricPage = usePage<Metric>("metrics", a.revision, {
      owner: ownerFilter,
    }),
    usagePage = usePage<Mapping>(
      "metric-mappings",
      a.revision,
      {},
      mappingsOnly,
    );
  const [selected, setSelected] = useState<Metric | null>(null),
    [formulas, setFormulas] = useState<Formula[]>([]),
    [formulaId, setFormulaId] = useState(""),
    [error, setError] = useState<unknown>(null);
  useEffect(() => {
    let active = true;
    loadOptions<Formula>("formulas")
      .then((f) => {
        if (active) setFormulas(f);
      })
      .catch((e) => {
        if (active) setError(e);
      });
    return () => {
      active = false;
    };
  }, [a.revision]);
  const canWrite = selected
    ? allowed(session, selected.owner_team_id)
    : w.teams.some((t) => allowed(session, t.id));
  const formulaVersions = useVersionOptions<FormulaVersion>(
    "formula-versions",
    formulaId,
    a.revision,
  );
  const metricVersions = useVersionOptions<Version>(
    "metric-versions",
    selected?.id ?? "",
    a.revision,
  );
  return (
    <>
      <PageHeader
        area="Definitions"
        title={mappingsOnly ? "Reporting" : "Metrics"}
        description={
          mappingsOnly
            ? "Track declared usage of metric versions in reports, models, and measures."
            : "Manage business definitions and the registered formulas that calculate them."
        }
        actions={
          w.teams.some((t) => t.active && allowed(session, t.id)) && (
            <button
              className="primary"
              onClick={() => {
                setSelected(null);
                if (mappingsOnly) setMapping(true);
                else setCreating(true);
              }}
            >
              {mappingsOnly ? "Add report mapping" : "Create metric"}
            </button>
          )
        }
      />
      <Feedback
        error={
          a.error ??
          w.error ??
          error ??
          formulaVersions.error ??
          metricVersions.error
        }
      />
      {a.notice && <Success>{a.notice}</Success>}
      {w.loading && (
        <Loading>Loading metric permissions and references…</Loading>
      )}
      {mappingsOnly &&
        !w.loading &&
        !w.assets.some((x) =>
          ["REPORT", "SEMANTIC_MODEL", "MEASURE", "VISUAL"].includes(
            x.asset_type,
          ),
        ) && (
          <section className="report-onboarding">
            <p className="eyebrow">Reporting onboarding</p>
            <h2>No reporting assets registered</h2>
            <p>
              Power BI is not connected. Register a real report, measure,
              semantic model or visual manually, or import genuine reporting
              metadata. Then choose a specific metric version and record the
              evidence for its usage.
            </p>
            <div className="definition-flow">
              <strong>Metric version</strong>
              <span>→</span>
              <strong>Reporting measure</strong>
              <span>→</span>
              <strong>Semantic model</strong>
              <span>→</span>
              <strong>Report / visual</strong>
            </div>
            <div className="action-row">
              <a className="button primary" href="#register">
                Register reporting asset
              </a>
              <a className="button" href="#import">
                Import reporting metadata
              </a>
              <a className="button" href="#lineage">
                Declare model and visual relationships
              </a>
            </div>
          </section>
        )}
      {!selected && !creating && (!mappingsOnly || mapping) && (
        <section className="panel table-panel">
          <div className="library-heading">
            <h2>
              {mapping ? "Choose the metric being used" : "All metrics"}{" "}
              <span>{metricPage.total}</span>
            </h2>
            {mapping && (
              <button onClick={() => setMapping(false)}>Cancel mapping</button>
            )}
          </div>
          <Pager
            page={metricPage}
            label="metrics"
            filters={
              <label>
                <span className="sr-only">Metric ownership on this page</span>
                <select
                  value={ownerFilter}
                  onChange={(e) => setOwnerFilter(e.target.value)}
                >
                  <option value="">All ownership</option>
                  <option value="missing">Missing business owner</option>
                  <option value="assigned">Business owner assigned</option>
                </select>
              </label>
            }
          />
          <TableRegion label="Metric library">
            <table>
              <thead>
                <tr>
                  <th>Business metric</th>
                  <th>Calculation</th>
                  <th>Version</th>
                  <th>Owner</th>
                  <th>Usage</th>
                </tr>
              </thead>
              <tbody>
                {metricPage.items.map((m) => (
                  <tr key={m.id}>
                    <td>
                      <button
                        className="record-name"
                        onClick={() =>
                          void a.run(async () => {
                            setSelected(
                              await request<Metric>(`/metrics/${m.id}`),
                            );
                            setCreating(false);
                          })
                        }
                      >
                        {m.name}
                      </button>
                      <small>{m.business_key}</small>
                      <small className="record-description">
                        {m.description || "No business definition recorded"}
                      </small>
                    </td>
                    <td>
                      {m.formula_name || "No registered calculation"}
                      <small>
                        {m.grain || "Grain not recorded"} ·{" "}
                        {m.unit || "Unit not recorded"}
                      </small>
                    </td>
                    <td>
                      {m.latest_version
                        ? `v${m.latest_version} · Registered`
                        : "Draft definition"}
                    </td>
                    <td>
                      {m.business_owner || "Business owner missing"}
                      <small>
                        {m.owner_team_name ||
                          w.teams.find((t) => t.id === m.owner_team_id)?.name}
                      </small>
                    </td>
                    <td>{m.consumer_count ?? 0} recorded consumers</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </TableRegion>
          {!metricPage.loading &&
            !metricPage.error &&
            !metricPage.items.length && (
              <EmptyState
                icon="metrics"
                title="No metrics found"
                description="Business definitions and their registered calculation versions will appear here."
              />
            )}
        </section>
      )}
      {(selected || creating) && (
        <div className="record-detail">
          <div className="detail-topline">
            <button
              onClick={() => {
                setSelected(null);
                setCreating(false);
                setMapping(false);
              }}
            >
              ← {mappingsOnly ? "Recorded mappings" : "All metrics"}
            </button>
            <span className="hint">
              {creating
                ? "Define the business meaning before choosing the calculation"
                : "Definition, calculation, ownership & version history"}
            </span>
          </div>
          <section className="panel">
            {!selected && (mappingsOnly || !canWrite) ? (
              <EmptyState
                icon={mappingsOnly ? "mapping" : "metrics"}
                title={
                  mappingsOnly ? "Select a metric to map" : "Select a metric"
                }
                description={
                  mappingsOnly
                    ? "Choose a metric with a registered version, then select the catalog resource that uses it."
                    : "Open a metric to see its definition, formula references, and version history."
                }
              />
            ) : !selected ? (
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  const d = new FormData(e.currentTarget);
                  void a.run(async () => {
                    const result = await mutate<{ id: string }>(
                      "/metrics",
                      Object.fromEntries(d.entries()),
                    );
                    setSelected(await request<Metric>(`/metrics/${result.id}`));
                    setCreating(false);
                    a.setNotice(
                      "Metric created. Register its first formula version.",
                    );
                  });
                }}
              >
                <h2>Create metric</h2>
                <fieldset disabled={!canWrite || a.busy}>
                  <GuidedFields
                    busy={a.busy}
                    submitLabel="Save definition & choose calculation"
                    steps={[
                      {
                        title: "Business meaning",
                        description:
                          "Explain what this number means. Use a stable business key to distinguish it from other metrics.",
                        fields: (
                          <>
                            {" "}
                            <label>
                              Business name
                              <input name="name" required maxLength={255} />
                            </label>
                            <label>
                              Business key
                              <input
                                name="businessKey"
                                required
                                pattern="[A-Z][A-Z0-9_]{0,99}"
                                placeholder="NET_REVENUE"
                              />
                            </label>
                            <label>
                              Description
                              <textarea name="description" />
                            </label>
                          </>
                        ),
                      },
                      {
                        title: "Measurement",
                        description:
                          "Record how values should be interpreted and grouped. Leave unknown information blank.",
                        fields: (
                          <>
                            {[
                              ["unit", "Unit", "INR, orders, percent…"],
                              [
                                "aggregation",
                                "Aggregation",
                                "SUM, COUNT, ratio…",
                              ],
                              ["grain", "Grain", "One row per month, store…"],
                            ].map(([name, label, placeholder]) => (
                              <label key={name}>
                                {label}
                                <input name={name} placeholder={placeholder} />
                              </label>
                            ))}
                          </>
                        ),
                      },
                      {
                        title: "Responsibility",
                        description:
                          "Choose the team maintaining this metric. Saving creates its definition; you will select and review a registered formula next.",
                        fields: (
                          <>
                            {[
                              ["businessOwner", "Business owner"],
                              ["technicalOwner", "Technical owner"],
                            ].map(([name, label]) => (
                              <OwnerField
                                key={name}
                                session={session}
                                name={name}
                                label={label}
                              />
                            ))}
                            <TeamSelect teams={w.teams} session={session} />
                            <ContextNote title="What happens next">
                              The definition is saved without inventing a
                              calculation. Choose an existing registered
                              formula, inspect its inputs and register the first
                              metric version. Approval is a separate review.
                            </ContextNote>
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
                  <code>{selected.business_key}</code>
                </div>
                <p>
                  {selected.description || "No business definition recorded."}
                </p>
                {metricVersions.items[0] && (
                  <MetricCalculationContext
                    key={`${selected.id}:${a.revision}`}
                    version={metricVersions.items[0]}
                    assets={w.assets}
                    formulas={formulas}
                  />
                )}
                {canWrite && metricVersions.items[0] && !mappingsOnly && (
                  <div className="action-row">
                    <SubmitReview
                      targetType="METRIC_VERSION"
                      targetId={metricVersions.items[0].id}
                      name={`${selected.name} · v${metricVersions.items[0].version}`}
                    />
                    <a className="button" href="#report-mappings">
                      Inspect reporting usage
                    </a>
                  </div>
                )}
                <dl className="details metric-details">
                  <div>
                    <dt>Grain</dt>
                    <dd>{selected.grain || "Not recorded"}</dd>
                  </div>
                  <div>
                    <dt>Aggregation</dt>
                    <dd>{selected.aggregation || "Not recorded"}</dd>
                  </div>
                  <div>
                    <dt>Unit</dt>
                    <dd>{selected.unit || "Not recorded"}</dd>
                  </div>
                  <div>
                    <dt>Owning team</dt>
                    <dd>
                      {w.teams.find(
                        (team) => team.id === selected.owner_team_id,
                      )?.name ?? "Unavailable"}
                    </dd>
                  </div>
                  <div>
                    <dt>Business owner</dt>
                    <dd>{selected.business_owner || "Not recorded"}</dd>
                  </div>
                  <div>
                    <dt>Technical owner</dt>
                    <dd>{selected.technical_owner || "Not recorded"}</dd>
                  </div>
                </dl>
                {!mappingsOnly && canWrite && (
                  <details>
                    <summary>Edit metric metadata</summary>
                    <form
                      key={`${selected.id}:${selected.revision}`}
                      onSubmit={(event) => {
                        event.preventDefault();
                        const data = Object.fromEntries(
                          new FormData(event.currentTarget),
                        );
                        void a.run(async () => {
                          const updated = await mutate<Metric>(
                            `/metrics/${selected.id}`,
                            {
                              definition: {
                                ...data,
                                businessKey: selected.business_key,
                                ownerTeamId: selected.owner_team_id,
                              },
                              expectedRevision: selected.revision,
                            },
                            "PATCH",
                          );
                          setSelected(updated);
                          a.setNotice(
                            "Metric metadata saved. Registered versions are preserved.",
                          );
                        });
                      }}
                    >
                      <fieldset disabled={a.busy}>
                        <label>
                          Business name
                          <input
                            name="name"
                            required
                            maxLength={255}
                            defaultValue={selected.name}
                          />
                        </label>
                        <label>
                          Description
                          <textarea
                            name="description"
                            maxLength={4000}
                            defaultValue={selected.description}
                          />
                        </label>
                        {(
                          [
                            ["unit", "Unit", selected.unit],
                            [
                              "aggregation",
                              "Aggregation",
                              selected.aggregation,
                            ],
                            ["grain", "Grain", selected.grain],
                            [
                              "businessOwner",
                              "Business owner",
                              selected.business_owner,
                            ],
                            [
                              "technicalOwner",
                              "Technical owner",
                              selected.technical_owner,
                            ],
                          ] as const
                        ).map(([name, label, value]) => (
                          <label key={name}>
                            {label}
                            <input
                              name={name}
                              maxLength={
                                name === "grain"
                                  ? 255
                                  : name.endsWith("Owner")
                                    ? 150
                                    : 100
                              }
                              defaultValue={value ?? ""}
                            />
                          </label>
                        ))}
                        <button className="primary">
                          Save metric metadata
                        </button>
                      </fieldset>
                    </form>
                  </details>
                )}
                {!mappingsOnly && (
                  <>
                    <details open={!metricVersions.items.length}>
                      <summary>
                        {metricVersions.items.length
                          ? "Register a new calculation version"
                          : "Next: choose the first calculation"}
                      </summary>
                      <ContextNote title="A version fixes the calculation and definition together">
                        Choose a reusable formula and an immutable version.
                        Review dependencies before saving. Existing report
                        mappings keep their original metric version.
                      </ContextNote>
                      <form
                        onSubmit={(e) => {
                          e.preventDefault();
                          const d = new FormData(e.currentTarget);
                          void a.run(async () => {
                            await mutate(`/metrics/${selected.id}/versions`, {
                              formulaVersionId: d.get("formulaVersionId"),
                            });
                            setSelected(
                              await request<Metric>(`/metrics/${selected.id}`),
                            );
                            a.setNotice("Metric version registered.");
                          });
                        }}
                      >
                        <fieldset disabled={!canWrite || a.busy}>
                          <label>
                            Find a reusable calculation
                            <input
                              type="search"
                              value={formulaQuery}
                              onChange={(e) => setFormulaQuery(e.target.value)}
                              placeholder="Search formulas by name or purpose…"
                            />
                          </label>
                          <label>
                            Formula
                            <select
                              required
                              value={formulaId}
                              onChange={(e) => {
                                setFormulaId(e.target.value);
                                setProposedVersion("");
                              }}
                            >
                              <option value="" disabled>
                                Select formula
                              </option>
                              {formulas
                                .filter(
                                  (f) =>
                                    allowed(session, f.owner_team_id) &&
                                    (f.id === formulaId ||
                                      `${f.name} ${f.description}`
                                        .toLowerCase()
                                        .includes(formulaQuery.toLowerCase())),
                                )
                                .map((f) => (
                                  <option key={f.id} value={f.id}>
                                    {f.name}
                                  </option>
                                ))}
                            </select>
                          </label>
                          <label>
                            Registered formula version
                            <select
                              name="formulaVersionId"
                              value={proposedVersion}
                              onChange={(e) =>
                                setProposedVersion(e.target.value)
                              }
                              key={formulaId}
                              required
                            >
                              <option value="">Select immutable version</option>
                              {formulaVersions.items
                                .filter((v) => v.state === "REGISTERED")
                                .map((v) => (
                                  <option key={v.id} value={v.id}>
                                    Version {v.version} ·{" "}
                                    {v.expression.slice(0, 60)}
                                  </option>
                                ))}
                            </select>
                          </label>
                          {proposedVersion && (
                            <DefinitionDependencies
                              name={
                                formulas.find((f) => f.id === formulaId)
                                  ?.name || "Selected formula"
                              }
                              assets={w.assets}
                              refs={JSON.parse(
                                formulaVersions.items.find(
                                  (v) => v.id === proposedVersion,
                                )?.referenced_assets || "[]",
                              )}
                              columnRefs={JSON.parse(
                                formulaVersions.items.find(
                                  (v) => v.id === proposedVersion,
                                )?.referenced_columns || "[]",
                              )}
                              target={
                                formulas.find((f) => f.id === formulaId)
                                  ?.asset_id
                              }
                              expression={
                                formulaVersions.items.find(
                                  (v) => v.id === proposedVersion,
                                )?.expression || ""
                              }
                              previous={
                                metricVersions.items[0]?.expression ?? ""
                              }
                              registeredId={proposedVersion}
                            />
                          )}
                          <p className="hint">
                            Review the proposed calculation and recorded
                            downstream consumers before registration. Existing
                            report mappings stay attached to their original
                            metric version.
                          </p>
                          <button>Register version</button>
                        </fieldset>
                      </form>
                    </details>
                    <h3>Version history</h3>
                    <MetricVersionHistory
                      id={selected.id}
                      revision={a.revision}
                    />
                  </>
                )}
                {mappingsOnly &&
                  (w.assets.some((x) =>
                    ["REPORT", "SEMANTIC_MODEL", "MEASURE", "VISUAL"].includes(
                      x.asset_type,
                    ),
                  ) ? (
                    <>
                      <h3>Map a version to a report or measure</h3>
                      <form
                        onSubmit={(e) => {
                          e.preventDefault();
                          const d = new FormData(e.currentTarget);
                          void a.run(async () => {
                            await mutate("/metric-mappings", {
                              metricVersionId: d.get("metricVersionId"),
                              assetId: d.get("assetId"),
                              evidenceRef: d.get("evidenceRef"),
                            });
                            setSelected(null);
                            setMapping(false);
                            a.setNotice(
                              "Version-specific usage mapping saved. It now appears in Recorded metric usage and downstream impact.",
                            );
                          });
                        }}
                      >
                        <fieldset disabled={!canWrite || a.busy}>
                          <GuidedFields
                            busy={a.busy}
                            submitLabel="Confirm report mapping"
                            steps={[
                              {
                                title: "Metric version",
                                description:
                                  "Choose the exact immutable definition used by the reporting resource.",
                                fields: (
                                  <>
                                    {" "}
                                    <label>
                                      Metric version
                                      <select
                                        name="metricVersionId"
                                        key={selected.id}
                                        required
                                      >
                                        <option value="">
                                          Select metric version
                                        </option>
                                        {metricVersions.items.map((v) => (
                                          <option key={v.id} value={v.id}>
                                            Version {v.version}
                                          </option>
                                        ))}
                                      </select>
                                    </label>
                                  </>
                                ),
                              },
                              {
                                title: "Reporting resource",
                                description:
                                  "Select a real registered report, model, measure or visual. Search by its name or source.",
                                fields: (
                                  <>
                                    {" "}
                                    <AssetSelect
                                      name="assetId"
                                      label="Report / semantic model / measure / visual"
                                      searchable
                                      assets={w.assets.filter(
                                        (x) =>
                                          [
                                            "REPORT",
                                            "SEMANTIC_MODEL",
                                            "MEASURE",
                                            "VISUAL",
                                          ].includes(x.asset_type) &&
                                          allowed(session, x.owner_team_id),
                                      )}
                                    />
                                  </>
                                ),
                              },
                              {
                                title: "Evidence & confirm",
                                description:
                                  "Explain where this usage is documented. This declaration will appear in reporting lineage and potential impact.",
                                fields: (
                                  <>
                                    {" "}
                                    <label>
                                      Evidence / declared usage
                                      <input
                                        name="evidenceRef"
                                        required
                                        maxLength={2000}
                                      />
                                    </label>
                                    <ContextNote title="What saving records">
                                      The chosen metric version and consumer are
                                      linked with your evidence. A future metric
                                      version does not silently move this
                                      mapping.
                                    </ContextNote>
                                  </>
                                ),
                              },
                            ]}
                          />
                        </fieldset>
                      </form>
                    </>
                  ) : (
                    <div className="report-onboarding">
                      <h3>No reporting assets registered</h3>
                      <p>
                        Register or import a genuine reporting asset before
                        mapping this metric version.
                      </p>
                      <a href="#report-mappings">Open reporting onboarding →</a>
                    </div>
                  ))}
              </>
            )}
          </section>
        </div>
      )}
      {revoke && (
        <ConfirmDialog
          title="Revoke report usage?"
          description="This declaration will no longer contribute to potential impact. Its history and evidence are retained."
          action="Revoke mapping"
          onClose={() => setRevoke(null)}
          onConfirm={() => {
            void a.run(async () => {
              await mutate(`/metric-mappings/${revoke}/revoke`);
              a.setNotice("Usage mapping revoked; history retained.");
            });
          }}
        />
      )}
      {mappingsOnly && (
        <section className="panel table-panel">
          <header>
            <h2>Recorded metric usage</h2>
            <p className="muted">
              Each mapping records a declaration and its evidence reference.
            </p>
          </header>
          <Pager page={usagePage} label="metric mappings" />
          {!usagePage.loading &&
            !usagePage.error &&
            (usagePage.items.length ? (
              <TableRegion label="Recorded metric usage">
                <table className="wide-table">
                  <thead>
                    <tr>
                      <th scope="col">Metric</th>
                      <th scope="col">Mapped resource</th>
                      <th scope="col">Provenance</th>
                      <th scope="col">Evidence</th>
                      <th scope="col">Status / action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {usagePage.items.map((mapping) => (
                      <tr key={mapping.id}>
                        <td>
                          {mapping.metric_name}{" "}
                          <span className="badge">v{mapping.version}</span>
                        </td>
                        <td>
                          <a href={`#asset/${mapping.asset_id}`}>
                            {mapping.asset_name}
                          </a>
                          <small>{humanize(mapping.asset_type)}</small>
                        </td>
                        <td>{humanize(mapping.provenance)}</td>
                        <td>{mapping.evidence_ref}</td>
                        <td>
                          <Status
                            value={mapping.active ? "ACTIVE" : "REVOKED"}
                          />
                          {mapping.active &&
                            allowed(session, mapping.metric_team_id) &&
                            allowed(session, mapping.asset_team_id) && (
                              <button
                                type="button"
                                disabled={a.busy}
                                onClick={() => setRevoke(mapping.id)}
                              >
                                Revoke usage mapping
                              </button>
                            )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </TableRegion>
            ) : (
              <EmptyState
                icon="mapping"
                title="No report mappings"
                description="Link a metric version to a catalog resource to record where the definition is used."
              />
            ))}
        </section>
      )}
    </>
  );
}

function MetricVersionHistory({
  id,
  revision,
}: {
  id: string;
  revision: number;
}) {
  const page = usePage<Version>("metric-versions", revision, { parent: id });
  return (
    <>
      <Pager page={page} label="metric versions" />
      {!page.loading &&
        !page.error &&
        (page.items.length ? (
          <TableRegion label="Metric version history">
            <table className="metric-version-table">
              <thead>
                <tr>
                  <th scope="col">Version</th>
                  <th scope="col">Calculation and dependencies</th>
                  <th scope="col">Registered</th>
                  <th scope="col">Created</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((version) => (
                  <tr key={version.id}>
                    <td>v{version.version}</td>
                    <td>
                      <strong>
                        {version.formula_name || version.formula_version_id}{" "}
                        {version.formula_version
                          ? `· v${version.formula_version}`
                          : ""}
                      </strong>
                      {version.expression && (
                        <pre className="sql-viewer">{version.expression}</pre>
                      )}
                      <a href={`#lineage/${version.id}`}>
                        Explore metric lineage →
                      </a>
                      <details>
                        <summary>Business definition at registration</summary>
                        <MetricBusinessSnapshot
                          value={version.definition_snapshot}
                        />
                      </details>
                    </td>
                    <td>
                      <Status value="REGISTERED" />
                    </td>
                    <td>
                      <Timestamp value={version.created_at} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </TableRegion>
        ) : (
          <p className="muted">No versions registered for this metric.</p>
        ))}
    </>
  );
}

function useVersionOptions<T>(
  kind: string,
  parent: string,
  revision: number,
): { items: T[]; error?: unknown } {
  const key = `${kind}:${parent}:${revision}`;
  const [result, setResult] = useState<{
    key: string;
    items: T[];
    error?: unknown;
  } | null>(null);
  useEffect(() => {
    if (!parent) return;
    let active = true;
    loadOptions<T>(kind, { parent })
      .then((items) => {
        if (active) setResult({ key, items });
      })
      .catch((error) => {
        if (active) setResult({ key, items: [], error });
      });
    return () => {
      active = false;
    };
  }, [kind, parent, key]);
  return result?.key === key ? result : { items: [] };
}

function MetricBusinessSnapshot({ value }: { value: unknown }) {
  let record = value;
  if (
    record &&
    typeof record === "object" &&
    "value" in record &&
    typeof record.value === "string"
  )
    record = record.value;
  if (typeof record === "string") {
    try {
      record = JSON.parse(record);
    } catch {
      record = null;
    }
  }
  if (!record || typeof record !== "object")
    return (
      <p className="hint">
        Historical business metadata snapshot unavailable for this legacy
        version.
      </p>
    );
  const fields = record as Record<string, unknown>;
  return (
    <dl className="metric-snapshot">
      {[
        ["name", "Business name"],
        ["description", "Business definition"],
        ["unit", "Unit"],
        ["aggregation", "Aggregation"],
        ["grain", "Grain"],
        ["business_owner", "Business owner"],
        ["technical_owner", "Technical owner"],
      ].map(([key, label]) => (
        <div key={key}>
          <dt>{label}</dt>
          <dd>{String(fields[key] || "Not recorded")}</dd>
        </div>
      ))}
    </dl>
  );
}

function MetricCalculationContext({
  version,
  assets,
  formulas,
}: {
  version: Version;
  assets: import("../../types/api").Asset[];
  formulas: Formula[];
}) {
  const [snapshot, setSnapshot] = useState<FormulaVersion | null>(null);
  const [error, setError] = useState<unknown>(null);
  useEffect(() => {
    if (!version.formula_id) return;
    let active = true;
    request<FormulaVersion>(
      `/formulas/${version.formula_id}/versions/${version.formula_version_id}`,
    )
      .then((v) => {
        if (active) setSnapshot(v);
      })
      .catch((e) => {
        if (active) setError(e);
      });
    return () => {
      active = false;
    };
  }, [version.formula_id, version.formula_version_id]);
  return (
    <details open>
      <summary>Current calculation and source dependencies</summary>
      <Feedback error={error} />
      {snapshot && (
        <DefinitionDependencies
          name={version.formula_name || "Registered calculation"}
          assets={assets}
          refs={JSON.parse(snapshot.referenced_assets || "[]")}
          columnRefs={JSON.parse(snapshot.referenced_columns || "[]")}
          target={formulas.find((f) => f.id === version.formula_id)?.asset_id}
          expression={snapshot.expression}
          registeredId={snapshot.id}
        />
      )}
      <a href={`#lineage/${version.id}`}>Explore complete metric lineage →</a>
    </details>
  );
}
