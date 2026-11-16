package com.trace.backend.config;

import com.trace.backend.security.CurrentIdentity;
import com.trace.backend.tenancy.TenantIdentityProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Configuration
public class TenantConfiguration {
  public static final String ORGANIZATION = "trace.selectedOrganization";

  @Bean
  public TenantIdentityProvider tenantIdentityProvider(HttpServletRequest request) {
    return () -> {
      var principal = CurrentIdentity.require();
      var session = request.getSession(false);
      var selected = session == null ? null : session.getAttribute(ORGANIZATION);
      if (!(selected instanceof UUID org))
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Select an active organization");
      return new TenantIdentityProvider.Selection(org, principal.id());
    };
  }
}
