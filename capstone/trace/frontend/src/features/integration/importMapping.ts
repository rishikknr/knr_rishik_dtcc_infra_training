export const canonicalFields = [
  "database",
  "schema",
  "table",
  "column",
  "data_type",
  "description",
  "external_key",
  "name",
  "asset_type",
  "bucket",
  "key",
  "size",
  "last_modified",
  "etag",
  "report_name",
  "visual_name",
  "measure_name",
  "metric_name",
];

export function readCsvHeaders(text: string): string[] {
  const values: string[] = [];
  let value = "";
  let quoted = false;
  for (
    let index = text.startsWith("\uFEFF") ? 1 : 0;
    index < text.length;
    index++
  ) {
    const character = text[index];
    if (character === '"' && quoted && text[index + 1] === '"') {
      value += character;
      index++;
    } else if (character === '"' && (quoted || !value)) {
      quoted = !quoted;
    } else if (!quoted && character === ",") {
      values.push(value);
      value = "";
    } else if (!quoted && (character === "\n" || character === "\r")) {
      break;
    } else {
      value += character;
    }
  }
  return [...values, value];
}

export function hasMappingConflict(
  headers: string[],
  mapping: Record<string, string>,
) {
  if (new Set(headers).size !== headers.length) return true;
  const fields = headers
    .map((header) => mapping[header] || header.trim().toLowerCase())
    .filter((field) => canonicalFields.includes(field));
  return new Set(fields).size !== fields.length;
}
