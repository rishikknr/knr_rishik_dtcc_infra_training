package com.trace.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.trace.backend.exception.ApiException;
import com.trace.backend.repository.*;
import com.trace.backend.service.*;
import com.trace.backend.tenancy.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "TRACE_TEST_ADMIN_URL", matches = ".*/trace_tenancy_test")
class ProductExperienceIntegrationTests {
  JdbcTemplate admin, jdbc;
  UUID org, user, reviewer, viewer, team, formula, version, connection;
  TenantTransactions tenants;
  GovernanceService governance;
  RegistryRepository guard;
  UUID actor, selection;

  @BeforeEach
  void setup() {
    String url = System.getenv("TRACE_TEST_ADMIN_URL");
    assertEquals("true", System.getenv("TRACE_TEST_ALLOW_RESET"));
    assertFalse(url.matches(".*:(5433|55433)/.*"));
    admin =
        new JdbcTemplate(
            new DriverManagerDataSource(url, "trace_test_admin", "trace-test-admin-only"));
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
    org = UUID.randomUUID();
    user = UUID.randomUUID();
    reviewer = UUID.randomUUID();
    viewer = UUID.randomUUID();
    team = UUID.randomUUID();
    formula = UUID.randomUUID();
    version = UUID.randomUUID();
    connection = UUID.randomUUID();
    actor = user;
    selection = org;
    admin.update(
        "INSERT INTO organizations(id,name,slug) VALUES(?,'Product tests',?)",
        org,
        "product-" + org);
    for (UUID u : List.of(user, reviewer, viewer)) {
      admin.update(
          "INSERT INTO users(id,email,display_name) VALUES(?,?,'Product tester')",
          u,
          u + "@example.test");
      admin.update(
          "INSERT INTO organization_memberships(organization_id,user_id,role) VALUES(?,?,?)",
          org,
          u,
          u.equals(viewer) ? "VIEWER" : "OWNER");
    }
    admin.update(
        "INSERT INTO teams(id,organization_id,name,slug) VALUES(?,?,'Product team','product')",
        team,
        org);
    admin.update(
        "INSERT INTO"
            + " source_connections(id,organization_id,name,platform,ingestion_mode,owner_team_id)"
            + " VALUES(?,?,'Product warehouse','SNOWFLAKE','MANUAL',?)",
        connection,
        org,
        team);
    admin.update(
        "INSERT INTO formulas(id,organization_id,owner_team_id,name,category,language)"
            + " VALUES(?,?,?,'Revenue','BUSINESS_METRIC','SQL')",
        formula,
        org,
        team);
    admin.update(
        "INSERT INTO"
            + " formula_versions(id,organization_id,formula_id,version,expression,state,created_by)"
            + " VALUES(?,?,?,1,'SELECT 1','REGISTERED',?)",
        version,
        org,
        formula,
        user);
    var ds = new DriverManagerDataSource(url, "trace_runtime", "trace-test-runtime-only");
    jdbc = new JdbcTemplate(ds);
    tenants =
        new TenantTransactions(
            jdbc,
            () -> new TenantIdentityProvider.Selection(selection, actor),
            new JdbcTransactionManager(ds));
    guard = new RegistryRepository(jdbc);
    governance = new GovernanceService(tenants, jdbc, guard, new ObjectMapper());
  }

  UUID submit() {
    return (UUID)
        governance
            .submit(
                new GovernanceService.Submit(
                    "FORMULA_VERSION", version, "Review calculation and dependencies"))
            .get("id");
  }

  @Test
  void independentApprovalPersistsReasonAndDoesNotRewriteVersion() {
    String before =
        admin.queryForObject(
            "SELECT to_jsonb(v)::text FROM formula_versions v WHERE id=?", String.class, version);
    UUID id = submit();
    actor = reviewer;
    governance.decide(id, new GovernanceService.Decision("UNDER_REVIEW", "Checking evidence", 1));
    governance.decide(
        id, new GovernanceService.Decision("APPROVED", "Evidence and meaning reviewed", 2));
    var detail = governance.get(id);
    assertEquals("APPROVED", detail.get("status"));
    assertEquals(3, ((List<?>) detail.get("events")).size());
    assertEquals(
        before,
        admin.queryForObject(
            "SELECT to_jsonb(v)::text FROM formula_versions v WHERE id=?", String.class, version));
    assertEquals(
        3L,
        admin.queryForObject(
            "SELECT count(*) FROM audit_events WHERE resource_id=?", Long.class, id));
  }

  @Test
  void authorCannotApproveOwnRequest() {
    UUID id = submit();
    assertEquals(
        403,
        assertThrows(
                ApiException.class,
                () ->
                    governance.decide(
                        id, new GovernanceService.Decision("APPROVED", "Own decision", 1)))
            .status
            .value());
  }

  @Test
  void viewerCannotSubmitOrDecide() {
    actor = viewer;
    assertThrows(ResponseStatusException.class, this::submit);
    actor = user;
    UUID id = submit();
    actor = viewer;
    assertThrows(
        ApiException.class,
        () -> governance.decide(id, new GovernanceService.Decision("APPROVED", "Viewer", 1)));
  }

