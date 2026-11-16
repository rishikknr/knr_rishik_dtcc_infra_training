"""Provider-specific deterministic adapter coverage. No test is evidence of live cloud access."""

import base64
from contextlib import contextmanager
from datetime import datetime, timezone
from unittest import TestCase
from unittest.mock import patch

import httpx

from connectors.providers import BoundedHttp, ProviderError, acquire


class ProviderMatrix(TestCase):
    @classmethod
    def setUpClass(cls):
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import rsa

        cls.pem = (
            rsa.generate_private_key(public_exponent=65537, key_size=2048)
            .private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            )
            .decode()
        )

    def settings(self, provider):
        return {
            "SNOWFLAKE": (
                {"account": "org-account", "username": "trace", "database": "SHOP"},
                {"privateKey": self.pem},
            ),
            "AMAZON_S3": (
                {"region": "us-east-1", "bucket": "scoped-bucket"},
                {"accessKeyId": "test-key", "secretAccessKey": "never-log-secret"},
            ),
            "GITHUB_SQL": (
                {"owner": "owner", "repository": "repo", "revision": "main"},
                {"token": "never-log-secret"},
            ),
            "POWER_BI": (
                {
                    "tenantId": "1" * 36,
                    "clientId": "2" * 36,
                    "workspaceIds": ["3" * 36],
                },
                {"clientSecret": "never-log-secret"},
            ),
        }[provider]

    def row(self, i=1):
        return (
            "SHOP",
            "RAW",
            "ORDERS",
            f"C{i}",
            "NUMBER",
            "NO",
            "column",
            i,
            "BASE TABLE",
            "table",
            "ROLE",
        )

    def object(self, key="a.csv"):
        return {
            "Key": key,
            "Size": 12,
            "LastModified": datetime.now(timezone.utc),
            "ETag": "opaque",
        }

    @contextmanager
    def scenario(self, provider, scenario):
        c, s = self.settings(provider)
        if provider == "SNOWFLAKE":
            import snowflake.connector.errors as errors

            with patch("snowflake.connector.connect") as connect:
                cursor = connect.return_value.__enter__.return_value.cursor.return_value.__enter__.return_value
                states = {
                    "auth": "28000",
                    "permission": "42501",
                    "rate": "53300",
                    "timeout": "08001",
                }
                if scenario in states:
                    connect.side_effect = errors.DatabaseError(
                        msg="never-log-secret", sqlstate=states[scenario]
                    )
                elif scenario == "invalid":
                    cursor.fetchmany.return_value = [("broken",)]
                elif scenario == "empty":
                    cursor.fetchmany.return_value = []
                elif scenario == "pages":
                    cursor.fetchmany.side_effect = [
                        [self.row(i) for i in range(1, 1001)],
                        [self.row(1001)],
                    ]
                elif scenario == "partial":
                    c["includeViewDefinitions"] = True
                    cursor.fetchmany.return_value = [self.row()]
                    cursor.execute.side_effect = [
                        None,
                        errors.DatabaseError(msg="secret", sqlstate="42501"),
                    ]
                else:
                    cursor.fetchmany.return_value = [self.row()]
                yield c, s
        elif provider == "AMAZON_S3":
            from botocore.exceptions import ClientError

            with patch("boto3.client") as client:
                target = client.return_value.list_objects_v2
                codes = {
                    "auth": "InvalidAccessKeyId",
                    "permission": "AccessDenied",
                    "rate": "SlowDown",
                }
                if scenario in codes:
                    target.side_effect = ClientError(
                        {"Error": {"Code": codes[scenario]}}, "ListObjectsV2"
                    )
                elif scenario == "timeout":
                    target.side_effect = TimeoutError("never-log-secret")
                elif scenario == "invalid":
                    target.return_value = {"Contents": [{"bad": "response"}]}
                elif scenario == "empty":
                    target.return_value = {}
                elif scenario == "pages":
                    target.side_effect = [
                        {
                            "Contents": [self.object()],
                            "IsTruncated": True,
                            "NextContinuationToken": "next",
                        },
                        {"Contents": [self.object("b.csv")]},
                    ]
                elif scenario == "partial":
                    c["maxObjects"] = 1
                    target.return_value = {
                        "Contents": [self.object()],
                        "IsTruncated": True,
                        "NextContinuationToken": "next",
                    }
                else:
                    target.return_value = {"Contents": [self.object()]}
                yield c, s
        else:
            commit = {"sha": "commit", "commit": {"tree": {"sha": "tree"}}}
            blob = {
                "encoding": "base64",
                "content": base64.b64encode(b"SELECT id FROM raw.orders").decode(),
            }
            entry = {
                "path": "x.sql",
                "type": "blob",
                "mode": "100644",
                "size": 10,
                "sha": "blob",
            }
            if scenario in {"auth", "permission", "rate", "timeout"}:

                def reply(request):
                    oauth = request.url.host == "login.microsoftonline.com"
                    if scenario == "auth":
                        return httpx.Response(
                            400 if oauth else 401, text="never-log-secret"
                        )
                    if oauth:
                        return httpx.Response(
                            200, json={"access_token": "never-log-secret"}
                        )
                    if scenario == "timeout":
                        raise httpx.ReadTimeout("never-log-secret", request=request)
                    return httpx.Response(
                        403 if scenario == "permission" else 429,
                        text="never-log-secret",
                    )

                with httpx.Client(transport=httpx.MockTransport(reply)) as client:
                    with patch(
                        "connectors.providers.httpx.Client", return_value=client
                    ):
                        yield c, s
                return
            if scenario == "invalid":
                responses = [{"unexpected": "payload"}]
            elif provider == "GITHUB_SQL":
                responses = (
                    [commit, {"tree": []}]
                    if scenario == "empty"
                    else [
                        commit,
                        {"tree": [entry], "truncated": scenario == "partial"},
                        blob,
                    ]
                )
            else:
                root = "https://api.powerbi.com/v1.0/myorg/groups/" + "3" * 36
                if scenario == "empty":
                    responses = [
                        {"access_token": "never-log-secret"},
                        {"value": []},
                        {"value": []},
                    ]
                elif scenario == "pages":
                    responses = [
                        {"access_token": "never-log-secret"},
                        {
                            "value": [{"id": "r", "name": "Report", "datasetId": "d"}],
                            "@odata.nextLink": root + "/reports?next=2",
                        },
                        {"value": [{"id": "r2", "name": "Second report"}]},
                        {"value": [{"id": "d", "name": "Model"}]},
                    ]
                else:
                    responses = [
                        {"access_token": "never-log-secret"},
                        {
                            "value": [
                                {
                                    "id": "r",
                                    "name": "Report",
                                    "datasetId": "missing"
                                    if scenario == "partial"
                                    else "d",
                                }
                            ]
                        },
                        {"value": [{"id": "d", "name": "Model"}]},
                    ]
            with patch.object(BoundedHttp, "json", side_effect=responses):
                yield c, s

    def run_case(self, provider, scenario, test=False):
        with self.scenario(provider, scenario) as (config, secrets):
            return acquire(provider, config, secrets, test)


