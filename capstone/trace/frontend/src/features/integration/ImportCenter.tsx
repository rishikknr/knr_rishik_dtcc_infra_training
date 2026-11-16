import { SuggestedMappings } from "./SuggestedMappings";
import { MetadataInventory } from "./MetadataInventory";
import { usePage, loadOptions } from "./paging";
import { Pager } from "./pagination";
import { useEffect, useRef, useState } from "react";
import { api, mutate, request } from "../../services/api";
import type { ImportBatch, Session } from "../../types/api";
import {
  Dialog,
  EmptyState,
  Icon,
  PageHeader,
  Status,
  TableRegion,
  Timestamp,
} from "../../components/Design";
import { Feedback, Loading, Success } from "../../components/Feedback";
import { JsonDetails } from "./shared";
import { useAction, useWorkspace } from "./hooks";
import { allowed } from "../../app/permissions";
import {
  canonicalFields,
  hasMappingConflict,
  readCsvHeaders,
} from "./importMapping";
type History = {
  id: string;
  status: string;
  filename: string;
  created_at: string;
};
type Profile = {
  id: string;
  connection_id: string;
  name: string;
  mapping: string;
};
export function ImportCenter({
  session,
  batchId,
  initialSource,
}: {
  session: Session;
  batchId?: string;
  initialSource?: string;
}) {
  const a = useAction(),
    w = useWorkspace(a.revision, { connections: true });
  const importPage = usePage<History>("imports", a.revision);
  const [connection, setConnection] = useState(initialSource ?? ""),
    [format, setFormat] = useState("SNOWFLAKE_CSV"),
    [file, setFile] = useState<File | null>(null),
    [columns, setColumns] = useState<string[]>([]),
    [mapping, setMapping] = useState<Record<string, string>>({}),
    [batch, setBatch] = useState<ImportBatch | null>(null),
    [profiles, setProfiles] = useState<Profile[]>([]),
    [dialect, setDialect] = useState("snowflake"),
    [loadError, setLoadError] = useState<unknown>(null);
  const [fileError, setFileError] = useState("");
  const [dragging, setDragging] = useState(false);
  const [profileDialog, setProfileDialog] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const selection = useRef(0);
  const stage =
    a.busy && !batch && !profileDialog
      ? 4
      : batch?.status === "COMMITTED"
        ? 6
        : batch
          ? 5
          : file && columns.length > 0 && format !== "SNOWFLAKE_CSV"
            ? 3
            : file
              ? 4
              : connection
                ? 2
                : 1;
  const changes = new Map(
    batch?.datasetChanges?.map((change) => [change.externalKey, change]),
  );
  const counts = batch?.results ?? batch?.comparison;
  const duplicateFields = hasMappingConflict(columns, mapping);

  useEffect(() => {
    let active = true;
    loadOptions<Profile>("profiles")
      .then((p) => {
        if (active) {
          setProfiles(p);
        }
      })
      .catch((e) => {
        if (active) setLoadError(e);
      });
    if (batchId)
      api
        .batch(batchId)
        .then((b) => {
          if (active) setBatch(b);
        })
        .catch((e) => {
          if (active) setLoadError(e);
        });
    return () => {
      active = false;
    };
  }, [a.revision, batchId]);
  const connections = w.connections.filter(
    (c) => c.status !== "DISABLED" && allowed(session, c.owner_team_id),
  );
  async function choose(next: File | null) {
    const id = ++selection.current;
    setFile(null);
    setBatch(null);
    setMapping({});
    setColumns([]);
    setFileError("");
    if (!next) return;
    if (!/\.(csv|json|sql|zip)$/i.test(next.name)) {
      setFileError("Choose a CSV, JSON, SQL, or ZIP file.");
      return;
    }
    if (next.size === 0 || next.size > 5 * 1024 * 1024) {
      setFileError(
        next.size === 0
          ? "The file is empty."
          : "The file exceeds the 5 MiB limit.",
      );
      return;
    }
    if (/\.csv$/i.test(next.name)) {
      try {
        const names = readCsvHeaders(await next.text());
        if (id !== selection.current) return;
        setColumns(names);
        setMapping(
          Object.fromEntries(
            names
              .filter((name) =>
                canonicalFields.includes(name.trim().toLowerCase()),
              )
              .map((name) => [name, name.trim().toLowerCase()]),
          ),
        );
      } catch {
        if (id === selection.current)
          setFileError("The file could not be read. Select it again.");
        return;
      }
    }
    if (id === selection.current) setFile(next);
  }
  return (
    <>
      <PageHeader
        area="Data sources"
        title="Import Center"
        description="Map source metadata, review registry changes, and commit a validated import."
        actions={
          <a className="button" href="#connections">
            Manage connections
          </a>
        }
      />
      <ol className="stage-strip" aria-label="Import stages">
        {[
          "Choose source",
          "Upload file",
          "Map fields",
          "Validate",
          "Review changes",
          "Commit",
        ].map((label, index) => (
          <li
            key={label}
            aria-current={index + 1 === stage ? "step" : undefined}
          >
            <span>{String(index + 1).padStart(2, "0")}</span>
            {label}
          </li>
        ))}
      </ol>
      <Feedback error={a.error ?? w.error ?? loadError} />
      {a.notice && <Success>{a.notice}</Success>}
      {batchId && !batch && !loadError && (
        <Loading>Loading import preview…</Loading>
      )}
      {w.loading ? (
        <Loading>Loading sources…</Loading>
      ) : batchId || batch ? null : connections.length === 0 ? (
        <section className="panel">
          <EmptyState
            icon="upload"
            title="No writable sources"
            description="Import metadata into an enabled connection owned by a team you can edit."
            actions={
              <a className="button" href="#connections">
                Open connections
              </a>
            }
          />
        </section>
      ) : (
        <form
          className="panel workflow-panel"
          onSubmit={(e) => {
            e.preventDefault();
            if (!file || (format !== "SNOWFLAKE_CSV" && duplicateFields))
              return;
            void a.run(async () => {
              const body = new FormData();
              body.append("connectionId", connection);
              body.append("file", file);
              body.append("format", format);
              body.append("dialect", dialect);
              body.append("mapping", JSON.stringify(mapping));
              const preview = await request<ImportBatch>("/imports/preview", {
                method: "POST",
                body,
              });
              setBatch(preview);
              window.location.hash = `import/${preview.id}`;
            });
          }}
        >
          <fieldset disabled={a.busy}>
            <legend>Source and file</legend>
            <div className="form-grid">
              <label>
                Source connection
                <select
                  value={connection}
                  onChange={(e) => {
                    setConnection(e.target.value);
                    setBatch(null);
                  }}
                  required
                >
                  <option value="">Select connection</option>
                  {connections.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name} · {c.platform}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Input format
                <select
                  value={format}
                  onChange={(e) => {
                    setFormat(e.target.value);
                    setBatch(null);
                  }}
                >
                  {[
                    "SNOWFLAKE_CSV",
                    "GENERIC_CSV",
                    "METADATA_JSON",
                    "S3_INVENTORY",
                    "SQL",
                    "SQL_ZIP",
                    "POWERBI_CSV",
                    "POWERBI_MAPPING_CSV",
                  ].map((f) => (
                    <option key={f}>{f}</option>
                  ))}
                </select>
              </label>
            </div>
            <div
              className={`import-drop ${dragging ? "dragging" : ""}`}
              onDragOver={(event) => {
                event.preventDefault();
                if (!a.busy) setDragging(true);
              }}
              onDragLeave={(event) => {
                if (
                  !event.currentTarget.contains(
                    event.relatedTarget as Node | null,
                  )
                )
                  setDragging(false);
              }}
              onDrop={(event) => {
                event.preventDefault();
                setDragging(false);
                if (a.busy) return;
                if (fileInput.current) fileInput.current.value = "";
                if (event.dataTransfer.files.length !== 1) {
                  setFileError("Drop one metadata file at a time.");
                  return;
                }
                void choose(event.dataTransfer.files[0]);
              }}
            >
              <label>
                <span className="provider-line">
                  <Icon name="upload" size={17} /> Metadata file
                </span>
                <input
                  ref={fileInput}
                  type="file"
                  accept=".csv,.json,.sql,.zip"
                  aria-describedby="upload-hint"
                  aria-invalid={!!fileError}
                  onChange={(event) =>
                    void choose(event.target.files?.[0] ?? null)
                  }
                />
              </label>
              <small id="upload-hint">
                Drop a file or use the picker. CSV, JSON, SQL, or ZIP · up to 5
                MiB. ZIP supports up to 100 SQL files and 2 MB expanded.
              </small>
              {fileError && (
                <p className="field-error" role="alert">
                  {fileError}
                </p>
              )}
              {file && (
                <div className="list-row">
                  <span>
                    <strong>{file.name}</strong>
                    <small>
                      {Math.ceil(file.size / 1024).toLocaleString()} KB
                    </small>
                  </span>
                  <button
                    type="button"
                    onClick={() => {
                      if (fileInput.current) fileInput.current.value = "";
                      void choose(null);
                    }}
                  >
                    Remove file
                  </button>
                </div>
              )}
            </div>
            {["SQL", "SQL_ZIP"].includes(format) && (
              <label>
                SQL dialect
                <select
                  value={dialect}
                  onChange={(e) => setDialect(e.target.value)}
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
            {columns.length > 0 && format !== "SNOWFLAKE_CSV" && (
              <>
                <h3>2. Map uploaded headers</h3>
                <p>
                  Map external_key or database + schema + table. With a column,
                  data_type is required. S3 inventory requires bucket + key.
                </p>
                {duplicateFields && (
                  <p className="notice error" role="alert">
                    Assign each canonical field once and remove duplicate source
                    headers before previewing.
                  </p>
                )}
                <TableRegion label="CSV field mapping">
                  <table className="mapping-table">
                    <thead>
                      <tr>
                        <th scope="col">Source column</th>
                        <th scope="col">Canonical field</th>
                      </tr>
                    </thead>
                    <tbody>
                      {columns.map((column, index) => (
                        <tr key={`${column}:${index}`}>
                          <td>
                            <code>{column}</code>
                          </td>
                          <td>
                            <select
                              aria-label={`Map ${column}`}
                              value={mapping[column] ?? ""}
                              onChange={(event) => {
                                const value = event.target.value;
                                setMapping((previous) => {
                                  const next = { ...previous };
                                  if (value) next[column] = value;
                                  else delete next[column];
                                  return next;
                                });
                              }}
                            >
                              <option value="">Use source header</option>
                              {canonicalFields.map((field) => (
                                <option
                                  key={field}
                                  disabled={Object.entries(mapping).some(
                                    ([key, value]) =>
                                      key !== column && value === field,
                                  )}
                                >
                                  {field}
                                </option>
                              ))}
                            </select>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </TableRegion>
                <label>
                  Saved mapping
                  <select
                    key={`${connection}:${file?.name}`}
                    defaultValue=""
                    onChange={(e) => {
                      const p = profiles.find((p) => p.id === e.target.value);
                      if (p) setMapping(JSON.parse(p.mapping));
                    }}
                  >
                    <option value="">Choose profile</option>
                    {profiles
                      .filter((p) => p.connection_id === connection)
                      .map((p) => (
                        <option key={p.id} value={p.id}>
                          {p.name}
                        </option>
                      ))}
                  </select>
                </label>
                <button
                  type="button"
                  disabled={!connection || a.busy || duplicateFields}
                  onClick={() => setProfileDialog(true)}
                >
                  Save mapping profile
                </button>
              </>
            )}
            <button
              className="primary"
              disabled={
                !file ||
                !connection ||
                a.busy ||
                !!fileError ||
                (format !== "SNOWFLAKE_CSV" && duplicateFields)
              }
            >
              {a.busy ? "Parsing metadata…" : "Validate and preview"}
            </button>
          </fieldset>
        </form>
      )}
      {batch && (
        <section className="panel">
          <div className="section-title">
            <h2>
              {batch.status === "COMMITTED"
                ? "Import results"
                : "Review import changes"}
            </h2>
            <Status value={batch.status} />
          </div>
          <p className="muted">
            {batch.filename} · <code>{batch.id}</code>
          </p>
          {batch.status === "PREVIEW_READY" && (
            <p>
              Review the changes below. Commit to apply them to the catalog.
            </p>
          )}
          {counts && (
            <div className="stats">
              {[
                ["New assets", counts.newDatasets],
                ["Updated assets", counts.updatedDatasets],
                ["Unchanged assets", counts.unchangedDatasets],
                ["New columns", counts.newColumns],
                ["Updated columns", counts.updatedColumns],
                ["Unchanged columns", counts.unchangedColumns],
              ].map(([label, value]) => (
                <div key={label}>
                  <strong>{value}</strong>
                  {label}
                </div>
              ))}
            </div>
          )}
          {batch.status === "COMMITTED" && <SuggestedMappings session={session} source={batch.connectionId}/>}
          {batch.preview.warnings.map((warning, i) => (
            <p className="notice warning" key={i}>
              {warning}
            </p>
          ))}
          <MetadataInventory
            datasets={batch.preview.datasets}
            changes={changes}
            committed={batch.status === "COMMITTED"}
            connectionId={batch.connectionId}
          />
          <details>
            <summary>Normalized metadata and evidence</summary>
            <JsonDetails value={batch.preview} />
          </details>
          {batch.status === "PREVIEW_READY" && (
            <button
              className="primary"
              disabled={a.busy}
              onClick={() =>
                void a.run(async () => {
                  setBatch(await api.commit(batch.id));
                  a.setNotice("Metadata committed to the canonical registry.");
                })
              }
            >
              Commit to Catalog
            </button>
          )}
          <div className="action-row">
            {batch.status === "COMMITTED" && (
              <a className="button primary" href="#catalog">
                View catalog
              </a>
            )}
            <a
              className="button"
              href="#import"
              onClick={() => {
                void choose(null);
                a.setNotice("");
              }}
            >
              Import another file
            </a>
          </div>
        </section>
      )}
      <section className="panel">
        <h2>Your import history</h2>
        <Pager page={importPage} label="imports" />
        {!importPage.loading &&
        !importPage.error &&
        importPage.items.length === 0 ? (
          <EmptyState
            icon="history"
            title="No imports yet"
            description="Your file previews and committed batches will appear here."
          />
        ) : (
          importPage.items.map((h) => (
            <div className="list-row" key={h.id}>
              <span>
                {h.filename}
                <small>
                  <Timestamp value={h.created_at} />
                </small>
              </span>
              <div className="heading-actions">
                <Status value={h.status} />
                <a href={`#import/${h.id}`}>Open import</a>
              </div>
            </div>
          ))
        )}
      </section>
      {profileDialog && (
        <Dialog
          title="Save mapping profile"
          onClose={() => setProfileDialog(false)}
        >
          <div className="dialog-body">
            <form
              onSubmit={(event) => {
                event.preventDefault();
                const name = String(
                  new FormData(event.currentTarget).get("name"),
                ).trim();
                if (!name) return;
                setProfileDialog(false);
                void a.run(async () => {
                  await mutate("/import-mappings", {
                    connectionId: connection,
                    name,
                    mapping,
                  });
                  a.setNotice("Mapping profile saved.");
                });
              }}
            >
              <label>
                Profile name
                <input name="name" required maxLength={150} autoFocus />
              </label>
              <button className="primary">Save profile</button>
            </form>
          </div>
        </Dialog>
      )}
    </>
  );
}
