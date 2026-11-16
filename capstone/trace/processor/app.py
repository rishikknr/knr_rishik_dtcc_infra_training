"""Local FastAPI transport. No registry credentials, identity, or filesystem writes."""

from fastapi import FastAPI, Request
from importers.snowflake_csv_importer import MAX_BYTES, invalid, parse_csv
from schemas.metadata import MetadataPreview
from starlette.concurrency import run_in_threadpool

app = FastAPI(
    title="TRACE metadata parser", docs_url=None, redoc_url=None, openapi_url=None
)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/parse/snowflake", response_model=MetadataPreview, include_in_schema=False)
@app.post("/parse/snowflake-columns", response_model=MetadataPreview)
async def parse_snowflake(request: Request):
    content = bytearray()
    async for chunk in request.stream():
        if len(content) + len(chunk) > MAX_BYTES:
            invalid("LIMIT", status=413)
        content.extend(chunk)
    return await run_in_threadpool(parse_csv, bytes(content))


# Privileged operations require a private shared token. The legacy metadata-only CSV
# parser remains compatible. Bind this process only to loopback/private networking.
import base64
import json
import os
import secrets

from connectors.providers import ProviderError, acquire
from fastapi import HTTPException
from parsers.files import parse_file
from parsers.sql import validate_sql, analyze_sql


async def internal_body(request: Request):
    expected = os.environ.get("TRACE_PROCESSOR_TOKEN", "")
    if not expected or not secrets.compare_digest(
        request.headers.get("X-Trace-Processor-Token", ""), expected
    ):
        raise HTTPException(403, detail={"code": "INTERNAL_AUTH_REQUIRED"})
    content = bytearray()
    async for chunk in request.stream():
        content.extend(chunk)
        if len(content) > 8 * 1024 * 1024:
            raise HTTPException(413, detail={"code": "LIMIT_EXCEEDED"})
    try:
        result = json.loads(content)
        if not isinstance(result, dict):
            raise TypeError("Object required")
        return result
    except Exception:
        raise HTTPException(422, detail={"code": "INVALID_METADATA"}) from None


@app.post("/internal/parse")
async def parse_metadata(request: Request):
    body = await internal_body(request)
    try:
        content = base64.b64decode(body["content"], validate=True)
        result = await run_in_threadpool(
            parse_file,
            content,
            body["format"],
            body.get("mapping"),
            body.get("dialect", "snowflake"),
            body.get("filename", "upload"),
        )
        return result
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(
            422,
            detail={
                "code": "INVALID_METADATA",
                "message": "Invalid metadata file or mapping",
            },
        ) from None


@app.post("/internal/sql/validate")
async def sql_validate(request: Request):
    body = await internal_body(request)
    return await run_in_threadpool(
        validate_sql, body.get("expression", ""), body.get("dialect", "snowflake")
    )


@app.post("/internal/acquire")
async def discover(request: Request):
    body = await internal_body(request)
    try:
        result = await run_in_threadpool(
            acquire,
            body.get("type"),
            body.get("configuration", {}),
            body.get("credentials", {}),
            body.get("test", False),
        )
        return {"status": "CONNECTED", "batch": result}
    except ProviderError as e:
        return {"status": e.code, "reasonCode": e.reason_code, "batch": None}


@app.post("/internal/sql/analyze")
async def sql_analyze(request: Request):
    body = await internal_body(request)
    return await run_in_threadpool(
        analyze_sql,
        body.get("expression", ""),
        body.get("dialect", "snowflake"),
        body.get("assets", []),
        body.get("target"),
    )
