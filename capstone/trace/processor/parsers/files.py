import csv
import io
import json
import zipfile
from pathlib import PurePosixPath

from fastapi import HTTPException
from importers.snowflake_csv_importer import parse_csv
from parsers.sql import validate_sql
from schemas.batch import Asset, Column, MetadataBatch, Relationship

FORMATS = {
    "SNOWFLAKE_CSV",
    "GENERIC_CSV",
    "METADATA_JSON",
    "S3_INVENTORY",
    "SQL",
    "SQL_ZIP",
    "POWERBI_CSV",
    "POWERBI_MAPPING_CSV",
}
FIELDS = {
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
}


def fail(message):
    raise HTTPException(422, detail={"code": "INVALID_METADATA", "message": message})


def bounded_json(content):
    text = content.decode("utf-8-sig")
    depth = 0
    quoted = False
    escaped = False
    for ch in text:
        if escaped:
            escaped = False
            continue
        if ch == "\\" and quoted:
            escaped = True
            continue
        if ch == '"':
            quoted = not quoted
        if not quoted:
            if ch in "[{":
                depth += 1
            elif ch in "]}":
                depth -= 1
            if depth > 20:
                fail("JSON exceeds maximum nesting depth of 20")

    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                fail("JSON object contains duplicate field names")
            result[key] = value
        return result

    return json.loads(
        text,
        object_pairs_hook=unique_object,
        parse_constant=lambda _: fail("Non-finite numeric metadata is unsupported"),
    )


