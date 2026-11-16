import { OwnerField } from "../../components/OwnerField";
import { loadOptions } from "./paging";
import { AssetActivity } from "../catalog/AssetActivity";
import { useEffect, useState } from "react";
import { mutate, request } from "../../services/api";
import type { AssetColumn, Session } from "../../types/api";
import {
  Dialog,
  EmptyState,
  Icon,
  PageHeader,
  TableRegion,
} from "../../components/Design";
import { Feedback, Loading, Success } from "../../components/Feedback";
import { NoTeams, TeamSelect } from "./shared";
import { useAction, useWorkspace } from "./hooks";
import { allowed } from "../../app/permissions";
type Column = {
  name: string;
  dataType: string;
  description: string;
  nullable: boolean;
  ordinalPosition: number;
};
type Curation = {
  id: string;
  connection_id: string;
  name: string;
  asset_type: string;
  external_key: string;
  owner_team_id: string;
  description: string;
  owner: string;
  business_owner: string;
  tag_ids: string;
  revision: number;
  provenance: string;
  metadata: string;
};
export function AssetEditor({
  session,
  id,
}: {
  session: Session;
  id?: string;
}) {
  const a = useAction(),
    w = useWorkspace(a.revision, { teams: true, connections: true });
  const [columns, setColumns] = useState<Column[]>([]),
    [existing, setExisting] = useState<Curation | null>(null),
    [error, setError] = useState<unknown>(null);
  useEffect(() => {
    let active = true;
    if (id)
      request<Curation>(`/assets/${id}/curation`)
        .then((r) => {
          if (active) setExisting(r);
        })
        .catch((e) => {
          if (active) setError(e);
        });
    return () => {
      active = false;
    };
  }, [id, a.revision]);
  const writable = existing
    ? allowed(session, existing.owner_team_id)
    : w.teams.some((t) => allowed(session, t.id));
  function update(
    index: number,
    field: keyof Column,
    value: string | boolean | number,
  ) {
    setColumns((cs) =>
      cs.map((c, i) => (i === index ? { ...c, [field]: value } : c)),
    );
  }
  return (
    <>
      <PageHeader
        area="Workspace / Catalog"
        title={id ? "Edit asset metadata" : "Register an asset"}
        description="Record a dataset, report, or other resource with its source, owner, and columns."
        actions={
          <a className="button" href={id ? `#asset/${id}` : "#catalog"}>
            {id ? "View asset" : "Back to catalog"}
          </a>
        }
      />
      <Feedback error={a.error ?? w.error ?? error} />
      {w.loading || (id && !existing && !error) ? (
        <Loading>
          {id ? "Loading asset…" : "Loading teams and sources…"}
        </Loading>
      ) : w.error || error ? null : !writable ? (
        <NoTeams />
      ) : (
        <form
          className="panel workflow-panel"
          key={existing?.revision ?? "new"}
          onSubmit={(e) => {
            e.preventDefault();
            const d = new FormData(e.currentTarget);
            void a.run(async () => {
              const body = {
                name: d.get("name"),
                assetType: d.get("assetType"),
                externalKey: d.get("externalKey"),
                connectionId: d.get("connectionId"),
                ownerTeamId: d.get("ownerTeamId"),
                description: d.get("description"),
                owner: d.get("owner"),
                technicalOwner: d.get("owner"),
                businessOwner: d.get("businessOwner"),
                tagIds: d.getAll("tagIds"),
                columns: columns.map((column, index) => ({
                  ...column,
                  ordinalPosition: index + 1,
                })),
                revision: existing?.revision,
              };
              const result = await mutate<{ id: string }>(
                id ? `/assets/${id}` : "/assets",
                body,
                id ? "PATCH" : "POST",
              );
              window.location.hash = `asset/${result.id}`;
            });
          }}
        >
          <fieldset disabled={a.busy}>
            <legend>Identity and source</legend>
            <div className="form-grid">
              <label>
                Asset name
                <input
                  name="name"
                  required
                  maxLength={255}
                  defaultValue={existing?.name}
                />
              </label>
              <label>
                Asset type
                <select
                  name="assetType"
                  defaultValue={existing?.asset_type ?? "DATASET"}
                  disabled={!!id}
                >
                  {[
                    "FILE",
                    "DATASET",
                    "TABLE",
                    "VIEW",
                    "SQL_TRANSFORMATION",
                    "SEMANTIC_MODEL",
                    "REPORT",
                    "VISUAL",
                    "MEASURE",
                    "WORKSPACE",
                  ].map((t) => (
                    <option key={t}>{t}</option>
                  ))}
                </select>
              </label>
              <label>
                Source connection
                <select
                  name="connectionId"
                  required
                  defaultValue={existing?.connection_id}
                  disabled={!!id}
                >
                  <option value="">Select source</option>
                  {w.connections
                    .filter(
                      (c) =>
                        c.status !== "DISABLED" &&
                        allowed(session, c.owner_team_id),
                    )
                    .map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.name}
                      </option>
                    ))}
                </select>
              </label>
              <label>
                Qualified identifier
                <input
                  name="externalKey"
                  required
                  maxLength={500}
                  defaultValue={existing?.external_key}
                  readOnly={!!id}
                />
              </label>
              {!id && <TeamSelect teams={w.teams} session={session} />}
            </div>
            <h2 className="subsection-heading">Description and ownership</h2>
            <label>
              Description
              <textarea
                name="description"
                maxLength={4000}
                defaultValue={existing?.description}
              />
            </label>
            <OwnerField
              session={session}
              name="owner"
              label="Technical owner"
              defaultValue={existing?.owner ?? ""}
            />
            <OwnerField
              session={session}
              name="businessOwner"
              label="Business owner"
              defaultValue={existing?.business_owner ?? ""}
            />
            <AssetTags session={session} selected={existing?.tag_ids ?? "[]"} />
            {!id && (
              <>
                <div className="section-title">
                  <h2>Columns</h2>
                  <span className="badge">{columns.length} defined</span>
                </div>
                {columns.length > 0 ? (
                  <TableRegion label="Columns to register">
                    <table className="wide-table column-table">
                      <thead>
                        <tr>
                          <th scope="col">Column name</th>
                          <th scope="col">Data type</th>
                          <th scope="col">Description</th>
                          <th scope="col">Nullable</th>
                          <th scope="col">Remove</th>
                        </tr>
                      </thead>
                      <tbody>
                        {columns.map((column, index) => (
                          <tr key={index}>
                            <td>
                              <input
                                aria-label={`Column ${index + 1} name`}
                                required
                                maxLength={255}
                                value={column.name}
                                onChange={(event) =>
                                  update(index, "name", event.target.value)
                                }
                              />
                            </td>
                            <td>
                              <input
                                aria-label={`Column ${index + 1} data type`}
                                required
                                maxLength={100}
                                value={column.dataType}
                                onChange={(event) =>
                                  update(index, "dataType", event.target.value)
                                }
                              />
                            </td>
                            <td>
                              <input
                                aria-label={`Column ${index + 1} description`}
                                value={column.description}
                                onChange={(event) =>
                                  update(
                                    index,
                                    "description",
                                    event.target.value,
                                  )
                                }
                              />
                            </td>
                            <td>
                              <input
                                type="checkbox"
                                aria-label={`Column ${index + 1} nullable`}
                                checked={column.nullable}
                                onChange={(event) =>
                                  update(
                                    index,
                                    "nullable",
                                    event.target.checked,
                                  )
                                }
                              />
                            </td>
                            <td>
                              <button
                                type="button"
                                className="icon-button"
                                aria-label={`Remove column ${index + 1}`}
                                onClick={() =>
                                  setColumns((previous) =>
                                    previous.filter(
                                      (_, position) => position !== index,
                                    ),
                                  )
                                }
                              >
                                <Icon name="close" size={16} />
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </TableRegion>
                ) : (
                  <EmptyState
                    icon="catalog"
                    title="No columns defined"
                    description="Add columns now or register the asset and document its columns later."
                  />
                )}
                <button
                  type="button"
                  onClick={() =>
                    setColumns((c) => [
                      ...c,
                      {
                        name: "",
                        dataType: "",
                        description: "",
                        nullable: true,
                        ordinalPosition: c.length + 1,
                      },
                    ])
                  }
                >
                  Add column
                </button>
              </>
            )}
            <button className="primary" disabled={a.busy}>
              {a.busy ? "Saving…" : id ? "Save metadata" : "Create asset"}
            </button>
          </fieldset>
        </form>
      )}
      {id && writable && (
        <form
          className="panel"
          onSubmit={(e) => {
            e.preventDefault();
            const f = e.currentTarget,
              d = new FormData(f);
            void a.run(async () => {
              await mutate(`/assets/${id}/columns`, {
                name: d.get("name"),
                dataType: d.get("dataType"),
                description: d.get("description"),
                nullable: d.get("nullable") === "on",
              });
              f.reset();
              a.setNotice("Column registered.");
            });
          }}
        >
          <h2>Register an additional column</h2>
          <label>
            Column name
            <input name="name" required />
          </label>
          <label>
            Data type
            <input name="dataType" required />
          </label>
          <label>
            Description
            <input name="description" />
          </label>
          <label>
            Nullable
            <input type="checkbox" name="nullable" defaultChecked />
          </label>
          <button disabled={a.busy}>Register column</button>
        </form>
      )}
      {id && writable && <ColumnCuration id={id} revision={a.revision} />}
      {id &&
        existing &&
        (["OWNER", "ADMIN"].includes(session.organizationRole ?? "") ||
          session.teamRoles[existing.owner_team_id] === "OWNER") && (
          <form
            className="panel"
            onSubmit={(e) => {
              e.preventDefault();
              const team = new FormData(e.currentTarget).get("transferTeam");
              void a.run(async () => {
                await mutate(
                  `/assets/${id}/owner-team`,
                  { ownerTeamId: team },
                  "PATCH",
                );
                a.setNotice(
                  "Owning team transferred. Source connection and curated owners are retained.",
                );
              });
            }}
          >
            <h2>Transfer owning team</h2>
            <TeamSelect
              teams={w.teams}
              session={session}
              name="transferTeam"
              value={existing.owner_team_id}
            />
            <button disabled={a.busy}>Transfer asset ownership</button>
          </form>
        )}
      {id && <AssetActivity id={id} revision={a.revision} />}{" "}
      {a.notice && <p role="status">{a.notice}</p>}
    </>
  );
}

