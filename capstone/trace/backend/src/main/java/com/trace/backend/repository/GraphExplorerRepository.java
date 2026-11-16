package com.trace.backend.repository;

import com.trace.backend.exception.ApiException;
import com.trace.backend.tenancy.TenantContext;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** One tenant-scoped graph for catalog, registered calculations and reporting usage. */
@Repository
public class GraphExplorerRepository {
  private final JdbcTemplate jdbc;
  private final RegistryRepository guard;

  public GraphExplorerRepository(JdbcTemplate jdbc, RegistryRepository guard) {
    this.jdbc = jdbc;
    this.guard = guard;
  }

  private static final String CANONICAL =
      """
      WITH RECURSIVE scope AS (SELECT ?::uuid AS org),
      calculation AS (
        SELECT v.*,f.name,f.asset_id,f.owner_team_id,f.description FROM formula_versions v
        JOIN formulas f ON f.organization_id=v.organization_id AND f.id=v.formula_id
        WHERE v.organization_id=(SELECT org FROM scope) AND v.state='REGISTERED'
      ), en AS (
        SELECT id,
        CASE WHEN relationship_type IN ('MODEL_CONTAINS_MEASURE','REPORT_CONTAINS_VISUAL') THEN target_asset_id ELSE source_asset_id END AS source,
        CASE WHEN relationship_type IN ('MODEL_CONTAINS_MEASURE','REPORT_CONTAINS_VISUAL') THEN source_asset_id ELSE target_asset_id END AS target,
        CASE relationship_type WHEN 'MODEL_CONTAINS_MEASURE' THEN 'MEASURE_IN_MODEL' WHEN 'REPORT_CONTAINS_VISUAL' THEN 'VISUAL_IN_REPORT' ELSE relationship_type END AS relationship_type,provenance,
          COALESCE(evidence_ref,'') AS evidence_ref,description,created_at FROM lineage_edges
          WHERE organization_id=(SELECT org FROM scope) AND active
        UNION ALL
        SELECT cl.id,s.asset_id,d.asset_id,'COLUMN_DERIVATION',cl.provenance,COALESCE(cl.evidence_ref,''),COALESCE(cl.expression,''),cl.created_at
        FROM column_lineage cl JOIN asset_columns s ON s.organization_id=cl.organization_id AND s.id=cl.source_column_id
        JOIN asset_columns d ON d.organization_id=cl.organization_id AND d.id=cl.target_column_id
        WHERE cl.organization_id=(SELECT org FROM scope) AND cl.active AND cl.transformation_asset_id IS NULL AND s.asset_id<>d.asset_id
        UNION ALL
        SELECT md5('formula:'||v.id::text||d.asset_id::text)::uuid,d.asset_id,v.id,'FORMULA_INPUT','MANUAL_DECLARATION',
          'Immutable formula snapshot '||v.id::text,'Explicitly registered asset or column reference',v.created_at
        FROM (SELECT organization_id,version_id,asset_id FROM formula_dependencies UNION
          SELECT d.organization_id,d.version_id,c.asset_id FROM formula_column_dependencies d
          JOIN asset_columns c ON c.organization_id=d.organization_id AND c.id=d.column_id) d
        JOIN calculation v ON v.organization_id=d.organization_id AND v.id=d.version_id
        UNION ALL
        SELECT md5('output:'||v.id::text)::uuid,v.id,v.asset_id,'FORMULA_OUTPUT','MANUAL_DECLARATION',
          'Linked target of registered formula '||v.id::text,'Registered linked output; not deployment evidence',v.created_at
        FROM calculation v WHERE v.asset_id IS NOT NULL
        UNION ALL
        SELECT md5('metric:'||v.id::text)::uuid,v.formula_version_id,v.id,'METRIC_DEFINITION_DEPENDENCY','REGISTERED_SNAPSHOT',
          'Immutable metric version '||v.id::text,'Version-specific calculation reference',v.created_at
        FROM metric_versions v WHERE v.organization_id=(SELECT org FROM scope)
        UNION ALL
        SELECT r.id,r.metric_version_id,r.asset_id,'METRIC_USED_BY_REPORT',r.provenance,r.evidence_ref,'Declared reporting usage',r.created_at
        FROM report_mappings r WHERE r.organization_id=(SELECT org FROM scope) AND r.active
      ), nn AS (
        SELECT a.id,a.name,a.asset_type,a.external_key,a.platform,a.connection_id,a.owner_team_id,
          a.description,a.last_seen_at,a.provenance,a.metadata::text AS metadata,a.inventory_state,c.name AS connection_name,t.name AS owner_team_name
        FROM assets a JOIN source_connections c ON c.organization_id=a.organization_id AND c.id=a.connection_id
        LEFT JOIN teams t ON t.organization_id=a.organization_id AND t.id=a.owner_team_id
        WHERE a.organization_id=(SELECT org FROM scope)
        UNION ALL
        SELECT v.id,v.name||' · v'||v.version,'FORMULA_VERSION',v.id::text,'REGISTRY',NULL,v.owner_team_id,
          v.description,v.created_at,'REGISTERED_SNAPSHOT',jsonb_build_object('expression',v.expression,'dialect',v.dialect,'formulaId',v.formula_id)::text,'CURRENT','Formula registry',NULL
        FROM calculation v
        UNION ALL
        SELECT v.id,COALESCE(v.definition_snapshot->>'name',m.name)||' · v'||v.version,'METRIC_VERSION',m.business_key,'REGISTRY',NULL,m.owner_team_id,
          COALESCE(v.definition_snapshot->>'description',m.description),v.created_at,'REGISTERED_SNAPSHOT',jsonb_build_object('metricId',m.id,'formulaVersionId',v.formula_version_id,'businessDefinitionSnapshot',v.definition_snapshot)::text,'CURRENT','Metric registry',NULL
        FROM metric_versions v JOIN metrics m ON m.organization_id=v.organization_id AND m.id=v.metric_id
        WHERE v.organization_id=(SELECT org FROM scope)
      )
      """;

