package com.trace.backend.repository;

import com.trace.backend.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class RegistryRepository {
  private final JdbcTemplate jdbc;

  public RegistryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void requireTransaction(TenantContext tenant) {
    if (tenant == null
        || !TransactionSynchronizationManager.isActualTransactionActive()
        || !tenant
            .organizationId()
            .equals(jdbc.queryForObject("SELECT trace_current_organization()", UUID.class))) {
      throw new IllegalStateException("Verified tenant transaction required");
    }
  }

  public String organizationName(TenantContext t) {
    requireTransaction(t);
    return jdbc.queryForObject(
        "SELECT name FROM organizations WHERE id=?", String.class, t.organizationId());
  }

  public long countAssets(TenantContext tenant) {
    requireTransaction(tenant);
    return jdbc.queryForObject(
        "SELECT count(*) FROM assets WHERE organization_id = ?",
        Long.class,
        tenant.organizationId());
  }

  public void requireTeamWrite(TenantContext tenant, UUID teamId) {
    requireTransaction(tenant);
    if (teamId == null
        || jdbc.queryForObject(
                "SELECT count(*) FROM teams WHERE organization_id = ? AND id = ? AND active",
                Integer.class,
                tenant.organizationId(),
                teamId)
            != 1) throw missing();
    if ("VIEWER".equals(tenant.organizationRole())
        || (!List.of("OWNER", "ADMIN").contains(tenant.organizationRole())
            && !List.of("OWNER", "EDITOR").contains(tenant.teamRoles().getOrDefault(teamId, "")))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Team write permission required");
    }
  }

  public static Map<String, Object> one(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) throw missing();
    return rows.getFirst();
  }

  public static ResponseStatusException missing() {
    // Identical response for absent UUIDs and resources belonging to other organizations.
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
  }
}
