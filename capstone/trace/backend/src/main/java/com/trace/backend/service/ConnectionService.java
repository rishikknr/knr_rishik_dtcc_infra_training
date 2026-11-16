package com.trace.backend.service;

import com.trace.backend.dto.request.ConnectionSettings;
import com.trace.backend.dto.response.IdResponse;
import com.trace.backend.exception.ApiException;
import com.trace.backend.integration.connectors.ConnectorOutcomes;
import com.trace.backend.integration.connectors.ConnectorValidation;
import com.trace.backend.integration.connectors.CredentialCipher;
import com.trace.backend.integration.processor.IntegrationProcessor;
import com.trace.backend.repository.*;
import com.trace.backend.tenancy.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class ConnectionService {
  public static final Set<String> TYPES =
      Set.of("SNOWFLAKE", "AMAZON_S3", "GITHUB_SQL", "POWER_BI", "MANUAL", "FILE_IMPORT");
  private static final Map<String, Set<String>> CONFIG =
      Map.of(
          "SNOWFLAKE",
          Set.of(
              "account",
              "username",
              "role",
              "warehouse",
              "database",
              "schemas",
              "includeViewDefinitions"),
          "AMAZON_S3",
          Set.of("region", "bucket", "prefix", "maxObjects"),
          "GITHUB_SQL",
          Set.of("owner", "repository", "revision", "prefix", "dialect", "maxFiles"),
          "POWER_BI",
          Set.of("tenantId", "clientId", "workspaceIds"),
          "MANUAL",
          Set.of(),
          "FILE_IMPORT",
          Set.of());
  private static final Map<String, Set<String>> SECRETS =
      Map.of(
          "SNOWFLAKE",
          Set.of("privateKey", "privateKeyPassphrase"),
          "AMAZON_S3",
          Set.of("accessKeyId", "secretAccessKey", "sessionToken"),
          "GITHUB_SQL",
          Set.of("token"),
          "POWER_BI",
          Set.of("clientSecret"));
  private final TenantTransactions tenants;
  private final ConnectionRepository repository;
  private final RegistryRepository guard;
  private final CredentialCipher cipher;
  private final IntegrationProcessor processor;
  private final ImportService imports;
  private final ObjectMapper json;

  public ConnectionService(
      TenantTransactions tenants,
      ConnectionRepository repository,
      RegistryRepository guard,
      CredentialCipher cipher,
      IntegrationProcessor processor,
      ImportService imports,
      ObjectMapper json) {
    this.tenants = tenants;
    this.repository = repository;
    this.guard = guard;
    this.cipher = cipher;
    this.processor = processor;
    this.imports = imports;
    this.json = json;
  }

  public static void invalid(String message) {
    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONFIGURATION", message);
  }

  private ConnectionSettings valid(ConnectionSettings r, String type) {
    if (r == null
        || r.name() == null
        || r.name().isBlank()
        || r.name().length() > 150
        || r.name().chars().anyMatch(c -> c < 32)
        || r.ownerTeamId() == null) invalid("Connection name and owning team are required");
    var config = r.configuration() == null ? Map.<String, Object>of() : r.configuration();
    if (!TYPES.contains(type) || !CONFIG.get(type).containsAll(config.keySet()))
      invalid("Unsupported connection type or configuration field");
    ConnectorValidation.configuration(type, config);
    if (json.writeValueAsString(config).length() > 16000) invalid("Configuration is too large");
    if (r.description() != null && r.description().length() > 4000)
      invalid("Description must not exceed 4000 characters");
    return new ConnectionSettings(
        r.name().trim(),
        r.ownerTeamId(),
        type,
        r.description() == null ? "" : r.description(),
        config);
  }

  public List<Map<String, Object>> list() {
    return tenants.execute(repository::list);
  }

  public Map<String, Object> get(UUID id) {
    return tenants.execute(t -> repository.get(t, id, false));
  }

  public IdResponse create(ConnectionSettings r) {
    var v = valid(r, r.connectionType() == null ? "SNOWFLAKE" : r.connectionType());
    return tenants.execute(t -> new IdResponse(repository.create(t, v)));
  }

  private Map<String, Object> writable(TenantContext t, UUID id, boolean enabled) {
    var c = repository.get(t, id, true);
    guard.requireTeamWrite(t, (UUID) c.get("owner_team_id"));
    if (enabled && "DISABLED".equals(c.get("status")))
      throw new ApiException(
          HttpStatus.CONFLICT, "CONNECTION_DISABLED", "Enable this connection before using it");
    return c;
  }

  public Map<String, Object> update(UUID id, ConnectionSettings r) {
    return tenants.execute(
        t -> {
          var old = writable(t, id, false);
          var v = valid(r, (String) old.get("platform"));
          if (!Objects.equals(v.ownerTeamId(), old.get("owner_team_id")))
            invalid("Use the explicit ownership transfer action to change team");
          if (r.connectionType() != null && !r.connectionType().equals(old.get("platform")))
            invalid("Connection type cannot be changed");
          repository.update(t, id, v);
          return repository.get(t, id, false);
        });
  }

  public Map<String, Object> enabled(UUID id, boolean enabled) {
    return tenants.execute(
        t -> {
          writable(t, id, false);
          repository.status(t, id, enabled ? "NOT_TESTED" : "DISABLED", null, false);
          repository.audit(t, id, enabled ? "CONNECTION_ENABLED" : "CONNECTION_DISABLED");
          return repository.get(t, id, false);
        });
  }

  public Map<String, Boolean> credentials(UUID id, Map<String, String> values) {
    return tenants.execute(
        t -> {
          var c = writable(t, id, false);
          var allowed = SECRETS.get((String) c.get("platform"));
          if (allowed == null
              || values == null
              || values.isEmpty()
              || !allowed.containsAll(values.keySet())
              || values.values().stream()
                  .anyMatch(v -> v == null || v.isBlank() || v.length() > 32000))
            invalid("Provide the supported credential fields for this source");
          ConnectorValidation.credentials((String) c.get("platform"), values);
          var data = json.writeValueAsBytes(values);
          try {
            var encrypted = cipher.encrypt(t.organizationId(), id, data);
            repository.secret(t, id, encrypted.ciphertext(), encrypted.nonce());
          } finally {
            Arrays.fill(data, (byte) 0);
          }
          return Map.of("saved", true);
        });
  }

  private record Request(
      UUID org,
      UUID user,
      String type,
      java.sql.Timestamp settingsVersion,
      Map<String, Object> configuration,
      Map<String, String> credentials) {}

  private Request request(UUID id) {
    return tenants.execute(
        t -> {
          var c = writable(t, id, true);
          String type = (String) c.get("platform");
          if (!SECRETS.containsKey(type))
            invalid(
                "Manual and file sources use imports or registration; no live test is applicable");
          if (!Boolean.TRUE.equals(c.get("has_credentials")))
            throw new ApiException(
                HttpStatus.CONFLICT,
                "CREDENTIALS_REQUIRED",
                "Save connector credentials before testing or discovering metadata");
          repository.requireAcquisitionBudget(t, id);
          repository.audit(t, id, "CONNECTION_TEST_REQUESTED");
          var s = repository.secret(t, id);
          var bytes =
              cipher.decrypt(
                  t.organizationId(), id, (byte[]) s.get("ciphertext"), (byte[]) s.get("nonce"));
          @SuppressWarnings("unchecked")
          var config = (Map<String, Object>) c.get("configuration");
          @SuppressWarnings("unchecked")
          Map<String, String> secrets = json.readValue(bytes, Map.class);
          Arrays.fill(bytes, (byte) 0);
          return new Request(
              t.organizationId(),
              t.userId(),
              type,
              (java.sql.Timestamp) c.get("updated_at"),
              config,
              secrets);
        });
  }

  private void same(TenantContext t, Request r) {
    if (!r.org().equals(t.organizationId()) || !r.user().equals(t.userId()))
      throw RegistryRepository.missing();
  }

  public Map<String, Object> test(UUID id) {
    var r = request(id);
    String outcome;
    try {
      ConnectorValidation.configuration(r.type(), r.configuration());
      var result =
          processor.call(
              "acquire",
              Map.of(
                  "type",
                  r.type(),
                  "configuration",
                  r.configuration(),
                  "credentials",
                  r.credentials(),
                  "test",
                  true));
      String status = result.path("status").asString("INVALID_PROVIDER_RESPONSE");
      outcome = ConnectorOutcomes.safe(result.path("reasonCode").asString(status));
      // Success needs a valid batch, not just a status string from the transport.
      if ("CONNECTED".equals(outcome)) processor.batch(result.path("batch"));
    } catch (ApiException error) {
      outcome = ConnectorOutcomes.safe(error.code);
    }
    final String code = outcome;
    UUID diagnostic = UUID.randomUUID();
    org.slf4j.LoggerFactory.getLogger(getClass())
        .info("Connector test diagnostic={} provider={} outcome={}", diagnostic, r.type(), code);
    return tenants.execute(
        t -> {
          same(t, r);
          var current = writable(t, id, true);
          if (!Objects.equals(current.get("updated_at"), r.settingsVersion()))
            throw new ApiException(
                HttpStatus.CONFLICT,
                "CONFIGURATION_CHANGED",
                "Connection settings or credentials changed during the test. Test the current"
                    + " settings again.");
          repository.status(t, id, ConnectorOutcomes.state(code), code, true);
          repository.testEvidence(t, id, diagnostic, "CONNECTED".equals(code));
          repository.audit(t, id, "CONNECTION_TESTED");
          return repository.get(t, id, false);
        });
  }

  public Map<String, Object> sync(UUID id) {
    return tenants.execute(
        t -> {
          var c = writable(t, id, true);
          if (!SECRETS.containsKey((String) c.get("platform")))
            invalid("Only live connectors support discovery");
          if (!Boolean.TRUE.equals(c.get("has_credentials")))
            throw new ApiException(
                HttpStatus.CONFLICT, "CREDENTIALS_REQUIRED", "Save credentials before discovery");
          UUID run = repository.run(t, id);
          return repository.run(t, id, run);
        });
  }

  public Map<String, Object> cancel(UUID connection, UUID run) {
    return tenants.execute(
        t -> {
          writable(t, connection, false);
          repository.run(t, connection, run);
          repository.cancel(t, run);
          repository.audit(t, connection, "DISCOVERY_CANCEL_REQUESTED");
          return repository.run(t, connection, run);
        });
  }

  public Map<String, Object> retry(UUID connection, UUID run) {
    return tenants.execute(
        t -> {
          writable(t, connection, true);
          var old = repository.run(t, connection, run);
          if (!Set.of("FAILED", "CANCELLED").contains(old.get("status")))
            throw new ApiException(
                HttpStatus.CONFLICT,
                "RUN_NOT_RETRYABLE",
                "Only failed or cancelled runs can be retried");
          UUID next = repository.enqueue(t, connection, run);
          return repository.run(t, connection, next);
        });
  }

  public List<Map<String, Object>> runs(UUID id) {
    return tenants.execute(
        t -> {
          repository.get(t, id, false);
          return repository.runs(t, id);
        });
  }

  public Map<String, Object> run(UUID id, UUID run) {
    return tenants.execute(
        t -> {
          repository.get(t, id, false);
          return repository.run(t, id, run);
        });
  }

  public Map<String, Object> capabilities(UUID id) {
    String type = (String) get(id).get("platform");
    return Map.of(
        "connectionType",
        type,
        "liveDiscovery",
        SECRETS.containsKey(type),
        "metadataImport",
        true,
        "sqlSource",
        "GITHUB_SQL".equals(type),
        "columnMetadata",
        "SNOWFLAKE".equals(type),
        "reportMetadata",
        "POWER_BI".equals(type),
        "automaticVisuals",
        false,
        "automaticDax",
        false);
  }
}
