import { useEffect, useState } from "react";
import type { Asset } from "../../types/api";
import { request } from "../../services/api";
import { Feedback } from "../../components/Feedback";
import { LineageGraph, type Node, type Edge } from "./LineageGraph";
export function DefinitionDependencies({
  name,
  assets,
  refs,
  columnRefs,
  target,
  expression,
  previous,
  registeredId,
  previousRefs,
  previousColumns,
}: {
  name: string;
  assets: Asset[];
  refs: string[];
  columnRefs: string[];
  target?: string;
  expression: string;
  previous?: string;
  registeredId?: string;
  previousRefs?: string[];
  previousColumns?: string[];
}) {
  const [columnDetails, setColumnDetails] = useState<
    {
      id: string;
      asset_id: string;
      asset_key: string;
      name: string;
      data_type: string;
    }[]
  >([]);
  type Impact = {
    nodes: Node[];
    edges: Edge[];
    totalNodes: number;
    totalEdges: number;
    hasMore: boolean;
  };
  const [impactResource, setImpactResource] = useState<{
    id: string;
    data: Impact;
  } | null>(null);
  const impact =
    impactResource && impactResource.id === registeredId
      ? impactResource.data
      : null;
  const [error, setError] = useState<unknown>(null);
  useEffect(() => {
    if (!registeredId) return;
    let active = true;
    request<Impact>(`/lineage/${registeredId}?direction=downstream&limit=60`)
      .then((r) => {
        if (active) setImpactResource({ id: registeredId, data: r });
      })
      .catch((e) => {
        if (active) setError(e);
      });
    return () => {
      active = false;
    };
  }, [registeredId]);
  const columnKey = columnRefs.join(",");
  useEffect(() => {
    if (!columnKey) return;
    let active = true;
    request<typeof columnDetails>(
      `/lineage/reference-columns?ids=${encodeURIComponent(columnKey)}`,
    )
      .then((c) => {
        if (active) setColumnDetails(c);
      })
      .catch((e) => {
        if (active) setError(e);
      });
    return () => {
      active = false;
    };
  }, [columnKey]);
  return (
    <section className="formula-dependency-preview">
      <div className="definition-flow">
        <div>
          <small>Explicit source assets</small>
          {refs.length ? (
            <ul>
              {refs.map((id) => (
                <li key={id}>
                  <a href={`#lineage/${id}`}>
                    {assets.find((x) => x.id === id)?.external_key || id}
                  </a>
                </li>
              ))}
            </ul>
          ) : (
            <strong>No dataset references declared</strong>
          )}
          <small>{columnRefs.length} source column references</small>
          {!!columnRefs.length && (
            <ul>
              {columnDetails
                .filter((c) => columnRefs.includes(c.id))
                .map((c) => (
                  <li key={c.id}>
                    <a href={`#lineage/${c.asset_id}?column=${c.id}`}>
                      {c.asset_key}.{c.name}
                    </a>{" "}
                    <small>{c.data_type}</small>
                  </li>
                ))}
            </ul>
          )}
        </div>
        <span>→</span>
        <div>
          <small>Calculation / expression</small>
          <strong>{name}</strong>
        </div>
        <span>→</span>
        <div>
          <small>Linked output</small>
          {target ? (
            <a href={`#asset/${target}`}>
              {assets.find((x) => x.id === target)?.external_key ||
                "Registered target"}
            </a>
          ) : (
            <strong>No linked target recorded</strong>
          )}
        </div>
      </div>
      {previousRefs && (
        <div className="definition-summary">
          <strong>Source-reference changes</strong>
          <p>
            Added:{" "}
            {refs
              .filter((id) => !previousRefs.includes(id))
              .map((id) => assets.find((a) => a.id === id)?.external_key || id)
              .join(", ") || "None"}
          </p>
          <p>
            Removed:{" "}
            {previousRefs
              .filter((id) => !refs.includes(id))
              .map((id) => assets.find((a) => a.id === id)?.external_key || id)
              .join(", ") || "None"}
          </p>
          {previousColumns && (
            <p>
              Column references:{" "}
              {columnRefs.filter((id) => !previousColumns.includes(id)).length}{" "}
              added ·{" "}
              {previousColumns.filter((id) => !columnRefs.includes(id)).length}{" "}
              removed
            </p>
          )}
        </div>
      )}
      {previous !== undefined && (
        <details className="definition-summary">
          <summary>
            Current vs proposed expression ·{" "}
            {previous === expression
              ? "expression unchanged"
              : "expression changed"}
          </summary>
          <h4>Current stored expression</h4>
          <pre>{previous || "No previous version"}</pre>
          <h4>Proposed expression</h4>
          <pre>{expression}</pre>
        </details>
      )}
      <Feedback error={error} />
      {impact && (
        <details>
          <summary>
            Pre-registration impact · {Math.max(0, impact.totalNodes - 1)} known
            downstream dependencies
          </summary>
          <p className="hint">
            Based on the current registered snapshot. Proposed source references
            above may change the dependency graph. Unknown consumers cannot be
            assessed.
          </p>
          <div className="formula-dependency-preview">
            <LineageGraph
              root={registeredId!}
              nodes={impact.nodes}
              edges={impact.edges}
            />
          </div>
          {impact.hasMore && (
            <a href={`#impact/${registeredId}`}>
              Continue complete impact exploration →
            </a>
          )}
        </details>
      )}
      <p className="hint">
        Registration preserves an immutable registry snapshot. It does not
        approve the business definition or deploy to Snowflake.
      </p>
    </section>
  );
}
