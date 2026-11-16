package com.trace.backend.service;

import com.trace.backend.dto.request.MetadataRequests.Mapping;
import com.trace.backend.dto.response.IdResponse;
import com.trace.backend.exception.ApiException;
import com.trace.backend.repository.*;
import com.trace.backend.tenancy.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LineageService {
  private static final Set<String> FLOW =
      Set.of("DATA_FLOW", "TRANSFORMATION_INPUT", "TRANSFORMATION_OUTPUT", "MODEL_SOURCE");
  private static final Set<String> KINDS =
      Set.of(
          "DATA_FLOW",
          "COLUMN_DERIVATION",
          "TRANSFORMATION_INPUT",
          "TRANSFORMATION_OUTPUT",
          "MODEL_SOURCE",
          "REPORT_USES_MODEL",
          "VISUAL_USES_MEASURE",
          "REPORT_CONTAINS_VISUAL",
          "MODEL_CONTAINS_MEASURE");
  private final TenantTransactions tenants;
  private final LineageRepository repository;
  private final ManualMetadataRepository assets;
  private final RegistryRepository guard;

  public LineageService(
      TenantTransactions tenants,
      LineageRepository repository,
      ManualMetadataRepository assets,
      RegistryRepository guard) {
    this.tenants = tenants;
    this.repository = repository;
    this.assets = assets;
    this.guard = guard;
  }

  private Map<String, Object> write(TenantContext t, UUID asset) {
    var a = assets.asset(t, asset);
    guard.requireTeamWrite(t, (UUID) a.get("owner_team_id"));
    return a;
  }

  public IdResponse create(Mapping r) {
    ManualMetadataService.text(r.evidenceRef(), 2000, "Evidence reference");
    ManualMetadataService.optional(r.description(), 4000);
    if (!KINDS.contains(r.relationshipType() == null ? "" : r.relationshipType()))
      ConnectionService.invalid("Choose a supported relationship type");
    return tenants.execute(
        t -> {
          repository.lock(t);
          boolean column = "COLUMN_DERIVATION".equals(r.relationshipType());
          UUID source = r.sourceAssetId(), target = r.targetAssetId();
          if (column) {
            if (r.sourceColumnId() == null || r.targetColumnId() == null)
              ConnectionService.invalid("Choose existing source and target columns");
            source = repository.columnAsset(t, r.sourceColumnId());
            target = repository.columnAsset(t, r.targetColumnId());
          } else if (source == null
              || target == null
              || r.sourceColumnId() != null
              || r.targetColumnId() != null)
            ConnectionService.invalid("Choose source and target assets");
          var s = write(t, source);
          var d = write(t, target);
          if (!column) {
            String st = (String) s.get("asset_type"), dt = (String) d.get("asset_type");
            if ("REPORT_CONTAINS_VISUAL".equals(r.relationshipType())
                && (!st.equals("REPORT") || !dt.equals("VISUAL")))
              ConnectionService.invalid("Direction is report to visual");
            if ("MODEL_CONTAINS_MEASURE".equals(r.relationshipType())
                && (!st.equals("SEMANTIC_MODEL") || !dt.equals("MEASURE")))
              ConnectionService.invalid("Direction is semantic model to measure");
            if ("TRANSFORMATION_INPUT".equals(r.relationshipType())
                && !dt.equals("SQL_TRANSFORMATION"))
              ConnectionService.invalid("Transformation input must target a SQL transformation");
            if ("TRANSFORMATION_OUTPUT".equals(r.relationshipType())
                && !st.equals("SQL_TRANSFORMATION"))
              ConnectionService.invalid("Transformation output must start at a SQL transformation");
            if ("REPORT_USES_MODEL".equals(r.relationshipType())
                && (!st.equals("SEMANTIC_MODEL") || !dt.equals("REPORT")))
              ConnectionService.invalid("Direction is semantic model to report");
            if ("MODEL_SOURCE".equals(r.relationshipType()) && !dt.equals("SEMANTIC_MODEL"))
              ConnectionService.invalid("Model source must target a semantic model");
            if ("VISUAL_USES_MEASURE".equals(r.relationshipType())
                && (!st.equals("MEASURE") || !dt.equals("VISUAL")))
              ConnectionService.invalid("Direction is measure to visual");
          }
          if (r.supersedes() != null) {
            var old = repository.get(t, r.supersedes());
            if (Boolean.TRUE.equals(old.get("column_mapping")) || column)
              ConnectionService.invalid(
                  "Supersession supports asset mappings; revoke and create column mappings"
                      + " explicitly");
            write(t, (UUID) old.get("source_asset_id"));
            write(t, (UUID) old.get("target_asset_id"));
            repository.revoke(t, r.supersedes(), false);
          }
          UUID from = column ? r.sourceColumnId() : source,
              to = column ? r.targetColumnId() : target;
          if (from.equals(to)
              || ((column || FLOW.contains(r.relationshipType()))
                  && repository.cycle(t, from, to, column)))
            throw new ApiException(
                HttpStatus.CONFLICT,
                "LINEAGE_CYCLE",
                "This mapping would create a data-flow cycle or self-dependency");
          return new IdResponse(repository.create(t, r));
        });
  }

  public Map<String, Object> get(UUID id) {
    return tenants.execute(t -> repository.get(t, id));
  }

  public Map<String, Boolean> revoke(UUID id) {
    return tenants.execute(
        t -> {
          repository.lock(t);
          var e = repository.get(t, id);
          boolean col = Boolean.TRUE.equals(e.get("column_mapping"));
          write(
              t,
              col
                  ? repository.columnAsset(t, (UUID) e.get("source_column_id"))
                  : (UUID) e.get("source_asset_id"));
          write(
              t,
              col
                  ? repository.columnAsset(t, (UUID) e.get("target_column_id"))
                  : (UUID) e.get("target_asset_id"));
          repository.revoke(t, id, col);
          return Map.of("revoked", true);
        });
  }

  public Map<String, Object> graph(UUID root, int depth, String direction) {
    if (depth < 1 || !Set.of("both", "upstream", "downstream").contains(direction))
      ConnectionService.invalid("Lineage depth must be positive with a supported direction");
    return tenants.execute(
        t -> {
          var nodes = new LinkedHashMap<UUID, Map<String, Object>>();
          var edges = new LinkedHashMap<UUID, Map<String, Object>>();

          var frontier = new LinkedHashSet<UUID>();
          frontier.add(root);
          nodes.put(root, assets.graphAsset(t, root));
          boolean truncated = false;
          for (int level = 0; level < depth && !frontier.isEmpty(); level++) {
            var next = new LinkedHashSet<UUID>();
            for (UUID node : frontier) {
              var rows = new ArrayList<Map<String, Object>>();
              if (!"METRIC_VERSION".equals(nodes.get(node).get("asset_type")))
                rows.addAll(repository.neighbors(t, node, direction));
              var associations = repository.metricAssociations(t, node, direction);
              for (var association : associations) {
                boolean outgoing = node.equals(association.get("source_asset_id")),
                    incoming = node.equals(association.get("target_asset_id"));
                if ((outgoing && !"upstream".equals(direction))
                    || (incoming && !"downstream".equals(direction))) rows.add(association);
              }
              if (rows.size() > 200 || associations.size() > 200) truncated = true;
              for (var e : rows) {
                if (edges.containsKey((UUID) e.get("id"))) continue;
                long additions =
                    java.util.stream.Stream.of(
                            (UUID) e.get("source_asset_id"), (UUID) e.get("target_asset_id"))
                        .distinct()
                        .filter(id -> !nodes.containsKey(id))
                        .count();
                if (edges.size() >= 200 || nodes.size() + additions > 100) {
                  truncated = true;
                  break;
                }
                edges.put((UUID) e.get("id"), e);
                for (String field : List.of("source_asset_id", "target_asset_id")) {
                  UUID id = (UUID) e.get(field);
                  if (!nodes.containsKey(id)) {
                    if (id.equals(e.get("metric_node_id")))
                      nodes.put(
                          id,
                          Map.of(
                              "id",
                              id,
                              "name",
                              e.get("metric_name"),
                              "asset_type",
                              "METRIC_VERSION",
                              "external_key",
                              id.toString()));
                    else nodes.put(id, assets.graphAsset(t, id));
                    next.add(id);
                  }
                }
              }
            }
            frontier = next;
          }
          var columns = repository.columnEdges(t, root);
          return Map.of(
              "nodes",
              nodes.values(),
              "edges",
              edges.values(),
              "columnEdges",
              columns.stream().limit(200).toList(),
              "history",
              repository.history(t, root),
              "truncated",
              truncated || columns.size() > 200,
              "limits",
              Map.of(
                  "requestedDepth",
                  depth,
                  "maximumNodes",
                  100,
                  "maximumEdges",
                  200,
                  "historyPageSize",
                  100),
              "label",
              "Potential impact from recorded relationships");
        });
  }
}
