import { useState } from "react";
import type { Dataset } from "../../types/api";
import { TableRegion, Timestamp } from "../../components/Design";
export function MetadataInventory({
  datasets,
  changes,
  committed = false,
  connectionId,
}: {
  datasets: Dataset[];
  changes: Map<string, { status: string }>;
  committed?: boolean;
  connectionId: string;
}) {
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState("");
  const objects = datasets.some(
    (d) => d.assetType === "FILE" || d.externalKey.startsWith("s3://"),
  );
  const visible = datasets.filter(
    (d) =>
      `${d.name} ${d.externalKey}`
        .toLowerCase()
        .includes(query.toLowerCase()) &&
      (!filter || changes.get(d.externalKey)?.status === filter),
  );
  return (
    <div className="inventory-review">
      <div className="inventory-toolbar">
        <label>
          Search inventory
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Object name or full path"
          />
        </label>
        <label>
          Change filter
          <select value={filter} onChange={(e) => setFilter(e.target.value)}>
            <option value="">All changes</option>
            {[...new Set([...changes.values()].map((c) => c.status))].map(
              (s) => (
                <option key={s}>{s}</option>
              ),
            )}
          </select>
        </label>
        <span role="status">
          {visible.length} of {datasets.length} assets
        </span>
      </div>
      {!datasets.length && (
        <p className="notice warning">
          The provider returned no accessible objects for this scope. Verify the
          prefix or schema allowlist before committing.
        </p>
      )}
      <TableRegion label="Proposed asset changes">
        <table>
          <thead>
            <tr>
              <th scope="col">
                {objects ? "Object / full path" : "Asset / identifier"}
              </th>
              <th scope="col">Change</th>
              {objects ? (
                <>
                  <th scope="col">Size (bytes)</th>
                  <th scope="col">Last modified</th>
                  <th scope="col">ETag</th>
                </>
              ) : (
                <th scope="col">Columns</th>
              )}
            </tr>
          </thead>
          <tbody>
            {visible.map((d) => (
              <tr key={d.externalKey}>
                <td>
                  <strong>{d.name}</strong>
                  <br />
                  {committed ? (
                    <a
                      href={`#catalog?${new URLSearchParams({ q: d.externalKey, source: connectionId })}`}
                    >
                      <code>{d.externalKey}</code>
                    </a>
                  ) : (
                    <code>{d.externalKey}</code>
                  )}
                </td>
                <td>{changes.get(d.externalKey)?.status ?? "—"}</td>
                {objects ? (
                  <>
                    <td>
                      {typeof d.metadata?.size === "number"
                        ? d.metadata.size.toLocaleString()
                        : "—"}
                    </td>
                    <td>
                      <Timestamp
                        value={
                          typeof d.metadata?.lastModified === "string"
                            ? d.metadata.lastModified
                            : null
                        }
                      />
                    </td>
                    <td>
                      <code>
                        {typeof d.metadata?.etag === "string"
                          ? d.metadata.etag
                          : "—"}
                      </code>
                    </td>
                  </>
                ) : (
                  <td>{d.columns.length}</td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </TableRegion>
    </div>
  );
}
