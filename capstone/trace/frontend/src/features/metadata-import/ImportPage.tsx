import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "../../services/api";
import type {
  Connection,
  ImportBatch,
  Statistics,
  Session,
} from "../../types/api";
import { Feedback, Loading } from "../../components/Feedback";

function Counts({
  value,
  committed,
}: {
  value: Statistics;
  committed: boolean;
}) {
  return (
    <>
      <div className="stats">
        <div>
          <strong>{value.datasets}</strong>datasets in file
        </div>
        <div>
          <strong>{value.columns}</strong>columns in file
        </div>
        <div>
          <strong>{value.newDatasets}</strong>
          {committed ? "datasets created" : "new datasets"}
        </div>
        <div>
          <strong>{value.newColumns}</strong>
          {committed ? "columns created" : "new columns"}
        </div>
      </div>
      <p className="muted">
        Datasets: {value.updatedDatasets} updated · {value.unchangedDatasets}{" "}
        unchanged. Columns: {value.updatedColumns} updated ·{" "}
        {value.unchangedColumns} unchanged.
      </p>
    </>
  );
}
export function ImportPage({
  onCommitted,
  session,
}: {
  onCommitted: () => void;
  session: Session;
}) {
  const [connections, setConnections] = useState<Connection[]>([]);
  const [connection, setConnection] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [batch, setBatch] = useState<ImportBatch | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const [reload, setReload] = useState(0);
  const permissionKey = JSON.stringify([
    session.organizationRole,
    session.teamRoles,
  ]);
  useEffect(() => {
    const [role, teamRoles] = JSON.parse(permissionKey) as [
      string,
      Record<string, string>,
    ];
    let active = true;
    api
      .connections()
      .then((rows) => {
        if (active) {
          rows = rows.filter(
            (c) =>
              ["OWNER", "ADMIN"].includes(role) ||
              (role === "MEMBER" &&
                ["OWNER", "EDITOR"].includes(teamRoles[c.owner_team_id])),
          );
          setConnections(rows);
          setConnection(rows[0]?.id ?? "");
        }
      })
      .catch((e) => {
        if (active) setError(e);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [reload, permissionKey]);
  function reset() {
    setBatch(null);
    setError(null);
  }
  async function preview(event: React.FormEvent) {
    event.preventDefault();
    if (!file || !connection || pending.current) return;
    setError(null);
    setBatch(null);
    if (
      !file.name.toLowerCase().endsWith(".csv") ||
      file.size === 0 ||
      file.size > 5 * 1024 * 1024
    ) {
      setError(new ApiError("Choose a non-empty .csv file, up to 5 MiB."));
      return;
    }
    pending.current = true;
    setBusy(true);
    try {
      setBatch(await api.preview(connection, file));
    } catch (e) {
      setError(e);
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }
  async function commit() {
    if (!batch || batch.status !== "PREVIEW_READY" || pending.current) return;
    pending.current = true;
    setBusy(true);
    setError(null);
    try {
      const result = await api.commit(batch.id);
      setBatch(result);
      if (result.status === "COMMITTED") onCommitted();
    } catch (e) {
      setError(e);
      // A timeout may occur after the server commits. Recover authoritative state before a retry.
      try {
        const result = await api.batch(batch.id);
        setBatch(result);
        if (result.status === "COMMITTED") onCommitted();
      } catch {
        /* Keep original error for retry. */
      }
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }
  return (
    <>
      <div className="page-title">
        <div>
          <p className="eyebrow">INGESTION</p>
          <h1>Import metadata</h1>
          <p className="muted">
            Preview a Snowflake schema export, then confirm the changes.
          </p>
        </div>
      </div>
      <Feedback error={error} />
      {loading ? (
        <Loading>Loading source connections…</Loading>
      ) : connections.length === 0 ? (
        <div className="panel">
          <h2>No source connections available</h2>
          <p>
            Ask your organization administrator to create a Snowflake connection
            owned by a team you can import for.
          </p>
          <button
            onClick={() => {
              setLoading(true);
              setError(null);
              setReload((v) => v + 1);
            }}
          >
            Retry connections
          </button>
        </div>
      ) : (
        <form className="panel" onSubmit={preview}>
          <fieldset disabled={busy}>
            <legend>1. Select your source</legend>
            <div className="form-grid">
              <label>
                Source connection
                <select
                  value={connection}
                  onChange={(e) => {
                    setConnection(e.target.value);
                    reset();
                  }}
                >
                  {connections.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name} · {c.platform}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Metadata CSV
                <input
                  type="file"
                  accept=".csv,text/csv"
                  onChange={(e) => {
                    setFile(e.target.files?.[0] ?? null);
                    reset();
                  }}
                />
              </label>
            </div>
            <p className="hint">
              UTF-8 CSV · Up to 5 MiB · Required headers:{" "}
              <code>database,schema,table,column,data_type</code>. Optional:{" "}
              <code>description</code>.
            </p>
            <p className="hint">
              Ordinary identifiers normalize to uppercase. Quoted,
              case-sensitive identifiers require the legacy export format; see
              the CSV guide.
            </p>
            <button
              className="primary"
              disabled={!file || !connection || busy}
              type="submit"
            >
              {busy && !batch ? "Preparing preview…" : "Preview import"}
            </button>
          </fieldset>
        </form>
      )}
      {batch && (
        <section className="panel" aria-labelledby="preview-title">
          <div className="section-title">
            <h2 id="preview-title">
              {batch.status === "COMMITTED"
                ? "Import complete"
                : "2. Review import"}
            </h2>
            <span
              className={`badge ${batch.status === "COMMITTED" ? "success" : ""}`}
            >
              {batch.status.replaceAll("_", " ")}
            </span>
          </div>
          <p className="muted">
            {batch.filename} · {batch.fileSize.toLocaleString()} bytes ·{" "}
            {batch.sourceType} / {batch.format}
          </p>
          {batch.status === "COMMITTED" && (
            <p className="notice success" role="status">
              Metadata was committed successfully.
            </p>
          )}
          {batch.status === "EXPIRED" && (
            <p className="notice error">
              This preview has expired. Upload the file again.
            </p>
          )}
          {batch.preview.warnings.map((w, i) => (
            <p className="notice warning" key={i}>
              {w}
            </p>
          ))}
          {(batch.results ?? batch.comparison) && (
            <Counts
              value={(batch.results ?? batch.comparison)!}
              committed={batch.status === "COMMITTED"}
            />
          )}
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Dataset</th>
                  <th>Preview change</th>
                  <th>Columns</th>
                  <th>Column names</th>
                </tr>
              </thead>
              <tbody>
                {batch.preview.datasets.map((d) => (
                  <tr key={d.externalKey}>
                    <td>
                      <strong>{d.name}</strong>
                      <small>{d.externalKey}</small>
                    </td>
                    <td>
                      <span className="badge">
                        {batch.datasetChanges.find(
                          (c) => c.externalKey === d.externalKey,
                        )?.status ?? "Existing preview"}
                      </span>
                    </td>
                    <td>{d.columns.length}</td>
                    <td>
                      {d.columns
                        .slice(0, 6)
                        .map((c) => c.name)
                        .join(", ")}
                      {d.columns.length > 6 ? "…" : ""}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {batch.status === "PREVIEW_READY" && (
            <>
              <p className="hint">
                Preview has saved no datasets or columns. Counts are a snapshot;
                commit recalculates changes. Expires{" "}
                {new Date(batch.expiresAt).toLocaleString()}.
              </p>
              <button
                type="button"
                className="primary"
                disabled={busy}
                onClick={commit}
              >
                {busy ? "Committing…" : "Confirm import"}
              </button>
            </>
          )}
          {batch.status === "COMMITTED" && (
            <a className="button primary" href="#catalog">
              Browse dataset catalog →
            </a>
          )}
        </section>
      )}
    </>
  );
}
