import "./lineage.css";
import { useEffect, useState } from "react";
import { request } from "../../services/api";
import { Feedback, Loading } from "../../components/Feedback";
import { humanize } from "../../components/format";
type Group = {
  connection_id: string;
  name: string;
  platform: string;
  status: string;
  asset_group: string;
  asset_count: number;
  definitions: number;
  last_successful_sync_at: string;
};
type Overview = {
  groups: Group[];
  definitionSources?: {
    target_asset_id: string;
    definition_hash: string;
    source_ref: string;
    created_at: string;
  }[];
  links: {
    source_connection: string;
    target_connection: string;
    relationship_count: number;
  }[];
  counts: {
    assets: number;
    relationships: number;
    columns: number;
    metrics: number;
  };
};
export function DataFlowOverview({
  onExplore,
}: {
  onExplore?: (id: string) => void;
}) {
  const [data, setData] = useState<Overview | null>(null);
  const [error, setError] = useState<unknown>(null);
  useEffect(() => {
    let active = true;
    request<Overview>("/lineage/overview")
      .then((d) => {
        if (active) setData(d);
      })
      .catch((e) => {
        if (active) setError(e);
      });
    return () => {
      active = false;
    };
  }, []);
  return (
    <div className="data-flow-overview">
      <p className="eyebrow">Architecture explorer</p>
      <h2>Your connected data environment</h2>
      <p className="muted">
        Start with a source system, then explore schemas, datasets and the
        evidence that connects them.
      </p>
      <Feedback error={error} />
      {!data && !error && <Loading>Reading source inventory…</Loading>}
      {data && (
        <>
          <div className="environment-stats">
            {Object.entries(data.counts).map(([label, n]) => (
              <div key={label}>
                <strong>{n}</strong>
                <span>{label === "columns" ? "Column derivations" : humanize(label)}</span>
              </div>
            ))}
          </div>
          <div className="system-grid">
            {[...new Set(data.groups.map((g) => g.connection_id))].map((id) => {
              const groups = data.groups.filter((g) => g.connection_id === id);
              const source = groups[0];
              return (
                <article className="system-card" key={id}>
                  <div className="system-icon">
                    {source.platform === "AMAZON_S3"
                      ? "S3"
                      : source.platform === "SNOWFLAKE"
                        ? "❄"
                        : "↥"}
                  </div>
                  <span className="eyebrow">{humanize(source.platform)}</span>
                  <h3>{source.name}</h3>
                  <span className="badge">{humanize(source.status)}</span>
                  <div className="schema-list">
                    {groups.map((g) => (
                      <a href={`#catalog?source=${id}`} key={g.asset_group}>
                        {g.asset_group || "No registered assets"}
                        <strong>{g.asset_count}</strong>
                      </a>
                    ))}
                  </div>
                  <p className="hint">
                    Last discovery ·{" "}
                    {source.last_successful_sync_at
                      ? new Date(
                          source.last_successful_sync_at,
                        ).toLocaleString()
                      : "No successful run"}
                  </p>
                  <a href={`#connections/${id}`}>Source details →</a>
                </article>
              );
            })}
            <article className="system-card unconnected">
              <div className="system-icon">↗</div>
              <span className="eyebrow">Reporting</span>
              <h3>Reporting consumers</h3>
              <p>
                Use manually registered or imported reports and measures.
                Connect Power BI when available.
              </p>
              <a href="#report-mappings">Review registered usage →</a>
            </article>
          </div>
          {!!data.definitionSources?.length && (
            <section className="flow-evidence-summary">
              <p className="eyebrow">Versioned SQL evidence</p>
              <h3>GitHub transformation definitions</h3>
              <p>
                {
                  new Set(data.definitionSources.map((x) => x.target_asset_id))
                    .size
                }{" "}
                targets have registered SQL snapshots. Each relationship retains
                its exact definition, SHA-256 revision and source reference.
              </p>
              {[
                ...new Set(
                  data.definitionSources
                    .filter((x) =>
                      x.source_ref.startsWith("https://github.com/"),
                    )
                    .map((x) => x.source_ref.split("/blob/")[0]),
                ),
              ].map((url) => (
                <a key={url} href={url} target="_blank" rel="noreferrer">
                  {url.replace("https://github.com/", "")} →
                </a>
              ))}
              <p className="hint">
                Registered repository evidence; provider connection and SQL
                deployment are separate.
              </p>
            </section>
          )}
          <section className="flow-evidence-summary">
            <h3>Evidence across systems</h3>
            {data.links.filter(
              (l) =>
                l.source_connection &&
                l.target_connection &&
                l.source_connection !== l.target_connection,
            ).length ? (
              data.links
                .filter(
                  (l) =>
                    l.source_connection &&
                    l.target_connection &&
                    l.source_connection !== l.target_connection,
                )
                .map((l, i) => (
                  <p key={i}>
                    {
                      data.groups.find(
                        (g) => g.connection_id === l.source_connection,
                      )?.name
                    }{" "}
                    →{" "}
                    {
                      data.groups.find(
                        (g) => g.connection_id === l.target_connection,
                      )?.name
                    }{" "}
                    <span className="badge">
                      {l.relationship_count} recorded relationships
                    </span>
                  </p>
                ))
            ) : (
              <p>
                No cross-system relationship is recorded. Discovery establishes
                inventory; use COPY statements, load metadata, or an explicit
                evidence-backed mapping to establish ingestion lineage.
              </p>
            )}
            <div className="action-row">
              <a className="button primary" href="#lineage">
                Explore dataset lineage
              </a>
              <a className="button" href="#transformations">
                Review SQL dependencies
              </a>
            </div>
            {onExplore && (
              <p className="hint">
                Choose an asset in the explorer to open its complete reachable
                graph.
              </p>
            )}
          </section>
        </>
      )}
    </div>
  );
}
