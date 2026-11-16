package com.trace.backend.dto.response;

import java.time.Instant;
import java.util.UUID;

// Preserve Step 07 snake_case response keys.
public record AssetResponse(
    UUID id,
    String name,
    String asset_type,
    String platform,
    String external_key,
    UUID connection_id,
    String connection_name,
    UUID owner_team_id,
    String owner_team_name,
    String owner,
    Instant last_seen_at,
    Instant created_at,
    long column_count) {}
