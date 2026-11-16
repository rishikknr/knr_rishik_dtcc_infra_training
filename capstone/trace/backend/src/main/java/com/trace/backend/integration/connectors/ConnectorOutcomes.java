package com.trace.backend.integration.connectors;

import java.util.*;

/** Allowlisted provider outcomes. Never persist arbitrary provider response strings. */
public final class ConnectorOutcomes {
  private ConnectorOutcomes() {}

  private static final Set<String> CODES =
      Set.of(
          "SNOWFLAKE_PUBLIC_KEY_PROVIDED",
          "SNOWFLAKE_PRIVATE_KEY_FORMAT",
          "SNOWFLAKE_KEY_PASSPHRASE_REQUIRED",
          "SNOWFLAKE_KEY_PASSPHRASE_UNEXPECTED",
          "SNOWFLAKE_PRIVATE_KEY_INVALID",
          "SNOWFLAKE_WAREHOUSE_IS_SCHEMA_LIST",
          "CONNECTED",
          "AUTHENTICATION_FAILED",
          "PERMISSION_DENIED",
          "CONFIGURATION_ERROR",
          "CREDENTIALS_REQUIRED",
          "CREDENTIAL_WHITESPACE",
          "DISCOVERY_LIMIT_INVALID",
          "NETWORK_TIMEOUT",
          "RATE_LIMITED",
          "UNAVAILABLE",
          "DRIVER_UNAVAILABLE",
          "INVALID_PROVIDER_RESPONSE",
          "S3_LIST_ACCESS_DENIED",
          "S3_INVALID_CREDENTIALS",
          "S3_SIGNATURE_MISMATCH",
          "S3_SESSION_EXPIRED",
          "S3_CLOCK_SKEW",
          "S3_REGION_MISMATCH",
          "S3_BUCKET_NOT_FOUND",
          "S3_THROTTLED",
          "S3_UNAVAILABLE",
          "PROCESSOR_UNAVAILABLE",
          "PROCESSOR_AUTH_UNAVAILABLE",
          "PROCESSOR_ERROR",
          "PROCESSOR_INVALID_RESPONSE");

  public static String safe(String code) {
    if ("INVALID_CONFIGURATION".equals(code)) return "CONFIGURATION_ERROR";
    return CODES.contains(code == null ? "" : code) ? code : "INVALID_PROVIDER_RESPONSE";
  }

  public static String state(String code) {
    return switch (safe(code)) {
      case "CONNECTED" -> "CONNECTED";
      case "AUTHENTICATION_FAILED",
          "S3_INVALID_CREDENTIALS",
          "S3_SIGNATURE_MISMATCH",
          "S3_SESSION_EXPIRED" ->
          "AUTHENTICATION_FAILED";
      case "PERMISSION_DENIED", "S3_LIST_ACCESS_DENIED" -> "PERMISSION_DENIED";
      case "SNOWFLAKE_PUBLIC_KEY_PROVIDED",
          "SNOWFLAKE_PRIVATE_KEY_FORMAT",
          "SNOWFLAKE_KEY_PASSPHRASE_REQUIRED",
          "SNOWFLAKE_KEY_PASSPHRASE_UNEXPECTED",
          "SNOWFLAKE_PRIVATE_KEY_INVALID",
          "SNOWFLAKE_WAREHOUSE_IS_SCHEMA_LIST",
          "CONFIGURATION_ERROR",
          "DISCOVERY_LIMIT_INVALID",
          "CREDENTIAL_WHITESPACE",
          "CREDENTIALS_REQUIRED",
          "S3_CLOCK_SKEW",
          "S3_REGION_MISMATCH",
          "S3_BUCKET_NOT_FOUND" ->
          "CONFIGURATION_ERROR";
      default -> "UNAVAILABLE";
    };
  }
}
