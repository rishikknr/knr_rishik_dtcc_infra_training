import base64
import io
import unittest
import zipfile
from datetime import datetime, timezone
from unittest.mock import Mock, patch

from connectors.providers import BoundedHttp, ProviderError, acquire
from fastapi import HTTPException
from parsers.files import parse_file
from parsers.sql import validate_sql


class FileTests(unittest.TestCase):
    def test_arbitrary_headers(self):
        b = parse_file(
            b"db,sch,obj,field,type\nSHOP,RAW,ORDERS,ID,NUMBER",
            "GENERIC_CSV",
            {
                "db": "database",
                "sch": "schema",
                "obj": "table",
                "field": "column",
                "type": "data_type",
            },
        )
        self.assertEqual(b.datasets[0].externalKey, "SHOP.RAW.ORDERS")
        self.assertEqual(b.datasets[0].columns[0].name, "ID")

    def test_bom_quotes_duplicates(self):
        b = parse_file(
            '\ufeffexternal_key,name,column,data_type\nx,X,ID,"NUMBER(10,2)"\nx,X,ID,"NUMBER(10,2)"'.encode(),
            "GENERIC_CSV",
        )
        self.assertEqual(len(b.datasets[0].columns), 1)
        self.assertEqual(len(b.warnings), 1)

    def test_conflict(self):
        with self.assertRaises(HTTPException):
            parse_file(
                b"external_key,name,column,data_type\nx,X,ID,NUMBER\nx,X,ID,VARCHAR",
                "GENERIC_CSV",
            )

    def test_duplicate_mapping(self):
        with self.assertRaises(HTTPException):
            parse_file(b"a,b\nx,y", "GENERIC_CSV", {"a": "name", "b": "name"})

    def test_missing_identity(self):
        with self.assertRaises(HTTPException):
            parse_file(b"column,data_type\nID,NUMBER", "GENERIC_CSV")

    def test_inventory(self):
        b = parse_file(
            b"bucket,key,size\nexample-bucket,orders/a.csv,22", "S3_INVENTORY"
        )
        self.assertEqual(b.datasets[0].assetType, "FILE")
        self.assertEqual(b.datasets[0].columns, [])

    def test_json(self):
        b = parse_file(
            b'{"datasets":[{"externalKey":"r1","name":"Report","assetType":"REPORT"}]}',
            "METADATA_JSON",
        )
        self.assertEqual(b.datasets[0].assetType, "REPORT")

    def test_json_scope_injection(self):
        with self.assertRaises(Exception):
            parse_file(b'{"organizationId":"evil","datasets":[]}', "METADATA_JSON")

    def test_depth(self):
        with self.assertRaises(HTTPException):
            parse_file(("[" * 21 + "0" + "]" * 21).encode(), "METADATA_JSON")

    def test_sql(self):
        b = parse_file(b"select id from raw.orders", "SQL", filename="fact.sql")
        self.assertEqual(b.datasets[0].metadata["validation"]["status"], "PARSED")
        self.assertEqual(b.datasets[0].metadata["validation"]["outputs"], [])

    def archive(self, name, data=b"select 1"):
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr(name, data)
        return out.getvalue()

    def test_zip(self):
        self.assertEqual(
            len(parse_file(self.archive("dir/x.sql"), "SQL_ZIP").datasets), 1
        )

    def test_zip_traversal(self):
        with self.assertRaises(HTTPException):
            parse_file(self.archive("../x.sql"), "SQL_ZIP")

    def test_zip_unsafe_type(self):
        with self.assertRaises(HTTPException):
            parse_file(self.archive("x.py"), "SQL_ZIP")

    def test_zip_bomb(self):
        with self.assertRaises(HTTPException):
            parse_file(self.archive("x.sql", b" " * 100000), "SQL_ZIP")

    def test_utf8(self):
        with self.assertRaises(Exception):
            parse_file(b"\xff", "GENERIC_CSV")


