import { useEffect, useState } from "react";
import { request } from "../../services/api";
import { Feedback, Loading } from "../../components/Feedback";
import { humanize } from "../../components/format";
import type { Node, Edge } from "../integration/LineageGraph";
import { ContextNote } from "../../components/Workflow";

type Relationships = {
  nodes: Node[];
  edges: Edge[];
  totalNodes: number;
  totalEdges: number;
  hasMore: boolean;
};
export function AssetRelationships({
  id,
  section = "all",
}: {
  id: string;
  section?: "all" | "lineage" | "transformations" | "definitions" | "consumers";
}) {
  const [resource, setResource] = useState<{
    id: string;
    data?: Relationships;
    error?: unknown;
  } | null>(null);
  useEffect(() => {
    const c = new AbortController();
    request<Relationships>(`/lineage/${id}?direction=both&limit=80`, {
      signal: c.signal,
    })
      .then((data) => setResource({ id, data }))
      .catch((error) => {
        if (!c.signal.aborted) setResource({ id, error });
      });
    return () => c.abort();
  }, [id]);
  if (resource?.id !== id)
    return <Loading>Reading registered relationships…</Loading>;
  if (resource.error) return <Feedback error={resource.error} />;
  const data = resource.data!;
  const edges = data.edges;
  const directInput = new Set(
    edges.filter((e) => e.target_asset_id === id).map((e) => e.source_asset_id),
  );
  const directOutput = new Set(
    edges.filter((e) => e.source_asset_id === id).map((e) => e.target_asset_id),
  );
  const transformation = data.nodes.find(
    (n) =>
      n.asset_type === "SQL_TRANSFORMATION" &&
      edges.some(
        (e) =>
          e.source_asset_id === n.id &&
          e.target_asset_id === id &&
          e.relationship_type === "TRANSFORMATION_OUTPUT",
      ),
  );
  const inputs = transformation
    ? data.nodes.filter((n) =>
        edges.some(
          (e) =>
            e.source_asset_id === n.id &&
            e.target_asset_id === transformation.id &&
            e.relationship_type === "TRANSFORMATION_INPUT",
        ),
      )
    : [];
  let operations: { kind: string; expression: string }[] = [];
  try {
    const meta = JSON.parse(transformation?.metadata || "{}");
    if (Array.isArray(meta.rules)) operations = meta.rules;
  } catch {
    /* Missing parser evidence stays unknown. */
  }
  function reachable(reverse = false) {
    const seen = new Set([id]),
      queue = [id];
    for (const current of queue)
      for (const e of edges) {
        const from = reverse ? e.target_asset_id : e.source_asset_id,
          to = reverse ? e.source_asset_id : e.target_asset_id;
        if (from === current && !seen.has(to)) {
          seen.add(to);
          queue.push(to);
        }
      }
    return seen;
  }
  const downstream = reachable(),
    upstream = reachable(true);
  const groups = [
    {
      title: "Direct inputs",
      description: "Recorded relationships that feed this asset.",
      ids: directInput,
      kind: "lineage",
    },
    {
      title: "Direct outputs",
      description: "Assets that directly depend on this asset.",
      ids: directOutput,
      kind: "lineage",
    },
    {
      title: "SQL transformations",
      description:
        "Definitions on recorded upstream or downstream paths from this asset.",
      types: ["SQL_TRANSFORMATION"],
      kind: "transformations",
    },
    {
      title: "Calculations & metrics",
      description:
        "Registered formula and metric versions reached downstream through recorded dependency paths.",
      types: ["FORMULA_VERSION", "METRIC_VERSION"],
      kind: "definitions",
    },
    {
      title: "Reporting consumers",
      description:
        "Only registered reports, measures, models and visuals are listed.",
      types: ["REPORT", "MEASURE", "SEMANTIC_MODEL", "VISUAL"],
      kind: "consumers",
    },
  ];
  return (
    <div>
      <ContextNote title="Relationships from the registry">
        These links describe recorded dependencies. SQL evidence records an
        inspected definition; ingestion declarations do not independently verify
        a load.{" "}
        {data.hasMore &&
          "This panel shows the first 80 relationships; open Data Flow for continuation."}
      </ContextNote>
      {section === "all" && transformation && (
        <section className="definition-summary">
          <h3>How this output is produced</h3>
          <div className="dependency-lanes transformation-flow">
            <div>
              <small>Recorded input datasets</small>
              {inputs.map((n) => (
                <p key={n.id}>
                  <a href={`#asset/${n.id}`}>{n.external_key}</a>
                </p>
              ))}
            </div>
            <span aria-hidden="true">→</span>
            <div>
              <small>Operations from stored SQL evidence</small>
              {operations.length ? (
                operations.map((o, i) => (
                  <details key={i}>
                    <summary>{humanize(o.kind)}</summary>
                    <pre className="sql-viewer">{o.expression}</pre>
                  </details>
                ))
              ) : (
                <p>Inspect the stored SQL definition.</p>
              )}
            </div>
            <span aria-hidden="true">→</span>
            <div>
              <small>Output</small>
              <strong>
                {data.nodes.find((n) => n.id === id)?.external_key}
              </strong>
            </div>
          </div>
        </section>
      )}
      {groups
        .filter((g) => section === "all" || section === g.kind)
        .map((g) => {
          const nodes = data.nodes.filter(
            (n) =>
              n.id !== id &&
              (g.ids
                ? g.ids.has(n.id)
                : g.types?.includes(n.asset_type) &&
                  (g.kind === "transformations"
                    ? downstream.has(n.id) || upstream.has(n.id)
                    : downstream.has(n.id))),
          );
          return (
            <section key={g.title}>
              <h3>
                {g.title}{" "}
                <span className="muted">
                  {nodes.length}
                  {data.hasMore ? " on this page" : ""}
                </span>
              </h3>
              <p className="hint">{g.description}</p>
              {nodes.length ? (
                nodes.map((n) => (
                  <div className="list-row" key={n.id}>
                    <div>
                      <a
                        href={
                          ["FORMULA_VERSION", "METRIC_VERSION"].includes(
                            n.asset_type,
                          )
                            ? `#lineage/${n.id}`
                            : `#asset/${n.id}`
                        }
                      >
                        {n.name}
                      </a>
                      <small>{humanize(n.asset_type)}</small>
                      {(() => {
                        const relation = edges.find(
                          (e) =>
                            (e.source_asset_id === id &&
                              e.target_asset_id === n.id) ||
                            (e.source_asset_id === n.id &&
                              e.target_asset_id === id),
                        );
                        return relation ? (
                          <details>
                            <summary>Inspect relationship evidence</summary>
                            <p className="hint">
                              {humanize(relation.provenance || "UNKNOWN")}
                            </p>
                            <p className="record-description">
                              {relation.evidence_ref ||
                                "No evidence reference recorded"}
                            </p>
                          </details>
                        ) : null;
                      })()}
                    </div>
                    <a className="button" href={`#impact/${n.id}`}>
                      Inspect impact
                    </a>
                  </div>
                ))
              ) : (
                <p className="muted">
                  None recorded
                  {data.hasMore ? " on this graph page" : " in this scope"}.
                </p>
              )}
            </section>
          );
        })}
      <div className="action-row">
        <a className="button" href={`#lineage/${id}`}>
          Explore complete data flow →
        </a>
        <a className="button" href={`#impact/${id}`}>
          Plan a change
        </a>
      </div>
    </div>
  );
}
