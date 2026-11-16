import { useWorkspace } from "../integration/hooks";
import { ColumnReferenceNames } from "../integration/ReferenceNames";
import { useEffect, useState } from "react";
import { request, mutate } from "../../services/api";
import { useAction } from "../integration/hooks";
import {
  PageHeader,
  TableRegion,
  Status,
  Timestamp,
  Dialog,
  EmptyState,
} from "../../components/Design";
import { Feedback, Loading, Success } from "../../components/Feedback";
import { ContextNote } from "../../components/Workflow";
import type { Session } from "../../types/api";
import { humanize } from "../../components/format";

type Review = {
  id: string;
  title: string;
  status: string;
  target_type: string;
  target_id: string;
  requester_name: string;
  owner_team_name: string;
  created_at: string;
  updated_at: string;
  revision: number;
  snapshot?: string;
  previous_snapshot?: string;
  canReview?: boolean;
  canResubmit?: boolean;
  events?: {
    id: string;
    actor_name: string;
    from_status: string;
    to_status: string;
    reason: string;
    created_at: string;
  }[];
};
export function SubmitReview({
  targetType,
  targetId,
  name,
}: {
  targetType: "FORMULA_VERSION" | "METRIC_VERSION";
  targetId: string;
  name: string;
}) {
  const [open, setOpen] = useState(false);
  const a = useAction();
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        Submit for review
      </button>
      {open && (
        <Dialog
          title="Submit version for review"
          onClose={() => setOpen(false)}
        >
          <div className="dialog-body">
            <Feedback error={a.error} />
            {a.notice ? (
              <>
                <Success>{a.notice}</Success>
                <a href="#governance">Open review queue →</a>
              </>
            ) : (
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  const data = new FormData(e.currentTarget);
                  void a.run(async () => {
                    await mutate("/governance/reviews", {
                      targetType,
                      targetId,
                      reason: data.get("reason"),
                    });
                    a.setNotice(
                      "Review request submitted. The version is preserved and awaits an independent reviewer.",
                    );
                  });
                }}
              >
                <ContextNote title={name}>
                  A reviewer will inspect this exact immutable version and its
                  recorded dependencies. A different organization administrator
                  or owning-team owner must decide.
                </ContextNote>
                <label>
                  Reason for this change
                  <textarea
                    name="reason"
                    required
                    maxLength={4000}
                    placeholder="Explain the change, evidence and what reviewers should check."
                  />
                </label>
                <button className="primary" disabled={a.busy}>
                  {a.busy ? "Submitting…" : "Submit request"}
                </button>
              </form>
            )}
          </div>
        </Dialog>
      )}
    </>
  );
}
function snapshot(value?: string) {
  try {
    const stored = JSON.parse(value || "{}") as Record<string, unknown>;
    let definition: Record<string, unknown> = {};
    if (typeof stored.definition_snapshot === "string") {
      try {
        definition = JSON.parse(stored.definition_snapshot);
      } catch {
        /* Legacy metadata remains unknown. */
      }
    }
    return { ...definition, ...stored };
  } catch {
    return {};
  }
}
function referenceIds(value: unknown): string[] {
  if (Array.isArray(value)) return value.map(String);
  if (typeof value === "string") {
    try {
      const ids = JSON.parse(value);
      return Array.isArray(ids) ? ids.map(String) : [];
    } catch {
      return [];
    }
  }
  return [];
}
export function GovernancePage({ session }: { session: Session }) {
  const a = useAction();
  const workspace = useWorkspace(a.revision, { assets: true });
  const [status, setStatus] = useState(""),
    [mine, setMine] = useState(""),
    [offset, setOffset] = useState(0),
    [selected, setSelected] = useState<Review | null>(null);
  const [result, setResult] = useState<{
    key: string;
    items: Review[];
    total: number;
    hasMore: boolean;
    error?: unknown;
  } | null>(null);
  const key = `${status}:${mine}:${offset}:${a.revision}`;
  useEffect(() => {
    const c = new AbortController();
    request<{ items: Review[]; total: number; hasMore: boolean }>(
      `/governance/reviews?${new URLSearchParams({ status, mine, offset: String(offset), limit: "25" })}`,
      { signal: c.signal },
    )
      .then((r) => setResult({ key, ...r }))
      .catch((error) => {
        if (!c.signal.aborted)
          setResult({ key, items: [], total: 0, hasMore: false, error });
      });
    return () => c.abort();
  }, [status, mine, offset, key]);
  const current = result?.key === key ? result : null;
  const before = snapshot(selected?.previous_snapshot),
    after = snapshot(selected?.snapshot);
  const final = selected && ["APPROVED", "REJECTED"].includes(selected.status);
  return (
    <>
      <PageHeader
        area="Management"
        title="Governance"
        description="Review changes to calculation and metric versions, inspect the evidence, and record an independent decision."
      />
      <Feedback error={a.error || current?.error} />
      {a.notice && <Success>{a.notice}</Success>}
      {selected ? (
        <>
          <div className="detail-topline">
            <button onClick={() => setSelected(null)}>← Review queue</button>
            <Status value={selected.status} />
          </div>
          <section className="panel record-detail">
            <h2>{selected.title}</h2>
            <ContextNote title="The reviewed version is immutable">
              Approval records a governance decision. It does not run SQL,
              deploy a definition, or update any source system.{" "}
              {final &&
                "This decision is final; changed definitions require a new version and review."}
            </ContextNote>
            <div className="action-row">
              <a className="button" href={`#impact/${selected.target_id}`}>
                Inspect dependency impact
              </a>
              <a className="button" href={`#lineage/${selected.target_id}`}>
                Explore lineage
              </a>
            </div>
            <h3>Definition comparison</h3>
            <div className="version-comparison">
              {[
                ["Previous registered version", before],
                ["Submitted version", after],
              ].map(([label, record]) => (
                <div key={String(label)}>
                  <h4>{String(label)}</h4>
                  {Object.keys(record as object).length ? (
                    <>
                      <dl className="metric-snapshot">
                        {[
                          "name",
                          "version",
                          "language",
                          "description",
                          "grain",
                          "unit",
                          "aggregation",
                          "business_owner",
                          "technical_owner",
                        ]
                          .filter(
                            (k) =>
                              (record as Record<string, unknown>)[k] != null,
                          )
                          .map((k) => (
                            <div key={k}>
                              <dt>{humanize(k)}</dt>
                              <dd>
                                {String((record as Record<string, unknown>)[k])}
                              </dd>
                            </div>
                          ))}
                      </dl>
                      <h4>Declared inputs</h4>
                      <ul>
                        {referenceIds(
                          (record as Record<string, unknown>).referenced_assets,
                        ).map((id) => (
                          <li key={id}>
                            <a href={`#asset/${id}`}>
                              {workspace.assets.find((a) => a.id === id)
                                ?.external_key ||
                                "Registered dataset details unavailable"}
                            </a>
                          </li>
                        ))}
                      </ul>
                      <ColumnReferenceNames
                        ids={referenceIds(
                          (record as Record<string, unknown>)
                            .referenced_columns,
                        )}
                      />
                      <pre className="sql-viewer">
                        {String(
                          (record as Record<string, unknown>).expression ||
                            "Calculation expression is preserved in the metric formula snapshot.",
                        )}
                      </pre>
                    </>
                  ) : (
                    <p className="muted">No earlier registered version.</p>
                  )}
                </div>
              ))}
            </div>
            {!final &&
            ((selected.canReview && selected.status !== "CHANGES_REQUESTED") ||
              (selected.canResubmit &&
                selected.status === "CHANGES_REQUESTED")) ? (
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  const d = new FormData(e.currentTarget);
                  void a.run(async () => {
                    await mutate(
                      `/governance/reviews/${selected.id}/decision`,
                      {
                        status: d.get("status"),
                        reason: d.get("reason"),
                        expectedRevision: selected.revision,
                      },
                    );
                    setSelected(
                      await request<Review>(
                        `/governance/reviews/${selected.id}`,
                      ),
                    );
                    a.setNotice(
                      "Decision recorded with your name, timestamp and reason.",
                    );
                  });
                }}
              >
                <h3>Record your review</h3>
                <label>
                  Decision
                  <select name="status">
                    {selected.canResubmit &&
                    selected.status === "CHANGES_REQUESTED" ? (
                      <option value="SUBMITTED">
                        Resubmit with clarification
                      </option>
                    ) : (
                      <>
                        {selected.status === "SUBMITTED" && (
                          <option value="UNDER_REVIEW">Start review</option>
                        )}
                        <option value="CHANGES_REQUESTED">
                          Request clarification
                        </option>
                        <option value="APPROVED">Approve this version</option>
                        <option value="REJECTED">Reject this version</option>
                      </>
                    )}
                  </select>
                </label>
                <label>
                  Reason / reviewer comment
                  <textarea name="reason" required maxLength={4000} />
                </label>
                <button className="primary" disabled={a.busy}>
                  Record decision
                </button>
              </form>
            ) : (
              !final && (
                <p className="context-note">
                  {selected.status === "CHANGES_REQUESTED"
                    ? "Clarification has been requested. The requester must resubmit a comment before review continues. A changed expression requires a new immutable version and review request."
                    : selected.canResubmit
                      ? "Your request is waiting for an independent reviewer. You cannot approve your own submission."
                      : `Review decisions require an organization administrator or an owning-team owner. Signed in as ${session.user.displayName}.`}
                </p>
              )
            )}
            <h3>Decision history</h3>
            {selected.events?.map((e) => (
              <article className="list-row" key={e.id}>
                <div>
                  <strong>{humanize(e.to_status)}</strong> · {e.actor_name}
                  <p className="record-description">{e.reason}</p>
                </div>
                <Timestamp value={e.created_at} />
              </article>
            ))}
          </section>
        </>
      ) : (
        <>
          <nav className="workbench-tabs" aria-label="Review queues">
            {[
              ["", "All requests"],
              ["reviews", "My pending reviews"],
              ["submitted", "My submissions"],
            ].map(([key, label]) => (
              <button
                key={key}
                aria-pressed={mine === key}
                onClick={() => {
                  setMine(key);
                  setOffset(0);
                }}
              >
                {label}
              </button>
            ))}
          </nav>
          <section className="panel table-panel">
            <div className="library-heading">
              <h2>
                Review requests <span>{current?.total ?? "…"}</span>
              </h2>
              <label>
                <span className="sr-only">Review status</span>
                <select
                  value={status}
                  onChange={(e) => {
                    setStatus(e.target.value);
                    setOffset(0);
                  }}
                >
                  <option value="">All states</option>
                  {[
                    "SUBMITTED",
                    "UNDER_REVIEW",
                    "CHANGES_REQUESTED",
                    "APPROVED",
                    "REJECTED",
                  ].map((s) => (
                    <option key={s} value={s}>
                      {humanize(s)}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            {!current ? (
              <Loading>Loading review queue…</Loading>
            ) : current.error ? null : current.items.length ? (
              <TableRegion label="Governance requests">
                <table>
                  <thead>
                    <tr>
                      <th>Change</th>
                      <th>State</th>
                      <th>Requested by</th>
                      <th>Owning team</th>
                      <th>Updated</th>
                    </tr>
                  </thead>
                  <tbody>
                    {current.items.map((r) => (
                      <tr key={r.id}>
                        <td>
                          <button
                            className="record-name"
                            onClick={() =>
                              void a.run(async () =>
                                setSelected(
                                  await request<Review>(
                                    `/governance/reviews/${r.id}`,
                                  ),
                                ),
                              )
                            }
                          >
                            {r.title}
                          </button>
                          <small>{humanize(r.target_type)}</small>
                        </td>
                        <td>
                          <Status value={r.status} />
                        </td>
                        <td>{r.requester_name}</td>
                        <td>{r.owner_team_name}</td>
                        <td>
                          <Timestamp value={r.updated_at} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </TableRegion>
            ) : (
              <EmptyState
                icon="shield"
                title="No review requests in this queue"
                description="Open a registered formula or metric version and select Submit for review. Requests and real decisions will appear here."
                actions={
                  <>
                    <a className="button" href="#formulas">
                      Browse formulas
                    </a>
                    <a className="button" href="#metrics">
                      Browse metrics
                    </a>
                  </>
                }
              />
            )}
            <div className="pagination">
              <span>{current ? `${current.total} requests` : "Loading…"}</span>
              <button
                disabled={!offset || !current}
                onClick={() => setOffset((n) => Math.max(0, n - 25))}
              >
                Previous
              </button>
              <button
                disabled={!current?.hasMore}
                onClick={() => setOffset((n) => n + 25)}
              >
                Next
              </button>
            </div>
          </section>
        </>
      )}
    </>
  );
}
