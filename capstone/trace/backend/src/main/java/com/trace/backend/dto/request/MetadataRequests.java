package com.trace.backend.dto.request;

import java.util.*;

public final class MetadataRequests {
  private MetadataRequests() {}

  public record Column(
      String name,
      String dataType,
      String description,
      Boolean nullable,
      Integer ordinalPosition) {}

  public record Asset(
      UUID connectionId,
      UUID ownerTeamId,
      String name,
      String assetType,
      String externalKey,
      String description,
      String owner,
      List<Column> columns,
      Integer revision,
      String technicalOwner,
      String businessOwner,
      List<UUID> tagIds) {
    public Asset(
        UUID connectionId,
        UUID ownerTeamId,
        String name,
        String assetType,
        String externalKey,
        String description,
        String owner,
        List<Column> columns,
        Integer revision) {
      this(
          connectionId,
          ownerTeamId,
          name,
          assetType,
          externalKey,
          description,
          owner,
          columns,
          revision,
          null,
          null,
          null);
    }
  }

  public record Mapping(
      UUID sourceAssetId,
      UUID targetAssetId,
      UUID sourceColumnId,
      UUID targetColumnId,
      String relationshipType,
      String evidenceRef,
      String description,
      UUID supersedes) {}

  public record Formula(
      String name,
      String description,
      String category,
      String language,
      UUID ownerTeamId,
      UUID assetId) {}

  public record FormulaVersion(
      String expression,
      String dialect,
      String state,
      List<UUID> referencedAssets,
      List<UUID> referencedColumns) {}

  public record DraftEdit(FormulaVersion definition, int expectedRevision) {}

  public record Validation(String expression, String language, String dialect) {}

  public record Metric(
      String name,
      String businessKey,
      String description,
      String unit,
      String aggregation,
      String grain,
      String businessOwner,
      String technicalOwner,
      UUID ownerTeamId) {}

  public record MetricEdit(Metric definition, int expectedRevision) {}

  public record MetricVersion(UUID formulaVersionId) {}

  public record MetricMapping(UUID metricVersionId, UUID assetId, String evidenceRef) {}

  public record MappingProfile(UUID connectionId, String name, Map<String, String> mapping) {}
}
