package com.trace.backend.repository;

import com.trace.backend.dto.response.*;
import com.trace.backend.entity.ImportBatch;
import com.trace.backend.enums.ImportStatus;
import com.trace.backend.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MetadataImportRepository {
  private final JdbcTemplate jdbc;
  private final RegistryRepository guard;
  private final ObjectMapper json;

  public MetadataImportRepository(JdbcTemplate jdbc, RegistryRepository guard, ObjectMapper json) {
    this.jdbc = jdbc;
    this.guard = guard;
    this.json = json;
  }

  public UUID create(
      TenantContext t,
      UUID connection,
      UUID team,
      MetadataPreview preview,
      ImportStatistics comparison,
      java.util.List<DatasetChange> changes,
      String filename,
      long size) {
    return create(t, connection, team, preview, comparison, changes, filename, size, "CSV");
  }

  public UUID create(
      TenantContext t,
      UUID connection,
      UUID team,
      MetadataPreview preview,
      ImportStatistics comparison,
      java.util.List<DatasetChange> changes,
      String filename,
      long size,
      String format) {
    guard.requireTransaction(t);
    UUID id = UUID.randomUUID();
    jdbc.update(
"""
INSERT INTO import_batches(id,organization_id,connection_id,created_by_user_id,owner_team_id,status,preview,comparison,dataset_changes,filename,file_size,source_type,format,configuration_updated_at)
VALUES (?,?,?,?,?,'PREVIEW_READY',?::jsonb,?::jsonb,?::jsonb,?,?,(SELECT platform FROM source_connections WHERE organization_id=? AND id=?),?,(SELECT updated_at FROM source_connections WHERE organization_id=? AND id=?))
""",
        id,
        t.organizationId(),
        connection,
        t.userId(),
        team,
        json.writeValueAsString(preview),
        json.writeValueAsString(comparison),
        json.writeValueAsString(changes),
        filename,
        size,
        t.organizationId(),
        connection,
        format,
        t.organizationId(),
        connection);
    return id;
  }

  public ImportBatch get(TenantContext t, UUID id, boolean lock) {
    guard.requireTransaction(t);
    return jdbc
        .query(
            """
            SELECT * FROM import_batches WHERE organization_id=? AND id=? AND created_by_user_id=?
            """
                + (lock ? " FOR UPDATE" : ""),
            (r, n) ->
                new ImportBatch(
                    r.getObject("id", UUID.class),
                    r.getObject("connection_id", UUID.class),
                    r.getObject("owner_team_id", UUID.class),
                    ImportStatus.valueOf(r.getString("status")),
                    json.readValue(r.getString("preview"), MetadataPreview.class),
                    r.getString("comparison") == null
                        ? null
                        : json.readValue(r.getString("comparison"), ImportStatistics.class),
                    r.getString("results") == null
                        ? null
                        : json.readValue(r.getString("results"), ImportStatistics.class),
                    r.getString("dataset_changes") == null
                        ? java.util.List.of()
                        : java.util.Arrays.asList(
                            json.readValue(r.getString("dataset_changes"), DatasetChange[].class)),
                    r.getString("filename"),
                    r.getLong("file_size"),
                    r.getTimestamp("created_at").toInstant(),
                    r.getTimestamp("committed_at") == null
                        ? null
                        : r.getTimestamp("committed_at").toInstant(),
                    r.getTimestamp("expires_at").toInstant(),
                    r.getString("source_type"),
                    r.getString("format")),
            t.organizationId(),
            id,
            t.userId())
        .stream()
        .findFirst()
        .orElseThrow(RegistryRepository::missing);
  }

  public void requireCurrentConnection(TenantContext t, UUID id) {
    guard.requireTransaction(t);
    boolean current =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT b.configuration_updated_at=c.updated_at FROM import_batches b JOIN"
                    + " source_connections c ON c.organization_id=b.organization_id AND"
                    + " c.id=b.connection_id WHERE b.organization_id=? AND b.id=?",
                Boolean.class,
                t.organizationId(),
                id));
    if (!current)
      throw new com.trace.backend.exception.ApiException(
          org.springframework.http.HttpStatus.CONFLICT,
          "CONFIGURATION_CHANGED",
          "Source settings changed or this preview predates the current release. Create a fresh"
              + " preview before committing.");
    boolean stale =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM import_batches b JOIN import_batches newer ON"
                    + " newer.organization_id=b.organization_id AND"
                    + " newer.connection_id=b.connection_id WHERE b.organization_id=? AND b.id=?"
                    + " AND b.format='LIVE_DISCOVERY' AND newer.format='LIVE_DISCOVERY' AND"
                    + " newer.status='COMMITTED' AND"
                    + " (newer.created_at,newer.id)>(b.created_at,b.id))",
                Boolean.class,
                t.organizationId(),
                id));
    if (stale)
      throw new com.trace.backend.exception.ApiException(
          org.springframework.http.HttpStatus.CONFLICT,
          "STALE_PREVIEW",
          "A newer discovery has already been committed. Run discovery again to review current"
              + " metadata.");
    jdbc.queryForObject("SELECT set_config('trace.import_id',?,true)", String.class, id.toString());
  }

  public void committed(TenantContext t, UUID id, ImportStatistics results) {
    guard.requireTransaction(t);
    jdbc.update(
        "UPDATE import_batches SET status='COMMITTED',committed_at=now(),results=?::jsonb WHERE"
            + " organization_id=? AND id=? AND created_by_user_id=?",
        json.writeValueAsString(results),
        t.organizationId(),
        id,
        t.userId());
    jdbc.queryForObject(
        "SELECT trace_audit(?,?,'METADATA_COMMITTED','IMPORT',?,'SUCCESS')",
        Object.class,
        t.userId(),
        t.organizationId(),
        id);
  }
}
