"""S3 SDK boundary regressions; deterministic responses are not live-provider evidence."""

import os
import unittest
from datetime import datetime, timezone
from unittest.mock import patch, Mock
import boto3
from botocore.exceptions import ClientError, ReadTimeoutError
from fastapi.testclient import TestClient
from connectors.providers import acquire, ProviderError
from app import app


class S3WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.config = dict(
            region="ap-south-1",
            bucket="novamart-trace-test-space",
            prefix="novamart/baseline/",
            maxObjects=1000,
        )
        self.secrets = dict(
            accessKeyId="controlled-key", secretAccessKey="DO-NOT-RELAY-secret"
        )
        self.client = Mock()

    def object(self, n):
        return dict(
            Key=f"novamart/baseline/{n}.csv",
            Size=12,
            LastModified=datetime(2026, 11, 11, tzinfo=timezone.utc),
            ETag='"opaque"',
        )

    def call(self, test=False):
        with patch("boto3.client", return_value=self.client) as factory:
            result = acquire("AMAZON_S3", self.config, self.secrets, test)
        self.assertEqual(
            factory.call_args.kwargs["aws_access_key_id"], self.secrets["accessKeyId"]
        )
        self.assertEqual(factory.call_args.kwargs["region_name"], "ap-south-1")
        self.assertTrue(
            factory.call_args.kwargs["config"].ignore_configured_endpoint_urls
        )
        self.client.close.assert_called_once()
        return result

    def test_saved_novamart_limit_fails_before_sdk(self):
        self.config["maxObjects"] = 10000000
        with patch("boto3.client") as factory, self.assertRaises(ProviderError) as e:
            acquire("AMAZON_S3", self.config, self.secrets, True)
        self.assertEqual(e.exception.reason_code, "DISCOVERY_LIMIT_INVALID")
        factory.assert_not_called()

    def test_22_object_inventory_and_real_test_request(self):
        self.client.list_objects_v2.return_value = dict(
            Contents=[self.object(i) for i in range(22)]
        )
        result = self.call()
        self.assertEqual(len(result.datasets), 22)
        self.assertTrue(result.snapshotComplete)
        self.assertEqual(result.datasets[0].metadata["key"], "novamart/baseline/0.csv")
        self.assertNotIn(self.secrets["secretAccessKey"], result.model_dump_json())
        self.client.list_objects_v2.assert_called_once_with(
            Bucket=self.config["bucket"], Prefix=self.config["prefix"], MaxKeys=1000
        )

    def test_test_requests_listing_and_returns_no_inventory(self):
        self.client.list_objects_v2.return_value = dict(
            Contents=[self.object(0)], IsTruncated=True, NextContinuationToken="next"
        )
        result = self.call(True)
        self.assertEqual(result.datasets, [])
        self.client.list_objects_v2.assert_called_once_with(
            Bucket=self.config["bucket"], Prefix=self.config["prefix"], MaxKeys=1
        )

    def test_pagination_carries_exact_prefix(self):
        self.client.list_objects_v2.side_effect = [
            dict(
                Contents=[self.object(0)],
                IsTruncated=True,
                NextContinuationToken="next",
            ),
            dict(Contents=[self.object(1)]),
        ]
        self.assertEqual(len(self.call().datasets), 2)
        self.assertEqual(
            self.client.list_objects_v2.call_args.kwargs["ContinuationToken"], "next"
        )
        self.assertEqual(
            self.client.list_objects_v2.call_args.kwargs["Prefix"],
            self.config["prefix"],
        )

    def test_partial_inventory_never_claims_complete(self):
        self.config["maxObjects"] = 1
        self.client.list_objects_v2.return_value = dict(
            Contents=[self.object(0)], IsTruncated=True, NextContinuationToken="next"
        )
        result = self.call()
        self.assertFalse(result.snapshotComplete)
        self.assertIn("partial", result.warnings[0])

    def test_zero_objects_is_accessible_but_not_fake_inventory(self):
        self.client.list_objects_v2.return_value = {}
        result = self.call()
        self.assertTrue(result.snapshotComplete)
        self.assertEqual(result.datasets, [])

    def test_unexpected_prefix_and_duplicate_tokens_reject_snapshot(self):
        for page in [
            dict(Contents=[{**self.object(0), "Key": "outside/private.csv"}]),
            dict(Contents=[], IsTruncated=True, NextContinuationToken="same"),
        ]:
            with self.subTest(page=page):
                self.client.list_objects_v2.return_value = page
                with (
                    patch("boto3.client", return_value=self.client),
                    self.assertRaises(ProviderError),
                ):
                    acquire("AMAZON_S3", self.config, self.secrets)

    def test_missing_and_whitespace_credentials(self):
        for secret, reason in [
            ({}, "CREDENTIALS_REQUIRED"),
            ({**self.secrets, "secretAccessKey": "secret\n"}, "CREDENTIAL_WHITESPACE"),
        ]:
            with (
                patch("boto3.client") as factory,
                self.assertRaises(ProviderError) as e,
            ):
                acquire("AMAZON_S3", self.config, secret, True)
            self.assertEqual(e.exception.reason_code, reason)
            factory.assert_not_called()

    def test_provider_error_categories_and_transport_redaction(self):
        cases = {
            "AccessDenied": ("PERMISSION_DENIED", "S3_LIST_ACCESS_DENIED"),
            "InvalidAccessKeyId": ("AUTHENTICATION_FAILED", "S3_INVALID_CREDENTIALS"),
            "SignatureDoesNotMatch": ("AUTHENTICATION_FAILED", "S3_SIGNATURE_MISMATCH"),
            "ExpiredToken": ("AUTHENTICATION_FAILED", "S3_SESSION_EXPIRED"),
            "PermanentRedirect": ("CONFIGURATION_ERROR", "S3_REGION_MISMATCH"),
            "AuthorizationHeaderMalformed": (
                "CONFIGURATION_ERROR",
                "S3_REGION_MISMATCH",
            ),
            "NoSuchBucket": ("CONFIGURATION_ERROR", "S3_BUCKET_NOT_FOUND"),
            "SlowDown": ("RATE_LIMITED", "S3_THROTTLED"),
            "RequestTimeTooSkewed": ("CONFIGURATION_ERROR", "S3_CLOCK_SKEW"),
            "SomethingUnknown": ("UNAVAILABLE", "S3_UNAVAILABLE"),
        }
        with (
            patch.dict(os.environ, {"TRACE_PROCESSOR_TOKEN": "controlled-token"}),
            TestClient(app) as transport,
        ):
            for aws, (status, reason) in cases.items():
                self.client.list_objects_v2.side_effect = ClientError(
                    {
                        "Error": {
                            "Code": aws,
                            "Message": self.secrets["secretAccessKey"],
                        }
                    },
                    "ListObjectsV2",
                )
                with (
                    self.subTest(aws=aws),
                    patch("boto3.client", return_value=self.client),
                ):
                    response = transport.post(
                        "/internal/acquire",
                        headers={"X-Trace-Processor-Token": "controlled-token"},
                        json={
                            "type": "AMAZON_S3",
                            "configuration": self.config,
                            "credentials": self.secrets,
                            "test": True,
                        },
                    )
                    self.assertEqual(
                        response.json(),
                        {"status": status, "reasonCode": reason, "batch": None},
                    )
                    self.assertNotIn(self.secrets["secretAccessKey"], response.text)

    def test_timeout_does_not_publish_partial_results(self):
        self.client.list_objects_v2.side_effect = [
            dict(
                Contents=[self.object(0)],
                IsTruncated=True,
                NextContinuationToken="next",
            ),
            ReadTimeoutError(endpoint_url="https://s3.ap-south-1.amazonaws.com"),
        ]
        with (
            patch("boto3.client", return_value=self.client),
            self.assertRaises(ProviderError) as e,
        ):
            acquire("AMAZON_S3", self.config, self.secrets)
        self.assertEqual(e.exception.code, "NETWORK_TIMEOUT")

    def test_optional_session_token_is_forwarded_and_client_is_explicit(self):
        self.client.list_objects_v2.return_value = {}
        self.secrets["sessionToken"] = "controlled-session"
        with patch("boto3.client", return_value=self.client) as factory:
            acquire("AMAZON_S3", self.config, self.secrets, True)
        self.assertEqual(
            factory.call_args.kwargs["aws_session_token"], "controlled-session"
        )
        self.assertNotIn("endpoint_url", factory.call_args.kwargs)
