import { useEffect, useMemo, useRef, useState } from "react";
import {
  ReactFlow,
  ReactFlowProvider,
  Background,
  Controls,
  MiniMap,
  Handle,
  Position,
  MarkerType,
  useNodesState,
  useNodesInitialized,
  useReactFlow,
  type NodeProps,
  type Node as FlowNode,
} from "@xyflow/react";
import ELK from "elkjs/lib/elk-api.js";
import ElkLayoutWorker from "elkjs/lib/elk-worker.min.js?worker";
import "@xyflow/react/dist/style.css";
import "./lineage.css";
import { request } from "../../services/api";
import { humanize } from "../../components/format";
import { Feedback } from "../../components/Feedback";
import type { AssetColumn } from "../../types/api";
export type Node = {
  id: string;
  name: string;
  asset_type: string;
  external_key: string;
  platform?: string;
  metadata?: string;
  connection_name?: string;
  owner_team_name?: string;
  description?: string;
  last_seen_at?: string;
  inventory_state?: string;
  provenance?: string;
};
export type Edge = {
  id: string;
  source_asset_id: string;
  target_asset_id: string;
  relationship_type: string;
  provenance: string;
  evidence_ref: string;
  active?: boolean;
  created_at?: string;
  created_by?: string;
  description?: string;
};
const elk = new ELK({ workerFactory: () => new ElkLayoutWorker() });
function meta(n?: Node) {
  try {
    return JSON.parse(n?.metadata || "{}") as Record<string, unknown>;
  } catch {
    return {};
  }
}
function tone(n: Node) {
  return n.asset_type === "SQL_TRANSFORMATION"
    ? "violet"
    : n.asset_type.includes("FORMULA")
      ? "violet"
      : n.asset_type.includes("METRIC")
        ? "amber"
        : n.platform === "AMAZON_S3"
          ? "orange"
          : ["REPORT", "MEASURE", "VISUAL", "SEMANTIC_MODEL"].includes(
                n.asset_type,
              )
            ? "blue"
            : "teal";
}
function labelType(n: Node) {
  return meta(n).objectType === "BASE TABLE"
    ? "Snowflake table"
    : meta(n).objectType === "VIEW"
      ? "Snowflake view"
      : n.platform === "AMAZON_S3"
        ? "S3 file"
        : humanize(n.asset_type);
}
function AssetNode({
  data,
}: NodeProps<FlowNode<{ entry: Node; vertical: boolean; root: boolean }>>) {
  const n = data.entry;
  return (
    <div className={`flow-asset ${tone(n)} ${data.root ? "is-root" : ""}`}>
      <Handle
        type="target"
        position={data.vertical ? Position.Top : Position.Left}
      />
      <span className="flow-node-kind">{labelType(n)}</span>
      <strong title={n.external_key}>{n.name.replace(/^SQL · /, "")}</strong>
      <small>{n.connection_name || n.platform}</small>
      <Handle
        type="source"
        position={data.vertical ? Position.Bottom : Position.Right}
      />
    </div>
  );
}
const nodeTypes = { asset: AssetNode };
export function LineageGraph(props: {
  nodes: Node[];
  edges: Edge[];
  root: string;
  onExplore?: (id: string, direction: string) => void;
}) {
  return (
    <ReactFlowProvider>
      <GraphCanvas {...props} />
    </ReactFlowProvider>
  );
}
function GraphCanvas({
  nodes,
  edges,
  root,
  onExplore,
}: {
  nodes: Node[];
  edges: Edge[];
  root: string;
  onExplore?: (id: string, direction: string) => void;
}) {
  const [flowNodes, setNodes, onNodesChange] = useNodesState<
    FlowNode<{ entry: Node; vertical: boolean; root: boolean }>
  >([]);
  const [selection, setSelection] = useState({ kind: "node", id: root });
  const selectionRef = useRef(selection);
  useEffect(() => {
    selectionRef.current = selection;
  }, [selection]);
  const [orientation, setOrientation] = useState("RIGHT");
  const [focus, setFocus] = useState("");
  const [highlight, setHighlight] = useState("all");
  const [collapsed, setCollapsed] = useState<string[]>([]);
  const [full, setFull] = useState(false);
  const [layoutError, setLayoutError] = useState<unknown>(null);
  const workspace = useRef<HTMLDivElement>(null);
  const flow = useReactFlow();
  const initialized = useNodesInitialized();
  const [layoutRevision, setLayoutRevision] = useState(0);
  const byId = useMemo(() => new Map(nodes.map((n) => [n.id, n])), [nodes]);
  const node = selection.kind === "node" ? byId.get(selection.id) : undefined;
  const edge =
    selection.kind === "edge"
      ? edges.find((e) => e.id === selection.id)
      : undefined;
  const visible = useMemo(() => {
    const hidden = new Set<string>();
    for (const id of collapsed) {
      const q = [id];
      const seen = new Set(q);
      for (const x of q)
        for (const e of edges)
          if (e.source_asset_id === x && !seen.has(e.target_asset_id)) {
            seen.add(e.target_asset_id);
            q.push(e.target_asset_id);
            if (e.target_asset_id !== root) hidden.add(e.target_asset_id);
          }
    }
    return nodes.filter((n) => !hidden.has(n.id));
  }, [nodes, edges, collapsed, root]);
  useEffect(() => {
    let active = true;
    const ids = new Set(visible.map((n) => n.id));
    void elk
      .layout({
        id: "root",
        layoutOptions: {
          "elk.algorithm": "layered",
          "elk.direction": orientation,
          "elk.spacing.nodeNode": "44",
          "elk.layered.spacing.nodeNodeBetweenLayers": "100",
          "elk.edgeRouting": "ORTHOGONAL",
        },
        children: visible.map((n) => ({ id: n.id, width: 224, height: 112 })),
        edges: edges
          .filter(
            (e) => ids.has(e.source_asset_id) && ids.has(e.target_asset_id),
          )
          .map((e) => ({
            id: e.id,
            sources: [e.source_asset_id],
            targets: [e.target_asset_id],
          })),
      })
      .then((result) => {
        if (!active) return;
        setNodes(
          (result.children || []).map((n) => ({
            id: n.id,
            type: "asset",
            position: { x: n.x || 0, y: n.y || 0 },
            data: {
              entry: byId.get(n.id)!,
              vertical: orientation === "DOWN",
              root: n.id === root,
            },
            ariaLabel: `${byId.get(n.id)?.name}, ${labelType(byId.get(n.id)!)}`,
            selected: n.id === root,
          })),
        );
        setLayoutRevision((v) => v + 1);
      })
      .catch((e) => {
        if (active) setLayoutError(e);
      });
    return () => {
      active = false;
    };
  }, [visible, edges, root, orientation, byId, setNodes, flow]);
  useEffect(() => {
    if (!initialized) return;
    const selected = selectionRef.current;
    const anchor =
      selected.kind === "node" && byId.has(selected.id) ? selected.id : root;
    const related = new Set([anchor]);
    for (const e of edges)
      if (e.source_asset_id === anchor || e.target_asset_id === anchor) {
        related.add(e.source_asset_id);
        related.add(e.target_asset_id);
      }
    const frame = requestAnimationFrame(
      () =>
        void flow.fitView({
          nodes: [...related].map((id) => ({ id })),
          padding: 0.1,
          minZoom: 0.7,
          maxZoom: 1,
          duration: 180,
        }),
    );
    return () => cancelAnimationFrame(frame);
  }, [initialized, layoutRevision, root, full, flow, edges, byId]);
  const emphasized = useMemo(() => {
    if (highlight === "all") return null;
    const found = new Set([selection.id]);
    const q = [selection.id];
    for (const id of q)
      for (const e of edges) {
        const from =
          highlight === "upstream" ? e.target_asset_id : e.source_asset_id;
        const to =
          highlight === "upstream" ? e.source_asset_id : e.target_asset_id;
        if (from === id && !found.has(to)) {
          found.add(to);
          q.push(to);
        }
      }
    return found;
  }, [highlight, selection.id, edges]);
  const flowEdges = edges.map((e) => ({
    id: e.id,
    source: e.source_asset_id,
    target: e.target_asset_id,
    type: "smoothstep",
    label: humanize(e.relationship_type)
      .replace("Transformation ", "")
      .replace("Metric definition dependency", "Calculation"),
    markerEnd: { type: MarkerType.ArrowClosed, color: "#60847b" },
    selected: e.id === edge?.id,
    style: {
      stroke: e.id === edge?.id ? "#2563eb" : "#8da9ca",
      strokeWidth: e.id === edge?.id ? 2.4 : 1.3,
      strokeDasharray:
        e.provenance === "MANUAL_DECLARATION" ? "5 4" : undefined,
      opacity:
        !emphasized ||
        (emphasized.has(e.source_asset_id) && emphasized.has(e.target_asset_id))
          ? 1
          : 0.15,
    },
    labelStyle: { fill: "#53665f", fontSize: 11 },
    labelBgStyle: { fill: "#f8faf9", fillOpacity: 0.96 },
    ariaLabel: `${byId.get(e.source_asset_id)?.name} → ${byId.get(e.target_asset_id)?.name} · ${humanize(e.relationship_type)}`,
  }));
  return (
    <div
      ref={workspace}
      className={`lineage-canvas-shell ${full ? "graph-fullscreen" : ""}`}
      role={full ? "dialog" : undefined}
      aria-modal={full || undefined}
      aria-label={full ? "Full-screen lineage workspace" : undefined}
      onKeyDown={(e) => {
        if (!full) return;
        if (e.key === "Escape") {
          setFull(false);
          e.stopPropagation();
        }
        if (e.key === "Tab") {
          const items = Array.from(
            workspace.current?.querySelectorAll<HTMLElement>(
              'button, a[href], select, [tabindex="0"]',
            ) || [],
          ).filter(
            (x) => !x.hasAttribute("disabled") && x.offsetParent !== null,
          );
          const first = items[0],
            last = items.at(-1);
          if (e.shiftKey && document.activeElement === first) {
            e.preventDefault();
            last?.focus();
          } else if (!e.shiftKey && document.activeElement === last) {
            e.preventDefault();
            first?.focus();
          }
        }
      }}
    >
      <div className="flow-toolbar">
        <div className="segmented">
          <button
            aria-pressed={orientation === "RIGHT"}
            onClick={() => setOrientation("RIGHT")}
          >
            Left → right
          </button>
          <button
            aria-pressed={orientation === "DOWN"}
            onClick={() => setOrientation("DOWN")}
          >
            Top ↓ bottom
          </button>
        </div>
        <label className="sr-only" htmlFor="graph-focus">
          Focus a visible node
        </label>
        <select
          id="graph-focus"
          value={focus}
          onChange={(e) => {
            setFocus(e.target.value);
            setSelection({ kind: "node", id: e.target.value });
            void flow.fitView({
              nodes: [{ id: e.target.value }],
              maxZoom: 1,
              duration: 250,
            });
          }}
        >
          <option value="">Find in graph…</option>
          {nodes.map((n) => (
            <option key={n.id} value={n.id}>
              {n.asset_type === "COLUMN"
                ? `${n.connection_name}.${n.name}`
                : n.name}
            </option>
          ))}
        </select>
        <button
          onClick={() =>
            void flow.fitView({ padding: 0.15, maxZoom: 1, duration: 250 })
          }
        >
          Fit to view
        </button>
        <button
          onClick={() =>
            void flow.fitView({
              nodes: [{ id: selection.kind === "node" ? selection.id : root }],
              padding: 0.2,
              maxZoom: 1,
              duration: 200,
            })
          }
        >
          Focus selection
        </button>
        <button onClick={() => setFull(!full)}>
          {full ? "Exit full screen" : "Full screen"}
        </button>
      </div>
      <Feedback error={layoutError} />
      <div className="lineage-stage">
        <div
          className="flow-surface"
          role="region"
          aria-label="Interactive lineage graph"
        >
          <ReactFlow
            nodes={flowNodes.map((n) => ({
              ...n,
              style: { opacity: !emphasized || emphasized.has(n.id) ? 1 : 0.2 },
            }))}
            edges={flowEdges}
            nodeTypes={nodeTypes}
            onNodesChange={onNodesChange}
            nodesConnectable={false}
            deleteKeyCode={null}
            minZoom={0.08}
            maxZoom={2}
            onlyRenderVisibleElements
            onNodeClick={(_, n) => setSelection({ kind: "node", id: n.id })}
            onEdgeClick={(_, e) => setSelection({ kind: "edge", id: e.id })}
            onNodeDoubleClick={(_, n) => onExplore?.(n.id, "both")}
            fitView
          >
            <Background gap={22} size={1} color="#dbe5e0" />
            <Controls showInteractive={false} />
            <MiniMap
              pannable
              zoomable
              nodeColor={(n) => (n.id === root ? "#2563eb" : "#c5d9f0")}
            />
          </ReactFlow>
          <div className="graph-legend">
            <span className="dot teal" />
            Dataset <span className="dot violet" />
            Calculation <span className="dot orange" />
            File <span className="dot amber" />
            Metric
          </div>
        </div>
        <aside className="context-inspector" aria-label="Lineage inspector">
          <div className="inspector-heading">
            <span className="eyebrow">Context inspector</span>
            <span className="badge">{node ? "Asset" : "Relationship"}</span>
          </div>
          {node ? (
            <>
              <h2>{node.name}</h2>
              <code className="qualified-id">{node.external_key}</code>
              <div className="inspector-actions">
                <button
                  onClick={() =>
                    setHighlight(highlight === "upstream" ? "all" : "upstream")
                  }
                >
                  Highlight upstream
                </button>
                <button
                  onClick={() =>
                    setHighlight(
                      highlight === "downstream" ? "all" : "downstream",
                    )
                  }
                >
                  Highlight downstream
                </button>
              </div>
              {onExplore && (
                <div className="inspector-actions">
                  <button onClick={() => onExplore(node.id, "upstream")}>
                    Explore upstream
                  </button>
                  <button onClick={() => onExplore(node.id, "downstream")}>
                    Explore downstream
                  </button>
                  <button
                    onClick={() =>
                      setCollapsed(
                        collapsed.includes(node.id)
                          ? collapsed.filter((x) => x !== node.id)
                          : [...collapsed, node.id],
                      )
                    }
                  >
                    Collapse / expand branch
                  </button>
                </div>
              )}
              <NodeInspector key={node.id} node={node} />
              <h3>Recorded neighbors in this page</h3>
              {edges
                .filter(
                  (e) =>
                    e.source_asset_id === node.id ||
                    e.target_asset_id === node.id,
                )
                .map((e) => (
                  <button
                    className="inspector-relationship"
                    key={e.id}
                    onClick={() => setSelection({ kind: "edge", id: e.id })}
                  >
                    {e.target_asset_id === node.id ? "←" : "→"}{" "}
                    {
                      byId.get(
                        e.target_asset_id === node.id
                          ? e.source_asset_id
                          : e.target_asset_id,
                      )?.name
                    }
                    <small>{humanize(e.relationship_type)}</small>
                  </button>
                ))}
            </>
          ) : edge ? (
            <>
              <h2>{humanize(edge.relationship_type)}</h2>
              <div className="relationship-path">
                {byId.get(edge.source_asset_id)?.name}
                <span>↓</span>
                {byId.get(edge.target_asset_id)?.name}
              </div>
              <dl>
                <dt>Evidence semantics</dt>
                <dd>
                  {edge.provenance === "SQL_PARSED_CONFIRMED"
                    ? "SQL dependency explicitly confirmed"
                    : humanize(edge.provenance)}
                </dd>
                <dt>Source reference</dt>
                <dd>
                  <EvidenceReference value={edge.evidence_ref} />
                </dd>
                <dt>Expression / declaration</dt>
                <dd>{edge.description || "No expression supplied"}</dd>
                <dt>Recorded</dt>
                <dd>
                  {edge.created_at
                    ? new Date(edge.created_at).toLocaleString()
                    : "Not recorded"}
                </dd>
              </dl>
              <p className="hint">
                A recorded dependency indicates potential impact. It does not
                prove that a proposed change will break a consumer.
              </p>
            </>
          ) : (
            <p>Select a node or edge to inspect evidence.</p>
          )}
        </aside>
      </div>
    </div>
  );
}
function EvidenceReference({ value }: { value?: string }) {
  return (
    <>
      {(value || "Not recorded")
        .split(/(https?:\/\/[^\s;]+)/g)
        .map((part, i) => {
          if (!/^https?:\/\//.test(part)) return <span key={i}>{part}</span>;
          try {
            const url = new URL(part);
            const revision = url.pathname.split("/blob/")[1]?.split("/")[0];
            return (
              <a
                key={i}
                className="evidence-link"
                href={part}
                target="_blank"
                rel="noreferrer"
                title={part}
              >
                {url.pathname.split("/").at(-1)}
                {url.hash}
                {revision ? ` · ${revision.slice(0, 7)}` : ""} ↗
              </a>
            );
          } catch {
            return <span key={i}>{part}</span>;
          }
        })}
    </>
  );
}
function NodeInspector({ node }: { node: Node }) {
  const [details, setDetails] = useState<Record<string, unknown> | null>(null);
  const [columns, setColumns] = useState<AssetColumn[]>([]);
  const [error, setError] = useState<unknown>(null);
  const virtual = [
    "METRIC_VERSION",
    "FORMULA_VERSION",
    "COLUMN",
    "SYSTEM",
  ].includes(node.asset_type);
  useEffect(() => {
    if (virtual) return;
    let active = true;
    Promise.all([
      request<Record<string, unknown>>(`/assets/${node.id}/curation`),
      request<AssetColumn[]>(`/assets/${node.id}/columns`),
    ])
      .then(([d, c]) => {
        if (active) {
          setDetails(d);
          setColumns(c);
        }
      })
      .catch((e) => {
        if (active) setError(e);
      });
    return () => {
      active = false;
    };
  }, [node.id, virtual]);
  const metadata = details
    ? meta({ metadata: details.metadata as string } as Node)
    : meta(node);
  const sql = metadata.sql || metadata.viewDefinition || metadata.expression;
  const target = String(metadata.targetAssetId || node.id);
  return (
    <>
      <Feedback error={error} />
      <dl>
        <dt>Type</dt>
        <dd>{labelType(node)}</dd>
        <dt>
          {node.asset_type === "COLUMN" ? "Parent asset" : "Source connection"}
        </dt>
        <dd>{node.connection_name || node.platform || "Not recorded"}</dd>
        {!!metadata.dataType && (
          <>
            <dt>Data type</dt>
            <dd>{String(metadata.dataType)}</dd>
          </>
        )}
        <dt>Owning team</dt>
        <dd>
          {node.owner_team_name ||
            String(details?.owner_team_id || "Not recorded")}
        </dd>
        <dt>Description</dt>
        <dd>
          {node.description ||
            String(details?.description || "No description recorded")}
        </dd>
        <dt>Provenance</dt>
        <dd>{humanize(node.provenance || "Not recorded")}</dd>
        {!!metadata.sourceRef && (
          <>
            <dt>Definition source</dt>
            <dd>
              <EvidenceReference value={String(metadata.sourceRef)} />
            </dd>
          </>
        )}
        <dt>Last discovery</dt>
        <dd>
          {node.last_seen_at
            ? new Date(node.last_seen_at).toLocaleString()
            : "Not recorded"}
        </dd>
      </dl>
      {node.inventory_state && node.inventory_state !== "CURRENT" && (
        <p className="notice warning">
          Not seen in the latest complete inventory. Historical dependencies
          remain recorded.
        </p>
      )}
      {sql ? (
        <details open>
          <summary>Transformation definition</summary>
          <pre className="sql-viewer">{String(sql)}</pre>
          {!!metadata.definitionHash && (
            <small>Revision SHA-256 · {String(metadata.definitionHash)}</small>
          )}
        </details>
      ) : (
        !virtual && (
          <p className="hint">
            No SQL definition recorded. Open transformation review to supply
            versioned SQL or discover view definitions.
          </p>
        )
      )}
      {!!columns.length && (
        <details open>
          <summary>Columns · {columns.length}</summary>
          <div className="inspector-columns">
            {columns.map((c) => (
              <a key={c.id} href={`#lineage/${node.id}?column=${c.id}`}>
                <strong>{c.name}</strong>
                <small>{c.data_type}</small>
              </a>
            ))}
          </div>
        </details>
      )}
      <div className="inspector-actions">
        {node.asset_type === "COLUMN" && !!metadata.assetId && (
          <>
            <a className="button" href={`#asset/${String(metadata.assetId)}`}>
              Open parent asset
            </a>
            <a
              className="button"
              href={`#impact/${String(metadata.assetId)}?column=${node.id}`}
            >
              Analyze column impact
            </a>
          </>
        )}
        {!virtual && (
          <>
            <a className="button" href={`#asset/${node.id}`}>
              Open catalog asset
            </a>
            <a className="button" href={`#transformations/${target}`}>
              Review transformation
            </a>
            <a className="button" href={`#impact/${node.id}`}>
              Analyze potential impact
            </a>
          </>
        )}
        {node.asset_type === "METRIC_VERSION" && (
          <a href="#metrics">Open metric registry →</a>
        )}
        {node.asset_type === "FORMULA_VERSION" && (
          <a href="#formulas">Open Formula Studio →</a>
        )}
      </div>
    </>
  );
}