  private static final String COLUMN_GRAPH =
      """
      , cn AS (
        SELECT c.id,c.name,'COLUMN'::text AS asset_type,a.external_key||'.'||c.name AS external_key,a.platform,a.connection_id,a.owner_team_id,
          COALESCE(c.description,'') AS description,a.last_seen_at,a.provenance,jsonb_build_object('assetId',a.id,'dataType',c.data_type)::text AS metadata,c.inventory_state,
          a.name AS connection_name,NULL::text AS owner_team_name
        FROM asset_columns c JOIN assets a ON a.organization_id=c.organization_id AND a.id=c.asset_id WHERE c.organization_id=(SELECT org FROM scope)
        UNION ALL SELECT * FROM nn WHERE asset_type IN ('FORMULA_VERSION','METRIC_VERSION','SEMANTIC_MODEL','MEASURE','REPORT','VISUAL','WORKSPACE')
      ), ce AS (
        SELECT c.id,c.source_column_id AS source,c.target_column_id AS target,'COLUMN_DERIVATION'::text AS relationship_type,c.provenance,COALESCE(c.evidence_ref,'') AS evidence_ref,COALESCE(c.expression,'') AS description,c.created_at
        FROM column_lineage c WHERE c.organization_id=(SELECT org FROM scope) AND c.active
        UNION ALL SELECT md5('column:'||v.id::text||d.column_id::text)::uuid,d.column_id,v.id,'FORMULA_COLUMN_INPUT','MANUAL_DECLARATION','Immutable formula column reference',v.expression,v.created_at
        FROM formula_column_dependencies d JOIN calculation v ON v.organization_id=d.organization_id AND v.id=d.version_id
        UNION ALL SELECT * FROM en WHERE relationship_type IN ('METRIC_DEFINITION_DEPENDENCY','METRIC_USED_BY_REPORT','MEASURE_IN_MODEL','REPORT_USES_MODEL','VISUAL_USES_MEASURE','VISUAL_IN_REPORT')
      )
      """;

