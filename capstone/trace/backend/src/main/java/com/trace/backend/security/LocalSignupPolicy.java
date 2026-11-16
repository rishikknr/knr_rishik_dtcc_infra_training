package com.trace.backend.security;

import com.trace.backend.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Local development signup and explicitly enabled public registration. */
@Component
public class LocalSignupPolicy {
  private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
  private final boolean enabled;
  private final boolean publicEnabled;
  private final boolean publicRequireHttps;

  public LocalSignupPolicy(
      Environment environment,
      @Value("${trace.security.local-signup-enabled:false}") boolean enabled) {
    if (enabled
        && (!Set.of(environment.getActiveProfiles()).equals(Set.of("local-dev"))
            || !"127.0.0.1".equals(environment.getProperty("server.address")))) {
      throw new IllegalStateException(
          "Local signup requires only local-dev and server.address=127.0.0.1");
    }
    this.enabled = enabled;
    this.publicEnabled = environment.getProperty("trace.security.public-signup-enabled", Boolean.class, false);
    this.publicRequireHttps = environment.getProperty("trace.security.public-signup-require-https", Boolean.class, true);
  }

  public boolean available(HttpServletRequest request) {
    if (publicEnabled)
      return (!publicRequireHttps || request.isSecure())
          && !"cross-site".equals(request.getHeader("Sec-Fetch-Site"));
    return enabled
        && LOOPBACK.contains(request.getRemoteAddr())
        && request.getHeader("Forwarded") == null
        && request.getHeader("X-Forwarded-For") == null
        && request.getHeader("X-Forwarded-Host") == null
        && request.getHeader("X-Real-IP") == null
        && !"cross-site".equals(request.getHeader("Sec-Fetch-Site"));
  }

  public void require(HttpServletRequest request) {
    if (!available(request))
      throw new ApiException(
          HttpStatus.FORBIDDEN,
          "REGISTRATION_DISABLED",
          "Sign up is unavailable. Contact your administrator or use an organization invitation.");
  }
}
