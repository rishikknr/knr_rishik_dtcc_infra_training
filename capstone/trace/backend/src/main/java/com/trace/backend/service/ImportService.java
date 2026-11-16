package com.trace.backend.service;

import com.trace.backend.dto.response.*;
import com.trace.backend.entity.AssetColumn;
import com.trace.backend.enums.ImportStatus;
import com.trace.backend.exception.ApiException;
import com.trace.backend.integration.processor.MetadataParser;
import com.trace.backend.mapper.RegistryMapper;
import com.trace.backend.repository.*;
import com.trace.backend.tenancy.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ImportService {
  private final TenantTransactions tenants;
  private final RegistryRepository registry;
  private final AssetRepository assets;
  private final AssetColumnRepository columns;
  private final MetadataImportRepository batches;
  private final MetadataParser parser;
  private final SourceConnectionRepository connections;

  public ImportService(
      TenantTransactions tenants,
      RegistryRepository registry,
      AssetRepository assets,
      AssetColumnRepository columns,
      MetadataImportRepository batches,
      MetadataParser parser,
      SourceConnectionRepository connections) {
    this.connections = connections;
    this.tenants = tenants;
    this.registry = registry;
    this.assets = assets;
    this.columns = columns;
    this.batches = batches;
    this.parser = parser;
  }

  public UUID preview(UUID connection, byte[] csv) {
    return preview(connection, csv, "metadata.csv");
  }

  public UUID preview(UUID connection, byte[] csv, String filename) {
    if (csv.length == 0)
      throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "Choose a non-empty CSV file");
    if (csv.length > 5 * 1024 * 1024)
      throw new ApiException(
          HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "CSV file must not exceed 5 MiB");
    if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".csv"))
      throw new ApiException(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE", "Choose a .csv metadata file");
    // Display only a sanitized basename. Never use filenames as filesystem paths.
    String display = filename.replace('\\', '/');
    display = display.substring(display.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
    if (display.length() > 255) display = display.substring(display.length() - 255);
    final String safeName = display;
    var initiator =
        tenants.execute(
            t -> {
              connections.connectionForImport(t, connection);
              return List.of(t.organizationId(), t.userId());
            });
    var parsed = parser.parse(csv).validated(); // Network I/O occurs outside transactions.
    return previewNormalized(connection, parsed, safeName, csv.length, "CSV", initiator);
  }

  public UUID previewNormalized(
      UUID connection, MetadataPreview parsed, String filename, long size) {
    return previewNormalized(connection, parsed, filename, size, "LIVE_DISCOVERY");
  }

  public UUID previewNormalized(
      UUID connection, MetadataPreview parsed, String filename, long size, String format) {
    var initiator =
        tenants.execute(
            t -> {
              connections.connectionForImport(t, connection);
              return List.of(t.organizationId(), t.userId());
            });
    return previewNormalized(connection, parsed.validated(), filename, size, format, initiator);
  }

  private UUID previewNormalized(
      UUID connection,
      MetadataPreview parsed,
      String filename,
      long size,
      String format,
      List<UUID> initiator) {
    return tenants.execute(
        t -> {
          if (!initiator.equals(List.of(t.organizationId(), t.userId())))
            throw RegistryRepository.missing();
          UUID team = connections.connectionForImport(t, connection);
          var plan = plan(t, connection, parsed, "LIVE_DISCOVERY".equals(format));
          return batches.create(
              t,
              connection,
              team,
              parsed,
              plan.statistics(),
              plan.changes(),
              filename,
              size,
              format);
        });
  }

  /** Runs inside the worker's fenced, freshly authorized tenant transaction. */
  public UUID previewForWorker(TenantContext t, UUID connection, MetadataPreview parsed) {
    parsed.validated();
    UUID team = connections.connectionForImport(t, connection);
    var comparison = plan(t, connection, parsed, true);
    return batches.create(
        t,
        connection,
        team,
        parsed,
        comparison.statistics(),
        comparison.changes(),
        "live-discovery.json",
        0,
        "LIVE_DISCOVERY");
  }

  public ImportResponse get(UUID id) {
    return tenants.execute(t -> RegistryMapper.batch(batches.get(t, id, false)));
  }

  public ImportResponse commit(UUID id) {
    return tenants.execute(
        t -> {
          var b = batches.get(t, id, true);
          UUID team = connections.connectionForImport(t, b.connectionId());
          registry.requireTeamWrite(t, b.ownerTeamId());
          if (b.status() == ImportStatus.COMMITTED) return RegistryMapper.batch(b);
          if (b.status() != ImportStatus.PREVIEW_READY || !b.expiresAt().isAfter(Instant.now()))
            throw new ApiException(
                HttpStatus.CONFLICT,
                "IMPORT_EXPIRED",
                "This import preview has expired. Upload the file again.");
          batches.requireCurrentConnection(t, id);
          var preview = b.preview().validated();
          var results =
              plan(t, b.connectionId(), preview, "LIVE_DISCOVERY".equals(b.format())).statistics();
          for (var d : preview.datasets()) {
            var old = assets.find(t, b.connectionId(), d.externalKey(), d.assetType());
            old.ifPresent(a -> registry.requireTeamWrite(t, a.ownerTeamId()));
            boolean live = "LIVE_DISCOVERY".equals(b.format());
            var sourceMetadata = sourceMetadata(d, preview, live);
            UUID asset =
                assets.upsert(
                    t,
                    b.connectionId(),
                    team,
                    id,
                    d.externalKey(),
                    d.name(),
                    d.assetType(),
                    sourceMetadata);
            for (var c : d.columns()) columns.upsert(t, asset, id, c);
            if (live && preview.snapshotComplete()) columns.inventory(t, asset, id);
          }
          assets.importRelationships(
              t, b.connectionId(), id, preview, "LIVE_DISCOVERY".equals(b.format()));
          if ("LIVE_DISCOVERY".equals(b.format()) && preview.snapshotComplete())
            assets.inventory(t, b.connectionId(), id, preview.inventoryScope());
          batches.committed(t, id, results);
          return RegistryMapper.batch(batches.get(t, id, false));
        });
  }

  private record Plan(ImportStatistics statistics, List<DatasetChange> changes) {}

  private Map<String, Object> sourceMetadata(
      MetadataPreview.Dataset d, MetadataPreview p, boolean live) {
    var metadata = new HashMap<>(d.metadata());
    metadata.put("sourceName", d.name());
    metadata.put("provenance", live ? "LIVE_DISCOVERY" : "IMPORTED_METADATA");
    if (live && p.inventoryScope() != null) metadata.put("inventoryScope", p.inventoryScope());
    return metadata;
  }

  private Plan plan(TenantContext t, UUID connection, MetadataPreview p, boolean live) {
    var changes = new ArrayList<DatasetChange>();
    int newD = 0, updatedD = 0, unchangedD = 0, newC = 0, updatedC = 0, unchangedC = 0, total = 0;
    for (var d : p.datasets()) {
      var old = assets.find(t, connection, d.externalKey(), d.assetType());
      Map<String, AssetColumn> existing =
          old.map(
                  a ->
                      columns.list(t, a.id()).stream()
                          .collect(Collectors.toMap(AssetColumn::name, Function.identity())))
              .orElse(Map.of());
      boolean changed =
          old.isPresent()
              && assets.changed(t, old.get().id(), d.name(), sourceMetadata(d, p, live), live);
      int startNew = newC, startUpdated = updatedC, startUnchanged = unchangedC;
      for (var c : d.columns()) {
        total++;
        var before = existing.get(c.name());
        if (before == null) {
          newC++;
          changed = true;
        } else if (!Objects.equals(before.dataType(), c.dataType())
            || (before.description() == null && c.description() != null)
            || (!before.nullableCurated()
                && c.nullable() != null
                && !Objects.equals(before.nullable(), c.nullable()))
            || (!before.ordinalCurated()
                && c.ordinalPosition() != null
                && !Objects.equals(before.ordinalPosition(), c.ordinalPosition()))) {
          updatedC++;
          changed = true;
        } else unchangedC++;
      }
      changes.add(
          new DatasetChange(
              d.externalKey(),
              old.isEmpty() ? "NEW" : changed ? "UPDATED" : "UNCHANGED",
              newC - startNew,
              updatedC - startUpdated,
              unchangedC - startUnchanged));
      if (old.isEmpty()) newD++;
      else if (changed) updatedD++;
      else unchangedD++;
    }
    return new Plan(
        new ImportStatistics(
            p.datasets().size(), total, newD, updatedD, unchangedD, newC, updatedC, unchangedC),
        List.copyOf(changes));
  }
}
