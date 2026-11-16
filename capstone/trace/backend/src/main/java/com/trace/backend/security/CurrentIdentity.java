package com.trace.backend.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

public final class CurrentIdentity {
  private CurrentIdentity() {}

  public static TracePrincipal optional() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof TracePrincipal p
        ? p
        : null;
  }

  public static TracePrincipal require() {
    var p = optional();
    if (p == null)
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    return p;
  }
}