def parse_file(content, format, mapping=None, dialect="snowflake", filename="upload"):
    if format not in FORMATS:
        fail("Unsupported format")
    if not content or len(content) > 5 * 1024 * 1024:
        fail("Choose a non-empty file no larger than 5 MiB")
    if format == "SNOWFLAKE_CSV":
        old = parse_csv(content)
        return MetadataBatch(
            datasets=[Asset(**d.model_dump()) for d in old.datasets],
            warnings=old.warnings,
        )
    if format == "METADATA_JSON":
        result = MetadataBatch.model_validate(bounded_json(content))
    elif format in {"SQL", "SQL_ZIP"}:
        files = []
        if format == "SQL":
            files = [(PurePosixPath(filename).name, content)]
        else:
            with zipfile.ZipFile(io.BytesIO(content)) as archive:
                total = 0
                if len(archive.infolist()) > 100:
                    fail("ZIP contains more than 100 entries")
                for info in archive.infolist():
                    path = PurePosixPath(info.filename)
                    if (
                        path.is_absolute()
                        or ".." in path.parts
                        or "\\" in info.filename
                        or (info.external_attr >> 16) & 0o170000 == 0o120000
                    ):
                        fail("Unsafe ZIP entry")
                    if info.is_dir():
                        continue
                    if path.suffix.lower() != ".sql":
                        fail("ZIP may contain only SQL files")
                    total += info.file_size
                    if (
                        info.file_size > 100000
                        or total > 2000000
                        or info.file_size > max(1, info.compress_size) * 100
                    ):
                        fail("ZIP exceeds decompression limits")
                    files.append((info.filename, archive.read(info)))
        assets = []
        for name, data in files:
            source = data.decode("utf-8-sig")
            if len(data) > 100000:
                fail("SQL file exceeds 100 KB")
            validation = validate_sql(source, dialect)
            assets.append(
                Asset(
                    externalKey="sql:" + name,
                    name=name,
                    assetType="SQL_TRANSFORMATION",
                    metadata={
                        "source": source,
                        "dialect": dialect,
                        "validation": validation,
                        "provenance": "IMPORTED_METADATA",
                    },
                )
            )
        result = MetadataBatch(datasets=assets)
    else:
        mapping = mapping or {}
        if len(mapping) != len(set(mapping.values())) or set(mapping.values()) - FIELDS:
            fail("Each canonical field may be mapped once")
        reader = csv.DictReader(io.StringIO(content.decode("utf-8-sig"), newline=""))
        if (
            not reader.fieldnames
            or len(reader.fieldnames) != len(set(reader.fieldnames))
            or len(reader.fieldnames) > 100
        ):
            fail("Missing or duplicate headers")
        effective = [mapping.get(h, h.strip().lower()) for h in reader.fieldnames]
        if len(effective) != len(set(effective)):
            fail("Mapped and normalized headers must be unique")
        if set(mapping) - set(reader.fieldnames):
            fail("Mapped header is missing")
        groups = {}
        warnings = []
        relationships = []
        for rownum, row in enumerate(reader, 2):
            if rownum > 20001:
                fail("CSV exceeds 20,000 rows")
            if None in row or None in row.values():
                fail(f"Malformed row {rownum}")
            row = {mapping.get(k, k.strip().lower()): v.strip() for k, v in row.items()}
            if format == "POWERBI_MAPPING_CSV":
                from urllib.parse import quote

                if (
                    not row.get("report_name")
                    or not row.get("visual_name")
                    or not row.get("measure_name")
                ):
                    fail(
                        f"Row {rownum}: report_name, visual_name, measure_name required"
                    )
                report = "powerbi-export:report:" + quote(row["report_name"], safe="")
                visual = report + ":visual:" + quote(row["visual_name"], safe="")
                measure = report + ":measure:" + quote(row["measure_name"], safe="")
                for key, name, kind in [
                    (report, row["report_name"], "REPORT"),
                    (visual, row["visual_name"], "VISUAL"),
                    (measure, row["measure_name"], "MEASURE"),
                ]:
                    groups.setdefault(
                        (key, kind),
                        Asset(
                            externalKey=key,
                            name=name,
                            assetType=kind,
                            metadata={
                                "declaredMetricName": row.get("metric_name", ""),
                                "verification": "IMPORTED_DECLARATION",
                            },
                        ),
                    )
                for src, st, dst, dt, kind in [
                    (report, "REPORT", visual, "VISUAL", "REPORT_CONTAINS_VISUAL"),
                    (measure, "MEASURE", visual, "VISUAL", "VISUAL_USES_MEASURE"),
                ]:
                    r = Relationship(
                        sourceKey=src,
                        sourceType=st,
                        targetKey=dst,
                        targetType=dt,
                        relationshipType=kind,
                        evidenceRef="Uploaded report/visual/measure mapping declaration",
                    )
                    if r not in relationships:
                        relationships.append(r)
                if row.get("metric_name") and len(warnings) < 100:
                    warnings.append(
                        "Metric name is an unresolved declaration; select an immutable registered metric version in Metrics to confirm usage"
                    )
                continue
            if format == "S3_INVENTORY":
                if not row.get("bucket") or not row.get("key"):
                    fail(f"Row {rownum}: bucket and key required")
                key = "s3://" + row["bucket"] + "/" + row["key"]
                asset = Asset(
                    externalKey=key,
                    name=row["key"].split("/")[-1] or row["key"],
                    assetType="FILE",
                    metadata={
                        k: row[k]
                        for k in ("size", "last_modified", "etag")
                        if row.get(k)
                    },
                )
            elif format == "POWERBI_CSV":
                if (
                    not row.get("external_key")
                    or not row.get("name")
                    or row.get("asset_type")
                    not in {
                        "REPORT",
                        "VISUAL",
                        "SEMANTIC_MODEL",
                        "MEASURE",
                        "WORKSPACE",
                    }
                ):
                    fail(
                        f"Row {rownum}: external_key, name and supported asset_type required"
                    )
                asset = Asset(
                    externalKey=row["external_key"],
                    name=row["name"],
                    assetType=row["asset_type"],
                )
            else:
                key = row.get("external_key") or ".".join(
                    row.get(k, "") for k in ("database", "schema", "table")
                )
                if not key or (
                    not row.get("external_key")
                    and not all(row.get(k) for k in ("database", "schema", "table"))
                ):
                    fail(
                        f"Row {rownum}: map external_key or database, schema and table"
                    )
                asset = Asset(
                    externalKey=key,
                    name=row.get("name") or row.get("table") or key,
                    assetType=row.get("asset_type") or "DATASET",
                )
                if row.get("column"):
                    if not row.get("data_type"):
                        fail(f"Row {rownum}: data_type is required with column")
                    asset.columns = [
                        Column(
                            name=row["column"],
                            dataType=row["data_type"],
                            description=row.get("description") or None,
                        )
                    ]
            identity = (asset.externalKey, asset.assetType)
            if identity in groups:
                old = groups[identity]
                if old.name != asset.name:
                    fail(f"Row {rownum}: conflicting asset name")
                for col in asset.columns:
                    existing = next(
                        (c for c in old.columns if c.name == col.name), None
                    )
                    if existing and existing != col:
                        fail(f"Row {rownum}: conflicting column definition")
                    if not existing:
                        old.columns.append(col)
                    elif len(warnings) < 100:
                        warnings.append(f"Row {rownum}: duplicate column ignored")
            else:
                groups[identity] = asset
        result = MetadataBatch(
            datasets=list(groups.values()),
            warnings=warnings,
            relationships=relationships,
        )
    keys = set()
    total = 0
    for a in result.datasets:
        identity = (a.externalKey, a.assetType)
        if identity in keys:
            fail("Duplicate asset identity")
        keys.add(identity)
        if (
            not a.externalKey.strip()
            or not a.name.strip()
            or len(a.metadata) > 30
            or len(a.columns) > 2000
        ):
            fail("Invalid asset identity or per-asset metadata/column limit")
        names = set()
        for col in a.columns:
            if (
                not col.name.strip()
                or not col.dataType.strip()
                or any(ord(ch) < 32 for ch in col.name + col.dataType)
            ):
                fail("Invalid column name or data type")
            if col.name in names:
                fail("Duplicate column name")
            names.add(col.name)
        total += len(a.columns)
        if any(ord(c) < 32 for c in a.externalKey + a.name):
            fail("Control character in identifier")
    if any(not warning.strip() or len(warning) > 500 for warning in result.warnings):
        fail("Invalid warning text or length")
    compatible = {
        "REPORT_USES_MODEL": ("SEMANTIC_MODEL", "REPORT"),
        "VISUAL_USES_MEASURE": ("MEASURE", "VISUAL"),
        "REPORT_CONTAINS_VISUAL": ("REPORT", "VISUAL"),
        "MODEL_CONTAINS_MEASURE": ("SEMANTIC_MODEL", "MEASURE"),
    }
    for edge in result.relationships:
        if (
            (edge.sourceKey, edge.sourceType) not in keys
            or (edge.targetKey, edge.targetType) not in keys
            or (edge.sourceType, edge.targetType) != compatible[edge.relationshipType]
            or not edge.evidenceRef.strip()
        ):
            fail(
                "Relationship requires compatible endpoints present in the metadata batch"
            )
    if total > 20000:
        fail("Too many columns")
    if not result.datasets:
        fail("File contains no metadata")
    return result
