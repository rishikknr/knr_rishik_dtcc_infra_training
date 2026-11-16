package com.trace.backend.repository;

import com.trace.backend.dto.request.MetadataRequests.Mapping;
import com.trace.backend.tenancy.TenantContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LineageRepository {
  private final JdbcTemplate jdbc;
  private final RegistryRepository guard;

  public LineageRepository(JdbcTemplate jdbc, RegistryRepository guard) {
    this.jdbc = jdbc;
    this.guard = guard;
  }

  public void lock(TenantContext t) {
    guard.requireTransaction(t);
    jdbc.execute("SET LOCAL statement_timeout='5s'");
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?::text,10))",
        Object.class,
        t.organizationId().toString());
  }

  public UUID columnAsset(TenantContext t, UUID column) {
    guard.requireTransaction(t);
    return (UUID)
        RegistryRepository.one(
                jdbc.queryForList(
                    "SELECT asset_id FROM asset_columns WHERE organization_id=? AND id=?",
                    t.organizationId(),
                    column))
            .get("asset_id");
  }

  public boolean cycle(TenantContext t, UUID source, UUID target, boolean columns) {
    guard.requireTransaction(t);
    // Fixed SQL fragments selected server-side; UNION deduplicates nodes and terminates on old
    // cycles.
    String table = columns ? "column_lineage" : "lineage_edges",
        from = columns ? "source_column_id" : "source_asset_id",
        to = columns ? "target_column_id" : "target_asset_id";
    return jdbc.queryForObject(
        "WITH RECURSIVE reachable(id) AS (SELECT ?::uuid UNION SELECT e."
            + to
            + " FROM "
            + table
            + " e JOIN reachable r ON e."
            + from
            + "=r.id WHERE e.organization_id=? AND e.active) SELECT EXISTS(SELECT 1 FROM reachable"
            + " WHERE id=?)",
        Boolean.class,
        target,
        t.organizationId(),
        source);
  }

  public UUID create(TenantContext t, Mapping r) {
    guard.requireTransaction(t);
    UUID id = UUID.randomUUID();
    if (r.sourceColumnId() != null)
      jdbc.update(
          "INSERT INTO"
              + " column_lineage(id,organization_id,source_column_id,target_column_id,expression,provenance,evidence_ref,created_by)"
              + " VALUES(?,?,?,?,?,'MANUAL_DECLARATION',?,?)",
          id,
          t.organizationId(),
          r.sourceColumnId(),
          r.targetColumnId(),
          r.description(),
          r.evidenceRef(),
          t.userId());
    else
      jdbc.update(
          "INSERT INTO"
              + " lineage_edges(id,organization_id,source_asset_id,target_asset_id,relationship_type,provenance,evidence_ref,description,created_by,supersedes)"
              + " VALUES(?,?,?,?,?,'MANUAL_DECLARATION',?,?,?,?)",
          id,
          t.organizationId(),
          r.sourceAssetId(),
          r.targetAssetId(),
          r.relationshipType(),
          r.evidenceRef(),
          r.description() == null ? "" : r.description(),
          t.userId(),
          r.supersedes());
    audit(t, id, "MAPPING_CREATED");
    return id;
  }

  public Map<String, Object> get(TenantContext t, UUID id) {
    guard.requireTransaction(t);
    var rows =
        jdbc.queryForList(
            "SELECT *,false AS column_mapping FROM lineage_edges WHERE organization_id=? AND id=?",
            t.organizationId(),
            id);
    if (!rows.isEmpty()) return rows.getFirst();
    return RegistryRepository.one(
        jdbc.queryForList(
            "SELECT *,true AS column_mapping FROM column_lineage WHERE organization_id=? AND id=?",
            t.organizationId(),
            id));
  }

  public void revoke(TenantContext t, UUID id, boolean column) {
    guard.requireTransaction(t);
    jdbc.update(
        "UPDATE "
            + (column ? "column_lineage" : "lineage_edges")
            + " SET active=false,revoked_at=now() WHERE organization_id=? AND id=?",
        t.organizationId(),
        id);
    audit(t, id, "MAPPING_REVOKED");
  }

  public List<Map<String, Object>> neighbors(TenantContext t, UUID asset, String direction) {
    guard.requireTransaction(t);
    String filter =
        "upstream".equals(direction)
            ? "target_asset_id=?"
            : "downstream".equals(direction)
                ? "source_asset_id=?"
                : "(source_asset_id=? OR target_asset_id=?)";
    Object[] args =
        "both".equals(direction)
            ? new Object[] {t.organizationId(), asset, asset}
            : new Object[] {t.organizationId(), asset};
    return jdbc.queryForList(
        "SELECT * FROM lineage_edges WHERE organization_id=? AND active AND "
            + filter
            + " ORDER BY created_at,id LIMIT 201",
        args);
  }

  public List<Map<String, Object>> columnEdges(TenantContext t, UUID asset) {
    guard.requireTransaction(t);
    return jdbc.queryForList(
        "SELECT cl.*,s.asset_id AS source_asset_id,d.asset_id AS target_asset_id,s.name AS"
            + " source_column,d.name AS target_column FROM column_lineage cl JOIN asset_columns s"
            + " ON s.organization_id=cl.organization_id AND s.id=cl.source_column_id JOIN"
            + " asset_columns d ON d.organization_id=cl.organization_id AND"
            + " d.id=cl.target_column_id WHERE cl.organization_id=? AND cl.active AND (s.asset_id=?"
            + " OR d.asset_id=?) ORDER BY cl.created_at,cl.id LIMIT 201",
        t.organizationId(),
        asset,
        asset);
  }

  public List<Map<String, Object>> history(TenantContext t, UUID asset) {
    guard.requireTransaction(t);
    return jdbc.queryForList(
        "SELECT * FROM lineage_edges WHERE organization_id=? AND (source_asset_id=? OR"
            + " target_asset_id=?) ORDER BY created_at DESC,id LIMIT 100",
        t.organizationId(),
        asset,
        asset);
  }

  public List<Map<String, Object>> metricAssociations(
      TenantContext t, UUID node, String direction) {
    guard.requireTransaction(t);
    return jdbc.queryForList(
        """
        SELECT * FROM (SELECT d.asset_id AS source_asset_id,v.id AS target_asset_id,md5(v.id::text || d.asset_id::text)::uuid AS id,
         'METRIC_DEFINITION_DEPENDENCY' AS relationship_type,'MANUAL_DECLARATION' AS provenance,
         'Explicit formula dependency for immutable metric version' AS evidence_ref,
         m.name || ' v' || v.version AS metric_name,v.id AS metric_node_id
        FROM (SELECT organization_id,version_id,asset_id FROM formula_dependencies
                 UNION SELECT d.organization_id,d.version_id,c.asset_id FROM formula_column_dependencies d JOIN asset_columns c ON c.organization_id=d.organization_id AND c.id=d.column_id) d JOIN metric_versions v ON v.organization_id=d.organization_id AND v.formula_version_id=d.version_id
        JOIN metrics m ON m.organization_id=v.organization_id AND m.id=v.metric_id WHERE d.organization_id=?
        UNION ALL
        SELECT v.id,r.asset_id,r.id,'METRIC_USED_BY_REPORT',r.provenance,r.evidence_ref,
         m.name || ' v' || v.version,v.id
        FROM report_mappings r JOIN metric_versions v ON v.organization_id=r.organization_id AND v.id=r.metric_version_id
        JOIN metrics m ON m.organization_id=v.organization_id AND m.id=v.metric_id WHERE r.organization_id=? AND r.active
        ) association WHERE (? <> 'upstream' AND source_asset_id=?) OR (? <> 'downstream' AND target_asset_id=?)
        ORDER BY source_asset_id,target_asset_id,id LIMIT 201
        """,
        t.organizationId(),
        t.organizationId(),
        direction,
        node,
        direction,
        node);
  }

  public void audit(TenantContext t, UUID id, String event) {
    jdbc.queryForObject(
        "SELECT trace_audit(?,?,?,'MAPPING',?,'SUCCESS')",
        Object.class,
        t.userId(),
        t.organizationId(),
        event,
        id);
  }
}