class SqlTests(unittest.TestCase):
    def test_cte_alias_join(self):
        v = validate_sql(
            "WITH x AS (SELECT id FROM raw.orders) SELECT x.id FROM x JOIN raw.customers c ON c.id=x.id"
        )
        self.assertEqual(v["status"], "PARSED")
        self.assertEqual(v["inputs"], ["raw.customers", "raw.orders"])

    def test_ctas(self):
        self.assertEqual(
            validate_sql("create table curated.orders as select * from raw.orders")[
                "outputs"
            ],
            ["curated.orders"],
        )

    def test_macros(self):
        self.assertEqual(
            validate_sql('select * from {{ ref("x") }}')["status"], "UNRESOLVED"
        )

    def test_invalid(self):
        self.assertEqual(validate_sql("select ( from")["status"], "INVALID")

    def test_unsupported(self):
        self.assertEqual(
            validate_sql("delete from raw.orders")["status"], "UNSUPPORTED"
        )

    def test_dialect(self):
        self.assertEqual(validate_sql("select 1", "dax")["status"], "INVALID")


class ProviderTests(unittest.TestCase):
    def error(self, kind, c, s, code):
        with self.assertRaises(ProviderError) as e:
            acquire(kind, c, s)
        self.assertEqual(e.exception.code, code)

    def test_no_ambient_aws(self):
        self.error(
            "AMAZON_S3",
            {"region": "us-east-1", "bucket": "example-bucket"},
            {},
            "CREDENTIALS_REQUIRED",
        )

    def test_custom_endpoint(self):
        self.error(
            "GITHUB_SQL",
            {
                "owner": "a",
                "repository": "b",
                "revision": "main",
                "url": "http://127.0.0.1",
            },
            {"token": "x"},
            "CONFIGURATION_ERROR",
        )

    def test_snowflake_host(self):
        self.error(
            "SNOWFLAKE",
            {"account": "evil/host", "database": "X", "username": "u"},
            {"privateKey": "x"},
            "CONFIGURATION_ERROR",
        )

    def test_s3_pagination(self):
        obj = lambda k: {
            "Key": k,
            "Size": 1,
            "LastModified": datetime.now(timezone.utc),
            "ETag": "tag",
        }
        client = Mock()
        client.list_objects_v2.side_effect = [
            {
                "Contents": [obj("a")],
                "IsTruncated": True,
                "NextContinuationToken": "next",
            },
            {"Contents": [obj("b")]},
        ]
        with patch("boto3.client", return_value=client) as f:
            b = acquire(
                "AMAZON_S3",
                {"region": "us-east-1", "bucket": "example-bucket"},
                {"accessKeyId": "test", "secretAccessKey": "secret"},
            )
        self.assertEqual(len(b.datasets), 2)
        self.assertEqual(
            client.list_objects_v2.call_args.kwargs["ContinuationToken"], "next"
        )
        self.assertEqual(f.call_args.kwargs["aws_access_key_id"], "test")

    def test_s3_empty(self):
        with patch("boto3.client") as f:
            f.return_value.list_objects_v2.return_value = {}
            self.assertEqual(
                acquire(
                    "AMAZON_S3",
                    {"region": "us-east-1", "bucket": "example-bucket"},
                    {"accessKeyId": "test", "secretAccessKey": "secret"},
                ).datasets,
                [],
            )

    def test_s3_denied(self):
        from botocore.exceptions import ClientError

        with patch("boto3.client") as f:
            f.return_value.list_objects_v2.side_effect = ClientError(
                {"Error": {"Code": "AccessDenied"}}, "ListObjectsV2"
            )
            self.error(
                "AMAZON_S3",
                {"region": "us-east-1", "bucket": "example-bucket"},
                {"accessKeyId": "test", "secretAccessKey": "secret"},
                "PERMISSION_DENIED",
            )

    def test_s3_partial(self):
        c = Mock()
        c.list_objects_v2.return_value = {
            "Contents": [
                {"Key": "a", "Size": 1, "LastModified": datetime.now(timezone.utc)}
            ],
            "IsTruncated": True,
            "NextContinuationToken": "n",
        }
        with patch("boto3.client", return_value=c):
            b = acquire(
                "AMAZON_S3",
                {"region": "us-east-1", "bucket": "example-bucket", "maxObjects": 1},
                {"accessKeyId": "test", "secretAccessKey": "secret"},
            )
        self.assertTrue(b.warnings)

    def test_github_adapter(self):
        with patch.object(
            BoundedHttp,
            "json",
            side_effect=[
                {"sha": "abc", "commit": {"tree": {"sha": "tree"}}},
                {
                    "tree": [
                        {
                            "path": "x.sql",
                            "type": "blob",
                            "mode": "100644",
                            "size": 10,
                            "sha": "blob",
                        }
                    ]
                },
                {
                    "encoding": "base64",
                    "content": base64.b64encode(b"select * from raw.orders").decode(),
                },
            ],
        ):
            b = acquire(
                "GITHUB_SQL",
                {"owner": "o", "repository": "r", "revision": "main"},
                {"token": "secret"},
            )
        self.assertEqual(b.datasets[0].metadata["revision"], "abc")
        self.assertNotIn("secret", b.model_dump_json())

    def test_github_auth(self):
        with patch.object(
            BoundedHttp, "json", side_effect=ProviderError("AUTHENTICATION_FAILED")
        ):
            self.error(
                "GITHUB_SQL",
                {"owner": "a", "repository": "b", "revision": "main"},
                {"token": "x"},
                "AUTHENTICATION_FAILED",
            )

    def test_powerbi_token_not_enough(self):
        with patch.object(
            BoundedHttp,
            "json",
            side_effect=[{"access_token": "token"}, ProviderError("PERMISSION_DENIED")],
        ):
            self.error(
                "POWER_BI",
                {
                    "tenantId": "1" * 36,
                    "clientId": "2" * 36,
                    "workspaceIds": ["3" * 36],
                },
                {"clientSecret": "secret"},
                "PERMISSION_DENIED",
            )

    def test_powerbi_inventory(self):
        with patch.object(
            BoundedHttp,
            "json",
            side_effect=[
                {"access_token": "token"},
                {"value": [{"id": "r", "name": "Sales", "datasetId": "d"}]},
                {"value": [{"id": "d", "name": "Model"}]},
            ],
        ):
            b = acquire(
                "POWER_BI",
                {
                    "tenantId": "1" * 36,
                    "clientId": "2" * 36,
                    "workspaceIds": ["3" * 36],
                },
                {"clientSecret": "secret"},
            )
        self.assertEqual(
            [x.assetType for x in b.datasets], ["REPORT", "SEMANTIC_MODEL"]
        )
        self.assertIn("DAX", b.warnings[0])

    def test_ssrf(self):
        h = BoundedHttp()
        try:
            with self.assertRaises(ProviderError):
                h.json("http://127.0.0.1/private")
        finally:
            h.close()

    def test_timeout(self):
        import httpx

        h = BoundedHttp()
        try:
            with patch.object(
                h.client, "stream", side_effect=httpx.ReadTimeout("secret")
            ):
                with self.assertRaises(ProviderError) as e:
                    h.json("https://api.github.com/repos/a/b")
            self.assertEqual(e.exception.code, "NETWORK_TIMEOUT")
        finally:
            h.close()

    def test_rate_limit(self):
        h = BoundedHttp()
        r = Mock()
        r.status_code = 429
        r.__enter__ = Mock(return_value=r)
        r.__exit__ = Mock(return_value=None)
        try:
            with patch.object(h.client, "stream", return_value=r):
                with self.assertRaises(ProviderError) as e:
                    h.json("https://api.github.com/repos/a/b")
            self.assertEqual(e.exception.code, "RATE_LIMITED")
        finally:
            h.close()


class SnowflakeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import rsa

        cls.key = (
            rsa.generate_private_key(public_exponent=65537, key_size=2048)
            .private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            )
            .decode()
        )

    def config(self):
        return {
            "account": "org-account",
            "username": "trace",
            "database": "SHOP",
            "schemas": ["RAW"],
        }

    def test_metadata_query_only_and_nullability(self):
        with patch("snowflake.connector.connect") as connect:
            cursor = connect.return_value.__enter__.return_value.cursor.return_value.__enter__.return_value
            cursor.fetchmany.return_value = [
                (
                    "SHOP",
                    "RAW",
                    "ORDERS",
                    "ID",
                    "NUMBER",
                    "NO",
                    "identifier",
                    1,
                    "BASE TABLE",
                    "Orders",
                    "ROLE",
                )
            ]
            batch = acquire("SNOWFLAKE", self.config(), {"privateKey": self.key})
            query = cursor.execute.call_args.args[0]
            self.assertIn("INFORMATION_SCHEMA.COLUMNS", query)
            self.assertNotIn("SELECT *", query)
            self.assertFalse(batch.datasets[0].columns[0].nullable)
            self.assertEqual(batch.datasets[0].metadata["owner"], "ROLE")

    def test_empty_schema(self):
        with patch("snowflake.connector.connect") as connect:
            connect.return_value.__enter__.return_value.cursor.return_value.__enter__.return_value.fetchmany.return_value = []
            self.assertEqual(
                acquire("SNOWFLAKE", self.config(), {"privateKey": self.key}).datasets,
                [],
            )

    def test_authentication_failure(self):
        import snowflake.connector.errors

        with patch(
            "snowflake.connector.connect",
            side_effect=snowflake.connector.errors.DatabaseError(
                msg="private detail", sqlstate="28000"
            ),
        ):
            with self.assertRaises(ProviderError) as e:
                acquire("SNOWFLAKE", self.config(), {"privateKey": self.key})
        self.assertEqual(e.exception.code, "AUTHENTICATION_FAILED")

    def test_permission_failure(self):
        import snowflake.connector.errors

        with patch(
            "snowflake.connector.connect",
            side_effect=snowflake.connector.errors.DatabaseError(
                msg="secret detail", sqlstate="42501"
            ),
        ):
            with self.assertRaises(ProviderError) as e:
                acquire("SNOWFLAKE", self.config(), {"privateKey": self.key})
        self.assertEqual(e.exception.code, "PERMISSION_DENIED")


class ReportingMappingTests(unittest.TestCase):
    def test_export_declarations_not_live(self):
        b = parse_file(
            b"report_name,visual_name,metric_name,measure_name\nExecutive Sales,Revenue Card,Net Revenue,Net Revenue",
            "POWERBI_MAPPING_CSV",
        )
        self.assertEqual(len(b.datasets), 3)
        self.assertEqual(len(b.relationships), 2)
        self.assertTrue(b.warnings)
        self.assertEqual(b.relationships[0].relationshipType, "REPORT_CONTAINS_VISUAL")

    def test_powerbi_live_model_reference(self):
        with patch.object(
            BoundedHttp,
            "json",
            side_effect=[
                {"access_token": "token"},
                {"value": [{"id": "r", "name": "Sales", "datasetId": "d"}]},
                {"value": [{"id": "d", "name": "Model"}]},
            ],
        ):
            b = acquire(
                "POWER_BI",
                {
                    "tenantId": "1" * 36,
                    "clientId": "2" * 36,
                    "workspaceIds": ["3" * 36],
                },
                {"clientSecret": "secret"},
            )
        self.assertEqual(len(b.relationships), 1)
        self.assertEqual(b.relationships[0].relationshipType, "REPORT_USES_MODEL")
