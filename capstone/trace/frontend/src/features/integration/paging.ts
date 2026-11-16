import { useEffect, useState, useCallback } from "react";
import { request, ApiError } from "../../services/api";

export type Page<T> = {
  items: T[];
  total: number;
  limit: number;
  offset: number;
  hasMore: boolean;
};
export function usePage<T>(
  kind: string,
  revision = 0,
  filters: Record<string, string> = {},
  enabled = true,
  initialQuery = "",
) {
  const [position, setPosition] = useState({ scope: "", offset: 0 }),
    [query, setQuery] = useState(initialQuery),
    [search, setSearch] = useState(initialQuery),
    [refresh, setRefresh] = useState(0);
  const filterText = new URLSearchParams(filters).toString();
  const scope = JSON.stringify([filterText, search]);
  const offset = position.scope === scope ? position.offset : 0;
  const key = JSON.stringify([
    kind,
    revision,
    filterText,
    offset,
    search,
    enabled,
  ]);
  const [result, setResult] = useState<{
    key: string;
    page: Page<T> | null;
    error: unknown;
  } | null>(null);
  useEffect(() => {
    if (!enabled) return;
    const controller = new AbortController();
    request<Page<T>>(
      `/browse/${kind}?limit=25&offset=${offset}&q=${encodeURIComponent(search)}&${filterText}`,
      { signal: controller.signal },
    )
      .then((page) => {
        if (!controller.signal.aborted) setResult({ key, page, error: null });
      })
      .catch((error) => {
        if (!controller.signal.aborted) setResult({ key, page: null, error });
      });
    return () => controller.abort();
  }, [kind, offset, search, filterText, key, enabled, refresh]);
  const current = result?.key === key ? result : null;
  const reload = useCallback(() => setRefresh((n) => n + 1), []);
  return {
    items: current?.page?.items ?? [],
    total: current?.page?.total ?? 0,
    hasMore: current?.page?.hasMore ?? false,
    offset,
    limit: 25,
    query,
    setQuery,
    search: () => {
      setPosition({ scope, offset: 0 });
      setSearch(query.trim());
    },
    next: () => setPosition({ scope, offset: offset + 25 }),
    previous: () => setPosition({ scope, offset: Math.max(0, offset - 25) }),
    reload,
    loading: enabled && !current,
    error: current?.error,
  };
}
/** Explicitly bounded option loading; primary lists remain visibly paginated. */
export async function loadOptions<T>(
  kind: string,
  filters: Record<string, string> = {},
) {
  const items: T[] = [];
  for (let offset = 0; offset < 10000; offset += 100) {
    const page = await request<Page<T>>(
      `/browse/${kind}?limit=100&offset=${offset}&${new URLSearchParams(filters)}`,
    );
    items.push(...page.items);
    if (!page.hasMore) return items;
  }
  throw new ApiError(
    "This selector exceeds 10,000 records. Narrow the registry scope before choosing an item.",
    "SELECTOR_LIMIT",
  );
}
