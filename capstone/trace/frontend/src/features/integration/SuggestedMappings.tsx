import { useEffect, useState } from "react";
import { mutate, request } from "../../services/api";
import { useAction } from "./hooks";
import { Feedback, Loading, Success } from "../../components/Feedback";
import { ContextNote } from "../../components/Workflow";
import { allowed } from "../../app/permissions";
import type { Session } from "../../types/api";
type Candidate = {
  source_id: string;
  source_name: string;
  source_key: string;
  source_team_id: string;
  target_id: string;
  target_name: string;
  target_key: string;
  target_team_id: string;
  source_matches: number;
  target_matches: number;
  matching_column_names: number;
};
export function SuggestedMappings({
  session,
  source,
}: {
  session: Session;
  source?: string;
}) {
  const a = useAction();
  const [data, setData] = useState<{
      key: string;
      items: Candidate[];
      hasMore: boolean;
      basis: string;
    } | null>(null),
    [dismissed, setDismissed] = useState<string[]>([]),
    [error, setError] = useState<unknown>(null);
  const key = `${source || ""}:${a.revision}`;
  useEffect(() => {
    const c = new AbortController();
    request<Omit<NonNullable<typeof data>, "key">>(
      `/mapping-suggestions${source ? `?source=${source}` : ""}`,
      { signal: c.signal },
    )
      .then((d) => {
        if (!Array.isArray(d.items))
          throw new Error(
            "Mapping suggestions returned an invalid response. Refresh or check the backend release.",
          );
        setData({ ...d, key });
        setError(null);
      })
      .catch((e) => {
        if (!c.signal.aborted) setError(e);
      });
    return () => c.abort();
  }, [source, key]);
  return (
    <section className="panel">
      <div className="section-title">
        <h2>Suggested source mappings</h2>
        <span className="badge">Review before confirming</span>
      </div>
      <Feedback error={error || a.error} />
      {a.notice && <Success>{a.notice}</Success>}
      <p className="muted">
        Find relationships between registered S3 files and Snowflake RAW tables
        using exact names. Confirmation records your declaration and its basis.
      </p>
      {!data && !error ? (
        <Loading>Comparing registered source names…</Loading>
      ) : (
        data?.key === key && (
          <>
            {data.items
              .filter(
                (m) => !dismissed.includes(`${m.source_id}:${m.target_id}`),
              )
              .map((m) => {
                const ambiguous =
                  Number(m.source_matches) !== 1 ||
                  Number(m.target_matches) !== 1;
                return (
                  <div
                    className="suggestion-row"
                    key={`${m.source_id}:${m.target_id}`}
                  >
                    <div>
                      <strong>
                        {m.source_name} → {m.target_name}
                      </strong>
                      <small className="qualified">
                        {m.source_key} → {m.target_key}
                      </small>
                      <p>
                        Exact filename / RAW table name match ·{" "}
                        {m.matching_column_names} matching column names.
                      </p>
                      <small>
                        {ambiguous
                          ? "Ambiguous: multiple registered identities share this name. Confirm manually with evidence."
                          : "Candidate only. Confirm if you know this file feeds this table. Load execution is unverified."}
                      </small>
                    </div>
                    <div className="action-row">
                      <button
                        className="primary"
                        disabled={
                          a.busy ||
                          ambiguous ||
                          !allowed(session, m.source_team_id) ||
                          !allowed(session, m.target_team_id)
                        }
                        onClick={() =>
                          void a.run(async () => {
                            await mutate("/mapping-suggestions/confirm", {
                              sourceId: m.source_id,
                              targetId: m.target_id,
                            });
                            a.setNotice(
                              `Declaration saved: ${m.source_name} → ${m.target_name}. Open Data Flow to inspect the recorded evidence.`,
                            );
                          })
                        }
                      >
                        Confirm declaration
                      </button>
                      <button
                        disabled={a.busy}
                        onClick={() =>
                          setDismissed((ids) => [
                            ...ids,
                            `${m.source_id}:${m.target_id}`,
                          ])
                        }
                      >
                        Dismiss for now
                      </button>
                    </div>
                  </div>
                );
              })}
            {!data.items.length && (
              <ContextNote title="No unrecorded exact-name candidates">
                Existing confirmed relationships are retained. A relationship
                needs genuine metadata or your declaration; a successful
                discovery alone does not establish lineage.{" "}
                <a href="#lineage">Inspect recorded data flow →</a>
              </ContextNote>
            )}
            {data.hasMore && (
              <p className="hint">
                Showing the first 100 candidates. Open this review from an
                individual source to narrow the scope.
              </p>
            )}
          </>
        )
      )}
    </section>
  );
}