function ColumnCuration({ id, revision }: { id: string; revision: number }) {
  const a = useAction();
  const [columns, setColumns] = useState<AssetColumn[]>([]),
    [error, setError] = useState<unknown>(null);
  useEffect(() => {
    let active = true;
    request<AssetColumn[]>(`/assets/${id}/columns`)
      .then((c) => {
        if (active) setColumns(c);
      })
      .catch((e) => {
        if (active) setError(e);
      });
    return () => {
      active = false;
    };
  }, [id, a.revision, revision]);
  return (
    <section className="panel">
      <h2>Curate existing columns</h2>
      <Feedback error={a.error ?? error} />
      {columns.map((c) => (
        <details key={c.id}>
          <summary>
            {c.name} · {c.data_type}
          </summary>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const d = new FormData(e.currentTarget);
              void a.run(async () => {
                await mutate(
                  `/assets/${id}/columns/${c.id}`,
                  {
                    name: c.name,
                    dataType: c.data_type,
                    description: d.get("description"),
                    nullable: d.get("nullable") === "on",
                    ordinalPosition: Number(d.get("ordinal")) || null,
                  },
                  "PATCH",
                );
                a.setNotice("Column metadata updated.");
              });
            }}
          >
            <label>
              Description
              <textarea
                name="description"
                defaultValue={c.description ?? ""}
                maxLength={4000}
              />
            </label>
            <label>
              Nullable
              <input
                name="nullable"
                type="checkbox"
                defaultChecked={c.nullable ?? true}
              />
            </label>
            <label>
              Ordinal position
              <input
                name="ordinal"
                type="number"
                min={1}
                defaultValue={c.ordinal_position ?? undefined}
              />
            </label>
            <button disabled={a.busy}>Save column metadata</button>
          </form>
        </details>
      ))}
      {a.notice && <p role="status">{a.notice}</p>}
    </section>
  );
}