# Distinct tests identify provider and outcome in reports instead of hiding an entire matrix in one test.
def success(provider, scenario):
    def test(self):
        batch = self.run_case(provider, scenario)
        if scenario == "empty":
            self.assertEqual(batch.datasets, [])
        else:
            self.assertTrue(batch.datasets)
        if scenario == "partial":
            self.assertTrue(batch.warnings)
        self.assertNotIn("never-log-secret", batch.model_dump_json())

    return test


def failure(provider, scenario, code):
    def test(self):
        with self.assertRaises(ProviderError) as raised:
            self.run_case(provider, scenario)
        self.assertEqual(raised.exception.code, code)
        self.assertNotIn("never-log-secret", str(raised.exception))

    return test


for provider in ("SNOWFLAKE", "AMAZON_S3", "GITHUB_SQL", "POWER_BI"):
    for scenario in ("success", "empty", "partial"):
        setattr(
            ProviderMatrix,
            f"test_{provider.lower()}_{scenario}",
            success(provider, scenario),
        )
    for scenario, code in [
        ("auth", "AUTHENTICATION_FAILED"),
        ("permission", "PERMISSION_DENIED"),
        ("rate", "RATE_LIMITED"),
        ("timeout", "NETWORK_TIMEOUT"),
        ("invalid", "INVALID_PROVIDER_RESPONSE"),
    ]:
        setattr(
            ProviderMatrix,
            f"test_{provider.lower()}_{scenario}",
            failure(provider, scenario, code),
        )
for provider in ("SNOWFLAKE", "AMAZON_S3", "POWER_BI"):
    setattr(
        ProviderMatrix,
        f"test_{provider.lower()}_pagination",
        success(provider, "pages"),
    )
