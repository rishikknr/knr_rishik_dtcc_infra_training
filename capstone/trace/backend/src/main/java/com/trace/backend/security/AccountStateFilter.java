package com.trace.backend.security;

import com.trace.backend.repository.IdentityRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class AccountStateFilter extends OncePerRequestFilter {
  private final IdentityRepository identities;

  public AccountStateFilter(IdentityRepository identities) {
    this.identities = identities;
  }

  protected void doFilterInternal(HttpServletRequest q, HttpServletResponse r, FilterChain chain)
      throws IOException, ServletException {
    var principal = CurrentIdentity.optional();
    if (principal != null) {
      var user = identities.profile(principal.id());
      if (!user.active() || user.securityVersion() != principal.version()) {
        var session = q.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        SecurityResponses.error(r, 401, "SESSION_REVOKED", "Your session has ended. Sign in again");
        return;
      }
    }
    chain.doFilter(q, r);
  }
}
