package com.trace.backend.entity;

import java.time.Instant;
import java.util.UUID;

public record Asset(
    UUID id,
    String name,
    String assetType,
    String platform,
    String externalKey,
    UUID connectionId,
    String connectionName,
    UUID ownerTeamId,
    String ownerTeamName,
    String owner,
    Instant lastSeenAt,
    Instant createdAt,
    long columnCount) {}
