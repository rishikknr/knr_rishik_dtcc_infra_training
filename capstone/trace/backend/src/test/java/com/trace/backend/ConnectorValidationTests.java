package com.trace.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.trace.backend.exception.ApiException;
import com.trace.backend.integration.connectors.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConnectorValidationTests {
  @Test
  void actualSavedLimitIsRejectedBeforePersistence() {
    var e =
        assertThrows(
            ApiException.class,
            () ->
                ConnectorValidation.configuration(
                    "AMAZON_S3",
                    Map.of(
                        "region",
                        "ap-south-1",
                        "bucket",
                        "novamart-trace-test-space",
                        "prefix",
                        "novamart/baseline/",
                        "maxObjects",
                        10000000)));
    assertEquals("DISCOVERY_LIMIT_INVALID", e.code);
    assertEquals("maxObjects", e.details.getFirst().field());
  }

  @Test
  void boundsRejectNonIntegersWithoutParsingOverflow() {
    for (Object n : List.of(0, 1001, -1, 1.5, true, "999999999999999999999999"))
      assertThrows(
          ApiException.class,
          () -> ConnectorValidation.configuration("AMAZON_S3", Map.of("maxObjects", n)));
    for (Object n : List.of(1, 1000, "22"))
      ConnectorValidation.configuration("AMAZON_S3", Map.of("maxObjects", n));
  }

  @Test
  void credentialsAreCompleteReplacementsWithNoSecretEcho() {
    var missing =
        assertThrows(
            ApiException.class,
            () ->
                ConnectorValidation.credentials("AMAZON_S3", Map.of("accessKeyId", "controlled")));
    assertEquals("CREDENTIALS_REQUIRED", missing.code);
    var whitespace =
        assertThrows(
            ApiException.class,
            () ->
                ConnectorValidation.credentials(
                    "AMAZON_S3",
                    Map.of("accessKeyId", "controlled", "secretAccessKey", "never-relay\n")));
    assertEquals("CREDENTIAL_WHITESPACE", whitespace.code);
    assertFalse(whitespace.getMessage().contains("never-relay"));
    ConnectorValidation.credentials(
        "SNOWFLAKE",
        Map.of(
            "privateKey",
            "-----BEGIN PRIVATE KEY-----\ncontrolled\n-----END PRIVATE KEY-----",
            "privateKeyPassphrase",
            "spaces are allowed"));
  }

  @Test
  void endpointAndScopeInputsAreBounded() {
    assertThrows(
        ApiException.class,
        () -> ConnectorValidation.configuration("AMAZON_S3", Map.of("bucket", "http://127.0.0.1")));
    assertThrows(
        ApiException.class,
        () -> ConnectorValidation.configuration("AMAZON_S3", Map.of("prefix", "../secret")));
    ConnectorValidation.configuration(
        "AMAZON_S3",
        Map.of(
            "region", "us-gov-west-1", "bucket", "scoped-bucket", "prefix", "novamart/baseline/"));
  }

  @Test
  void snowflakeSchemaListCannotBeUsedAsWarehouseAndDiagnosticReasonsPersist() {
    var error =
        assertThrows(
            ApiException.class,
            () ->
                ConnectorValidation.configuration(
                    "SNOWFLAKE",
                    Map.of(
                        "warehouse",
                        "RAW, STAGING, CURATED, ANALYTICS",
                        "schemas",
                        List.of("RAW", "STAGING", "CURATED", "ANALYTICS"))));
    assertEquals("SNOWFLAKE_WAREHOUSE_IS_SCHEMA_LIST", error.code);
    assertEquals("warehouse", error.details.getFirst().field());
    ConnectorValidation.configuration(
        "SNOWFLAKE", Map.of("warehouse", "COMPUTE_WH", "schemas", List.of("RAW", "STAGING")));
    for (String reason :
        List.of(
            "SNOWFLAKE_PUBLIC_KEY_PROVIDED",
            "SNOWFLAKE_PRIVATE_KEY_FORMAT",
            "SNOWFLAKE_PRIVATE_KEY_INVALID",
            "SNOWFLAKE_KEY_PASSPHRASE_REQUIRED",
            "SNOWFLAKE_KEY_PASSPHRASE_UNEXPECTED",
            "SNOWFLAKE_WAREHOUSE_IS_SCHEMA_LIST")) {
      assertEquals(reason, ConnectorOutcomes.safe(reason));
      assertEquals("CONFIGURATION_ERROR", ConnectorOutcomes.state(reason));
    }
    assertEquals("CONFIGURATION_ERROR", ConnectorOutcomes.safe("INVALID_CONFIGURATION"));
  }

  @Test
  void providerOutcomesAreAllowlistedAndSpecific() {
    assertEquals("AUTHENTICATION_FAILED", ConnectorOutcomes.state("S3_SESSION_EXPIRED"));
    assertEquals("PERMISSION_DENIED", ConnectorOutcomes.state("S3_LIST_ACCESS_DENIED"));
    assertEquals("CONFIGURATION_ERROR", ConnectorOutcomes.state("DISCOVERY_LIMIT_INVALID"));
    assertEquals("UNAVAILABLE", ConnectorOutcomes.state("PROCESSOR_UNAVAILABLE"));
    assertEquals("INVALID_PROVIDER_RESPONSE", ConnectorOutcomes.safe("SECRET_FROM_PROVIDER"));
  }
}
