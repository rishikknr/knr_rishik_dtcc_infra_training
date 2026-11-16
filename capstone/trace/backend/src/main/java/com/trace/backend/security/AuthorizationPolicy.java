package com.trace.backend.security;

import com.trace.backend.repository.IdentityRepository;
import com.trace.backend.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AuthorizationPolicy {
  private final IdentityRepository identities;

  public AuthorizationPolicy(IdentityRepository identities) {
    this.identities = identities;
  }

  public void platform() {
    if (!"PLATFORM_ADMIN".equals(identities.profile(CurrentIdentity.require().id()).platformRole()))
      deny();
  }

  public void administrator(TenantContext t) {
    if (!java.util.Set.of("OWNER", "ADMIN").contains(t.organizationRole())) deny();
  }

  public static void deny() {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Permission denied");
  }
}
