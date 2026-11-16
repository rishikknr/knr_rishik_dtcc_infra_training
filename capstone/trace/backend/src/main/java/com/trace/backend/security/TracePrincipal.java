package com.trace.backend.security;

import java.util.*;
import org.springframework.security.core.userdetails.User;

public final class TracePrincipal extends User {
  private final UUID id;
  private final long version;

  public TracePrincipal(UUID id, String email, String hash, boolean active, long version) {
    super(email, hash, active, true, true, true, List.of());
    this.id = id;
    this.version = version;
  }

  public UUID id() {
    return id;
  }

  public long version() {
    return version;
  }
}
