package com.trace.backend.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestDiagnosticsFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString();
    response.setHeader("X-Request-ID", id);
    long started = System.nanoTime();
    try (var ignored = MDC.putCloseable("requestId", id)) {
      try {
        chain.doFilter(request, response);
      } finally {
        // Route template, never query strings, request bodies, cookies, or credentials.
        Object route =
            request.getAttribute(
                org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        LoggerFactory.getLogger(getClass())
            .info(
                "request id={} method={} operation={} status={} durationMs={}",
                id,
                request.getMethod(),
                route == null ? "unmatched" : route,
                response.getStatus(),
                (System.nanoTime() - started) / 1_000_000);
      }
    }
  }
}
