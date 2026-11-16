package com.trace.backend.dto.request;

import java.util.Map;
import java.util.UUID;

public record ConnectionSettings(
    String name,
    UUID ownerTeamId,
    String connectionType,
    String description,
    Map<String, Object> configuration) {}
