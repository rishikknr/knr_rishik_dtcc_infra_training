package com.trace.backend.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class SecurityResponses {
  private SecurityResponses() {}

  public static void error(HttpServletResponse r, int status, String code, String message)
      throws IOException {
    r.setStatus(status);
    r.setContentType("application/json");
    r.getWriter()
        .write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"errors\":[]}");
  }

  public static void success(HttpServletResponse r) throws IOException {
    r.setContentType("application/json");
    r.getWriter().write("{\"ok\":true}");
  }
}
