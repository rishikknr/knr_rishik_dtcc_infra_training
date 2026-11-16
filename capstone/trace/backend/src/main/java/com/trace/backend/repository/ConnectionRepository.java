package com.trace.backend.repository;

import com.trace.backend.dto.request.ConnectionSettings;
import com.trace.backend.tenancy.TenantContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ConnectionRepository {
  private final JdbcTemplate jdbc;
  private final RegistryRepository guard;
  private final ObjectMapper json;

  public ConnectionRepository(JdbcTemplate jdbc, RegistryRepository guard, ObjectMapper json) {
    this.jdbc = jdbc;
    this.guard = guard;
    this.json = json;
  }

  private static final String SELECT =
      """
      SELECT c.id,c.name,c.platform,c.platform AS connection_type,c.ingestion_mode,c.owner_team_id,
      c.description,c.configuration::text,c.status,c.reason_code,c.last_tested_at,c.last_successful_sync_at,
      c.created_at,c.updated_at,c.last_successful_test_at,c.last_test_diagnostic_id,
      (SELECT count(*) FROM assets a WHERE a.organization_id=c.organization_id AND a.connection_id=c.id) AS registered_asset_count,
      (SELECT run.records_discovered FROM connection_sync_runs run WHERE run.organization_id=c.organization_id AND run.connection_id=c.id AND run.status IN ('COMPLETED','PARTIALLY_COMPLETED') ORDER BY run.finished_at DESC,run.id LIMIT 1) AS discovered_asset_count,EXISTS(SELECT 1 FROM connection_secret_storage s WHERE s.organization_id=c.organization_id AND s.connection_id=c.id) AS has_credentials
      FROM source_connections c WHERE c.organization_id=?
      """;

  private Map<String, Object> decode(Map<String, Object> row) {
    row.put("configuration", json.readValue((String) row.get("configuration"), Map.class));
    return row;
  }

  public List<Map<String, Object>> list(TenantContext t) {
    guard.requireTransaction(t);
    return jdbc
        .queryForList(SELECT + " ORDER BY c.name,c.id LIMIT 100", t.organizationId())
        .stream()
        .map(this::decode)
        .toList();
  }

  public Map<String, Object> get(TenantContext t, UUID id, boolean lock) {
    guard.requireTransaction(t);
    return decode(
        RegistryRepository.one(
            jdbc.queryForList(
                SELECT + " AND c.id=?" + (lock ? " FOR UPDATE OF c" : ""),
                t.organizationId(),
                id)));
  }

  public UUID create(TenantContext t, ConnectionSettings r) {
    guard.requireTeamWrite(t, r.ownerTeamId());
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO source_connections(id,organization_id,name,platform,ingestion_mode,owner_team_id,description,configuration,created_by)
        VALUES(?,?,?,?,'METADATA_IMPORT',?,?,?::jsonb,?)
        """,
        id,
        t.organizationId(),
        r.name(),
        r.connectionType(),
        r.ownerTeamId(),
        r.description(),
        json.writeValueAsString(r.configuration()),
        t.userId());
    audit(t, id, "CONNECTION_CREATED");
    return id;
  }

  public void update(TenantContext t, UUID id, ConnectionSettings r) {
    guard.requireTransaction(t);
    jdbc.update(
        "UPDATE source_connections SET name=?,description=?,configuration=?::jsonb,status=CASE WHEN"
            + " status='DISABLED' THEN 'DISABLED' ELSE 'NOT_TESTED'"
            + " END,reason_code=NULL,updated_at=now() WHERE organization_id=? AND id=?",
        r.name(),
        r.description(),
        json.writeValueAsString(r.configuration()),
        t.organizationId(),
        id);
    audit(t, id, "CONNECTION_UPDATED");
  }

  public void status(TenantContext t, UUID id, String status, String code, boolean tested) {
    guard.requireTransaction(t);
    jdbc.update(
        "UPDATE source_connections SET status=?,reason_code=?,last_tested_at=CASE WHEN ? THEN now()"
            + " ELSE last_tested_at END,updated_at=now() WHERE organization_id=? AND id=?",
        status,
        code,
        tested,
        t.organizationId(),
        id);
  }

  public void testEvidence(TenantContext t, UUID id, UUID diagnostic, boolean success) {
    guard.requireTransaction(t);
    jdbc.update(
        "UPDATE source_connections SET last_test_diagnostic_id=?,last_successful_test_at=CASE WHEN"
            + " ? THEN now() ELSE last_successful_test_at END WHERE organization_id=? AND id=?",
        diagnostic,
        success,
        t.organizationId(),
        id);
  }

  public void secret(TenantContext t, UUID id, byte[] value, byte[] nonce) {
    guard.requireTransaction(t);
    jdbc.update(
        """
        INSERT INTO connection_secret_storage(organization_id,connection_id,ciphertext,nonce) VALUES(?,?,?,?)
        ON CONFLICT(organization_id,connection_id) DO UPDATE SET ciphertext=EXCLUDED.ciphertext,nonce=EXCLUDED.nonce,key_version=1,updated_at=now()
        """,
        t.organizationId(),
        id,
        value,
        nonce);
    if (!"DISABLED".equals(get(t, id, false).get("status")))
      status(t, id, "NOT_TESTED", null, false);
    audit(t, id, "CONNECTION_CREDENTIAL_ROTATED");
  }

  public Map<String, Object> secret(TenantContext t, UUID id) {
    guard.requireTransaction(t);
    return RegistryRepository.one(
        jdbc.queryForList(
            "SELECT ciphertext,nonce,key_version FROM connection_secret_storage WHERE"
                + " organization_id=? AND connection_id=?",
            t.organizationId(),
            id));
  }

  /** Shared, tenant-scoped budget serialized by the caller's connection row lock. */
  public void requireAcquisitionBudget(TenantContext t, UUID connection) {
    guard.requireTransaction(t);
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_events WHERE organization_id=? AND resource_id=? AND"
                + " resource_type='CONNECTION' AND event_type IN"
                + " ('CONNECTION_TEST_REQUESTED','DISCOVERY_QUEUED') AND created_at >"
                + " now()-interval '1 minute'",
            Integer.class,
            t.organizationId(),
            connection);
    if (count != null && count >= 20)
      throw new com.trace.backend.exception.ApiException(
          org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
          "CONNECTION_RATE_LIMITED",
          "Too many acquisition requests for this connection. Wait one minute and retry.");
  }

  public UUID run(TenantContext t, UUID connection) {
    return enqueue(t, connection, null);
  }

  public UUID enqueue(TenantContext t, UUID connection, UUID retryOf) {
    guard.requireTransaction(t);
    requireAcquisitionBudget(t, connection);
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " connection_sync_runs(id,organization_id,connection_id,initiated_by,status,configuration_updated_at,retry_of)"
            + " SELECT ?,?,?,?,'PENDING',updated_at,? FROM source_connections WHERE"
            + " organization_id=? AND id=?",
        id,
        t.organizationId(),
        connection,
        t.userId(),
        retryOf,
        t.organizationId(),
        connection);
    audit(t, connection, "DISCOVERY_QUEUED");
    return id;
  }

  public static final String RUN_FIELDS =
      "id,organization_id,connection_id,initiated_by,status,started_at,finished_at,queued_at,next_attempt_at,attempt_count,phase,records_discovered,error_code,import_id,cancel_requested_at,warnings::text";

  public Map<String, Object> run(TenantContext t, UUID connection, UUID run) {
    guard.requireTransaction(t);
    return RegistryRepository.one(
        jdbc.queryForList(
            "SELECT "
                + RUN_FIELDS
                + " FROM connection_sync_runs WHERE organization_id=? AND connection_id=? AND id=?",
            t.organizationId(),
            connection,
            run));
  }

  public boolean configurationMatches(TenantContext t, UUID run, java.sql.Timestamp timestamp) {
    guard.requireTransaction(t);
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT configuration_updated_at=? FROM connection_sync_runs WHERE organization_id=?"
                + " AND id=?",
            Boolean.class,
            timestamp,
            t.organizationId(),
            run));
  }

  public void cancel(TenantContext t, UUID run) {
    guard.requireTransaction(t);
    jdbc.queryForObject(
        "SELECT trace_cancel_discovery(?,?)", Boolean.class, t.organizationId(), run);
  }

  public List<Map<String, Object>> runs(TenantContext t, UUID id) {
    guard.requireTransaction(t);
    return jdbc.queryForList(
        "SELECT "
            + RUN_FIELDS
            + " FROM connection_sync_runs WHERE organization_id=? AND connection_id=? ORDER BY"
            + " started_at DESC,id LIMIT 100",
        t.organizationId(),
        id);
  }

  public void audit(TenantContext t, UUID id, String event) {
    guard.requireTransaction(t);
    jdbc.queryForObject(
        "SELECT trace_audit(?,?,?,'CONNECTION',?,'SUCCESS')",
        Object.class,
        t.userId(),
        t.organizationId(),
        event,
        id);
  }
}
