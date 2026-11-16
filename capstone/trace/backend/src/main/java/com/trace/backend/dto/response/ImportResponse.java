package com.trace.backend.dto.response;

import com.trace.backend.enums.ImportStatus;
import java.time.Instant;
import java.util.UUID;

public record ImportResponse(
    UUID id,
    UUID connectionId,
    ImportStatus status,
    String sourceType,
    String format,
    MetadataPreview preview,
    ImportStatistics comparison,
    ImportStatistics results,
    java.util.List<DatasetChange> datasetChanges,
    String filename,
    long fileSize,
    Instant createdAt,
    Instant committedAt,
    Instant expiresAt) {}