  public Map<String, Object> explore(
      TenantContext t,
      UUID root,
      String direction,
      int offset,
      int limit,
      String snapshot,
      UUID column) {
    guard.requireTransaction(t);
    jdbc.execute("SET LOCAL statement_timeout='8s'");
    String base = CANONICAL;
    if (column != null) {
      RegistryRepository.one(
          jdbc.queryForList(
              "SELECT id FROM asset_columns WHERE organization_id=? AND asset_id=? AND id=?",
              t.organizationId(),
              root,
              column));
      base += COLUMN_GRAPH;
    }
    String edgeTable = column == null ? "en" : "ce", nodeTable = column == null ? "nn" : "cn";
    UUID start = column == null ? root : column;
    String next =
        "upstream".equals(direction)
            ? "e.source"
            : "downstream".equals(direction)
                ? "e.target"
                : "CASE WHEN e.source=r.id THEN e.target ELSE e.source END";
    String match =
        "upstream".equals(direction)
            ? "e.target=r.id"
            : "downstream".equals(direction) ? "e.source=r.id" : "(e.source=r.id OR e.target=r.id)";
    base +=
        ", reachable(id) AS (SELECT ?::uuid UNION SELECT "
            + next
            + " FROM "
            + edgeTable
            + " e JOIN reachable r ON "
            + match
            + ")";
    var stats =
        jdbc.queryForMap(
            base
                + " SELECT (SELECT count(*) FROM "
                + nodeTable
                + " n JOIN reachable r ON r.id=n.id) AS \"totalNodes\",(SELECT count(*) FROM "
                + edgeTable
                + " e WHERE source IN (SELECT id FROM reachable) AND target IN (SELECT id FROM"
                + " reachable)) AS \"totalEdges\",md5(COALESCE((SELECT"
                + " count(*)::text||':'||COALESCE(sum(hashtextextended(id::text||source::text||target::text||relationship_type||provenance||evidence_ref||description,0)::numeric),0)::text||':'||COALESCE(sum(hashtextextended(id::text||source::text||target::text||relationship_type||provenance||evidence_ref||description,17)::numeric),0)::text"
                + " FROM "
                + edgeTable
                + "),'')) AS snapshot",
            t.organizationId(),
            start);
    if (snapshot != null && !snapshot.isBlank() && !snapshot.equals(stats.get("snapshot")))
      throw new ApiException(
          HttpStatus.CONFLICT,
          "GRAPH_CHANGED",
          "Relationships changed during exploration. Refresh the graph to start a consistent"
              + " traversal.");
    RegistryRepository.one(
        jdbc.queryForList(
            base + " SELECT * FROM " + nodeTable + " WHERE id=?",
            t.organizationId(),
            start,
            start));
    var edges =
        jdbc.queryForList(
            base
                + " SELECT id,source AS source_asset_id,target AS"
                + " target_asset_id,relationship_type,provenance,evidence_ref,description,created_at"
                + " FROM "
                + edgeTable
                + " WHERE source IN (SELECT id FROM reachable) AND target IN (SELECT id FROM"
                + " reachable) ORDER BY id LIMIT ? OFFSET ?",
            t.organizationId(),
            start,
            limit,
            offset);
    var ids = new LinkedHashSet<UUID>();
    ids.add(start);
    for (var e : edges) {
      ids.add((UUID) e.get("source_asset_id"));
      ids.add((UUID) e.get("target_asset_id"));
    }
    // Include isolated root and endpoints only: the browser never has to render the full reachable
    // set.
    var nodes =
        jdbc.queryForList(
            base
                + " SELECT * FROM "
                + nodeTable
                + " WHERE id IN ("
                + String.join(",", Collections.nCopies(ids.size(), "?"))
                + ") ORDER BY external_key,id",
            concat(t.organizationId(), start, ids));
    long total = ((Number) stats.get("totalEdges")).longValue();
    var result = new LinkedHashMap<String, Object>(stats);
    result.put("nodes", nodes);
    result.put("edges", edges);
    result.put("offset", offset);
    result.put("nextOffset", offset + edges.size());
    result.put("hasMore", offset + edges.size() < total);
    result.put("truncated", offset > 0 || offset + edges.size() < total);
    result.put("completeTraversal", true);
    result.put("depthLimit", false);
    result.put("label", "Potential impact from recorded relationships");
    result.put(
        "columnEdges",
        jdbc.queryForList(
            "SELECT cl.*,s.asset_id AS source_asset_id,d.asset_id AS target_asset_id,s.name AS"
                + " source_column,d.name AS target_column FROM column_lineage cl JOIN asset_columns"
                + " s ON s.organization_id=cl.organization_id AND s.id=cl.source_column_id JOIN"
                + " asset_columns d ON d.organization_id=cl.organization_id AND"
                + " d.id=cl.target_column_id WHERE cl.organization_id=? AND cl.active AND"
                + " (s.asset_id=? OR d.asset_id=?) ORDER BY cl.id LIMIT 201",
            t.organizationId(),
            root,
            root));
    result.put("history", List.of());
    result.put(
        "limits",
        Map.of("maximumNodes", limit * 2 + 1, "maximumEdges", limit, "statementTimeoutSeconds", 8));
    return result;
  }

