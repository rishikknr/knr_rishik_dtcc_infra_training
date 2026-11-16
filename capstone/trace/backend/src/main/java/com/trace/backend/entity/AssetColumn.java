package com.trace.backend.entity;

import java.util.UUID;

public record AssetColumn(
    UUID id,
    UUID assetId,
    String name,
    String dataType,
    String description,
    Boolean nullable,
    Integer ordinalPosition,
    boolean nullableCurated,
    boolean ordinalCurated,
    String inventoryState) {
  public AssetColumn(
      UUID id,
      UUID assetId,
      String name,
      String type,
      String description,
      Boolean nullable,
      Integer ordinal) {
    this(id, assetId, name, type, description, nullable, ordinal, false, false, "CURRENT");
  }

  public AssetColumn(UUID id, UUID assetId, String name, String dataType, String description) {
    this(id, assetId, name, dataType, description, null, null);
  }
}
