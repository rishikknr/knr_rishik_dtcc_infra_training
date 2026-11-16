"""Explicit local provider simulation for browser acceptance only.

Run with: python -m uvicorn tests.acceptance_processor:app --host 127.0.0.1 --port 18000
Never use as the operator processor entry point. It invokes the real GitHub adapter
against deterministic responses for one reserved fixture repository only.
"""

import base64
import threading
import time
from unittest.mock import patch, Mock
from datetime import datetime, timezone
from botocore.exceptions import ClientError

import app as transport
from connectors.providers import BoundedHttp, ProviderError
from connectors.providers import acquire as real_acquire

app = transport.app
_lock = threading.Lock()
_attempts = {}


def simulated_acquire(kind, configuration, credentials, test=False):
    if kind == "AMAZON_S3" and configuration.get("bucket") in {"trace-fixture-inventory", "trace-fixture-denied"}:
        client = Mock()
        if configuration["bucket"] == "trace-fixture-denied":
            client.list_objects_v2.side_effect = ClientError({"Error": {"Code": "AccessDenied", "Message": "controlled provider diagnostic"}}, "ListObjectsV2")
        else:
            prefix = configuration.get("prefix", "")
            client.list_objects_v2.return_value = {"Contents": [
                {"Key": prefix + name, "Size": size, "LastModified": datetime(2026, 11, 11, tzinfo=timezone.utc), "ETag": '"controlled-etag"'}
                for name, size in [("orders.csv", 1234), ("customers.csv", 5678)][:1 if test else 2]]}
        with patch("boto3.client", return_value=client):
            result = real_acquire(kind, configuration, credentials, test)
        result.warnings.append("CONTROLLED S3 SDK SIMULATION — NOT LIVE AWS VERIFICATION")
        return result
    if (
        kind != "GITHUB_SQL"
        or configuration.get("owner") != "trace-fixture"
        or configuration.get("repository") != "controlled-metadata"
    ):
        return real_acquire(kind, configuration, credentials, test)
    revision = configuration.get("revision", "stable")
    with _lock:
        _attempts[revision] = _attempts.get(revision, 0) + 1
        attempt = _attempts[revision]
    if revision == "retry-first" and attempt == 1:
        raise ProviderError("UNAVAILABLE")
    if revision.startswith("slow"):
        time.sleep(12)
    commit = {
        "sha": "controlled-commit",
        "commit": {"tree": {"sha": "controlled-tree"}},
    }
    tree = {
        "tree": [
            {
                "path": "fact_orders.sql",
                "type": "blob",
                "mode": "100644",
                "size": 60,
                "sha": "controlled-blob",
            }
        ]
    }
    blob = {
        "encoding": "base64",
        "content": base64.b64encode(
            b"SELECT gross_amount AS net_amount FROM raw.orders;"
        ).decode(),
    }

    def response(url, headers=None, data=None):
        if "/commits/" in url:
            return commit
        if "/git/trees/" in url:
            return tree
        if "/git/blobs/" in url:
            return blob
        raise ProviderError("INVALID_PROVIDER_RESPONSE")

    # All other configured sources remain actual adapters. This fixture scope is visibly labeled.
    with patch.object(BoundedHttp, "json", side_effect=response):
        result = real_acquire(kind, configuration, credentials, test)
    result.warnings.append(
        "CONTROLLED PROVIDER SIMULATION — NOT LIVE VENDOR VERIFICATION"
    )
    return result


transport.acquire = simulated_acquire
