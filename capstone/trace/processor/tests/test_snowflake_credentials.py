"""Local credential diagnostics; no Snowflake requests or real user credentials."""

import base64
import os
import unittest
from unittest.mock import patch

from app import app
from connectors.providers import ProviderError, acquire, snowflake_private_key
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient


class SnowflakeCredentialTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        cls.pem = cls.key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ).decode()
        cls.encrypted = cls.key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.BestAvailableEncryption(b"synthetic key passphrase"),
        ).decode()
        cls.public = (
            cls.key.public_key()
            .public_bytes(
                serialization.Encoding.PEM,
                serialization.PublicFormat.SubjectPublicKeyInfo,
            )
            .decode()
        )
        cls.public_body = base64.b64encode(
            cls.key.public_key().public_bytes(
                serialization.Encoding.DER,
                serialization.PublicFormat.SubjectPublicKeyInfo,
            )
        ).decode()

    def error(self, credentials, reason):
        with self.assertRaises(ProviderError) as e:
            snowflake_private_key(credentials)
        self.assertEqual(e.exception.code, "CONFIGURATION_ERROR")
        self.assertEqual(e.exception.reason_code, reason)
        self.assertNotIn(
            credentials.get("privateKeyPassphrase", "no-real-secret"), str(e.exception)
        )

    def test_public_key_body_and_terminal_prompt_are_identified(self):
        for value in [self.public, self.public_body, self.public_body + "%"]:
            with self.subTest(format=value[:5]):
                self.error({"privateKey": value}, "SNOWFLAKE_PUBLIC_KEY_PROVIDED")

    def test_private_pem_and_encrypted_private_pem_load(self):
        self.assertEqual(snowflake_private_key({"privateKey": self.pem}).key_size, 2048)
        self.assertEqual(
            snowflake_private_key(
                {
                    "privateKey": self.encrypted,
                    "privateKeyPassphrase": "synthetic key passphrase",
                }
            ).key_size,
            2048,
        )

    def test_missing_and_unexpected_passphrases_are_distinct(self):
        self.error({"privateKey": self.encrypted}, "SNOWFLAKE_KEY_PASSPHRASE_REQUIRED")
        self.error(
            {
                "privateKey": self.pem,
                "privateKeyPassphrase": "synthetic sign-in password",
            },
            "SNOWFLAKE_KEY_PASSPHRASE_UNEXPECTED",
        )

    def test_wrong_passphrase_does_not_claim_provider_authentication_failure(self):
        self.error(
            {
                "privateKey": self.encrypted,
                "privateKeyPassphrase": "wrong synthetic passphrase",
            },
            "SNOWFLAKE_PRIVATE_KEY_INVALID",
        )

    def test_truncated_pem_and_prompt_suffix_are_rejected(self):
        self.error({"privateKey": "partial key body"}, "SNOWFLAKE_PRIVATE_KEY_FORMAT")
        self.error({"privateKey": self.pem + "%"}, "SNOWFLAKE_PRIVATE_KEY_FORMAT")

    def test_actual_saved_warehouse_mistake_is_rejected_before_provider_access(self):
        with (
            patch("snowflake.connector.connect") as connect,
            self.assertRaises(ProviderError) as e,
        ):
            acquire(
                "SNOWFLAKE",
                {
                    "account": "YJUJFLN-CW27089",
                    "username": "TRACE_METADATA_READER",
                    "database": "NOVAMART_DB",
                    "warehouse": "RAW, STAGING, CURATED, ANALYTICS",
                    "schemas": ["RAW", "STAGING", "CURATED", "ANALYTICS"],
                },
                {"privateKey": self.pem},
                True,
            )
        self.assertEqual(e.exception.reason_code, "SNOWFLAKE_WAREHOUSE_IS_SCHEMA_LIST")
        connect.assert_not_called()

    def test_transport_returns_safe_key_category_without_authenticating(self):
        with (
            patch.dict(os.environ, {"TRACE_PROCESSOR_TOKEN": "synthetic-token"}),
            patch("snowflake.connector.connect") as connect,
            TestClient(app) as client,
        ):
            r = client.post(
                "/internal/acquire",
                headers={"X-Trace-Processor-Token": "synthetic-token"},
                json={
                    "type": "SNOWFLAKE",
                    "configuration": {
                        "account": "YJUJFLN-CW27089",
                        "username": "TRACE_METADATA_READER",
                        "database": "NOVAMART_DB",
                    },
                    "credentials": {
                        "privateKey": self.public_body + "%",
                        "privateKeyPassphrase": "synthetic sign-in password",
                    },
                    "test": True,
                },
            )
            self.assertEqual(
                r.json(),
                {
                    "status": "CONFIGURATION_ERROR",
                    "reasonCode": "SNOWFLAKE_PUBLIC_KEY_PROVIDED",
                    "batch": None,
                },
            )
            self.assertNotIn(self.public_body, r.text)
            self.assertNotIn("synthetic sign-in password", r.text)
            connect.assert_not_called()
