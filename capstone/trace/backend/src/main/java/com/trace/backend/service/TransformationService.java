package com.trace.backend.service;

import com.trace.backend.integration.processor.IntegrationProcessor;
import com.trace.backend.repository.*;
import com.trace.backend.tenancy.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class TransformationService {
  private final TenantTransactions tenants;
  private final JdbcTemplate jdbc;
  private final RegistryRepository guard;
  private final ManualMetadataRepository assets;
  private final IntegrationProcessor processor;
  private final ObjectMapper json;

  public TransformationService(
      TenantTransactions tenants,
      JdbcTemplate jdbc,
      RegistryRepository guard,
      ManualMetadataRepository assets,
      IntegrationProcessor processor,
      ObjectMapper json) {
    this.tenants = tenants;
    this.jdbc = jdbc;
    this.guard = guard;
    this.assets = assets;
    this.processor = processor;
    this.json = json;
  }

  public record Analyze(String definition, String dialect, String sourceRef) {}

  public record Confirm(
      String definition,
      String dialect,
      String sourceRef,
      Map<String, UUID> references,
      List<Integer> columnMappings) {}

  private Map<String, Object> metadata(Map<String, Object> a) {
    return json.readValue((String) a.get("metadata"), Map.class);
  }

  private List<Map<String, Object>> columns(TenantContext t, UUID id) {
    return jdbc.queryForList(
        "SELECT id,name,data_type FROM asset_columns WHERE organization_id=? AND asset_id=? ORDER"
            + " BY ordinal_position NULLS LAST,name",
        t.organizationId(),
        id);
  }

  private Map<String, Object> analyze(TenantContext t, UUID target, Analyze r) {
    var a = assets.asset(t, target);
    var meta = metadata(a);
    String sql = r.definition();
    if (sql == null || sql.isBlank())
      sql = (String) meta.getOrDefault("viewDefinition", meta.getOrDefault("sql", ""));
    ManualMetadataService.text(sql, 100000, "SQL definition");
    var inventory =
        jdbc.queryForList(
            "SELECT id,external_key FROM assets WHERE organization_id=? AND asset_type IN"
                + " ('DATASET','TABLE','VIEW') AND platform='SNOWFLAKE' ORDER BY external_key,id"
                + " LIMIT 5001",
            t.organizationId());
    if (inventory.size() > 5000)
      ConnectionService.invalid(
          "Narrow the SQL registry scope; more than 5,000 datasets are registered");

    var targetInfo = new LinkedHashMap<String, Object>();
    targetInfo.put("id", target);
    targetInfo.put("external_key", a.get("external_key"));
    targetInfo.put("columns", columns(t, target));
    String dialect = r.dialect() == null ? "snowflake" : r.dialect();
    var identities =
        processor.call(
            "sql/analyze",
            Map.of(
                "expression", sql, "dialect", dialect, "assets", inventory, "target", targetInfo));
    Set<String> matched = new HashSet<>();
    for (var reference : identities.path("references"))
      for (var match : reference.path("matches")) matched.add(match.path("id").asText());
    var relevant =
        inventory.stream().filter(item -> matched.contains(item.get("id").toString())).toList();
    if (relevant.size() > 100)
      ConnectionService.invalid(
          "SQL references more than 100 datasets; analyze smaller definitions");
    for (var item : relevant) item.put("columns", columns(t, (UUID) item.get("id")));
    var parsed =
        processor.call(
            "sql/analyze",
            Map.of(
                "expression", sql, "dialect", dialect, "assets", relevant, "target", targetInfo));
    Map<String, Object> result = json.convertValue(parsed, Map.class);
    result.put("definition", sql);
    result.put("dialect", dialect);
    result.put("target", a);
    result.put(
        "sourceRef",
        r.sourceRef() == null
            ? "Registry asset " + target + " · import " + a.get("last_import_id")
            : r.sourceRef());
    return result;
  }

  public Map<String, Object> analyze(UUID target, Analyze r) {
    return tenants.execute(t -> analyze(t, target, r));
  }

  public List<Map<String, Object>> history(UUID target) {
    return tenants.execute(
        t -> {
          assets.asset(t, target);
          return jdbc.queryForList(
              "SELECT id,definition,definition_hash,dialect,source_ref,analysis::text,created_at"
                  + " FROM lineage_evidence WHERE organization_id=? AND target_asset_id=? ORDER BY"
                  + " created_at DESC,id LIMIT 100",
              t.organizationId(),
              target);
        });
  }

  public Map<String, Object> historyPage(UUID target, int limit, int offset) {
    if (limit < 1 || limit > 100 || offset < 0)
      ConnectionService.invalid("Invalid evidence-history page");
    return tenants.executeSnapshot(
        t -> {
          assets.asset(t, target);
          long total =
              jdbc.queryForObject(
                  "SELECT count(*) FROM lineage_evidence WHERE organization_id=? AND"
                      + " target_asset_id=?",
                  Long.class,
                  t.organizationId(),
                  target);
          var rows =
              jdbc.queryForList(
                  "SELECT id,definition,definition_hash,dialect,source_ref,created_at FROM"
                      + " lineage_evidence WHERE organization_id=? AND target_asset_id=? ORDER BY"
                      + " created_at DESC,id LIMIT ? OFFSET ?",
                  t.organizationId(),
                  target,
                  limit,
                  offset);
          return Map.of(
              "items",
              rows,
              "total",
              total,
              "offset",
              offset,
              "hasMore",
              offset + rows.size() < total);
        });
  }

  public Map<String, Object> confirm(UUID target, Confirm r) {
    ManualMetadataService.text(r.sourceRef(), 2000, "Definition evidence reference");
    if (r.references() == null
        || r.references().size() > 100
        || r.columnMappings() == null
        || r.columnMappings().size() > 2000)
      ConnectionService.invalid("Select bounded source and column candidates");
    return tenants.execute(
        t -> {
          jdbc.execute("SET LOCAL statement_timeout='8s'");
          jdbc.queryForObject(
              "SELECT pg_advisory_xact_lock(hashtextextended(?::text,10))",
              Object.class,
              t.organizationId().toString());
          var a = assets.asset(t, target);
          guard.requireTeamWrite(t, (UUID) a.get("owner_team_id"));
          var analysis =
              analyze(t, target, new Analyze(r.definition(), r.dialect(), r.sourceRef()));
          if (!"PARSED".equals(analysis.get("status")))
            ConnectionService.invalid("Resolve SQL parsing before confirming dependencies");
          var refs = (List<Map<String, Object>>) analysis.get("references");
          for (var entry : r.references().entrySet()) {
            var ref =
                refs.stream()
                    .filter(x -> entry.getKey().equals(x.get("identifier")))
                    .findFirst()
                    .orElseThrow(RegistryRepository::missing);
            var matches = (List<Map<String, Object>>) ref.get("matches");
            if (!matches.isEmpty()
                && matches.stream()
                    .noneMatch(x -> entry.getValue().toString().equals(x.get("id").toString())))
              ConnectionService.invalid(
                  "Choose an identity offered by the parser for this reference");
            if (entry.getValue().equals(target))
              ConnectionService.invalid("Output cannot reference itself");
            var source = assets.asset(t, entry.getValue());
            guard.requireTeamWrite(t, (UUID) source.get("owner_team_id"));
          }
          if (r.references().isEmpty())
            ConnectionService.invalid("Confirm at least one source dependency");
          var mappingCandidates = (List<Map<String, Object>>) analysis.get("columnMappings");
          for (int index : r.columnMappings()) {
            if (index < 0 || index >= mappingCandidates.size())
              ConnectionService.invalid("Column candidate changed; analyze again");
            var mapping = mappingCandidates.get(index);
            if (!r.references()
                .containsValue(UUID.fromString(mapping.get("sourceAssetId").toString())))
              ConnectionService.invalid("Confirm the source dataset before its columns");
          }
          analysis.put("confirmedReferences", r.references());
          analysis.put("confirmedColumnMappings", r.columnMappings());
          String key = "trace:sql:" + target;
          String hash = (String) analysis.get("definitionHash");
          var previous =
              jdbc.queryForList(
                  "SELECT id,analysis::text FROM lineage_evidence WHERE organization_id=? AND"
                      + " target_asset_id=? AND definition_hash=? ORDER BY created_at DESC LIMIT 1",
                  t.organizationId(),
                  target,
                  hash);
          if (!previous.isEmpty()
              && json.readTree((String) previous.getFirst().get("analysis"))
                  .equals(json.valueToTree(analysis)))
            return Map.of("evidenceId", previous.getFirst().get("id"), "unchanged", true);
          UUID evidence = UUID.randomUUID();
          jdbc.update(
              "INSERT INTO"
                  + " lineage_evidence(id,organization_id,target_asset_id,definition,definition_hash,dialect,source_ref,analysis,created_by)"
                  + " VALUES(?,?,?,?,?,?,?,?::jsonb,?)",
              evidence,
              t.organizationId(),
              target,
              analysis.get("definition"),
              hash,
              analysis.get("dialect"),
              r.sourceRef(),
              json.writeValueAsString(analysis),
              t.userId());
          UUID transform =
              jdbc.queryForObject(
                  """
                  INSERT INTO assets(organization_id,connection_id,external_key,name,asset_type,platform,owner_team_id,provenance,description,metadata)
                  VALUES(?,?,?,?,'SQL_TRANSFORMATION',?,?,'SQL_PARSED_CONFIRMED','Registered SQL dependency evidence',?::jsonb)
                  ON CONFLICT(organization_id,connection_id,external_key,asset_type) DO UPDATE SET metadata=EXCLUDED.metadata,revision=assets.revision+1 RETURNING id
                  """,
                  UUID.class,
                  t.organizationId(),
                  a.get("connection_id"),
                  key,
                  "SQL · " + a.get("name"),
                  "SNOWFLAKE",
                  a.get("owner_team_id"),
                  json.writeValueAsString(
                      Map.of(
                          "sql",
                          analysis.get("definition"),
                          "definitionHash",
                          hash,
                          "evidenceId",
                          evidence,
                          "targetAssetId",
                          target,
                          "sourceRef",
                          r.sourceRef(),
                          "rules",
                          analysis.get("rules"))));
          // Reconcile only this analyzer's active records. Human mappings and old evidence stay
          // intact.
          jdbc.update(
              "UPDATE lineage_edges SET active=false,revoked_at=now() WHERE organization_id=? AND"
                  + " provenance='SQL_PARSED_CONFIRMED' AND (source_asset_id=? OR"
                  + " target_asset_id=?)",
              t.organizationId(),
              transform,
              transform);
          jdbc.update(
              "UPDATE column_lineage SET active=false,revoked_at=now() WHERE organization_id=? AND"
                  + " transformation_asset_id=? AND provenance='SQL_PARSED_CONFIRMED'",
              t.organizationId(),
              transform);
          for (UUID input : new HashSet<>(r.references().values()))
            edge(t, input, transform, "TRANSFORMATION_INPUT", evidence, r.sourceRef());
          edge(t, transform, target, "TRANSFORMATION_OUTPUT", evidence, r.sourceRef());
          for (int index : new HashSet<>(r.columnMappings())) {
            var m = mappingCandidates.get(index);
            jdbc.update(
                "INSERT INTO"
                    + " column_lineage(organization_id,source_column_id,target_column_id,transformation_asset_id,expression,provenance,evidence_ref,created_by,evidence_id)"
                    + " VALUES(?,?,?,?,?,'SQL_PARSED_CONFIRMED',?,?,?) ON"
                    + " CONFLICT(organization_id,source_column_id,target_column_id) WHERE active DO"
                    + " NOTHING",
                t.organizationId(),
                UUID.fromString(m.get("sourceColumnId").toString()),
                UUID.fromString(m.get("targetColumnId").toString()),
                transform,
                m.get("expression"),
                r.sourceRef(),
                t.userId(),
                evidence);
          }
          jdbc.queryForObject(
              "SELECT trace_audit(?,?,?,'LINEAGE',?,'SUCCESS')",
              Object.class,
              t.userId(),
              t.organizationId(),
              "SQL_DEPENDENCIES_CONFIRMED",
              target);
          return Map.of(
              "evidenceId",
              evidence,
              "transformationId",
              transform,
              "confirmedSources",
              r.references().size(),
              "confirmedColumns",
              r.columnMappings().size());
        });
  }

  private void edge(TenantContext t, UUID from, UUID to, String type, UUID evidence, String ref) {
    jdbc.update(
        "INSERT INTO"
            + " lineage_edges(organization_id,source_asset_id,target_asset_id,relationship_type,provenance,evidence_ref,description,created_by,evidence_id)"
            + " VALUES(?,?,?,?,'SQL_PARSED_CONFIRMED',?,'SQL candidates explicitly confirmed in"
            + " registry',?,?) ON"
            + " CONFLICT(organization_id,source_asset_id,target_asset_id,relationship_type) WHERE"
            + " active DO NOTHING",
        t.organizationId(),
        from,
        to,
        type,
        ref,
        t.userId(),
        evidence);
  }
}
