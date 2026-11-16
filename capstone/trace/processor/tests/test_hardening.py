"""Regression evidence uses controlled transports, never live provider identities."""

import base64
import unittest
from unittest.mock import patch

from tests import test_provider_matrix as provider_matrix
from app import app
from connectors.providers import BoundedHttp, ProviderError, acquire
from fastapi import HTTPException
from fastapi.testclient import TestClient
from parsers.files import parse_file
from pydantic import ValidationError


class HardeningTests(unittest.TestCase):
    def test_normalized_header_collision_is_rejected(self):
        with self.assertRaises(HTTPException):
            parse_file(b"external_key,name,NAME\nx,First,Second", "GENERIC_CSV")

    def test_mapping_cannot_overwrite_an_existing_canonical_column(self):
        with self.assertRaises(HTTPException):
            parse_file(
                b"external_key,title,name\nx,First,Second",
                "GENERIC_CSV",
                {"title": "name"},
            )

    def test_reporting_export_supports_arbitrary_source_headers(self):
        result = parse_file(
            b"Report,Visual,Measure\nSales,Card,Revenue",
            "POWERBI_MAPPING_CSV",
            {
                "Report": "report_name",
                "Visual": "visual_name",
                "Measure": "measure_name",
            },
        )
        self.assertEqual(len(result.relationships), 2)
        self.assertFalse(result.snapshotComplete)

    def test_s3_missing_continuation_is_not_a_complete_inventory(self):
        with patch("boto3.client") as client:
            client.return_value.list_objects_v2.return_value = {"IsTruncated": True}
            with self.assertRaises(ProviderError) as error:
                acquire(
                    "AMAZON_S3",
                    {"region": "us-east-1", "bucket": "test-bucket"},
                    {"accessKeyId": "test", "secretAccessKey": "test"},
                )
            self.assertEqual(error.exception.code, "INVALID_PROVIDER_RESPONSE")

    def test_s3_repeated_continuation_is_bounded(self):
        with patch("boto3.client") as client:
            client.return_value.list_objects_v2.return_value = {
                "IsTruncated": True,
                "NextContinuationToken": "same",
            }
            with self.assertRaises(ProviderError):
                acquire(
                    "AMAZON_S3",
                    {"region": "us-east-1", "bucket": "test-bucket"},
                    {"accessKeyId": "test", "secretAccessKey": "test"},
                )
            self.assertEqual(client.return_value.list_objects_v2.call_count, 2)

    def test_inventory_scope_changes_with_prefix_and_stays_stable_when_limits_change(
        self,
    ):
        with patch("boto3.client") as client:
            client.return_value.list_objects_v2.return_value = {}
            config = {"region": "us-east-1", "bucket": "test-bucket", "prefix": "one/"}
            secret = {"accessKeyId": "test", "secretAccessKey": "test"}
            first = acquire("AMAZON_S3", config, secret)
            self.assertTrue(first.snapshotComplete)
            second = acquire("AMAZON_S3", {**config, "maxObjects": 1}, secret)
            self.assertEqual(first.inventoryScope, second.inventoryScope)
            third = acquire("AMAZON_S3", {**config, "prefix": "two/"}, secret)
            self.assertNotEqual(first.inventoryScope, third.inventoryScope)

    def test_github_directory_scope_excludes_sibling_prefix_and_accepts_sql_extension_case(
        self,
    ):
        commit = {"sha": "commit", "commit": {"tree": {"sha": "tree"}}}
        entries = [
            {"path": name, "sha": "blob", "size": 10, "type": "blob", "mode": "100644"}
            for name in ["models/a.SQL", "models-old/b.sql"]
        ]
        blob = {
            "encoding": "base64",
            "content": base64.b64encode(b"SELECT id FROM raw.orders").decode(),
        }
        with patch.object(
            BoundedHttp, "json", side_effect=[commit, {"tree": entries}, blob]
        ) as http:
            batch = acquire(
                "GITHUB_SQL",
                {
                    "owner": "test",
                    "repository": "repo",
                    "revision": "main",
                    "prefix": "models",
                },
                {"token": "test"},
            )
            self.assertEqual([a.name for a in batch.datasets], ["models/a.SQL"])
            self.assertEqual(http.call_count, 3)
            self.assertTrue(batch.snapshotComplete)

    def test_powerbi_informational_warning_does_not_make_complete_metadata_partial(
        self,
    ):
        matrix = provider_matrix.ProviderMatrix()
        matrix.pem = "unused"
        with matrix.scenario("POWER_BI", "success") as (config, secret):
            batch = acquire("POWER_BI", config, secret)
            self.assertTrue(batch.warnings)
            self.assertTrue(batch.snapshotComplete)

    def test_powerbi_unresolved_model_cannot_retire_prior_relationships(self):
        matrix = provider_matrix.ProviderMatrix()
        matrix.pem = "unused"
        with matrix.scenario("POWER_BI", "partial") as (config, secret):
            batch = acquire("POWER_BI", config, secret)
            self.assertFalse(batch.snapshotComplete)
            self.assertEqual(batch.relationships, [])

    def test_internal_routes_reject_non_object_payloads_without_server_errors(self):
        with patch.dict("os.environ", {"TRACE_PROCESSOR_TOKEN": "private-test-token"}):
            client = TestClient(app)
            for route in ["parse", "sql/validate", "acquire"]:
                for value in [[], None, "text"]:
                    response = client.post(
                        "/internal/" + route,
                        json=value,
                        headers={"X-Trace-Processor-Token": "private-test-token"},
                    )
                    self.assertEqual(
                        response.status_code, 422, (route, value, response.text)
                    )

    def test_invalid_provider_field_types_are_configuration_failures(self):
        base = {"owner": "test", "repository": "repo", "revision": "main"}
        for update in [
            {"revision": True},
            {"maxFiles": "many"},
            {"maxFiles": True},
            {"dialect": "not-a-dialect"},
        ]:
            with self.subTest(update=update), self.assertRaises(ProviderError) as error:
                acquire("GITHUB_SQL", {**base, **update}, {"token": "synthetic"})
            self.assertEqual(error.exception.code, "CONFIGURATION_ERROR")

    def test_json_duplicate_fields_are_not_silently_overwritten(self):
        with self.assertRaises(HTTPException):
            parse_file(
                b'{"datasets":[{"externalKey":"old","externalKey":"new","name":"Name"}]}',
                "METADATA_JSON",
            )

    def test_nonfinite_json_is_an_actionable_file_error(self):
        with self.assertRaises(HTTPException):
            parse_file(
                b'{"datasets":[{"externalKey":"id","name":"Name","metadata":{"number":NaN}}]}',
                "METADATA_JSON",
            )

    def test_import_batch_limits_fail_before_the_java_transport_boundary(self):
        import json

        for dataset in [
            {"externalKey": "id", "name": " "},
            {
                "externalKey": "id",
                "name": "Name",
                "metadata": {str(i): i for i in range(31)},
            },
            {
                "externalKey": "id",
                "name": "Name",
                "columns": [
                    {"name": f"C{i}", "dataType": "NUMBER"} for i in range(2001)
                ],
            },
        ]:
            with (
                self.subTest(dataset=dataset.get("name")),
                self.assertRaises(Exception),
            ):
                parse_file(
                    json.dumps({"datasets": [dataset]}).encode(), "METADATA_JSON"
                )

    def test_unresolved_imported_relationship_is_a_file_error(self):
        import json

        payload = {
            "datasets": [{"externalKey": "r", "name": "Report", "assetType": "REPORT"}],
            "relationships": [
                {
                    "sourceKey": "missing",
                    "sourceType": "SEMANTIC_MODEL",
                    "targetKey": "r",
                    "targetType": "REPORT",
                    "relationshipType": "REPORT_USES_MODEL",
                    "evidenceRef": "Declared export",
                }
            ],
        }
        with self.assertRaises(HTTPException):
            parse_file(json.dumps(payload).encode(), "METADATA_JSON")
