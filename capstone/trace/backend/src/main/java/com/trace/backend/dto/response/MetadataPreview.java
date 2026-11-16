package com.trace.backend.dto.response;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record MetadataPreview(
    List<Dataset> datasets,
    List<String> warnings,
    Integer schemaVersion,
    List<Relationship> relationships,
    Boolean snapshotComplete,
    String inventoryScope) {
  public MetadataPreview(
      List<Dataset> datasets,
      List<String> warnings,
      Integer schemaVersion,
      List<Relationship> relationships) {
    this(datasets, warnings, schemaVersion, relationships, false, null);
  }

  public MetadataPreview(List<Dataset> datasets, List<String> warnings, Integer schemaVersion) {
    this(datasets, warnings, schemaVersion, List.of());
  }

  public MetadataPreview(List<Dataset> datasets, List<String> warnings) {
    this(datasets, warnings, 0);
  }

  public MetadataPreview(List<Dataset> datasets) {
    this(datasets, List.of(), 0);
  }

  public MetadataPreview {
    snapshotComplete = Boolean.TRUE.equals(snapshotComplete);
    relationships = relationships == null ? List.of() : List.copyOf(relationships);
    schemaVersion = schemaVersion == null ? 0 : schemaVersion;
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }

  public record Relationship(
      String sourceKey,
      String sourceType,
      String targetKey,
      String targetType,
      String relationshipType,
      String evidenceRef) {}

  public record Dataset(
      String externalKey,
      String name,
      List<Column> columns,
      String assetType,
      Map<String, Object> metadata) {
    public Dataset(String key, String name, List<Column> columns) {
      this(key, name, columns, "DATASET", Map.of());
    }

    public Dataset {
      assetType = assetType == null ? "DATASET" : assetType;
      metadata =
          metadata == null
              ? Map.of()
              : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata));
    }
  }

  public record Column(
      String name, String dataType, String description, Boolean nullable, Integer ordinalPosition) {
    public Column(String name, String type, String description) {
      this(name, type, description, null, null);
    }

    public Column(String name, String dataType) {
      this(name, dataType, null);
    }
  }

  public MetadataPreview validated() {
    if ((schemaVersion != 0 && schemaVersion != 1)
        || datasets == null
        || (schemaVersion == 0 && datasets.isEmpty())
        || datasets.size() > 1000
        || warnings.size() > 100
        || (snapshotComplete
            && (inventoryScope == null || !inventoryScope.matches("[a-f0-9]{64}"))))
      throw new IllegalArgumentException();
    if (warnings.stream().anyMatch(w -> !valid(w, 500))) throw new IllegalArgumentException();
    var keys = new HashSet<String>();
    int total = 0;
    for (var d : datasets) {
      if (d == null
          || !valid(d.externalKey(), 500)
          || !valid(d.name(), 255)
          || !keys.add(d.externalKey() + "\0" + d.assetType())
          || !Set.of(
                  "FILE",
                  "DATASET",
                  "TABLE",
                  "VIEW",
                  "SQL_TRANSFORMATION",
                  "SEMANTIC_MODEL",
                  "REPORT",
                  "VISUAL",
                  "MEASURE",
                  "WORKSPACE")
              .contains(d.assetType())
          || d.metadata().size() > 30
          || d.columns() == null
          || (schemaVersion == 0 && d.columns().isEmpty())
          || d.columns().size() > 2000) throw new IllegalArgumentException();
      var names = new HashSet<String>();
      for (var c : d.columns()) {
        if (c == null
            || !valid(c.name(), 255)
            || !valid(c.dataType(), 100)
            || !names.add(c.name())
            || (c.ordinalPosition() != null && c.ordinalPosition() < 1)
            || (c.description() != null && !valid(c.description(), 4000)))
          throw new IllegalArgumentException();
      }
      total += d.columns().size();
      if (total > 20000) throw new IllegalArgumentException();
    }
    if (relationships.size() > 2000) throw new IllegalArgumentException();
    for (var r : relationships) {
      if (r == null
          || !keys.contains(r.sourceKey() + "\0" + r.sourceType())
          || !keys.contains(r.targetKey() + "\0" + r.targetType())
          || !valid(r.evidenceRef(), 2000)) throw new IllegalArgumentException();
      boolean compatible =
          switch (r.relationshipType()) {
            case "REPORT_USES_MODEL" ->
                "SEMANTIC_MODEL".equals(r.sourceType()) && "REPORT".equals(r.targetType());
            case "VISUAL_USES_MEASURE" ->
                "MEASURE".equals(r.sourceType()) && "VISUAL".equals(r.targetType());
            case "REPORT_CONTAINS_VISUAL" ->
                "REPORT".equals(r.sourceType()) && "VISUAL".equals(r.targetType());
            case "MODEL_CONTAINS_MEASURE" ->
                "SEMANTIC_MODEL".equals(r.sourceType()) && "MEASURE".equals(r.targetType());
            default -> false;
          };
      if (!compatible) throw new IllegalArgumentException();
    }
    return this;
  }

  private static boolean valid(String s, int n) {
    return s != null
        && !s.isBlank()
        && s.length() <= n
        && s.chars().noneMatch(c -> c == 0 || (c < 32 && c != 10 && c != 9 && c != 13));
  }
}
