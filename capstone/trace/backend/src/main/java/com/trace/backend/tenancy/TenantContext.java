package com.trace.backend.tenancy;

import java.util.Map;
import java.util.UUID;

/** Transaction-scoped verified identity. No ThreadLocal or connection-session state. */
public final class TenantContext {
  private final UUID organizationId;
  private final UUID userId;
  private final String organizationRole;
  private final Map<UUID, String> teamRoles;

  TenantContext(
      UUID organizationId, UUID userId, String organizationRole, Map<UUID, String> teamRoles) {
    this.organizationId = organizationId;
    this.userId = userId;
    this.organizationRole = organizationRole;
    this.teamRoles = Map.copyOf(teamRoles);
  }

  public UUID organizationId() {
    return organizationId;
  }

  public UUID userId() {
    return userId;
  }

  public String organizationRole() {
    return organizationRole;
  }

  public Map<UUID, String> teamRoles() {
    return teamRoles;
  }
}
