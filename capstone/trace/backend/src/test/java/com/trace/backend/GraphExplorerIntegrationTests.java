package com.trace.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.trace.backend.exception.ApiException;
import com.trace.backend.repository.*;
import com.trace.backend.tenancy.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

@EnabledIfEnvironmentVariable(named = "TRACE_TEST_ADMIN_URL", matches = ".*/trace_tenancy_test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphExplorerIntegrationTests {
  private JdbcTemplate admin;
  private JdbcTemplate runtimeJdbc;
  private GraphExplorerRepository graph;
  private TenantTransactions tenants;
  private final UUID org = UUID.randomUUID(),
      other = UUID.randomUUID(),
      user = UUID.randomUUID(),
      team = UUID.randomUUID(),
      connection = UUID.randomUUID();
  private final List<UUID> ids = new ArrayList<>();

  @BeforeAll
  void setup() {
    String url = System.getenv("TRACE_TEST_ADMIN_URL");
    assertEquals("true", System.getenv("TRACE_TEST_ALLOW_RESET"));
    assertFalse(url.matches(".*:(5433|55433)/.*"));
    var owner = new DriverManagerDataSource(url, "trace_test_admin", "trace-test-admin-only");
    admin = new JdbcTemplate(owner);
    admin.execute(
        "DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='trace_migrator') THEN"
            + " CREATE ROLE trace_migrator LOGIN PASSWORD 'trace-test-migration-only' NOSUPERUSER"
            + " NOBYPASSRLS; END IF; IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE"
            + " rolname='trace_runtime') THEN CREATE ROLE trace_runtime LOGIN PASSWORD"
            + " 'trace-test-runtime-only' NOSUPERUSER NOBYPASSRLS; END IF; END $$");
    admin.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
    admin.execute("GRANT USAGE,CREATE ON SCHEMA public TO trace_migrator");
    admin.execute("GRANT USAGE ON SCHEMA public TO trace_runtime");
    Flyway.configure()
        .dataSource(new DriverManagerDataSource(url, "trace_migrator", "trace-test-migration-only"))
        .load()
        .migrate();
    admin.update(
        "INSERT INTO organizations(id,name,slug) VALUES(?,'Graph regression',?),(?,'Other"
            + " tenant',?)",
        org,
        "graph-" + org,
        other,
        "other-" + other);
    admin.update(
        "INSERT INTO users(id,email,display_name) VALUES(?,?,'Graph tester')",
        user,
        "graph-" + user + "@example.test");
    admin.update(
        "INSERT INTO organization_memberships(organization_id,user_id,role) VALUES(?,?,'OWNER')",
        org,
        user);
    admin.update(
        "INSERT INTO teams(id,organization_id,name,slug) VALUES(?,?,'Graph team','graph')",
        team,
        org);
    admin.update(
        "INSERT INTO"
            + " source_connections(id,organization_id,name,platform,ingestion_mode,owner_team_id)"
            + " VALUES(?,?,'Graph source','SNOWFLAKE','MANUAL',?)",
        connection,
        org,
        team);
    for (int i = 0; i < 24; i++) {
      UUID id = UUID.randomUUID();
      ids.add(id);
      admin.update(
          "INSERT INTO"
              + " assets(id,organization_id,connection_id,name,external_key,asset_type,platform,owner_team_id)"
              + " VALUES(?,?,?,?,?,'DATASET','SNOWFLAKE',?)",
          id,
          org,
          connection,
          "Node " + i,
          "TEST.CHAIN.N" + i,
          team);
      if (i > 0) edge(ids.get(i - 1), id);
    }
    edge(
        ids.get(23),
        ids.get(
            5)); // historical cycles must terminate, irrespective of new-mapping cycle validation.
    var runtime = new DriverManagerDataSource(url, "trace_runtime", "trace-test-runtime-only");
    var jdbc = new JdbcTemplate(runtime);
    runtimeJdbc = jdbc;
    tenants =
        new TenantTransactions(
            jdbc,
            () -> new TenantIdentityProvider.Selection(org, user),
            new JdbcTransactionManager(runtime));
    graph = new GraphExplorerRepository(jdbc, new RegistryRepository(jdbc));
  }

  private void edge(UUID s, UUID d) {
    admin.update(
        "INSERT INTO"
            + " lineage_edges(organization_id,source_asset_id,target_asset_id,relationship_type,provenance,evidence_ref)"
            + " VALUES(?,?,?,'DATA_FLOW','MANUAL_DECLARATION','Deterministic disposable test"
            + " fixture')",
        org,
        s,
        d);
  }

  private Map<String, Object> page(
      UUID root, String direction, int offset, int limit, String snapshot) {
    return tenants.executeSnapshot(
        t -> graph.explore(t, root, direction, offset, limit, snapshot, null));
  }

  @Test
  void completeDownstreamTraversalBeyondFiveAndCycles() {
    var r = page(ids.getFirst(), "downstream", 0, 100, null);
    assertEquals(24L, ((Number) r.get("totalNodes")).longValue());
    assertEquals(24L, ((Number) r.get("totalEdges")).longValue());
    assertEquals(false, r.get("depthLimit"));
    assertEquals(false, r.get("hasMore"));
  }

  @Test
  void fullUpstreamTraversal() {
    var r = page(ids.getLast(), "upstream", 0, 100, null);
    assertEquals(24L, ((Number) r.get("totalNodes")).longValue());
  }

  @Test
  void paginationIsCompleteAndStable() {
    Set<Object> seen = new HashSet<>();
    int offset = 0;
    String snapshot = null;
    do {
      var r = page(ids.getFirst(), "both", offset, 3, snapshot);
      snapshot = (String) r.get("snapshot");
      var edges = (List<Map<String, Object>>) r.get("edges");
      assertTrue(edges.size() <= 3);
      assertTrue(((List<?>) r.get("nodes")).size() <= 7);
      for (var e : edges) assertTrue(seen.add(e.get("id")));
      offset = ((Number) r.get("nextOffset")).intValue();
      if (!Boolean.TRUE.equals(r.get("hasMore"))) break;
    } while (true);
    assertEquals(24, seen.size());
  }

  @Test
  void mutationRequiresRefresh() {
    var before = page(ids.getFirst(), "both", 0, 3, null);
    UUID edge = UUID.randomUUID();
    admin.update(
        "INSERT INTO"
            + " lineage_edges(id,organization_id,source_asset_id,target_asset_id,relationship_type,provenance,evidence_ref)"
            + " VALUES(?,?,?,?,'DATA_FLOW','MANUAL_DECLARATION','New fixture evidence')",
        edge,
        org,
        ids.get(0),
        ids.get(20));
    try {
      assertThrows(
          ApiException.class,
          () -> page(ids.getFirst(), "both", 3, 3, (String) before.get("snapshot")));
    } finally {
      admin.update("DELETE FROM lineage_edges WHERE id=?", edge);
    }
  }

  @Test
  void unknownAndCrossTenantRootsAreRejected() {
    assertThrows(Exception.class, () -> page(UUID.randomUUID(), "both", 0, 5, null));
    assertThrows(
        Exception.class,
        () ->
            new TenantTransactions(
                    new JdbcTemplate(
                        new DriverManagerDataSource(
                            System.getenv("TRACE_TEST_ADMIN_URL"),
                            "trace_runtime",
                            "trace-test-runtime-only")),
                    () -> new TenantIdentityProvider.Selection(other, user),
                    new JdbcTransactionManager(
                        new DriverManagerDataSource(
                            System.getenv("TRACE_TEST_ADMIN_URL"),
                            "trace_runtime",
                            "trace-test-runtime-only")))
                .execute(t -> graph.explore(t, ids.getFirst(), "both", 0, 5, null, null)));
  }

  @Test
  void columnTraversalPreservesExactEvidence() {
    UUID source = UUID.randomUUID(), target = UUID.randomUUID();
    admin.update(
        "INSERT INTO asset_columns(id,organization_id,asset_id,name,data_type)"
            + " VALUES(?,?,?,'NET_AMOUNT','NUMBER'),(?,?,?,'NET_REVENUE','NUMBER')",
        source,
        org,
        ids.get(0),
        target,
        org,
        ids.get(10));
    admin.update(
        "INSERT INTO"
            + " column_lineage(organization_id,source_column_id,target_column_id,expression,provenance,evidence_ref)"
            + " VALUES(?,?,?,'NET_AMOUNT - REFUND','MANUAL_DECLARATION','fixture revision 1')",
        org,
        source,
        target);
    try {
      var r =
          tenants.execute(t -> graph.explore(t, ids.getFirst(), "downstream", 0, 10, null, source));
      assertEquals(2L, ((Number) r.get("totalNodes")).longValue());
      assertEquals(
          "fixture revision 1",
          ((List<Map<String, Object>>) r.get("edges")).getFirst().get("evidence_ref"));
    } finally {
      admin.update("DELETE FROM column_lineage WHERE source_column_id=?", source);
      admin.update("DELETE FROM asset_columns WHERE id IN (?,?)", source, target);
    }
  }

  @Test
  void shortestPathBeyondFivePaginates() {
    var r = tenants.execute(t -> graph.path(t, ids.getFirst(), ids.getLast(), 0, 5, null));
    assertEquals(24, r.get("totalNodes"));
    assertEquals(5, ((List<?>) r.get("nodes")).size());
    assertEquals(true, r.get("hasMore"));
    var last = tenants.execute(t -> graph.path(t, ids.getFirst(), ids.getLast(), 20, 5, null));
    assertEquals(4, ((List<?>) last.get("nodes")).size());
    assertEquals(false, last.get("hasMore"));
  }

  @Test
  void analysisConfirmationKeepsImmutableEvidenceAndReconcilesOnlyItsOwnEdges() throws Exception {
    int port;
    try (var socket = new java.net.ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    java.nio.file.Path processor =
        java.nio.file.Path.of("../processor").toAbsolutePath().normalize();
    var builder =
        new ProcessBuilder(
            processor.resolve(".venv/bin/uvicorn").toString(),
            "app:app",
            "--host",
            "127.0.0.1",
            "--port",
            String.valueOf(port));
    builder.directory(processor.toFile());
    builder.environment().put("TRACE_PROCESSOR_TOKEN", "disposable-lineage-regression-token");
    builder.redirectErrorStream(true);
    builder.redirectOutput(
        java.nio.file.Files.createTempFile("trace-lineage-parser-", ".log").toFile());
    var process = builder.start();
    try {
      boolean ready = false;
      for (int i = 0; i < 100; i++) {
        try (var socket = new java.net.Socket("127.0.0.1", port)) {
          ready = true;
          break;
        } catch (java.io.IOException e) {
          Thread.sleep(40);
        }
      }
      assertTrue(ready);
      UUID input = UUID.randomUUID(),
          output = UUID.randomUUID(),
          ic = UUID.randomUUID(),
          oc = UUID.randomUUID();
      admin.update(
          "INSERT INTO"
              + " assets(id,organization_id,connection_id,name,external_key,asset_type,platform,owner_team_id)"
              + " VALUES(?,?,?,'SQL input','TEST.SQL.INPUT','DATASET','SNOWFLAKE',?),(?,?,?,'SQL"
              + " output','TEST.SQL.OUTPUT','DATASET','SNOWFLAKE',?)",
          input,
          org,
          connection,
          team,
          output,
          org,
          connection,
          team);
      admin.update(
          "INSERT INTO asset_columns(id,organization_id,asset_id,name,data_type)"
              + " VALUES(?,?,?,'NET_AMOUNT','NUMBER'),(?,?,?,'REVENUE','NUMBER')",
          ic,
          org,
          input,
          oc,
          org,
          output);
      var jdbc = runtimeJdbc;
      var guard = new RegistryRepository(jdbc);
      var json = new tools.jackson.databind.ObjectMapper();
      var service =
          new com.trace.backend.service.TransformationService(
              tenants,
              jdbc,
              guard,
              new ManualMetadataRepository(jdbc, guard, json),
              new com.trace.backend.integration.processor.IntegrationProcessor(
                  "http://127.0.0.1:" + port, "disposable-lineage-regression-token", json),
              json);
      String sql =
          "CREATE VIEW TEST.SQL.OUTPUT AS SELECT SUM(NET_AMOUNT) AS REVENUE FROM TEST.SQL.INPUT";
      var analysis =
          service.analyze(
              output,
              new com.trace.backend.service.TransformationService.Analyze(
                  sql, "snowflake", "fixture revision one"));
      assertEquals("PARSED", analysis.get("status"));
      assertEquals(1, ((List<?>) analysis.get("columnMappings")).size());
      var confirmation =
          new com.trace.backend.service.TransformationService.Confirm(
              sql,
              "snowflake",
              "fixture revision one",
              Map.of("TEST.SQL.INPUT", input),
              List.of(0));
      var first = service.confirm(output, confirmation);
      assertEquals(true, service.confirm(output, confirmation).get("unchanged"));
      assertEquals(1, service.history(output).size());
      var changed =
          new com.trace.backend.service.TransformationService.Confirm(
              sql.replace("SUM(NET_AMOUNT)", "SUM(NET_AMOUNT)*2"),
              "snowflake",
              "fixture revision two",
              Map.of("TEST.SQL.INPUT", input),
              List.of(0));
      service.confirm(output, changed);
      assertEquals(2, service.history(output).size());
      var historyPage = service.historyPage(output, 1, 0);
      assertEquals(2L, ((Number) historyPage.get("total")).longValue());
      assertEquals(true, historyPage.get("hasMore"));
      assertEquals(1, ((List<?>) historyPage.get("items")).size());
      assertEquals(false, service.historyPage(output, 1, 1).get("hasMore"));
      assertEquals(
          2,
          admin.queryForObject(
              "SELECT count(*) FROM lineage_edges WHERE organization_id=? AND evidence_id=? AND NOT"
                  + " active",
              Integer.class,
              org,
              first.get("evidenceId")));
      assertThrows(
          Exception.class,
          () ->
              tenants.execute(
                  t -> {
                    jdbc.update(
                        "UPDATE lineage_evidence SET source_ref='overwrite' WHERE"
                            + " organization_id=?",
                        t.organizationId());
                    return true;
                  }));
      var mismatched =
          service.analyze(
              output,
              new com.trace.backend.service.TransformationService.Analyze(
                  sql.replace("TEST.SQL.OUTPUT", "TEST.SQL.WRONG"), "snowflake", "wrong target"));
      assertEquals("TARGET_MISMATCH", mismatched.get("status"));
      var formulas =
          new com.trace.backend.service.FormulaService(
              tenants,
              new FormulaRepository(jdbc, guard, json),
              new ManualMetadataRepository(jdbc, guard, json),
              guard,
              new com.trace.backend.integration.processor.IntegrationProcessor(
                  "http://127.0.0.1:" + port, "disposable-lineage-regression-token", json));
      var formula =
          formulas.create(
              new com.trace.backend.dto.request.MetadataRequests.Formula(
                  "Invalid SQL registration regression",
                  "Disposable fixture",
                  "BUSINESS_METRIC",
                  "SQL",
                  team,
                  output));
      var invalid =
          new com.trace.backend.dto.request.MetadataRequests.FormulaVersion(
              "SELECT (", "snowflake", "REGISTERED", List.of(input), List.of(ic));
      assertThrows(
          com.trace.backend.exception.ApiException.class,
          () -> formulas.version(formula.id(), invalid));
      var draft =
          formulas.version(
              formula.id(),
              new com.trace.backend.dto.request.MetadataRequests.FormulaVersion(
                  "SELECT (", "snowflake", "DRAFT", List.of(input), List.of(ic)));
      assertEquals("DRAFT", formulas.snapshot(formula.id(), draft.id()).get("state"));
      assertThrows(
          com.trace.backend.exception.ApiException.class,
          () ->
              formulas.editDraft(
                  formula.id(),
                  draft.id(),
                  new com.trace.backend.dto.request.MetadataRequests.DraftEdit(invalid, 1)));
      var names = tenants.executeSnapshot(t -> graph.referenceColumns(t, List.of(ic, oc)));
      assertEquals(2, names.size());
      assertTrue(names.stream().anyMatch(c -> "NET_AMOUNT".equals(c.get("name"))));
      assertThrows(
          Exception.class,
          () ->
              tenants.executeSnapshot(t -> graph.referenceColumns(t, List.of(UUID.randomUUID()))));

    } finally {
      process.destroy();
      assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  @Test
  void largeFanoutCountsEverythingButBoundsResponse() {
    UUID root = UUID.randomUUID();
    admin.update(
        "INSERT INTO"
            + " assets(id,organization_id,connection_id,name,external_key,asset_type,platform,owner_team_id)"
            + " VALUES(?,?,?,'Fanout','TEST.FANOUT.ROOT','DATASET','SNOWFLAKE',?)",
        root,
        org,
        connection,
        team);
    for (int i = 0; i < 600; i++) {
      UUID id = UUID.randomUUID();
      admin.update(
          "INSERT INTO"
              + " assets(id,organization_id,connection_id,name,external_key,asset_type,platform,owner_team_id)"
              + " VALUES(?,?,?,?,?,'DATASET','SNOWFLAKE',?)",
          id,
          org,
          connection,
          "Fanout " + i,
          "TEST.FANOUT.N" + i,
          team);
      edge(root, id);
    }
    long start = System.nanoTime();
    var r = page(root, "downstream", 0, 80, null);
    assertEquals(601L, ((Number) r.get("totalNodes")).longValue());
    assertEquals(600L, ((Number) r.get("totalEdges")).longValue());
    assertTrue(((List<?>) r.get("nodes")).size() <= 161);
    assertEquals(80, ((List<?>) r.get("edges")).size());
    assertEquals(true, r.get("hasMore"));
    assertTrue((System.nanoTime() - start) / 1_000_000 < 8000);
  }

  @Test
  void sourceOverviewReadsActualTenantInventory() {
    var result = tenants.execute(graph::overview);
    assertFalse(((List<?>) result.get("groups")).isEmpty());
  }

  @Test
  void reportingMembershipFlowsFromMeasureAndVisualToTheirConsumers() {
    UUID model = UUID.randomUUID(),
        measure = UUID.randomUUID(),
        report = UUID.randomUUID(),
        visual = UUID.randomUUID();
    for (var pair :
        List.of(
            Map.entry(model, "SEMANTIC_MODEL"),
            Map.entry(measure, "MEASURE"),
            Map.entry(report, "REPORT"),
            Map.entry(visual, "VISUAL")))
      admin.update(
          "INSERT INTO"
              + " assets(id,organization_id,connection_id,name,external_key,asset_type,platform,owner_team_id)"
              + " VALUES(?,?,?,?,?,?,'MANUAL',?)",
          pair.getKey(),
          org,
          connection,
          pair.getValue(),
          "TEST.REPORTING." + pair.getKey(),
          pair.getValue(),
          team);
    for (var row :
        List.of(
            List.of(model, measure, "MODEL_CONTAINS_MEASURE"),
            List.of(model, report, "REPORT_USES_MODEL"),
            List.of(measure, visual, "VISUAL_USES_MEASURE"),
            List.of(report, visual, "REPORT_CONTAINS_VISUAL")))
      admin.update(
          "INSERT INTO"
              + " lineage_edges(organization_id,source_asset_id,target_asset_id,relationship_type,provenance,evidence_ref)"
              + " VALUES(?,?,?,?,'MANUAL_DECLARATION','Explicit reporting membership fixture')",
          org,
          row.get(0),
          row.get(1),
          row.get(2));
    var result = page(measure, "downstream", 0, 80, null);
    assertEquals(4L, ((Number) result.get("totalNodes")).longValue());
    var path = tenants.execute(t -> graph.path(t, measure, report, 0, 80, null));
    assertEquals(true, path.get("reachable"));
    assertEquals(3, path.get("totalNodes"));
    assertEquals(
        1,
        admin.queryForObject(
            "SELECT count(*) FROM lineage_edges WHERE organization_id=? AND source_asset_id=? AND"
                + " target_asset_id=? AND relationship_type='MODEL_CONTAINS_MEASURE'",
            Integer.class,
            org,
            model,
            measure));
  }

  @Test
  void columnImpactContinuesThroughFormulaAndMetricIntoReportingConsumers() {
    UUID sourceAsset = UUID.randomUUID(),
        column = UUID.randomUUID(),
        formula = UUID.randomUUID(),
        fv = UUID.randomUUID(),
        metric = UUID.randomUUID(),
        mv = UUID.randomUUID(),
        report = UUID.randomUUID();
    admin.update(
        "INSERT INTO"
            + " assets(id,organization_id,connection_id,name,external_key,asset_type,platform,owner_team_id)"
            + " VALUES(?,?,?,'Isolated column source',?,'DATASET','MANUAL',?)",
        sourceAsset,
        org,
        connection,
        "TEST.COLUMN." + sourceAsset,
        team);
    admin.update(
        "INSERT INTO asset_columns(id,organization_id,asset_id,name,data_type)"
            + " VALUES(?,?,?,'REPORT_VALUE','NUMBER')",
        column,
        org,
        sourceAsset);
    admin.update(
        "INSERT INTO formulas(id,organization_id,owner_team_id,name,category,language)"
            + " VALUES(?,?,?,'Column reporting calculation','BUSINESS_METRIC','SQL')",
        formula,
        org,
        team);
    admin.update(
        "INSERT INTO"
            + " formula_versions(id,organization_id,formula_id,version,expression,state,created_by)"
            + " VALUES(?,?,?,1,'SELECT REPORT_VALUE FROM TEST.CHAIN.N0','DRAFT',?)",
        fv,
        org,
        formula,
        user);
    admin.update(
        "INSERT INTO formula_column_dependencies(organization_id,version_id,column_id)"
            + " VALUES(?,?,?)",
        org,
        fv,
        column);
    admin.update("UPDATE formula_versions SET state='REGISTERED' WHERE id=?", fv);
    admin.update(
        "INSERT INTO metrics(id,organization_id,owner_team_id,name,business_key)"
            + " VALUES(?,?,?,'Column reporting metric',?)",
        metric,
        org,
        team,
        "COL_REPORT_" + metric.toString().replace("-", ""));
    admin.update(
        "INSERT INTO metric_versions(id,organization_id,metric_id,version,formula_version_id)"
            + " VALUES(?,?,?,1,?)",
        mv,
        org,
        metric,
        fv);
    admin.update(
        "INSERT INTO"
            + " assets(id,organization_id,connection_id,name,external_key,asset_type,platform,owner_team_id)"
            + " VALUES(?,?,?,'Column reporting consumer',?,'REPORT','MANUAL',?)",
        report,
        org,
        connection,
        "TEST.REPORT." + report,
        team);
    admin.update(
        "INSERT INTO"
            + " report_mappings(organization_id,metric_version_id,asset_id,evidence_ref,created_by)"
            + " VALUES(?,?,?,'Explicit column-to-report fixture',?)",
        org,
        mv,
        report,
        user);
    var result =
        tenants.execute(t -> graph.explore(t, sourceAsset, "downstream", 0, 80, null, column));
    assertEquals(4L, ((Number) result.get("totalNodes")).longValue());
    var path = tenants.execute(t -> graph.path(t, sourceAsset, report, 0, 80, column));
    assertEquals(true, path.get("reachable"));
    assertEquals(4, path.get("totalNodes"));
  }

  @Test
  void responseUsesOneReadOnlySnapshotAcrossConcurrentMetadataChanges() {
    UUID edge = UUID.randomUUID();
    try {
      tenants.executeSnapshot(
          t -> {
            assertEquals(
                "repeatable read",
                runtimeJdbc.queryForObject("SHOW transaction_isolation", String.class));
            assertEquals(
                "on", runtimeJdbc.queryForObject("SHOW transaction_read_only", String.class));
            var before = graph.explore(t, ids.getFirst(), "downstream", 0, 80, null, null);
            admin.update(
                "INSERT INTO"
                    + " lineage_edges(id,organization_id,source_asset_id,target_asset_id,relationship_type,provenance,evidence_ref)"
                    + " VALUES(?,?,?,?,'DATA_FLOW','MANUAL_DECLARATION','Concurrent fixture"
                    + " update')",
                edge,
                org,
                ids.getFirst(),
                ids.get(20));
            var after =
                graph.explore(
                    t, ids.getFirst(), "downstream", 0, 80, (String) before.get("snapshot"), null);
            assertEquals(before.get("totalEdges"), after.get("totalEdges"));
            return true;
          });
    } finally {
      admin.update("DELETE FROM lineage_edges WHERE id=?", edge);
    }
  }

  @Test
  void catalogViewAndTableFiltersRespectDiscoveredObjectTypesWithoutChangingIdentities() {
    for (String type : List.of("VIEW", "BASE TABLE"))
      admin.update(
          "INSERT INTO"
              + " assets(organization_id,connection_id,name,external_key,asset_type,platform,owner_team_id,metadata)"
              + " VALUES(?,?,'Type-filter fixture',?,'DATASET','SNOWFLAKE',?,?::jsonb)",
          org,
          connection,
          "TEST.TYPE." + UUID.randomUUID(),
          team,
          "{\"objectType\":\"" + type + "\"}");
    var browse =
        new BrowseRepository(
            runtimeJdbc,
            new RegistryRepository(runtimeJdbc),
            new tools.jackson.databind.ObjectMapper());
    for (String type : List.of("VIEW", "TABLE")) {
      var page =
          tenants.executeSnapshot(
              t ->
                  browse.page(
                      t, "assets", 100, 0, "Type-filter fixture", null, type, "", "", connection));
      assertEquals(1L, page.total());
      assertEquals("DATASET", page.items().getFirst().get("asset_type"));
    }
  }
}
