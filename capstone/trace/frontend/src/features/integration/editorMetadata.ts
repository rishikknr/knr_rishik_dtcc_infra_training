import type { Asset, AssetColumn } from "../../types/api";
export type EditorDataset = { asset: Asset; columns: AssetColumn[] };
export const sqlFunctions = [
  {
    label: "SUM",
    detail: "SUM(number)",
    info: "Adds numeric values within each group.",
  },
  {
    label: "COUNT",
    detail: "COUNT(expression)",
    info: "Counts non-null values; COUNT(*) counts rows.",
  },
  {
    label: "AVG",
    detail: "AVG(number)",
    info: "Returns the arithmetic mean of non-null values.",
  },
  {
    label: "COALESCE",
    detail: "COALESCE(value, fallback)",
    info: "Returns the first non-null argument.",
  },
  {
    label: "NULLIF",
    detail: "NULLIF(value, other)",
    info: "Returns null when both values are equal. Useful for safe division.",
  },
  {
    label: "ROUND",
    detail: "ROUND(number, decimals)",
    info: "Rounds a numeric expression to the requested precision.",
  },
  {
    label: "DATE_TRUNC",
    detail: "DATE_TRUNC('month', date)",
    info: "Truncates a date or timestamp to a chosen period.",
  },
  {
    label: "CAST",
    detail: "CAST(value AS type)",
    info: "Converts an expression to a specified data type.",
  },
];
/** Exact matches only. Ambiguous unqualified table names are intentionally excluded. */
export function resolveEditorToken(
  token: string,
  datasets: EditorDataset[],
): { asset: Asset; column?: AssetColumn } | null {
  const normalized = token.toLowerCase();
  const exact = datasets.filter(
    (d) => d.asset.external_key.toLowerCase() === normalized,
  );
  if (exact.length === 1) return { asset: exact[0].asset };
  const matches = datasets.flatMap((d) =>
    d.columns
      .filter(
        (c) => `${d.asset.external_key}.${c.name}`.toLowerCase() === normalized,
      )
      .map((column) => ({ asset: d.asset, column })),
  );
  if (matches.length === 1) return matches[0];
  const short = datasets.filter(
    (d) => d.asset.external_key.split(".").at(-1)?.toLowerCase() === normalized,
  );
  return short.length === 1 ? { asset: short[0].asset } : null;
}

/** A conservative lookup for simple, uniquely declared FROM/JOIN aliases in the editor. */
export function resolveEditorReference(
  token: string,
  expression: string,
  datasets: EditorDataset[],
) {
  const exact = resolveEditorToken(token, datasets);
  if (exact) return exact;
  const [alias, column, ...rest] = token.split(".");
  if (!column || rest.length) return null;
  const bindings = [
    ...expression.matchAll(
      /\b(?:FROM|JOIN)\s+([\w$]+(?:\.[\w$]+){0,2})\s+(?:AS\s+)?([\w$]+)/gi,
    ),
  ].filter((m) => m[2].toLowerCase() === alias.toLowerCase());
  if (bindings.length !== 1) return null;
  const bound = resolveEditorToken(bindings[0][1], datasets);
  if (!bound) return null;
  const fields =
    datasets
      .find((d) => d.asset.id === bound.asset.id)
      ?.columns.filter((c) => c.name.toLowerCase() === column.toLowerCase()) ||
    [];
  return fields.length === 1 ? { asset: bound.asset, column: fields[0] } : null;
}
