"""Bounded, stateless CSV parsing. Canonical registry writes belong to Spring Boot."""
import csv
import io
import re

from fastapi import HTTPException
from schemas.metadata import Column, Dataset, MetadataPreview

MAX_BYTES = 5 * 1024 * 1024
MAX_ROWS = 20000
MAX_DATASETS = 1000
MAX_COLUMNS = 2000
REQUIRED = {"database", "schema", "table", "column", "data_type"}
LEGACY = {"DATABASE_NAME": "database", "SCHEMA_NAME": "schema", "TABLE_NAME": "table",
          "COLUMN_NAME": "column", "DATA_TYPE": "data_type", "DESCRIPTION": "description"}
ORDINARY = re.compile(r"[A-Za-z_][A-Za-z0-9_$]*\Z", re.ASCII)
csv.field_size_limit(16384)


def invalid(code: str, row: int | None = None, field: str = "csv", status: int = 422):
    # Safe, row-specific diagnostics; never echo uploaded values or exception text.
    raise HTTPException(status_code=status, detail={"code": "INVALID_CSV", "errors": [
        {"code": code, "row": row, "field": field}]})


def identifier(value: str) -> str:
    """Escape literal identifiers in the backwards-compatible *_NAME export format."""
    if ORDINARY.fullmatch(value) and value == value.upper():
        return value
    return '"' + value.replace('"', '""') + '"'


def parse_csv(content: bytes) -> dict:
    if not content:
        invalid("EMPTY_FILE")
    if len(content) > MAX_BYTES:
        invalid("LIMIT", status=413)
    try:
        reader = csv.DictReader(io.StringIO(content.decode("utf-8-sig"), newline=""), strict=True)
        headers = reader.fieldnames
        if not headers:
            invalid("MISSING_HEADERS")
        headers = [h.strip() for h in headers]
        legacy = "DATABASE_NAME" in headers
        normalized = [LEGACY.get(h, h) if legacy else h.lower() for h in headers]
        if len(set(normalized)) != len(normalized) or not REQUIRED.issubset(normalized) or set(normalized) - REQUIRED - {"description"}:
            invalid("MISSING_HEADERS")
        reader.fieldnames = normalized
        datasets: dict[tuple, Dataset] = {}
        seen: dict[tuple, dict[str, Column]] = {}
        duplicates = 0
        for count, row in enumerate(reader, start=1):
            line = reader.line_num
            if count > MAX_ROWS:
                invalid("LIMIT", line)
            if None in row or any(v is None for v in row.values()):
                invalid("MALFORMED_CSV", line)
            for field in REQUIRED:
                if not row[field].strip():
                    invalid("EMPTY_VALUE", line, field)
            for field, value in row.items():
                if any(ord(c) < 32 and c not in '\t\r\n' for c in value):
                    invalid("MALFORMED_CSV", line, field)
            names = []
            for field in ("database", "schema", "table", "column"):
                value = row[field] if legacy else row[field].strip()
                if len(value) > 255 or (not legacy and not ORDINARY.fullmatch(value)):
                    invalid("IDENTIFIER", line, field)
                names.append(value if legacy else value.upper())
            key = tuple(names[:3])
            external = ".".join(map(identifier, key))
            name = ".".join(map(identifier, key[1:]))
            if len(external) > 500 or len(name) > 255:
                invalid("IDENTIFIER", line)
            dtype = " ".join(row["data_type"].strip().upper().split())
            if len(dtype) > 100:
                invalid("LIMIT", line, "data_type")
            description = row.get("description", "").strip() or None
            if description is not None and len(description) > 4000:
                invalid("DESCRIPTION", line, "description")
            column = Column(name=names[3], dataType=dtype, description=description)
            if key not in datasets:
                if len(datasets) >= MAX_DATASETS:
                    invalid("LIMIT", line)
                datasets[key] = Dataset(externalKey=external, name=name, columns=[])
                seen[key] = {}
            existing = seen[key].get(column.name)
            if existing is not None:
                if existing != column:
                    invalid("CONFLICT", line, "column")
                duplicates += 1
                continue
            if len(seen[key]) >= MAX_COLUMNS:
                invalid("LIMIT", line)
            seen[key][column.name] = column
            datasets[key].columns.append(column)
        if not datasets:
            invalid("EMPTY_FILE")
        warnings = [f"Ignored {duplicates} duplicate metadata row(s)."] if duplicates else []
        return MetadataPreview(datasets=list(datasets.values()), warnings=warnings).model_dump()
    except (UnicodeError, csv.Error):
        invalid("MALFORMED_CSV")