  @Test
  void duplicateSubmissionAndStaleDecisionAreRejected() {
    UUID id = submit();
    assertThrows(ApiException.class, this::submit);
    actor = reviewer;
    assertThrows(
        ApiException.class,
        () ->
            governance.decide(id, new GovernanceService.Decision("APPROVED", "Old revision", 99)));
    assertEquals("SUBMITTED", governance.get(id).get("status"));
  }

  @Test
  void clarificationCanBeResubmittedAndFinalDecisionCannotChange() {
    UUID id = submit();
    actor = reviewer;
    governance.decide(
        id, new GovernanceService.Decision("CHANGES_REQUESTED", "Explain the grain", 1));
    actor = user;
    governance.decide(
        id,
        new GovernanceService.Decision(
            "SUBMITTED", "Monthly grain documented in source evidence", 2));
    actor = reviewer;
    governance.decide(
        id, new GovernanceService.Decision("REJECTED", "Needs another immutable version", 3));
    assertThrows(
        ApiException.class,
        () -> governance.decide(id, new GovernanceService.Decision("APPROVED", "Change mind", 4)));
  }

  @Test
  void crossOrganizationReviewIsNotVisible() {
    UUID id = submit();
    selection = UUID.randomUUID();
    admin.update(
        "INSERT INTO organizations(id,name,slug) VALUES(?,'Other',?)",
        selection,
        "other-" + selection);
    admin.update(
        "INSERT INTO organization_memberships(organization_id,user_id,role) VALUES(?,?,'OWNER')",
        selection,
        actor);
    assertThrows(ResponseStatusException.class, () -> governance.get(id));
    assertEquals(0L, ((Number) governance.list("", "", 25, 0).get("total")).longValue());
    assertEquals(
        0,
        tenants
            .execute(
                t -> jdbc.queryForObject("SELECT count(*) FROM governance_reviews", Integer.class))
            .intValue());
  }

  @Test
  void draftCannotBeSubmitted() {
    UUID draft = UUID.randomUUID();
    admin.update(
        "INSERT INTO"
            + " formula_versions(id,organization_id,formula_id,version,expression,state,created_by)"
            + " VALUES(?,?,?,2,'SELECT 2','DRAFT',?)",
        draft,
        org,
        formula,
        user);
    assertThrows(
        ResponseStatusException.class,
        () -> governance.submit(new GovernanceService.Submit("FORMULA_VERSION", draft, "Draft")));
  }

  @Test
  void reviewSnapshotAndEventsAreImmutableInDatabase() {
    UUID id = submit();
    assertThrows(
        Exception.class,
        () ->
            tenants.execute(
                t ->
                    jdbc.update(
                        "UPDATE governance_reviews SET snapshot='{}'::jsonb WHERE organization_id=?"
                            + " AND id=?",
                        org,
                        id)));
    assertThrows(
        Exception.class,
        () ->
            admin.update(
                "UPDATE governance_events SET reason='overwritten' WHERE review_id=?", id));
    assertEquals("SUBMITTED", governance.get(id).get("status"));
  }

  UUID asset(UUID conn, String key, String type, String platform) {
    UUID id = UUID.randomUUID();
    admin.update(
        "INSERT INTO"
            + " assets(id,organization_id,connection_id,owner_team_id,name,external_key,asset_type,platform)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        id,
        org,
        conn,
        team,
        key,
        key,
        type,
        platform);
    return id;
  }

  MappingSuggestionService suggestions() {
    return new MappingSuggestionService(tenants, jdbc, guard, new LineageRepository(jdbc, guard));
  }

  @Test
  void nameCandidatesRequireExplicitConfirmationAndKeepProvenance() {
    UUID source = UUID.randomUUID();
    admin.update(
        "INSERT INTO"
            + " source_connections(id,organization_id,name,platform,ingestion_mode,owner_team_id)"
            + " VALUES(?,?,'Product S3','AMAZON_S3','MANUAL',?)",
        source,
        org,
        team);
    UUID file = asset(source, "s3://product/orders.csv", "FILE", "AMAZON_S3"),
        target = asset(connection, "DB.RAW.ORDERS", "DATASET", "SNOWFLAKE");
    var service = suggestions();
    assertEquals(1, ((List<?>) service.list(source).get("items")).size());
    assertEquals(
        0,
        admin.queryForObject(
            "SELECT count(*) FROM lineage_edges WHERE organization_id=?", Integer.class, org));
    service.confirm(new MappingSuggestionService.Confirm(file, target));
    assertEquals(0, ((List<?>) service.list(source).get("items")).size());
    assertEquals(
        "MANUAL_DECLARATION",
        admin.queryForObject(
            "SELECT provenance FROM lineage_edges WHERE organization_id=?", String.class, org));
    assertTrue(
        admin
            .queryForObject(
                "SELECT evidence_ref FROM lineage_edges WHERE organization_id=?", String.class, org)
            .contains("not verified"));
  }

