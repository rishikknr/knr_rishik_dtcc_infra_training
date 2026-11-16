package com.trace.backend.service;

import com.trace.backend.repository.*;
import com.trace.backend.security.AuthorizationPolicy;
import com.trace.backend.tenancy.TenantTransactions;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ResourceOwnershipService {
  private final TenantTransactions tenants;
  private final ResourceOwnershipRepository resources;
  private final RegistryRepository guard;
  private final IdentityRepository identities;

  public ResourceOwnershipService(
      TenantTransactions tenants,
      ResourceOwnershipRepository resources,
      RegistryRepository guard,
      IdentityRepository identities) {
    this.tenants = tenants;
    this.resources = resources;
    this.guard = guard;
    this.identities = identities;
  }

  public void transfer(UUID resource, UUID team, boolean connection) {
    tenants.execute(
        t -> {
          UUID old = resources.lock(t, resource, connection);
          if (!Set.of("OWNER", "ADMIN").contains(t.organizationRole())
              && !(t.organizationRole().equals("MEMBER") && "OWNER".equals(t.teamRoles().get(old))))
            AuthorizationPolicy.deny();
          guard.requireTeamWrite(t, team);
          resources.transfer(t, resource, team, connection);
          identities.auditOwnership(
              t.userId(), t.organizationId(), resource, connection, old, team);
          return null;
        });
  }
}
