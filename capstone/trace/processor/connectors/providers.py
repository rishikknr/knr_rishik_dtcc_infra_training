"""Real metadata-only adapters. No ambient AWS identity, arbitrary URLs, or data queries."""

import base64
import hashlib
import json
import random
import re
import time
from urllib.parse import quote, urlparse

import httpx
from parsers.sql import DIALECTS, validate_sql
from schemas.batch import Asset, Column, MetadataBatch, Relationship


class ProviderError(Exception):
    def __init__(self, code, reason_code=None):
        self.code = code
        self.reason_code = reason_code or code


SCHEMAS = {
    "SNOWFLAKE": (
        {
            "account",
            "username",
            "role",
            "warehouse",
            "database",
            "schemas",
            "includeViewDefinitions",
        },
        {"privateKey", "privateKeyPassphrase"},
    ),
    "AMAZON_S3": (
        {"region", "bucket", "prefix", "maxObjects"},
        {"accessKeyId", "secretAccessKey", "sessionToken"},
    ),
    "GITHUB_SQL": (
        {"owner", "repository", "revision", "prefix", "dialect", "maxFiles"},
        {"token"},
    ),
    "POWER_BI": ({"tenantId", "clientId", "workspaceIds"}, {"clientSecret"}),
}


def validate(kind, c, s):
    if kind not in SCHEMAS:
        raise ProviderError("CONFIGURATION_ERROR")
    fields, secrets = SCHEMAS[kind]
    if set(c) - fields or set(s) - secrets:
        raise ProviderError("CONFIGURATION_ERROR")
    if any(not isinstance(v, (str, int, bool, list)) for v in c.values()):
        raise ProviderError("CONFIGURATION_ERROR")
    if any(not isinstance(v, str) or not v or len(v) > 32000 for v in s.values()):
        raise ProviderError("CONFIGURATION_ERROR")

    def required(*names):
        if any(not c.get(k) for k in names):
            raise ProviderError("CONFIGURATION_ERROR")

    def strings(*names):
        for name in names:
            value = c.get(name)
            if value is not None and (
                not isinstance(value, str)
                or not value.strip()
                or len(value) > 255
                or any(ord(ch) < 32 for ch in value)
            ):
                raise ProviderError("CONFIGURATION_ERROR")

    def bounded_integer(name, default, maximum):
        value = c.get(name, default)
        if (
            isinstance(value, bool)
            or not isinstance(value, (str, int))
            or not re.fullmatch(r"[0-9]+", str(value))
            or not 1 <= int(value) <= maximum
        ):
            raise ProviderError("CONFIGURATION_ERROR", "DISCOVERY_LIMIT_INVALID")

    def match(k, pattern):
        if not isinstance(c.get(k), str) or not re.fullmatch(pattern, c[k]):
            raise ProviderError("CONFIGURATION_ERROR")

    if kind == "SNOWFLAKE":
        required("account", "username", "database")
        strings("account", "username", "database", "role", "warehouse")
        warehouse = c.get("warehouse", "")
        schemas = c.get("schemas", [])
        if (
            isinstance(warehouse, str)
            and isinstance(schemas, list)
            and len(schemas) > 1
            and [x.strip() for x in warehouse.split(",")] == schemas
        ):
            raise ProviderError(
                "CONFIGURATION_ERROR", "SNOWFLAKE_WAREHOUSE_IS_SCHEMA_LIST"
            )
        if "includeViewDefinitions" in c and not isinstance(
            c["includeViewDefinitions"], bool
        ):
            raise ProviderError("CONFIGURATION_ERROR")
        match("account", r"[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+){0,3}")
        if not s.get("privateKey"):
            raise ProviderError("CREDENTIALS_REQUIRED")
        if (
            not isinstance(c.get("schemas", []), list)
            or len(c.get("schemas", [])) > 50
            or any(
                not isinstance(x, str)
                or not x
                or len(x) > 255
                or any(ord(ch) < 32 for ch in x)
                for x in c.get("schemas", [])
            )
        ):
            raise ProviderError("CONFIGURATION_ERROR")
    if kind == "AMAZON_S3":
        required("region", "bucket")
        match("region", r"[a-z]{2}(?:-[a-z]+)+-\d+")
        match("bucket", r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
        if re.fullmatch(r"[0-9.]+", c["bucket"]) or ".." in c["bucket"]:
            raise ProviderError("CONFIGURATION_ERROR")
        if not s.get("accessKeyId") or not s.get("secretAccessKey"):
            raise ProviderError("CREDENTIALS_REQUIRED")
        bounded_integer("maxObjects", 1000, 1000)
        if any(v != v.strip() or any(ch.isspace() for ch in v) for v in s.values()):
            raise ProviderError("CONFIGURATION_ERROR", "CREDENTIAL_WHITESPACE")
    if kind == "GITHUB_SQL":
        required("owner", "repository", "revision")
        strings("owner", "repository", "revision", "dialect")
        if c.get("dialect", "snowflake") not in DIALECTS:
            raise ProviderError("CONFIGURATION_ERROR")
        match("owner", r"[A-Za-z0-9-]{1,100}")
        match("repository", r"[A-Za-z0-9_.-]{1,100}")
        if (
            c["repository"] in {".", ".."}
            or ".." in c["revision"]
            or len(c["revision"]) > 200
        ):
            raise ProviderError("CONFIGURATION_ERROR")
        if not s.get("token"):
            raise ProviderError("CREDENTIALS_REQUIRED")
        bounded_integer("maxFiles", 50, 100)
    if kind == "POWER_BI":
        required("tenantId", "clientId", "workspaceIds")
        for k in ("tenantId", "clientId"):
            match(k, r"[a-fA-F0-9-]{36}")
        if (
            not isinstance(c["workspaceIds"], list)
            or not 1 <= len(c["workspaceIds"]) <= 10
            or any(
                not isinstance(x, str) or not re.fullmatch(r"[a-fA-F0-9-]{36}", x)
                for x in c["workspaceIds"]
            )
        ):
            raise ProviderError("CONFIGURATION_ERROR")
        if not s.get("clientSecret"):
            raise ProviderError("CREDENTIALS_REQUIRED")
    prefix = c.get("prefix", "")
    if (
        not isinstance(prefix, str)
        or len(prefix) > 500
        or ".." in prefix.split("/")
        or prefix.startswith("/")
        or "\\" in prefix
    ):
        raise ProviderError("CONFIGURATION_ERROR")


class BoundedHttp:
    def __init__(self):
        self.client = httpx.Client(timeout=10, follow_redirects=False, trust_env=False)
        self.deadline = time.monotonic() + 90
        self.calls = 0

    def close(self):
        self.client.close()

    def json(self, url, headers=None, data=None):
        parsed = urlparse(url)
        if (
            parsed.scheme != "https"
            or parsed.hostname
            not in {"api.github.com", "api.powerbi.com", "login.microsoftonline.com"}
            or parsed.username
            or parsed.port not in (None, 443)
        ):
            raise ProviderError("CONFIGURATION_ERROR")
        for attempt in range(3):
            self.calls += 1
            if self.calls > 110 or time.monotonic() > self.deadline:
                raise ProviderError("LIMIT_EXCEEDED")
            try:
                with self.client.stream(
                    "POST" if data else "GET", url, headers=headers, data=data
                ) as r:
                    if r.status_code in (429, 502, 503, 504) and attempt < 2:
                        time.sleep(0.1 * (2**attempt) + random.uniform(0, 0.05))
                        continue
                    if (
                        r.status_code == 400
                        and parsed.hostname == "login.microsoftonline.com"
                    ):
                        raise ProviderError("AUTHENTICATION_FAILED")
                    if (
                        r.status_code == 403
                        and r.headers.get("x-ratelimit-remaining") == "0"
                    ):
                        raise ProviderError("RATE_LIMITED")
                    if r.status_code == 401:
                        raise ProviderError("AUTHENTICATION_FAILED")
                    if r.status_code == 403:
                        raise ProviderError("PERMISSION_DENIED")
                    if r.status_code == 429:
                        raise ProviderError("RATE_LIMITED")
                    if r.status_code == 404:
                        raise ProviderError("NOT_FOUND")
                    if not 200 <= r.status_code < 300:
                        raise ProviderError("UNAVAILABLE")
                    body = bytearray()
                    for chunk in r.iter_bytes():
                        if time.monotonic() > self.deadline:
                            raise ProviderError("NETWORK_TIMEOUT")
                        body.extend(chunk)
                        if len(body) > 5 * 1024 * 1024:
                            raise ProviderError("LIMIT_EXCEEDED")
                    import json

                    return json.loads(body)
            except (httpx.TimeoutException, httpx.NetworkError):
                if attempt == 2:
                    raise ProviderError("NETWORK_TIMEOUT")
            except (ValueError, KeyError):
                raise ProviderError("INVALID_PROVIDER_RESPONSE")
        raise ProviderError("UNAVAILABLE")


def github(c, s, test, http):
    root = "https://api.github.com/repos/" + c["owner"] + "/" + c["repository"]
    headers = {
        "Authorization": "Bearer " + s["token"],
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    commit = http.json(root + "/commits/" + quote(c["revision"], safe=""), headers)
    sha = commit["sha"]
    if test:
        return MetadataBatch(warnings=["Repository revision accessible: " + sha[:40]])
    tree = http.json(
        root
        + "/git/trees/"
        + quote(commit["commit"]["tree"]["sha"], safe="")
        + "?recursive=1",
        headers,
    )
    warnings = (
        ["Git tree was truncated; narrow the repository scope"]
        if tree.get("truncated")
        else []
    )
    files = [
        x
        for x in tree.get("tree", [])
        if x["type"] == "blob"
        and x.get("mode") == "100644"
        and x["path"].lower().endswith(".sql")
        and (not c.get("prefix") or x["path"].startswith(c["prefix"].rstrip("/") + "/"))
    ]
    limit = int(c.get("maxFiles", 50))
    assets = []
    total = 0
    if len(files) > limit:
        warnings.append("File limit reached; inventory is partial")
    for f in sorted(files, key=lambda x: x["path"])[:limit]:
        if f.get("size", 100001) > 100000:
            warnings.append("Oversized SQL file skipped")
            continue
        blob = http.json(root + "/git/blobs/" + quote(f["sha"], safe=""), headers)
        if blob.get("encoding") != "base64":
            raise ProviderError("INVALID_PROVIDER_RESPONSE")
        data = base64.b64decode(blob["content"])
        total += len(data)
        if len(data) > 100000 or total > 2000000:
            raise ProviderError("LIMIT_EXCEEDED")
        source = data.decode("utf-8-sig")
        dialect = c.get("dialect", "snowflake")
        assets.append(
            Asset(
                externalKey="github:"
                + c["owner"]
                + "/"
                + c["repository"]
                + ":"
                + f["path"],
                name=f["path"],
                assetType="SQL_TRANSFORMATION",
                metadata={
                    "source": source,
                    "dialect": dialect,
                    "revision": sha,
                    "blobSha": f["sha"],
                    "path": f["path"],
                    "validation": validate_sql(source, dialect),
                    "provenance": "LIVE_DISCOVERY",
                },
            )
        )
    return MetadataBatch(
        datasets=assets, warnings=warnings[:100], snapshotComplete=not warnings
    )


def s3(c, s, test):
    import boto3
    from botocore.config import Config
    from botocore.exceptions import (
        BotoCoreError,
        ClientError,
        ConnectTimeoutError,
        ReadTimeoutError,
    )

    try:
        # Explicit connector identity: never the machine's default credential chain.
        client = boto3.client(
            "s3",
            region_name=c["region"],
            aws_access_key_id=s["accessKeyId"],
            aws_secret_access_key=s["secretAccessKey"],
            aws_session_token=s.get("sessionToken"),
            config=Config(
                connect_timeout=5,
                read_timeout=10,
                retries={"mode": "standard", "total_max_attempts": 3},
                ignore_configured_endpoint_urls=True,
                proxies={},
                s3={"addressing_style": "virtual"},
            ),
        )
        assets = []
        token = None
        seen_tokens = set()
        pages = 0
        limit = 1 if test else int(c.get("maxObjects", 1000))
        deadline = time.monotonic() + 90
        while len(assets) < limit:
            pages += 1
            if pages > 1000:
                raise ProviderError("LIMIT_EXCEEDED")
            if time.monotonic() > deadline:
                raise ProviderError("NETWORK_TIMEOUT")
            args = {
                "Bucket": c["bucket"],
                "Prefix": c.get("prefix", ""),
                "MaxKeys": min(1000, limit - len(assets)),
            }
            if token:
                args["ContinuationToken"] = token
            page = client.list_objects_v2(**args)
            for x in page.get("Contents", []):
                key = x["Key"]
                if not isinstance(key, str) or not key.startswith(c.get("prefix", "")):
                    raise ProviderError("INVALID_PROVIDER_RESPONSE")
                if len(assets) >= limit:
                    raise ProviderError("INVALID_PROVIDER_RESPONSE")
                assets.append(
                    Asset(
                        externalKey="s3://" + c["bucket"] + "/" + key,
                        name=key.split("/")[-1] or key,
                        assetType="FILE",
                        metadata={
                            "key": key,
                            "bucket": c["bucket"],
                            "size": x["Size"],
                            "lastModified": x["LastModified"].isoformat(),
                            "etag": x.get("ETag"),
                            "provenance": "LIVE_DISCOVERY",
                        },
                    )
                )
            token = (
                page.get("NextContinuationToken") if page.get("IsTruncated") else None
            )
            if page.get("IsTruncated") and (not token or token in seen_tokens):
                raise ProviderError("INVALID_PROVIDER_RESPONSE")
            if not token:
                break
            seen_tokens.add(token)
        return MetadataBatch(
            datasets=[] if test else assets,
            warnings=["Object limit reached; inventory is partial"]
            if token and not test
            else [],
            snapshotComplete=not token and not test,
        )
    except ClientError as e:
        # Never relay AWS Message, headers or request parameters: they may contain secrets.
        code = e.response.get("Error", {}).get("Code", "")
        status, reason = {
            "AccessDenied": ("PERMISSION_DENIED", "S3_LIST_ACCESS_DENIED"),
            "AllAccessDisabled": ("PERMISSION_DENIED", "S3_LIST_ACCESS_DENIED"),
            "InvalidAccessKeyId": ("AUTHENTICATION_FAILED", "S3_INVALID_CREDENTIALS"),
            "InvalidToken": ("AUTHENTICATION_FAILED", "S3_INVALID_CREDENTIALS"),
            "SignatureDoesNotMatch": ("AUTHENTICATION_FAILED", "S3_SIGNATURE_MISMATCH"),
            "ExpiredToken": ("AUTHENTICATION_FAILED", "S3_SESSION_EXPIRED"),
            "TokenRefreshRequired": ("AUTHENTICATION_FAILED", "S3_SESSION_EXPIRED"),
            "RequestTimeTooSkewed": ("CONFIGURATION_ERROR", "S3_CLOCK_SKEW"),
            "AuthorizationHeaderMalformed": (
                "CONFIGURATION_ERROR",
                "S3_REGION_MISMATCH",
            ),
            "PermanentRedirect": ("CONFIGURATION_ERROR", "S3_REGION_MISMATCH"),
            "IllegalLocationConstraintException": (
                "CONFIGURATION_ERROR",
                "S3_REGION_MISMATCH",
            ),
            "NoSuchBucket": ("CONFIGURATION_ERROR", "S3_BUCKET_NOT_FOUND"),
            "SlowDown": ("RATE_LIMITED", "S3_THROTTLED"),
            "Throttling": ("RATE_LIMITED", "S3_THROTTLED"),
            "ThrottlingException": ("RATE_LIMITED", "S3_THROTTLED"),
            "RequestTimeout": ("NETWORK_TIMEOUT", "NETWORK_TIMEOUT"),
        }.get(code, ("UNAVAILABLE", "S3_UNAVAILABLE"))
        raise ProviderError(status, reason) from None
    except (TimeoutError, ReadTimeoutError, ConnectTimeoutError):
        raise ProviderError("NETWORK_TIMEOUT") from None
    except BotoCoreError:
        raise ProviderError("UNAVAILABLE", "S3_UNAVAILABLE") from None
    finally:
        if "client" in locals():
            client.close()


def powerbi(c, s, test, http):
    token = http.json(
        "https://login.microsoftonline.com/" + c["tenantId"] + "/oauth2/v2.0/token",
        data={
            "client_id": c["clientId"],
            "client_secret": s["clientSecret"],
            "grant_type": "client_credentials",
            "scope": "https://analysis.windows.net/powerbi/api/.default",
        },
    )
    headers = {"Authorization": "Bearer " + token["access_token"]}
    assets = []
    warnings = [
        "Standard inventory does not expose DAX measures or report visuals. Use manual registration and declared mappings."
    ]

    def pages(url):
        expected = urlparse(url)
        rows = []
        for _ in range(10):
            data = http.json(url, headers)
            rows.extend(data.get("value", []))
            if len(rows) > 1000:
                raise ProviderError("LIMIT_EXCEEDED")
            url = data.get("@odata.nextLink") or data.get("odata.nextLink")
            if not url:
                return rows
            candidate = urlparse(url)
            if (
                candidate.scheme != expected.scheme
                or candidate.netloc != expected.netloc
                or candidate.path != expected.path
            ):
                raise ProviderError("INVALID_PROVIDER_RESPONSE")
        raise ProviderError("LIMIT_EXCEEDED")

    for workspace in c["workspaceIds"]:
        root = "https://api.powerbi.com/v1.0/myorg/groups/" + workspace
        # Test actual workspace metadata access, not merely OAuth success.
        reports = pages(root + "/reports")
        models = pages(root + "/datasets")
        if test:
            continue
        for kind, rows in [("REPORT", reports), ("SEMANTIC_MODEL", models)]:
            for row in rows:
                metadata = {
                    "workspaceId": workspace,
                    "providerId": row["id"],
                    "provenance": "LIVE_DISCOVERY",
                }
                if kind == "REPORT":
                    metadata["modelAssociationObserved"] = "datasetId" in row
                for field in (
                    "datasetId",
                    "configuredBy",
                    "isRefreshable",
                    "reportType",
                ):
                    if field in row:
                        metadata[field] = row[field]
                assets.append(
                    Asset(
                        externalKey="powerbi:"
                        + workspace
                        + ":"
                        + kind
                        + ":"
                        + row["id"],
                        name=row["name"],
                        assetType=kind,
                        metadata=metadata,
                    )
                )
    identities = {a.externalKey for a in assets}
    relationships = []
    for report in assets:
        if report.assetType == "REPORT" and report.metadata.get("datasetId"):
            model = (
                "powerbi:"
                + report.metadata["workspaceId"]
                + ":SEMANTIC_MODEL:"
                + report.metadata["datasetId"]
            )
            if model in identities:
                relationships.append(
                    Relationship(
                        sourceKey=model,
                        sourceType="SEMANTIC_MODEL",
                        targetKey=report.externalKey,
                        targetType="REPORT",
                        relationshipType="REPORT_USES_MODEL",
                        evidenceRef="Power BI report.datasetId",
                    )
                )
            else:
                warnings.append(
                    "Report model is outside accessible inventory; relationship unresolved"
                )
    return MetadataBatch(
        datasets=assets,
        warnings=warnings[:100],
        relationships=relationships,
        snapshotComplete=not any("unresolved" in w for w in warnings),
    )


def snowflake_private_key(credentials):
    """Classify local key mistakes without returning key bytes or parser messages."""
    from cryptography.exceptions import UnsupportedAlgorithm
    from cryptography.hazmat.primitives import serialization

    value = credentials.get("privateKey", "").strip()
    candidate = value.removesuffix(
        "%"
    ).strip()  # Diagnostic only; never authenticate with repaired input.
    if re.search(r"-----BEGIN (?:RSA )?PUBLIC KEY-----", candidate):
        raise ProviderError("CONFIGURATION_ERROR", "SNOWFLAKE_PUBLIC_KEY_PROVIDED")
    if not candidate.startswith("-----"):
        try:
            serialization.load_der_public_key(
                base64.b64decode(candidate, validate=True)
            )
        except (ValueError, TypeError, UnsupportedAlgorithm):
            pass
        else:
            raise ProviderError("CONFIGURATION_ERROR", "SNOWFLAKE_PUBLIC_KEY_PROVIDED")
    if not re.match(
        r"-----BEGIN (?:ENCRYPTED |RSA |EC )?PRIVATE KEY-----", value
    ) or value.endswith("%"):
        raise ProviderError("CONFIGURATION_ERROR", "SNOWFLAKE_PRIVATE_KEY_FORMAT")
    password = credentials.get("privateKeyPassphrase", "")
    encrypted = (
        "-----BEGIN ENCRYPTED PRIVATE KEY-----" in value
        or "Proc-Type: 4,ENCRYPTED" in value
    )
    if encrypted and not password:
        raise ProviderError("CONFIGURATION_ERROR", "SNOWFLAKE_KEY_PASSPHRASE_REQUIRED")
    if not encrypted and password:
        raise ProviderError(
            "CONFIGURATION_ERROR", "SNOWFLAKE_KEY_PASSPHRASE_UNEXPECTED"
        )
    try:
        return serialization.load_pem_private_key(
            value.encode(), password=password.encode() or None
        )
    except (ValueError, TypeError, UnsupportedAlgorithm):
        raise ProviderError(
            "CONFIGURATION_ERROR", "SNOWFLAKE_PRIVATE_KEY_INVALID"
        ) from None


def snowflake(c, s, test):
    import snowflake.connector

    try:
        key = snowflake_private_key(s)
        args = {
            "account": c["account"],
            "user": c["username"],
            "private_key": key,
            "authenticator": "SNOWFLAKE_JWT",
            "login_timeout": 10,
            "network_timeout": 15,
            "socket_timeout": 15,
            "session_parameters": {"STATEMENT_TIMEOUT_IN_SECONDS": 20},
        }
        for field in ("role", "warehouse", "database"):
            if c.get(field):
                args[field] = c[field]
        with snowflake.connector.connect(**args) as conn:
            with conn.cursor() as cursor:
                # Quote a bounded database identifier; schema names are values, never SQL fragments.
                database = c["database"]
                if (
                    not isinstance(database, str)
                    or len(database) > 255
                    or any(ord(x) < 32 for x in database)
                ):
                    raise ProviderError("CONFIGURATION_ERROR")
                db = '"' + database.replace('"', '""') + '"'
                cursor.execute(
                    "SELECT c.TABLE_CATALOG,c.TABLE_SCHEMA,c.TABLE_NAME,c.COLUMN_NAME,c.DATA_TYPE,c.IS_NULLABLE,c.COMMENT,c.ORDINAL_POSITION,t.TABLE_TYPE,t.COMMENT,t.TABLE_OWNER FROM "
                    + db
                    + ".INFORMATION_SCHEMA.COLUMNS c JOIN "
                    + db
                    + ".INFORMATION_SCHEMA.TABLES t ON t.TABLE_CATALOG=c.TABLE_CATALOG AND t.TABLE_SCHEMA=c.TABLE_SCHEMA AND t.TABLE_NAME=c.TABLE_NAME ORDER BY c.TABLE_CATALOG,c.TABLE_SCHEMA,c.TABLE_NAME,c.ORDINAL_POSITION LIMIT 20001",
                    timeout=20,
                )
                rows = []
                while len(rows) < 20001:
                    size = min(1000, 20001 - len(rows))
                    chunk = cursor.fetchmany(size)
                    rows.extend(chunk)
                    if len(chunk) < size:
                        break
                if test:
                    return MetadataBatch()
                warnings = []
                groups = {}
                if len(rows) > 20000:
                    rows = rows[:20000]
                    warnings.append("Column limit reached; inventory is partial")
                for (
                    cat,
                    schema,
                    table,
                    column,
                    dtype,
                    nullable,
                    comment,
                    ordinal,
                    table_type,
                    table_comment,
                    table_owner,
                ) in rows:
                    if c.get("schemas") and schema not in c["schemas"]:
                        continue
                    external = ".".join(
                        '"' + v.replace('"', '""') + '"'
                        if not re.fullmatch(r"[A-Z_][A-Z0-9_$]*", v)
                        else v
                        for v in (cat, schema, table)
                    )
                    if external not in groups:
                        groups[external] = Asset(
                            externalKey=external,
                            name=schema + "." + table,
                            metadata={
                                "provenance": "LIVE_DISCOVERY",
                                "objectType": table_type,
                                "comment": table_comment,
                                "owner": table_owner,
                            },
                        )
                    if len(groups[external].columns) >= 2000:
                        if (
                            "Per-object column limit reached; inventory is partial"
                            not in warnings
                        ):
                            warnings.append(
                                "Per-object column limit reached; inventory is partial"
                            )
                        continue
                    groups[external].columns.append(
                        Column(
                            name=column,
                            dataType=dtype,
                            description=comment or None,
                            nullable=nullable == "YES",
                            ordinalPosition=ordinal,
                        )
                    )
                if len(groups) > 1000:
                    raise ProviderError("LIMIT_EXCEEDED")
                if c.get("includeViewDefinitions"):
                    try:
                        cursor.execute(
                            "SELECT TABLE_CATALOG,TABLE_SCHEMA,TABLE_NAME,VIEW_DEFINITION FROM "
                            + db
                            + ".INFORMATION_SCHEMA.VIEWS LIMIT 1001",
                            timeout=20,
                        )
                        for cat, schema, table, definition in cursor.fetchmany(1000):
                            key = ".".join(
                                '"' + v.replace('"', '""') + '"'
                                if not re.fullmatch(r"[A-Z_][A-Z0-9_$]*", v)
                                else v
                                for v in (cat, schema, table)
                            )
                            if key in groups and definition:
                                if len(definition) <= 100000:
                                    groups[key].metadata["viewDefinition"] = definition
                                else:
                                    warnings.append("Oversized view definition omitted")
                    except snowflake.connector.errors.Error:
                        warnings.append(
                            "View definitions unavailable to the configured role; column inventory retained"
                        )
                return MetadataBatch(
                    datasets=list(groups.values()),
                    warnings=warnings,
                    snapshotComplete=not warnings,
                )
    except ProviderError:
        raise
    except TimeoutError:
        raise ProviderError("NETWORK_TIMEOUT") from None
    except (ValueError, TypeError):
        raise ProviderError("INVALID_PROVIDER_RESPONSE") from None
    except snowflake.connector.errors.Error as e:
        state = getattr(e, "sqlstate", "") or ""
        raise ProviderError(
            "AUTHENTICATION_FAILED"
            if state.startswith("28")
            else "PERMISSION_DENIED"
            if state == "42501"
            else "RATE_LIMITED"
            if state in {"53300", "53200"}
            else "NETWORK_TIMEOUT"
            if state.startswith("08")
            else "UNAVAILABLE"
        ) from None


def acquire(kind, configuration, credentials, test=False):
    try:
        validate(kind, configuration, credentials)
        http = BoundedHttp()
        try:
            if kind == "AMAZON_S3":
                result = s3(configuration, credentials, test)
            elif kind == "SNOWFLAKE":
                result = snowflake(configuration, credentials, test)
            elif kind == "GITHUB_SQL":
                result = github(configuration, credentials, test, http)
            else:
                result = powerbi(configuration, credentials, test, http)
            scope = {
                k: v
                for k, v in configuration.items()
                if k not in {"maxObjects", "maxFiles", "includeViewDefinitions"}
            }
            result.inventoryScope = hashlib.sha256(
                json.dumps(
                    [kind, scope], sort_keys=True, separators=(",", ":")
                ).encode()
            ).hexdigest()
            return result
        finally:
            http.close()
    except ProviderError:
        raise
    except ImportError:
        raise ProviderError("DRIVER_UNAVAILABLE") from None
    except Exception:
        raise ProviderError("INVALID_PROVIDER_RESPONSE") from None
