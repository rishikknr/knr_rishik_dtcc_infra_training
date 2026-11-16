package com.trace.backend.entity;

import java.util.UUID;

public record Identity(
    UUID id,
    String email,
    String displayName,
    boolean active,
    String platformRole,
    long securityVersion) {}
