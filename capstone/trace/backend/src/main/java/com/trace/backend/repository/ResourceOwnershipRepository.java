package com.trace.backend.repository;

import com.trace.backend.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ResourceOwnershipRepository {
  private final JdbcTemplate jdbc;
  private final RegistryRepository guard;

  public ResourceOwnershipRepository(JdbcTemplate jdbc, RegistryRepository guard) {
    this.jdbc = jdbc;
    this.guard = guard;
  }

  public UUID lock(TenantContext t, UUID resource, boolean connection) {
    guard.requireTransaction(t);
    return (UUID)
        RegistryRepository.one(
                jdbc.queryForList(
                    "SELECT owner_team_id FROM "
                        + (connection ? "source_connections" : "assets")
                        + " WHERE organization_id=? AND id=? FOR UPDATE",
                    t.organizationId(),
                    resource))
            .get("owner_team_id");
  }

  public void transfer(TenantContext t, UUID resource, UUID team, boolean connection) {
    guard.requireTransaction(t);
    jdbc.update(
        "UPDATE "
            + (connection ? "source_connections" : "assets")
            + " SET owner_team_id=?,"
            + (connection ? "updated_at=now()" : "revision=revision+1")
            + " WHERE organization_id=? AND id=?",
        team,
        t.organizationId(),
        resource);
  }
}
