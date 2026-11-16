package com.trace.backend.integration.connectors;

import com.trace.backend.exception.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;

/** Validate the same bounds before persistence and acquisition. No secret values enter errors. */
public final class ConnectorValidation {
  private ConnectorValidation() {}

  private static void invalid(String code, String field, String message) {
    throw new ApiException(
        HttpStatus.BAD_REQUEST,
        code,
        message,
        List.of(new ApiException.Detail(null, field, message)));
  }

  public static void configuration(String type, Map<String, Object> c) {
    if ("AMAZON_S3".equals(type)) {
      text(c, "region", "[a-z]{2}(?:-[a-z]+)+-\\d+", "Enter an AWS Region such as ap-south-1.");
      text(
          c,
          "bucket",
          "[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]",
          "Enter the bucket name without s3:// or a path.");
      Object bucket = c.get("bucket");
      if (bucket instanceof String b && (b.contains("..") || b.matches("[0-9.]+")))
        invalid("INVALID_CONFIGURATION", "bucket", "Enter a valid S3 bucket name.");
      limit(c, "maxObjects", 1000);
    }
    if ("SNOWFLAKE".equals(type)
        && c.get("warehouse") instanceof String warehouse
        && c.get("schemas") instanceof List<?> schemas
        && schemas.size() > 1
        && Arrays.stream(warehouse.split(",")).map(String::trim).toList().equals(schemas))
      invalid(
          "SNOWFLAKE_WAREHOUSE_IS_SCHEMA_LIST",
          "warehouse",
          "Warehouse contains the schema allowlist. Enter one actual Snowflake warehouse name, or"
              + " leave it empty to use the user's default warehouse.");
    if ("GITHUB_SQL".equals(type)) limit(c, "maxFiles", 100);
    if (c.containsKey("prefix")) {
      Object p = c.get("prefix");
      if (!(p instanceof String v)
          || v.length() > 500
          || v.startsWith("/")
          || v.contains("\\")
          || Arrays.asList(v.split("/")).contains("..")
          || v.chars().anyMatch(ch -> ch < 32))
        invalid(
            "INVALID_CONFIGURATION",
            "prefix",
            "Enter a relative prefix, without a leading slash or parent path.");
    }
  }

  private static void text(Map<String, Object> c, String field, String pattern, String message) {
    // Empty configurations remain valid drafts for existing callers. Supplied fields must be valid.
    if (c.containsKey(field) && (!(c.get(field) instanceof String v) || !v.matches(pattern)))
      invalid("INVALID_CONFIGURATION", field, message);
  }

  private static void limit(Map<String, Object> c, String field, int maximum) {
    if (!c.containsKey(field)) return;
    Object v = c.get(field);
    if (!(v instanceof Number || v instanceof String)
        || !v.toString().matches("[0-9]+")
        || v.toString().length() > 9
        || Long.parseLong(v.toString()) < 1
        || Long.parseLong(v.toString()) > maximum)
      invalid(
          "DISCOVERY_LIMIT_INVALID",
          field,
          "Maximum discovery objects must be between 1 and "
              + maximum
              + ". Narrow the source scope for larger inventories.");
  }

  public static void credentials(String type, Map<String, String> values) {
    Set<String> required =
        switch (type) {
          case "AMAZON_S3" -> Set.of("accessKeyId", "secretAccessKey");
          case "SNOWFLAKE" -> Set.of("privateKey");
          case "GITHUB_SQL" -> Set.of("token");
          case "POWER_BI" -> Set.of("clientSecret");
          default -> Set.of();
        };
    if (required.stream().anyMatch(k -> !values.containsKey(k) || values.get(k).isBlank()))
      invalid(
          "CREDENTIALS_REQUIRED",
          "credentials",
          "Supply all required credentials when saving or rotating this connection.");
    if ("AMAZON_S3".equals(type)
        && values.values().stream().anyMatch(v -> v.chars().anyMatch(Character::isWhitespace)))
      invalid(
          "CREDENTIAL_WHITESPACE",
          "credentials",
          "AWS credentials contain whitespace. Paste each complete value without spaces or line"
              + " breaks.");
  }
}
