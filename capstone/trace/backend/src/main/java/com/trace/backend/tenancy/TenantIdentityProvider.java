package com.trace.backend.tenancy;

import java.util.UUID;

/**
 * Server-side selection only. Step 09 supplies an authenticated session adapter. Never implement
 * this by reading user, role, or organization headers/body fields.
 */
public interface TenantIdentityProvider {
  Selection selectedIdentity();

  record Selection(UUID organizationId, UUID userId) {
    public Selection {
      if (organizationId == null || userId == null)
        throw new IllegalArgumentException("Identity required");
    }
  }
}
