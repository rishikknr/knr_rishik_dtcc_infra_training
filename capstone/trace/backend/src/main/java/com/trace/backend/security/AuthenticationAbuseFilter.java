package com.trace.backend.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

public final class AuthenticationAbuseFilter extends OncePerRequestFilter {
  private final AuthenticationRateLimiter limiter;
  private static final Set<String> PATHS =
      Set.of(
          "/api/auth/register",
          "/api/auth/login",
          "/api/auth/invitations/accept",
          "/api/auth/password/reset");

  public AuthenticationAbuseFilter(AuthenticationRateLimiter limiter) {
    this.limiter = limiter;
  }

  protected void doFilterInternal(HttpServletRequest q, HttpServletResponse r, FilterChain chain)
      throws IOException, ServletException {
    if ("POST".equals(q.getMethod())
        && (PATHS.contains(q.getServletPath())
            || q.getServletPath().endsWith("/password-recovery"))) {
      if (q.getContentLengthLong() > 16384) {
        SecurityResponses.error(r, 413, "REQUEST_TOO_LARGE", "Authentication request is too large");
        return;
      }
      if (!limiter.allow(q.getRemoteAddr() + ":" + q.getServletPath())) {
        r.setHeader("Retry-After", "60");
        SecurityResponses.error(r, 429, "RATE_LIMITED", "Too many attempts. Try again later");
        return;
      }
    }
    if (q.getContentType() != null
        && q.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
      byte[] body = q.getInputStream().readNBytes(16385);
      if (body.length > 16384) {
        SecurityResponses.error(r, 413, "REQUEST_TOO_LARGE", "Request is too large");
        return;
      }
      q =
          new HttpServletRequestWrapper(q) {
            public ServletInputStream getInputStream() {
              var input = new java.io.ByteArrayInputStream(body);
              return new ServletInputStream() {
                public int read() {
                  return input.read();
                }

                public boolean isFinished() {
                  return input.available() == 0;
                }

                public boolean isReady() {
                  return true;
                }

                public void setReadListener(ReadListener listener) {
                  throw new UnsupportedOperationException();
                }
              };
            }

            public java.io.BufferedReader getReader() {
              return new java.io.BufferedReader(
                  new java.io.InputStreamReader(
                      getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            }
          };
    }
    chain.doFilter(q, r);
  }
}
