package com.trace.backend.entity;

import java.util.UUID;

public record SourceConnection(
    UUID id, String name, String platform, String ingestionMode, UUID ownerTeamId) {}
