package com.trace.backend.mapper;

import com.trace.backend.dto.response.*;
import com.trace.backend.entity.*;

public final class RegistryMapper {
  private RegistryMapper() {}

  public static ConnectionResponse connection(SourceConnection c) {
    return new ConnectionResponse(
        c.id(), c.name(), c.platform(), c.ingestionMode(), c.ownerTeamId());
  }

  public static AssetResponse asset(Asset a) {
    return new AssetResponse(
        a.id(),
        a.name(),
        a.assetType(),
        a.platform(),
        a.externalKey(),
        a.connectionId(),
        a.connectionName(),
        a.ownerTeamId(),
        a.ownerTeamName(),
        a.owner(),
        a.lastSeenAt(),
        a.createdAt(),
        a.columnCount());
  }

  public static ColumnResponse column(AssetColumn c) {
    return new ColumnResponse(
        c.id(),
        c.assetId(),
        c.name(),
        c.dataType(),
        c.description(),
        c.nullable(),
        c.ordinalPosition(),
        c.inventoryState());
  }

  public static ImportResponse batch(ImportBatch b) {
    var status =
        b.status() == com.trace.backend.enums.ImportStatus.PREVIEW_READY
                && b.expiresAt().isBefore(java.time.Instant.now())
            ? com.trace.backend.enums.ImportStatus.EXPIRED
            : b.status();
    return new ImportResponse(
        b.id(),
        b.connectionId(),
        status,
        b.sourceType(),
        b.format(),
        b.preview(),
        b.comparison(),
        b.results(),
        b.datasetChanges(),
        b.filename(),
        b.fileSize(),
        b.createdAt(),
        b.committedAt(),
        b.expiresAt());
  }
}
