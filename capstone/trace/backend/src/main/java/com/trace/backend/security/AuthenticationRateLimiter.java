package com.trace.backend.security;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AuthenticationRateLimiter {
  private final int limit, maxKeys;
  private final long windowMillis;
  private final Map<String, Window> attempts = new HashMap<>();

  private record Window(long start, int count) {}

  public AuthenticationRateLimiter(
      @Value("${trace.security.rate-limit-attempts:20}") int limit,
      @Value("${trace.security.rate-limit-window-seconds:60}") int seconds,
      @Value("${trace.security.rate-limit-max-keys:10000}") int maxKeys) {
    if (limit < 1 || seconds < 1 || maxKeys < 1)
      throw new IllegalArgumentException("Invalid rate limits");
    this.limit = limit;
    this.windowMillis = seconds * 1000L;
    this.maxKeys = maxKeys;
  }

  public synchronized boolean allow(String key) {
    long now = System.currentTimeMillis();
    attempts.entrySet().removeIf(e -> now - e.getValue().start >= windowMillis);
    Window old = attempts.get(key);
    if (old == null && attempts.size() >= maxKeys) return false;
    int count = old == null ? 1 : old.count + 1;
    attempts.put(key, new Window(old == null ? now : old.start, Math.min(count, limit + 1)));
    return count <= limit;
  }
}
