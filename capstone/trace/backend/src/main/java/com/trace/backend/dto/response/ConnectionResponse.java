package com.trace.backend.dto.response;

import java.util.UUID;

public record ConnectionResponse(
    UUID id, String name, String platform, String ingestion_mode, UUID owner_team_id) {}
