export function humanize(value: string) {
  return value
    .toLowerCase()
    .replaceAll("_", " ")
    .replace(/\b(sql|dax|csv|api|json|s3|bi)\b/g, (word) => word.toUpperCase())
    .replace(/^./, (letter) => letter.toUpperCase());
}