  private Object[] concat(UUID org, UUID root, Collection<UUID> ids) {
    var a = new ArrayList<Object>();
    a.add(org);
    a.add(root);
    a.addAll(ids);
    return a.toArray();
  }

  public Map<String, Object> path(
      TenantContext t, UUID root, UUID target, int offset, int limit, UUID column) {
    guard.requireTransaction(t);
    jdbc.execute("SET LOCAL statement_timeout='8s'");
    if (column != null)
      RegistryRepository.one(
          jdbc.queryForList(
              "SELECT id FROM asset_columns WHERE organization_id=? AND asset_id=? AND id=?",
              t.organizationId(),
              root,
              column));
    UUID start = column == null ? root : column;
    String nt = column == null ? "nn" : "cn", et = column == null ? "en" : "ce";
    String base = CANONICAL + (column == null ? "" : COLUMN_GRAPH);
    RegistryRepository.one(
        jdbc.queryForList(
            base + " SELECT id FROM " + nt + " WHERE id=?", t.organizationId(), start));
    RegistryRepository.one(
        jdbc.queryForList(
            base + " SELECT id FROM " + nt + " WHERE id=?", t.organizationId(), target));
    base +=
        ", reachable(id) AS (SELECT ?::uuid UNION SELECT e.target FROM "
            + et
            + " e JOIN reachable r ON e.source=r.id)";
    var rows =
        jdbc.queryForList(
            base
                + " SELECT id,source,target FROM "
                + et
                + " WHERE source IN (SELECT id FROM reachable) ORDER BY id LIMIT 100001",
            t.organizationId(),
            start);
    if (rows.size() > 100000)
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "PATH_RESOURCE_LIMIT",
          "The complete graph remains paginated. Narrow the starting point for path explanation;"
              + " this path request exceeds 100,000 relationships.");
    var neighbors = new HashMap<UUID, List<Map<String, Object>>>();
    for (var e : rows)
      neighbors.computeIfAbsent((UUID) e.get("source"), x -> new ArrayList<>()).add(e);
    var parents = new HashMap<UUID, UUID>();
    var q = new ArrayList<UUID>();
    q.add(start);
    parents.put(start, start);
    for (int i = 0; i < q.size() && !parents.containsKey(target); i++)
      for (var e : neighbors.getOrDefault(q.get(i), List.of())) {
        UUID next = (UUID) e.get("target");
        if (!parents.containsKey(next)) {
          parents.put(next, q.get(i));
          q.add(next);
        }
      }
    if (!parents.containsKey(target))
      return Map.of("reachable", false, "nodes", List.of(), "totalNodes", 0, "hasMore", false);
    var path = new ArrayList<UUID>();
    UUID current = target;
    while (!current.equals(start)) {
      path.add(current);
      current = parents.get(current);
    }
    path.add(start);
    Collections.reverse(path);
    var page = path.subList(Math.min(offset, path.size()), Math.min(path.size(), offset + limit));
    var names = new HashMap<UUID, Map<String, Object>>();
    if (!page.isEmpty())
      for (var n :
          jdbc.queryForList(
              CANONICAL
                  + (column == null ? "" : COLUMN_GRAPH)
                  + " SELECT id,name,asset_type,external_key FROM "
                  + nt
                  + " WHERE id IN ("
                  + String.join(",", Collections.nCopies(page.size(), "?"))
                  + ")",
              concatPath(t.organizationId(), page))) names.put((UUID) n.get("id"), n);
    return Map.of(
        "reachable",
        true,
        "nodes",
        page.stream().map(names::get).toList(),
        "totalNodes",
        path.size(),
        "offset",
        offset,
        "nextOffset",
        offset + page.size(),
        "hasMore",
        offset + page.size() < path.size(),
        "basis",
        "Shortest directed path through recorded relationships");
  }

  private Object[] concatPath(UUID org, Collection<UUID> ids) {
    var a = new ArrayList<Object>();
    a.add(org);
    a.addAll(ids);
    return a.toArray();
  }

  public List<Map<String, Object>> referenceColumns(TenantContext t, List<UUID> requested) {
    guard.requireTransaction(t);
    jdbc.execute("SET LOCAL statement_timeout='5s'");
    var ids = new LinkedHashSet<>(requested);
    if (ids.isEmpty()) return List.of();
    var args = new ArrayList<Object>();
    args.add(t.organizationId());
    args.addAll(ids);
    var rows =
        jdbc.queryForList(
            "SELECT c.id,c.asset_id,c.name,c.data_type,a.external_key AS asset_key,a.name AS"
                + " asset_name FROM asset_columns c JOIN assets a ON"
                + " a.organization_id=c.organization_id AND a.id=c.asset_id WHERE"
                + " c.organization_id=? AND c.id IN ("
                + String.join(",", Collections.nCopies(ids.size(), "?"))
                + ") ORDER BY a.external_key,c.name,c.id",
            args.toArray());
    if (rows.size() != ids.size()) throw RegistryRepository.missing();
    return rows;
  }

  public Map<String, Object> overview(TenantContext t) {
    guard.requireTransaction(t);
    jdbc.execute("SET LOCAL statement_timeout='5s'");
    var groups =
        jdbc.queryForList(
            """
            SELECT c.id AS connection_id,c.name,c.platform,c.status,c.last_successful_sync_at,
              CASE WHEN a.asset_type='SQL_TRANSFORMATION' THEN 'SQL definitions' WHEN a.platform='SNOWFLAKE' THEN split_part(a.external_key,'.',1)||'.'||split_part(a.external_key,'.',2) ELSE a.asset_type END AS asset_group,
              count(a.id) AS asset_count,count(a.id) FILTER(WHERE jsonb_exists(a.metadata,'viewDefinition') OR jsonb_exists(a.metadata,'sql')) AS definitions
            FROM source_connections c LEFT JOIN assets a ON a.organization_id=c.organization_id AND a.connection_id=c.id
            WHERE c.organization_id=? GROUP BY c.id,c.name,c.platform,c.status,c.last_successful_sync_at,asset_group ORDER BY c.name,asset_group
            """,
            t.organizationId());
    var links =
        jdbc.queryForList(
            CANONICAL
                + " SELECT a.connection_id AS source_connection,b.connection_id AS"
                + " target_connection,count(*) AS relationship_count FROM en e JOIN nn a ON"
                + " a.id=e.source JOIN nn b ON b.id=e.target GROUP BY"
                + " a.connection_id,b.connection_id",
            t.organizationId());
    return Map.of(
        "groups",
        groups,
        "links",
        links,
        "definitionSources",
        jdbc.queryForList(
            "SELECT target_asset_id,definition_hash,source_ref,created_at FROM lineage_evidence"
                + " WHERE organization_id=? ORDER BY created_at DESC LIMIT 100",
            t.organizationId()),
        "counts",
        jdbc.queryForMap(
            "SELECT (SELECT count(*) FROM assets WHERE organization_id=?) AS assets,(SELECT"
                + " count(*) FROM lineage_edges WHERE organization_id=? AND active) AS"
                + " relationships,(SELECT count(*) FROM column_lineage WHERE organization_id=? AND"
                + " active) AS columns,(SELECT count(*) FROM metrics WHERE organization_id=?) AS"
                + " metrics",
            t.organizationId(),
            t.organizationId(),
            t.organizationId(),
            t.organizationId()));
  }
}