function AssetTags({
  session,
  selected,
}: {
  session: Session;
  selected: string;
}) {
  const [tagDialog, setTagDialog] = useState(false);
  const [chosen, setChosen] = useState<string[]>(JSON.parse(selected));
  const a = useAction(),
    [tags, setTags] = useState<{ id: string; name: string }[]>([]),
    [error, setError] = useState<unknown>(null);
  useEffect(() => {
    let active = true;
    loadOptions<{ id: string; name: string }>("tags")
      .then((t) => {
        if (active) setTags(t);
      })
      .catch((e) => {
        if (active) setError(e);
      });
    return () => {
      active = false;
    };
  }, [a.revision]);
  return (
    <>
      <Feedback error={a.error ?? error} />
      <label>
        Organization tags
        <select
          name="tagIds"
          multiple
          size={4}
          value={chosen}
          onChange={(e) =>
            setChosen(Array.from(e.target.selectedOptions, (o) => o.value))
          }
        >
          {tags.map((t) => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
        </select>
      </label>
      {a.notice && <Success>{a.notice}</Success>}
      {["OWNER", "ADMIN"].includes(session.organizationRole ?? "") && (
        <button
          type="button"
          disabled={a.busy}
          onClick={() => setTagDialog(true)}
        >
          Create organization tag
        </button>
      )}
      {tagDialog && (
        <Dialog
          title="Create organization tag"
          onClose={() => setTagDialog(false)}
        >
          <div className="dialog-body">
            <form
              onSubmit={(event) => {
                event.preventDefault();
                event.stopPropagation();
                const name = String(
                  new FormData(event.currentTarget).get("name"),
                ).trim();
                if (!name) return;
                setTagDialog(false);
                void a.run(async () => {
                  const { id } = await mutate<{ id: string }>("/tags", {
                    name,
                  });
                  setChosen((previous) => [...new Set([...previous, id])]);
                  a.setNotice("Tag created and selected for this asset.");
                });
              }}
            >
              <label>
                Tag name
                <input name="name" required maxLength={80} autoFocus />
              </label>
              <button className="primary">Create tag</button>
            </form>
          </div>
        </Dialog>
      )}
    </>
  );
}
