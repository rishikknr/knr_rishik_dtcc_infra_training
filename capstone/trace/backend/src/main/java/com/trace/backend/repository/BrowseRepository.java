package com.trace.backend.repository;

import com.trace.backend.dto.response.PageResponse;
import com.trace.backend.tenancy.TenantContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class BrowseRepository {
  private record Source(
      String select, String from, String name, String order, String parent, boolean creator) {}

  private static final Map<String, Source> SOURCES =
      Map.ofEntries(
          Map.entry(
              "connections",
              new Source(
                  "r.id,r.name,r.platform,r.platform AS"
                      + " connection_type,r.ingestion_mode,r.owner_team_id,r.description,r.configuration::text,r.status,r.reason_code,r.last_tested_at,r.last_successful_sync_at,r.created_at,r.updated_at,EXISTS(SELECT"
                      + " 1 FROM connection_secret_storage s WHERE"
                      + " s.organization_id=r.organization_id AND s.connection_id=r.id) AS"
                      + " has_credentials,t.name AS owner_team_name,(SELECT run.status FROM"
                      + " connection_sync_runs run WHERE run.organization_id=r.organization_id AND"
                      + " run.connection_id=r.id ORDER BY run.started_at DESC,run.id LIMIT 1) AS"
                      + " recent_run_status,r.last_successful_test_at,r.last_test_diagnostic_id,"
                      + " (SELECT count(*) FROM assets a WHERE a.organization_id=r.organization_id"
                      + " AND a.connection_id=r.id) AS registered_asset_count, (SELECT"
                      + " run.records_discovered FROM connection_sync_runs run WHERE"
                      + " run.organization_id=r.organization_id AND run.connection_id=r.id AND"
                      + " run.status IN ('COMPLETED','PARTIALLY_COMPLETED') ORDER BY"
                      + " run.finished_at DESC,run.id LIMIT 1) AS discovered_asset_count, (SELECT"
                      + " run.error_code FROM connection_sync_runs run WHERE"
                      + " run.organization_id=r.organization_id AND run.connection_id=r.id ORDER BY"
                      + " run.queued_at DESC,run.id LIMIT 1) AS recent_run_error_code",
                  "source_connections r LEFT JOIN teams t ON t.organization_id=r.organization_id"
                      + " AND t.id=r.owner_team_id",
                  "r.name",
                  "r.name,r.id",
                  null,
                  false)),
          Map.entry(
              "assets",
              new Source(
                  "r.id,r.name,r.asset_type,r.metadata->>'objectType' AS"
                      + " object_type,r.platform,r.external_key,r.connection_id,c.name AS"
                      + " connection_name,r.owner_team_id,t.name AS"
                      + " owner_team_name,r.owner,r.business_owner,r.inventory_state,r.last_seen_at,r.created_at,"
                      + " (SELECT COALESCE(jsonb_agg(tag.name ORDER BY tag.name),'[]')::text FROM"
                      + " asset_tags at JOIN tags tag ON tag.organization_id=at.organization_id AND"
                      + " tag.id=at.tag_id WHERE at.organization_id=r.organization_id AND"
                      + " at.asset_id=r.id) AS tags,(SELECT count(*) FROM asset_columns ac WHERE"
                      + " ac.organization_id=r.organization_id AND ac.asset_id=r.id) AS"
                      + " column_count",
                  "assets r JOIN source_connections c ON c.organization_id=r.organization_id AND"
                      + " c.id=r.connection_id LEFT JOIN teams t ON"
                      + " t.organization_id=r.organization_id AND t.id=r.owner_team_id",
                  "r.name || ' ' || r.external_key",
                  "r.name,r.id",
                  null,
                  false)),
          Map.entry(
              "teams",
              new Source(
                  "r.id,r.name,r.slug,r.description,r.active",
                  "teams r",
                  "r.name",
                  "r.name,r.id",
                  null,
                  false)),
          Map.entry(
              "formulas",
              new Source(
                  "r.*,(SELECT t.name FROM teams t WHERE t.organization_id=r.organization_id AND"
                      + " t.id=r.owner_team_id) AS owner_team_name,(SELECT fv.version FROM"
                      + " formula_versions fv WHERE fv.organization_id=r.organization_id AND"
                      + " fv.formula_id=r.id ORDER BY fv.version DESC LIMIT 1) AS"
                      + " latest_version,(SELECT fv.state FROM formula_versions fv WHERE"
                      + " fv.organization_id=r.organization_id AND fv.formula_id=r.id ORDER BY"
                      + " fv.version DESC LIMIT 1) AS latest_state,(SELECT fv.created_at FROM"
                      + " formula_versions fv WHERE fv.organization_id=r.organization_id AND"
                      + " fv.formula_id=r.id ORDER BY fv.version DESC LIMIT 1) AS"
                      + " latest_created_at,(SELECT count(*) FROM formula_versions fv WHERE"
                      + " fv.organization_id=r.organization_id AND fv.formula_id=r.id) AS"
                      + " version_count",
                  "formulas r",
                  "r.name || ' ' || r.description",
                  "r.name,r.id",
                  null,
                  false)),
          Map.entry(
              "metrics",
              new Source(
                  "r.*,(SELECT t.name FROM teams t WHERE t.organization_id=r.organization_id AND"
                      + " t.id=r.owner_team_id) AS owner_team_name,(SELECT mv.version FROM"
                      + " metric_versions mv WHERE mv.organization_id=r.organization_id AND"
                      + " mv.metric_id=r.id ORDER BY mv.version DESC LIMIT 1) AS"
                      + " latest_version,(SELECT f.name FROM metric_versions mv JOIN"
                      + " formula_versions fv ON fv.organization_id=mv.organization_id AND"
                      + " fv.id=mv.formula_version_id JOIN formulas f ON"
                      + " f.organization_id=fv.organization_id AND f.id=fv.formula_id WHERE"
                      + " mv.organization_id=r.organization_id AND mv.metric_id=r.id ORDER BY"
                      + " mv.version DESC LIMIT 1) AS formula_name,(SELECT count(DISTINCT"
                      + " rm.asset_id) FROM report_mappings rm JOIN metric_versions mv ON"
                      + " mv.organization_id=rm.organization_id AND mv.id=rm.metric_version_id"
                      + " WHERE mv.organization_id=r.organization_id AND mv.metric_id=r.id AND"
                      + " rm.active) AS consumer_count",
                  "metrics r",
                  "r.name || ' ' || r.business_key || ' ' || r.description",
                  "r.name,r.id",
                  null,
                  false)),
          Map.entry(
              "imports",
              new Source(
                  "r.id,r.connection_id,r.status,r.filename,r.created_at,r.committed_at",
                  "import_batches r",
                  "COALESCE(r.filename,'')",
                  "r.created_at DESC,r.id",
                  null,
                  true)),
          Map.entry(
              "sync-runs",
              new Source(
                  ConnectionRepository.RUN_FIELDS.replace("warnings::text", "warnings::text"),
                  "connection_sync_runs r",
                  "r.status",
                  "r.started_at DESC,r.id",
                  "r.connection_id",
                  false)),
          Map.entry(
              "mappings",
              new Source(
                  "r.*",
                  "lineage_edges r",
                  "r.relationship_type",
                  "r.created_at DESC,r.id",
                  null,
                  false)),
          Map.entry(
              "column-mappings",
              new Source(
                  "r.*,s.name AS source_column,t.name AS target_column,s.asset_id AS"
                      + " source_asset_id,t.asset_id AS target_asset_id",
                  "column_lineage r JOIN asset_columns s ON s.organization_id=r.organization_id AND"
                      + " s.id=r.source_column_id JOIN asset_columns t ON"
                      + " t.organization_id=r.organization_id AND t.id=r.target_column_id",
                  "COALESCE(r.expression,'')",
                  "r.created_at DESC,r.id",
                  null,
                  false)),
          Map.entry(
              "formula-versions",
              new Source(
                  "r.*,COALESCE((SELECT jsonb_agg(d.asset_id) FROM formula_dependencies d WHERE"
                      + " d.organization_id=r.organization_id AND d.version_id=r.id),'[]')::text AS"
                      + " referenced_assets,COALESCE((SELECT jsonb_agg(d.column_id) FROM"
                      + " formula_column_dependencies d WHERE d.organization_id=r.organization_id"
                      + " AND d.version_id=r.id),'[]')::text AS referenced_columns",
                  "formula_versions r",
                  "r.state",
                  "r.version DESC,r.id",
                  "r.formula_id",
                  false)),
          Map.entry(
              "metric-versions",
              new Source(
                  "r.id,r.organization_id,r.metric_id,r.version,r.formula_version_id,r.created_at,r.definition_snapshot::text"
                      + " AS definition_snapshot,fv.expression,fv.formula_id,fv.version AS"
                      + " formula_version,f.name AS formula_name",
                  "metric_versions r JOIN formula_versions fv ON"
                      + " fv.organization_id=r.organization_id AND fv.id=r.formula_version_id JOIN"
                      + " formulas f ON f.organization_id=fv.organization_id AND"
                      + " f.id=fv.formula_id",
                  "r.version::text",
                  "r.version DESC,r.id",
                  "r.metric_id",
                  false)),
          Map.entry(
              "metric-mappings",
              new Source(
                  "r.*,a.name AS asset_name,a.asset_type,m.name AS"
                      + " metric_name,v.version,m.owner_team_id AS metric_team_id,a.owner_team_id"
                      + " AS asset_team_id",
                  "report_mappings r JOIN assets a ON a.organization_id=r.organization_id AND"
                      + " a.id=r.asset_id JOIN metric_versions v ON"
                      + " v.organization_id=r.organization_id AND v.id=r.metric_version_id JOIN"
                      + " metrics m ON m.organization_id=v.organization_id AND m.id=v.metric_id",
                  "m.name",
                  "r.created_at DESC,r.id",
                  null,
                  false)),
          Map.entry(
              "profiles",
              new Source(
                  "r.id,r.connection_id,r.name,r.mapping::text",
                  "import_mapping_profiles r",
                  "r.name",
                  "r.name,r.id",
                  null,
                  false)),
          Map.entry(
              "tags",
              new Source(
                  "r.id,r.name,r.created_at", "tags r", "r.name", "r.name,r.id", null, false)),
          Map.entry(
              "metadata-history",
              new Source(
                  "r.*",
                  "metadata_change_history r",
                  "r.event_type",
                  "r.created_at DESC,r.id",
                  "r.asset_id",
                  false)),
          Map.entry(
              "asset-history",
              new Source(
                  "r.*",
                  "asset_curation_history r",
                  "r.event_type",
                  "r.created_at DESC,r.id",
                  "r.asset_id",
                  false)),
          Map.entry(
              "sync-attempts",
              new Source(
                  "r.*,r.run_id::text || ':' || r.attempt::text AS id",
                  "connection_sync_attempts r",
                  "r.status",
                  "r.attempt,r.run_id",
                  "r.run_id",
                  false)),
          Map.entry(
              "columns",
              new Source("r.*", "asset_columns r", "r.name", "r.name,r.id", "r.asset_id", false)));
  private final JdbcTemplate jdbc;
  private final RegistryRepository guard;
  private final ObjectMapper json;

  public BrowseRepository(JdbcTemplate jdbc, RegistryRepository guard, ObjectMapper json) {
    this.jdbc = jdbc;
    this.guard = guard;
    this.json = json;
  }

  public PageResponse<Map<String, Object>> page(
      TenantContext t,
      String kind,
      int limit,
      int offset,
      String q,
      UUID parent,
      String type,
      String status,
      String sort,
      UUID source) {
    return page(t, kind, limit, offset, q, parent, type, status, sort, source, Map.of());
  }

  public PageResponse<Map<String, Object>> page(
      TenantContext t,
      String kind,
      int limit,
      int offset,
      String q,
      UUID parent,
      String type,
      String status,
      String sort,
      UUID source,
      Map<String, String> filters) {
    guard.requireTransaction(t);
    jdbc.execute("SET LOCAL statement_timeout='5s'");
    var s = SOURCES.get(kind);
    if (s == null) throw RegistryRepository.missing();
    if (s.parent() != null && parent == null)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "Parent ID required");
    String where = " WHERE r.organization_id=?";
    var args = new ArrayList<Object>();
    args.add(t.organizationId());
    if (s.creator()) {
      where += " AND r.created_by_user_id=?";
      args.add(t.userId());
    }
    if (s.parent() != null) {
      where += " AND " + s.parent() + "=?";
      args.add(parent);
    }
    if (!q.isBlank()) {
      where += " AND strpos(lower(" + s.name() + "),lower(?))>0";
      args.add(q);
    }
    if (kind.equals("assets") && !type.isBlank()) {
      where +=
          switch (type) {
            case "VIEW" ->
                " AND (r.asset_type=? OR (r.asset_type='DATASET' AND"
                    + " r.metadata->>'objectType'='VIEW'))";
            case "TABLE" ->
                " AND (r.asset_type=? OR (r.asset_type='DATASET' AND"
                    + " r.metadata->>'objectType'='BASE TABLE'))";
            default -> " AND r.asset_type=?";
          };
      args.add(type);
    }
    if (kind.equals("mappings") && !type.isBlank()) {
      where += " AND r.relationship_type=?";
      args.add(type);
    }
    if (kind.equals("mappings") && !filters.getOrDefault("provenance", "").isBlank()) {
      where += " AND r.provenance=?";
      args.add(filters.get("provenance"));
    }
    if (kind.equals("formulas") && !type.isBlank()) {
      where += " AND r.language=?";
      args.add(type);
    }
    if (kind.equals("formulas") && !status.isBlank()) {
      where +=
          " AND COALESCE((SELECT fv.state FROM formula_versions fv WHERE"
              + " fv.organization_id=r.organization_id AND fv.formula_id=r.id ORDER BY fv.version"
              + " DESC LIMIT 1),'NO_VERSION')=?";
      args.add(status);
    }
    if (kind.equals("metrics")) {
      String owner = filters.getOrDefault("owner", "");
      if ("missing".equals(owner)) where += " AND nullif(trim(r.business_owner),'') IS NULL";
      if ("assigned".equals(owner)) where += " AND nullif(trim(r.business_owner),'') IS NOT NULL";
    }
    if (kind.equals("connections") && !type.isBlank()) {
      where += " AND r.platform=?";
      args.add(type);
    }
    if (kind.equals("assets") && source != null) {
      where += " AND r.connection_id=?";
      args.add(source);
    }
    if (!status.isBlank() && Set.of("connections", "imports", "sync-runs").contains(kind)) {
      where += " AND r.status=?";
      args.add(status);
    }
    if (parent != null && Set.of("assets", "connections").contains(kind)) {
      where += " AND r.owner_team_id=?";
      args.add(parent);
    }
    if (parent != null && Set.of("mappings", "column-mappings").contains(kind)) {
      where +=
          kind.equals("mappings")
              ? " AND (r.source_asset_id=? OR r.target_asset_id=?)"
              : " AND (s.asset_id=? OR t.asset_id=?)";
      args.add(parent);
      args.add(parent);
    }
    if (kind.equals("assets")) {
      for (var entry : Map.of("database", 1, "schema", 2).entrySet()) {
        String value = filters.getOrDefault(entry.getKey(), "");
        if (!value.isBlank()) {
          where +=
              " AND r.platform='SNOWFLAKE' AND split_part(CASE WHEN"
                  + " r.asset_type='SQL_TRANSFORMATION' THEN COALESCE((SELECT ta.external_key FROM"
                  + " assets ta WHERE ta.organization_id=r.organization_id AND"
                  + " ta.id::text=r.metadata->>'targetAssetId'),r.external_key) ELSE r.external_key"
                  + " END,'.',"
                  + entry.getValue()
                  + ")=?";
          args.add(value);
        }
      }
      String owner = filters.getOrDefault("owner", "");
      if ("missing".equals(owner)) where += " AND nullif(trim(r.owner),'') IS NULL";
      else if (!owner.isBlank()) {
        where += " AND r.owner_team_id::text=?";
        args.add(owner);
      }
      String fresh = filters.getOrDefault("freshness", "");
      if ("unknown".equals(fresh)) where += " AND r.last_seen_at IS NULL";
      if ("older-7d".equals(fresh)) where += " AND r.last_seen_at < now()-interval '7 days'";
      if ("recent-7d".equals(fresh)) where += " AND r.last_seen_at >= now()-interval '7 days'";
      String lineage = filters.getOrDefault("lineage", "");
      if (Set.of("recorded", "missing").contains(lineage))
        where +=
            ("missing".equals(lineage) ? " AND NOT" : " AND")
                + " EXISTS(SELECT 1 FROM lineage_edges le WHERE"
                + " le.organization_id=r.organization_id AND le.active AND (le.source_asset_id=r.id"
                + " OR le.target_asset_id=r.id))";
    }
    long total =
        jdbc.queryForObject("SELECT count(*) FROM " + s.from() + where, Long.class, args.toArray());
    args.add(limit);
    args.add(offset);
    String order =
        switch (sort) {
          case "name-desc" -> "r.name DESC,r.id";
          case "last-seen" -> "r.last_seen_at DESC NULLS LAST,r.name,r.id";
          default -> s.order();
        };
    var rows =
        jdbc.queryForList(
            "SELECT "
                + s.select()
                + " FROM "
                + s.from()
                + where
                + " ORDER BY "
                + order
                + " LIMIT ? OFFSET ?",
            args.toArray());
    if (kind.equals("connections"))
      for (var row : rows)
        row.put("configuration", json.readValue((String) row.get("configuration"), Map.class));
    if (kind.equals("assets"))
      for (var row : rows) row.put("tags", json.readValue((String) row.get("tags"), List.class));
    return new PageResponse<>(rows, total, limit, offset, (long) offset + rows.size() < total);
  }
}
