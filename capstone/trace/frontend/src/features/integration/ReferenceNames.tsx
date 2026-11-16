import { useEffect, useState } from "react";
import { request } from "../../services/api";
import { Feedback } from "../../components/Feedback";
export function ColumnReferenceNames({ ids }: { ids: string[] }) {
  const key = ids.join(",");
  const [resource, setResource] = useState<{
    key: string;
    columns: {
      id: string;
      name: string;
      asset_id: string;
      asset_key: string;
    }[];
    error?: unknown;
  } | null>(null);
  useEffect(() => {
    if (!key) return;
    const c = new AbortController();
    request<NonNullable<typeof resource>["columns"]>(
      `/lineage/reference-columns?ids=${encodeURIComponent(key)}`,
      { signal: c.signal },
    )
      .then((columns) => setResource({ key, columns }))
      .catch((error) => {
        if (!c.signal.aborted) setResource({ key, columns: [], error });
      });
    return () => c.abort();
  }, [key]);
  if (!ids.length) return <span>None declared</span>;
  return (
    <>
      <Feedback error={resource?.key === key ? resource.error : null} />
      <ul>
        {ids.map((id) => {
          const column =
            resource?.key === key
              ? resource.columns.find((c) => c.id === id)
              : null;
          return (
            <li key={id}>
              {column ? (
                <a href={`#lineage/${column.asset_id}?column=${column.id}`}>
                  {column.asset_key}.{column.name}
                </a>
              ) : (
                "Registered field details loading or unavailable"
              )}
            </li>
          );
        })}
      </ul>
    </>
  );
}
