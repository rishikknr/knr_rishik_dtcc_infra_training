package com.trace.backend.repository;

import com.trace.backend.entity.SourceConnection;
import com.trace.backend.tenancy.TenantContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SourceConnectionRepository {
  private final JdbcTemplate jdbc;
  private final RegistryRepository guard;

  public SourceConnectionRepository(JdbcTemplate jdbc, RegistryRepository guard) {
    this.jdbc = jdbc;
    this.guard = guard;
  }

  public List<SourceConnection> connections(TenantContext tenant) {
    guard.requireTransaction(tenant);
    return jdbc.query(
        """
        SELECT id, name, platform, ingestion_mode, owner_team_id FROM source_connections
        WHERE organization_id = ? ORDER BY name LIMIT 100
        """,
        (rs, n) ->
            new SourceConnection(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("platform"),
                rs.getString("ingestion_mode"),
                rs.getObject("owner_team_id", UUID.class)),
        tenant.organizationId());
  }

  public UUID createConnection(TenantContext tenant, String name, UUID teamId) {
    guard.requireTransaction(tenant);
    guard.requireTeamWrite(tenant, teamId);
    var id = UUID.randomUUID();
    jdbc.update(
"""
INSERT INTO source_connections(id, organization_id, name, platform, ingestion_mode, owner_team_id)
VALUES (?, ?, ?, 'SNOWFLAKE', 'METADATA_IMPORT', ?)
""",
        id,
        tenant.organizationId(),
        name,
        teamId);
    return id;
  }

  public UUID connectionForImport(TenantContext tenant, UUID id) {
    guard.requireTransaction(tenant);
    var connection =
        RegistryRepository.one(
            jdbc.queryForList(
"""
SELECT owner_team_id FROM source_connections WHERE organization_id = ? AND id = ? AND status <> 'DISABLED' FOR UPDATE
""",
                tenant.organizationId(),
                id));
    var teamId = (UUID) connection.get("owner_team_id");
    guard.requireTeamWrite(tenant, teamId);
    return teamId;
  }
}
