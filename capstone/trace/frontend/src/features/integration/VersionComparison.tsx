import { ColumnReferenceNames } from "./ReferenceNames";
import { SubmitReview } from "../governance/GovernancePage";
import type { Asset } from "../../types/api";
import { Status, Timestamp } from "../../components/Design";
import { useMemo, useState, useEffect, useRef } from "react";
import type { FormulaVersion } from "./FormulaStudio";
import { request } from "../../services/api";
import { Feedback } from "../../components/Feedback";
import { usePage } from "./paging";
import { Pager } from "./pagination";
type Change = { type: "same" | "added" | "removed"; text: string };
function changes(
  left: string,
  right: string,
): { rows: Change[]; approximate: boolean } {
  const a = left.split("\n"),
    b = right.split("\n");
  if (a.length * b.length > 1000000) {
    const rows: Change[] = [];
    for (let i = 0; i < Math.max(a.length, b.length); i++) {
      if (a[i] === b[i]) rows.push({ type: "same", text: a[i] });
      else {
        if (i < a.length) rows.push({ type: "removed", text: a[i] });
        if (i < b.length) rows.push({ type: "added", text: b[i] });
      }
    }
    return { rows, approximate: true };
  }
  const dp = Array.from(
    { length: a.length + 1 },
    () => new Uint32Array(b.length + 1),
  );
  for (let i = a.length - 1; i >= 0; i--)
    for (let j = b.length - 1; j >= 0; j--)
      dp[i][j] =
        a[i] === b[j]
          ? 1 + dp[i + 1][j + 1]
          : Math.max(dp[i + 1][j], dp[i][j + 1]);
  const rows: Change[] = [];
  let i = 0,
    j = 0;
  while (i < a.length || j < b.length) {
    if (i < a.length && j < b.length && a[i] === b[j]) {
      rows.push({ type: "same", text: a[i++] });
      j++;
    } else if (j < b.length && (i === a.length || dp[i][j + 1] >= dp[i + 1][j]))
      rows.push({ type: "added", text: b[j++] });
    else rows.push({ type: "removed", text: a[i++] });
  }
  return { rows, approximate: false };
}
export function VersionComparison({
  formula,
  revision,
  onEdit,
  writable,
  onUse,
  assets = [],
}: {
  assets?: Asset[];
  formula: string;
  revision: number;
  writable: boolean;
  onEdit: (v: FormulaVersion) => void;
  onUse: (v: FormulaVersion) => void;
}) {
  const page = usePage<FormulaVersion>("formula-versions", revision, {
      parent: formula,
    }),
    [left, setLeft] = useState<FormulaVersion | null>(null),
    [right, setRight] = useState<FormulaVersion | null>(null),
    [error, setError] = useState<unknown>(null);
  const pending = useRef({ left: 0, right: 0 });
  const leftId = left?.id,
    rightId = right?.id;
  useEffect(() => {
    let active = true;
    if (leftId)
      request<FormulaVersion>(`/formulas/${formula}/versions/${leftId}`)
        .then((v) => {
          if (active) setLeft(v);
        })
        .catch((e) => {
          if (active) setError(e);
        });
    if (rightId)
      request<FormulaVersion>(`/formulas/${formula}/versions/${rightId}`)
        .then((v) => {
          if (active) setRight(v);
        })
        .catch((e) => {
          if (active) setError(e);
        });
    return () => {
      active = false;
    };
  }, [formula, revision, leftId, rightId]);
  const diff = useMemo(
    () => (left && right ? changes(left.expression, right.expression) : null),
    [left, right],
  );
  async function choose(
    id: string,
    set: (v: FormulaVersion) => void,
    side: "left" | "right",
  ) {
    const sequence = ++pending.current[side];
    try {
      const version = await request<FormulaVersion>(
        `/formulas/${formula}/versions/${id}`,
      );
      if (sequence !== pending.current[side]) return;
      set(version);
      setError(null);
    } catch (e) {
      if (sequence === pending.current[side]) setError(e);
    }
  }
  return (
    <section>
      <h3>Version history and comparison</h3>
      <Pager page={page} label="formula versions" />
      {page.items.map((v) => (
        <details key={v.id}>
          <summary>
            Version {v.version} · <Status value={v.state} /> ·{" "}
            <Timestamp value={v.created_at} />
          </summary>
          <pre className="formula-editor">{v.expression}</pre>
          {writable && v.state === "REGISTERED" && (
            <SubmitReview
              targetType="FORMULA_VERSION"
              targetId={v.id}
              name={`Formula version ${v.version}`}
            />
          )}
          {writable && (
            <button type="button" onClick={() => onUse(v)}>
              Use as next version
            </button>
          )}
          {writable && v.state === "DRAFT" && (
            <button type="button" onClick={() => onEdit(v)}>
              Edit draft version {v.version}
            </button>
          )}
          <button
            type="button"
            onClick={() => void choose(v.id, setLeft, "left")}
          >
            Compare as left
          </button>
          <button
            type="button"
            onClick={() => void choose(v.id, setRight, "right")}
          >
            Compare as right
          </button>
        </details>
      ))}
      <div className="action-row">
        <label>
          Left version
          <select
            value={left?.id ?? ""}
            onChange={(e) => void choose(e.target.value, setLeft, "left")}
          >
            <option value="" disabled>
              Select left
            </option>
            {left && !page.items.some((v) => v.id === left.id) && (
              <option value={left.id}>Version {left.version}</option>
            )}
            {page.items.map((v) => (
              <option key={v.id} value={v.id}>
                Version {v.version} · {v.state}
              </option>
            ))}
          </select>
        </label>
        <label>
          Right version
          <select
            value={right?.id ?? ""}
            onChange={(e) => void choose(e.target.value, setRight, "right")}
          >
            <option value="" disabled>
              Select right
            </option>
            {right && !page.items.some((v) => v.id === right.id) && (
              <option value={right.id}>Version {right.version}</option>
            )}
            {page.items.map((v) => (
              <option key={v.id} value={v.id}>
                Version {v.version} · {v.state}
              </option>
            ))}
          </select>
        </label>
      </div>
      <Feedback error={error} />
      {left && right && diff && (
        <>
          <h4>
            Version {left.version} ↔ Version {right.version}
          </h4>
          <div className="context-note">
            <strong>Dependency changes</strong>
            {(() => {
              const before = JSON.parse(
                left.referenced_assets || "[]",
              ) as string[];
              const after = JSON.parse(
                right.referenced_assets || "[]",
              ) as string[];
              const added = after.filter((id) => !before.includes(id)),
                removed = before.filter((id) => !after.includes(id));
              return (
                <>
                  <p>
                    {added.length} added dataset inputs · {removed.length}{" "}
                    removed dataset inputs.
                  </p>
                  {added.map((id) => (
                    <p key={id}>
                      Added:{" "}
                      {assets.find((a) => a.id === id)?.external_key ||
                        "Unavailable input"}
                    </p>
                  ))}
                  {removed.map((id) => (
                    <p key={id}>
                      Removed:{" "}
                      {assets.find((a) => a.id === id)?.external_key ||
                        "Unavailable input"}
                    </p>
                  ))}
                  <a href={`#impact/${right.id}`}>
                    Inspect recorded consumers of the right-hand version →
                  </a>
                </>
              );
            })()}
          </div>
          <div className="version-comparison">
            {[left, right].map((version, index) => (
              <div key={index}>
                <div className="section-title">
                  <strong>Version {version.version}</strong>
                  <Status value={version.state} />
                </div>
                <Timestamp value={version.created_at} />
                {version.created_by && (
                  <small>
                    Recorded by <code>{version.created_by}</code>
                  </small>
                )}
                <pre
                  aria-label={
                    index === 0 ? "Left expression" : "Right expression"
                  }
                >
                  {version.expression}
                </pre>
                <details>
                  <summary>Declared dependencies</summary>
                  <dl>
                    <div>
                      <dt>Assets</dt>
                      <dd>
                        <code>
                          {(
                            JSON.parse(
                              version.referenced_assets ?? "[]",
                            ) as string[]
                          )
                            .map(
                              (id) =>
                                assets.find((a) => a.id === id)?.external_key ||
                                "Unavailable dataset reference",
                            )
                            .join(", ") || "None declared"}
                        </code>
                      </dd>
                    </div>
                    <div>
                      <dt>Columns</dt>
                      <dd>
                        <ColumnReferenceNames
                          ids={JSON.parse(version.referenced_columns || "[]")}
                        />
                      </dd>
                    </div>
                  </dl>
                </details>
              </div>
            ))}
          </div>
          {diff.approximate && (
            <p>Large expressions use bounded positional line alignment.</p>
          )}
          <pre
            className="expression-diff"
            aria-label="Added and removed expression lines"
          >
            {diff.rows.map((r, i) => (
              <div key={i} className={`diff-${r.type}`}>
                <span aria-label={r.type}>
                  {r.type === "added" ? "+" : r.type === "removed" ? "-" : " "}
                </span>{" "}
                {r.text || " "}
              </div>
            ))}
          </pre>
        </>
      )}
    </section>
  );
}
