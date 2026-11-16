package com.trace.backend.service;

import com.trace.backend.dto.request.MetadataRequests.*;
import com.trace.backend.dto.response.*;
import com.trace.backend.exception.ApiException;
import com.trace.backend.integration.processor.IntegrationProcessor;
import com.trace.backend.repository.*;
import com.trace.backend.tenancy.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ManualMetadataService {
  public static final Set<String> TYPES =
      Set.of(
          "FILE",
          "DATASET",
          "TABLE",
          "VIEW",
          "SQL_TRANSFORMATION",
          "SEMANTIC_MODEL",
          "REPORT",
          "VISUAL",
          "MEASURE",
          "WORKSPACE");
  private final TenantTransactions tenants;
  private final ManualMetadataRepository repository;
  private final RegistryRepository guard;
  private final ConnectionRepository connections;
  private final IntegrationProcessor processor;
  private final ImportService imports;

  public ManualMetadataService(
      TenantTransactions tenants,
      ManualMetadataRepository repository,
      RegistryRepository guard,
      ConnectionRepository connections,
      IntegrationProcessor processor,
      ImportService imports) {
    this.tenants = tenants;
    this.repository = repository;
    this.guard = guard;
    this.connections = connections;
    this.processor = processor;
    this.imports = imports;
  }

  public static String text(String s, int max, String field) {
    if (s == null || s.isBlank() || s.length() > max || s.indexOf('\0') >= 0)
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "INVALID_METADATA",
          field + " is required and must be at most " + max + " characters");
    return s.trim();
  }

  public static void optional(String s, int max) {
    if (s != null && (s.length() > max || s.indexOf('\0') >= 0))
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "INVALID_METADATA", "Metadata text exceeds the supported limit");
  }

  private void column(Column c) {
    text(c.name(), 255, "Column name");
    text(c.dataType(), 100, "Data type");
    optional(c.description(), 4000);
    if (c.ordinalPosition() != null && c.ordinalPosition() < 1)
      ConnectionService.invalid("Column ordinal must be positive");
  }

  public IdResponse create(Asset r) {
    text(r.name(), 255, "Name");
    text(r.externalKey(), 500, "Qualified identifier");
    optional(r.description(), 4000);
    optional(r.owner(), 150);
    optional(r.technicalOwner(), 150);
    optional(r.businessOwner(), 150);
    if (r.tagIds() != null && r.tagIds().size() > 100)
      ConnectionService.invalid("At most 100 tags per asset");
    if (!TYPES.contains(r.assetType() == null ? "" : r.assetType())
        || r.connectionId() == null
        || r.ownerTeamId() == null)
      ConnectionService.invalid("Select a supported asset type, connection and owning team");
    if (r.columns() != null) {
      if (r.columns().size() > 2000) ConnectionService.invalid("Too many columns");
      var names = new HashSet<String>();
      for (var c : r.columns()) {
        column(c);
        if (!names.add(c.name())) ConnectionService.invalid("Duplicate column name");
      }
    }
    return tenants.execute(
        t -> {
          var c = connections.get(t, r.connectionId(), true);
          guard.requireTeamWrite(t, (UUID) c.get("owner_team_id"));
          if ("DISABLED".equals(c.get("status")))
            ConnectionService.invalid("Connection is disabled");
          return new IdResponse(repository.create(t, r, (String) c.get("platform")));
        });
  }

  public Map<String, Object> get(UUID id) {
    return tenants.execute(t -> repository.asset(t, id));
  }

  public Map<String, Object> update(UUID id, Asset r) {
    text(r.name(), 255, "Name");
    optional(r.description(), 4000);
    optional(r.owner(), 150);
    optional(r.technicalOwner(), 150);
    optional(r.businessOwner(), 150);
    if (r.tagIds() != null && r.tagIds().size() > 100)
      ConnectionService.invalid("At most 100 tags per asset");
    return tenants.execute(
        t -> {
          var old = repository.asset(t, id);
          guard.requireTeamWrite(t, (UUID) old.get("owner_team_id"));
          if (!repository.update(t, id, r))
            throw new ApiException(
                HttpStatus.CONFLICT,
                "REVISION_CONFLICT",
                "This asset changed. Reload before saving.");
          return repository.asset(t, id);
        });
  }

  public IdResponse column(UUID asset, UUID id, Column r) {
    column(r);
    return tenants.execute(
        t -> {
          var a = repository.asset(t, asset);
          guard.requireTeamWrite(t, (UUID) a.get("owner_team_id"));
          return new IdResponse(repository.column(t, asset, id, r));
        });
  }

  public ImportResponse preview(
      UUID connection,
      byte[] content,
      String filename,
      String format,
      Map<String, String> mapping,
      String dialect) {
    if (content.length == 0 || content.length > 5 * 1024 * 1024)
      ConnectionService.invalid("Choose a non-empty file up to 5 MiB");
    var identity =
        tenants.execute(
            t -> {
              var c = connections.get(t, connection, true);
              guard.requireTeamWrite(t, (UUID) c.get("owner_team_id"));
              if ("DISABLED".equals(c.get("status")))
                ConnectionService.invalid("Connection is disabled");
              return List.of(t.organizationId(), t.userId());
            });
    String safe = filename == null ? "metadata" : filename.replace('\\', '/');
    safe = safe.substring(safe.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
    if (safe.length() > 255) safe = safe.substring(safe.length() - 255);
    var input = processor.parse(content, format, mapping, dialect, safe);
    var parsed =
        new MetadataPreview(
            input.datasets().stream()
                .map(
                    d -> {
                      var metadata = new HashMap<>(d.metadata());
                      metadata.put("provenance", "IMPORTED_METADATA");
                      return new MetadataPreview.Dataset(
                          d.externalKey(), d.name(), d.columns(), d.assetType(), metadata);
                    })
                .toList(),
            input.warnings(),
            1,
            input.relationships());
    tenants.execute(
        t -> {
          if (!identity.equals(List.of(t.organizationId(), t.userId())))
            throw RegistryRepository.missing();
          return null;
        });
    return imports.get(imports.previewNormalized(connection, parsed, safe, content.length, format));
  }

  public IdResponse tag(String name) {
    text(name, 80, "Tag name");
    return tenants.execute(
        t -> {
          if (!Set.of("OWNER", "ADMIN").contains(t.organizationRole()))
            com.trace.backend.security.AuthorizationPolicy.deny();
          return new IdResponse(repository.tag(t, name.trim()));
        });
  }

  public List<Map<String, Object>> profiles() {
    return tenants.execute(repository::profiles);
  }

  public IdResponse profile(MappingProfile r) {
    text(r.name(), 150, "Profile name");
    var fields =
        Set.of(
            "database",
            "schema",
            "table",
            "column",
            "data_type",
            "description",
            "external_key",
            "name",
            "asset_type",
            "bucket",
            "key",
            "size",
            "last_modified",
            "etag",
            "report_name",
            "visual_name",
            "measure_name",
            "metric_name");
    if (r.mapping() == null
        || r.mapping().entrySet().stream()
            .anyMatch(
                e ->
                    e.getKey() == null
                        || e.getKey().isBlank()
                        || e.getKey().length() > 255
                        || e.getValue() == null
                        || !fields.contains(e.getValue()))
        || r.mapping().size() > 30
        || new HashSet<>(r.mapping().values()).size() != r.mapping().size())
      ConnectionService.invalid("Each canonical field can be mapped once");
    return tenants.execute(
        t -> {
          var c = connections.get(t, r.connectionId(), true);
          guard.requireTeamWrite(t, (UUID) c.get("owner_team_id"));
          var profile = repository.profile(t, r);
          repository.audit(t, profile, "IMPORT_MAPPING_SAVED");
          return new IdResponse(profile);
        });
  }

  public List<Map<String, Object>> history() {
    return tenants.execute(repository::history);
  }
}
