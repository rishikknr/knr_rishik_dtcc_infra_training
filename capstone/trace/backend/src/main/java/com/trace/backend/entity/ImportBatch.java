package com.trace.backend.entity;

import com.trace.backend.dto.response.*;
import com.trace.backend.enums.ImportStatus;
import java.time.Instant;
import java.util.UUID;

public record ImportBatch(
    UUID id,
    UUID connectionId,
    UUID ownerTeamId,
    ImportStatus status,
    MetadataPreview preview,
    ImportStatistics comparison,
    ImportStatistics results,
    java.util.List<DatasetChange> datasetChanges,
    String filename,
    long fileSize,
    Instant createdAt,
    Instant committedAt,
    Instant expiresAt,
    String sourceType,
    String format) {}
