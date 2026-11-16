import { useEffect, useState } from "react";
import type { Asset, AssetColumn } from "../../types/api";
import { request } from "../../services/api";
import { loadOptions } from "./paging";
import { AssetSelect } from "./shared";
import { Feedback } from "../../components/Feedback";
export function AssetDependencies({
  assets,
  selected,
  onChange,
}: {
  assets: Asset[];
  selected: string[];
  onChange: (ids: string[]) => void;
}) {
  const [query, setQuery] = useState("");
  return (
    <section className="reference-browser">
      <h4>Input assets</h4>
      <p className="hint">
        Select the datasets this calculation reads. These are explicit
        dependency declarations, separate from text suggestions.
      </p>
      <input
        type="search"
        aria-label="Search formula inputs"
        placeholder="Find source datasets…"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />
      <div className="reference-results">
        {assets
          .filter(
            (a) =>
              selected.includes(a.id) ||
              `${a.name} ${a.external_key}`
                .toLowerCase()
                .includes(query.toLowerCase()),
          )
          .sort(
            (a, b) =>
              Number(selected.includes(b.id)) - Number(selected.includes(a.id)),
          )
          .map((a) => (
            <label className="dependency-choice" key={a.id}>
              <input
                type="checkbox"
                checked={selected.includes(a.id)}
                onChange={(e) =>
                  onChange(
                    e.target.checked
                      ? [...selected, a.id]
                      : selected.filter((id) => id !== a.id),
                  )
                }
              />
              <span>
                {a.name}
                <small>{a.external_key}</small>
              </span>
            </label>
          ))}
      </div>
      <small>{selected.length} asset inputs selected</small>
    </section>
  );
}
export function ColumnDependencies({
  assets,
  selected,
  onChange,
}: {
  assets: Asset[];
  selected: string[];
  onChange: (ids: string[]) => void;
}) {
  const [parent, setParent] = useState(""),
    [query, setQuery] = useState(""),
    [resource, setResource] = useState<{
      parent: string;
      columns: AssetColumn[];
    } | null>(null),
    [error, setError] = useState<unknown>(null);
  const [stored, setStored] = useState<
    { id: string; name: string; asset_key: string; data_type: string }[]
  >([]);
  const key = selected.join(",");
  useEffect(() => {
    if (!key) return;
    const c = new AbortController();
    request<typeof stored>(
      `/lineage/reference-columns?ids=${encodeURIComponent(key)}`,
      { signal: c.signal },
    )
      .then(setStored)
      .catch((e) => {
        if (!c.signal.aborted) setError(e);
      });
    return () => c.abort();
  }, [key]);
  useEffect(() => {
    if (!parent) return;
    let active = true;
    loadOptions<AssetColumn>("columns", { parent })
      .then((columns) => {
        if (active) {
          setResource({ parent, columns });
          setError(null);
        }
      })
      .catch((e) => {
        if (active) setError(e);
      });
    return () => {
      active = false;
    };
  }, [parent]);
  const columns = resource?.parent === parent ? resource.columns : [];
  return (
    <section className="reference-browser">
      <h4>Input fields</h4>
      <p className="hint">
        Choose fields from an existing dataset. Selections from other datasets
        stay selected.
      </p>
      {selected.map((id) => {
        const c = stored.find((c) => c.id === id);
        return (
          <div className="list-row" key={id}>
            <span>
              {c?.name || "Stored field reference"}
              <small>{c?.asset_key || "Reference details loading"}</small>
            </span>
            <button
              type="button"
              aria-label={`Remove ${c?.name || "field reference"}`}
              onClick={() => onChange(selected.filter((s) => s !== id))}
            >
              ×
            </button>
          </div>
        );
      })}
      <AssetSelect
        assets={assets.filter((a) =>
          ["DATASET", "TABLE", "VIEW"].includes(a.asset_type),
        )}
        name="columnParent"
        label="Choose a dataset for fields"
        optional
        value={parent}
        onChange={(id) => {
          setParent(id);
          setQuery("");
        }}
      />
      <Feedback error={error} />
      {parent && (
        <>
          <input
            type="search"
            aria-label="Filter source fields"
            placeholder="Search columns…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <div className="reference-results">
            {columns
              .filter((c) => c.name.toLowerCase().includes(query.toLowerCase()))
              .map((c) => (
                <label className="dependency-choice" key={c.id}>
                  <input
                    type="checkbox"
                    checked={selected.includes(c.id)}
                    onChange={(e) =>
                      onChange(
                        e.target.checked
                          ? [...selected, c.id]
                          : selected.filter((id) => id !== c.id),
                      )
                    }
                  />
                  <span>
                    {c.name}
                    <small>{c.data_type}</small>
                  </span>
                </label>
              ))}
          </div>
          {resource?.parent !== parent && (
            <small>Loading registered fields…</small>
          )}
        </>
      )}
    </section>
  );
}