  @Test
  void ambiguousCandidatesAndWrongOrganizationCannotBeConfirmed() {
    UUID source = UUID.randomUUID();
    admin.update(
        "INSERT INTO"
            + " source_connections(id,organization_id,name,platform,ingestion_mode,owner_team_id)"
            + " VALUES(?,?,'Product S3','AMAZON_S3','MANUAL',?)",
        source,
        org,
        team);
    UUID file = asset(source, "s3://product/orders.csv", "FILE", "AMAZON_S3"),
        target = asset(connection, "DB.RAW.ORDERS", "DATASET", "SNOWFLAKE");
    asset(source, "s3://product/another/orders.csv", "FILE", "AMAZON_S3");
    assertThrows(
        ApiException.class,
        () -> suggestions().confirm(new MappingSuggestionService.Confirm(file, target)));
    assertThrows(
        ResponseStatusException.class,
        () -> suggestions().confirm(new MappingSuggestionService.Confirm(file, UUID.randomUUID())));
  }

  @Test
  void browseFiltersAndVersionSummariesUseRealRecords() {
    asset(connection, "DB.RAW.ORDERS", "DATASET", "SNOWFLAKE");
    asset(connection, "DB.STAGING.ORDERS", "DATASET", "SNOWFLAKE");
    var browse = new BrowseService(new BrowseRepository(jdbc, guard, new ObjectMapper()), tenants);
    var page =
        browse.page(
            "assets",
            25,
            0,
            "",
            null,
            "",
            "",
            "",
            null,
            Map.of("database", "DB", "schema", "RAW", "owner", "missing", "lineage", "missing"));
    assertEquals(1, page.total());
    var formulas = browse.page("formulas", 25, 0, "Revenue", null, "", "", "", null);
    assertEquals(1, ((Number) formulas.items().getFirst().get("latest_version")).intValue());
    assertEquals("REGISTERED", formulas.items().getFirst().get("latest_state"));
  }

  @Test
  void metricReviewUsesRegisteredBusinessSnapshotAndFormulaDependencies() {
    UUID input = asset(connection, "DB.RAW.ORDERS", "DATASET", "SNOWFLAKE"),
        metric = UUID.randomUUID(),
        mv = UUID.randomUUID();
    version = UUID.randomUUID();
    admin.update(
        "INSERT INTO"
            + " formula_versions(id,organization_id,formula_id,version,expression,state,created_by)"
            + " VALUES(?,?,?,2,'SELECT 1','DRAFT',?)",
        version,
        org,
        formula,
        user);
    admin.update(
        "INSERT INTO formula_dependencies(organization_id,version_id,asset_id) VALUES(?,?,?)",
        org,
        version,
        input);
    admin.update("UPDATE formula_versions SET state='REGISTERED' WHERE id=?", version);
    admin.update(
        "INSERT INTO metrics(id,organization_id,owner_team_id,name,business_key)"
            + " VALUES(?,?,?,'Current edited name','PRODUCT_REVENUE')",
        metric,
        org,
        team);
    admin.update(
        "INSERT INTO"
            + " metric_versions(id,organization_id,metric_id,version,formula_version_id,definition_snapshot)"
            + " VALUES(?,?,?,1,?,?::jsonb)",
        mv,
        org,
        metric,
        version,
        "{\"name\":\"Registered revenue\",\"grain\":\"monthly\"}");
    UUID id =
        (UUID)
            governance
                .submit(
                    new GovernanceService.Submit("METRIC_VERSION", mv, "Review business meaning"))
                .get("id");
    var snapshot = new ObjectMapper().readTree((String) governance.get(id).get("snapshot"));
    assertEquals("Registered revenue", snapshot.path("name").asText());
    assertEquals("SELECT 1", snapshot.path("expression").asText());
    assertTrue(snapshot.path("referenced_assets").asText().contains(input.toString()));
  }

  @Test
  void owningTeamOwnerCanReviewButTeamEditorCannot() {
    UUID id = submit();
    actor = reviewer;
    admin.update(
        "UPDATE organization_memberships SET role='MEMBER' WHERE organization_id=? AND user_id=?",
        org,
        reviewer);
    admin.update(
        "INSERT INTO team_memberships(organization_id,team_id,user_id,role) VALUES(?,?,?,'EDITOR')",
        org,
        team,
        reviewer);
    assertThrows(
        ApiException.class,
        () -> governance.decide(id, new GovernanceService.Decision("APPROVED", "Editor", 1)));
    admin.update(
        "UPDATE team_memberships SET role='OWNER' WHERE organization_id=? AND team_id=? AND"
            + " user_id=?",
        org,
        team,
        reviewer);
    governance.decide(
        id, new GovernanceService.Decision("APPROVED", "Owning-team owner reviewed", 1));
    assertEquals("APPROVED", governance.get(id).get("status"));
  }
}
