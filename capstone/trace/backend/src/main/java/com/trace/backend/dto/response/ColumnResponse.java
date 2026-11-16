package com.trace.backend.dto.response;

import java.util.UUID;

public record ColumnResponse(
    UUID id,
    UUID asset_id,
    String name,
    String data_type,
    String description,
    Boolean nullable,
    Integer ordinal_position,
    String inventory_state) {}
