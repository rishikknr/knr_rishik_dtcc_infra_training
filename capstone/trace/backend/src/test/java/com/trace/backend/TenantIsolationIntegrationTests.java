package com.trace.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trace.backend.config.TenantConfiguration;
import com.trace.backend.controller.*;
import com.trace.backend.dto.response.*;
import com.trace.backend.repository.*;
import com.trace.backend.service.*;
import com.trace.backend.tenancy.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Real PostgreSQL 16, actual Flyway migrations, no H2 and no owner-role RLS tests. The URL must
 * point to the disposable test database; its public schema is RESET.
 */
@EnabledIfEnvironmentVariable(named = "TRACE_TEST_ADMIN_URL", matches = ".*/trace_tenancy_test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantIsolationIntegrationTests {
  private static final UUID A = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID B = UUID.fromString("20000000-0000-0000-0000-000000000001");
  private static final UUID SHARED = UUID.fromString("30000000-0000-0000-0000-000000000001");
  private static final UUID B_IMPORTER = UUID.fromString("30000000-0000-0000-0000-000000000002");
  private static final UUID TEAM_A = UUID.fromString("40000000-0000-0000-0000-000000000001");
  private static final UUID TEAM_B = UUID.fromString("40000000-0000-0000-0000-000000000002");
  private static final UUID CONNECTION_A = UUID.fromString("50000000-0000-0000-0000-000000000001");
  private static final UUID CONNECTION_B = UUID.fromString("50000000-0000-0000-0000-000000000002");
  private static final MetadataPreview PREVIEW =
      new MetadataPreview(
          List.of(
              new MetadataPreview.Dataset(
                  "DB.RAW.ORDERS",
                  "RAW.ORDERS",
                  List.of(new MetadataPreview.Column("ID", "NUMBER")))));
  private final AtomicReference<TenantIdentityProvider.Selection> selection =
      new AtomicReference<>();
  private HikariDataSource pool;
  private JdbcTemplate jdbc;
  private JdbcTemplate admin;
  private TenantTransactions tenants;
  private RegistryRepository registry;
  private ImportService imports;
  private AssetRepository assets;
  private AssetColumnRepository columns;
  private MetadataImportRepository batches;
  private UUID assetA, assetB, batchA, batchB, columnA, columnB;

  @BeforeAll
  void migrate() {
    String url = System.getenv("TRACE_TEST_ADMIN_URL");
    assertEquals(
        "true",
        System.getenv("TRACE_TEST_ALLOW_RESET"),
        "Set TRACE_TEST_ALLOW_RESET=true only for a newly created disposable database");
    assertFalse(
        url.matches(".*:(5433|55433)/.*"),
        "Persistent development and acceptance databases are protected");
    var adminSource = new DriverManagerDataSource(url, "trace_test_admin", "trace-test-admin-only");
    admin = new JdbcTemplate(adminSource);
    assertEquals(
        "trace_tenancy_test", admin.queryForObject("SELECT current_database()", String.class));
    assertTrue(admin.queryForObject("SHOW server_version", String.class).startsWith("16."));
    admin.execute("DROP SCHEMA public CASCADE");
    admin.execute("CREATE SCHEMA public");
    admin.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
    admin.execute(
        """
        DO $$ BEGIN
          IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'trace_runtime') THEN
            CREATE ROLE trace_runtime LOGIN PASSWORD 'trace-test-runtime-only'
              NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
          END IF;
        END $$
        """);
    admin.execute(
        """
        DO $$ BEGIN
          IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'trace_migrator') THEN
            CREATE ROLE trace_migrator LOGIN PASSWORD 'trace-test-migration-only'
              NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
          END IF;
        END $$
        """);
    admin.execute("GRANT USAGE, CREATE ON SCHEMA public TO trace_migrator");
    admin.execute("GRANT USAGE ON SCHEMA public TO trace_runtime");
    Flyway.configure().dataSource(adminSource).target("1").load().migrate();
    // Exercise an actual V1 -> V3 upgrade with legacy IDs, not just an empty schema.
    var legacyConnection = UUID.randomUUID();
    var legacyAsset = UUID.randomUUID();
    admin.update(
        "INSERT INTO source_connections(id,name,platform,ingestion_mode) VALUES"
            + " (?,'legacy','SNOWFLAKE','IMPORT')",
        legacyConnection);
    admin.update(
        "INSERT INTO assets(id,connection_id,external_key,name,asset_type,platform) VALUES"
            + " (?,?,'RAW.OLD','RAW.OLD','DATASET','SNOWFLAKE')",
        legacyAsset,
        legacyConnection);
    // Mirror bootstrap-roles.sql: transfer the existing V1 objects, then migrate
    // as a NON-superuser. The SECURITY DEFINER bridge must work with FORCE RLS.
    for (var table :
        List.of(
            "flyway_schema_history",
            "source_connections",
            "assets",
            "asset_columns",
            "lineage_edges",
            "column_lineage")) {
      admin.execute("ALTER TABLE public." + table + " OWNER TO trace_migrator");
    }
    var migrationSource =
        new DriverManagerDataSource(url, "trace_migrator", "trace-test-migration-only");
    Flyway.configure().dataSource(migrationSource).load().migrate();
    assertEquals(
        legacyAsset,
        admin.queryForObject(
            "SELECT id FROM assets WHERE id = ? AND organization_id ="
                + " '00000000-0000-0000-0000-000000000001'",
            UUID.class,
            legacyAsset));
    var config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername("trace_runtime");
    config.setPassword("trace-test-runtime-only");
    config.setMaximumPoolSize(1); // Every transaction reuses the SAME physical connection.
    pool = new HikariDataSource(config);
    jdbc = new JdbcTemplate(pool);
    new RuntimeDatabaseGuard(jdbc).afterPropertiesSet();
    tenants = new TenantTransactions(jdbc, selection::get, new JdbcTransactionManager(pool));
    registry = new RegistryRepository(jdbc);
    assets = new AssetRepository(jdbc, registry);
    columns = new AssetColumnRepository(jdbc, registry);
    batches = new MetadataImportRepository(jdbc, registry, new ObjectMapper());
    imports =
        new ImportService(
            tenants,
            registry,
            assets,
            columns,
            batches,
            bytes -> PREVIEW,
            new SourceConnectionRepository(jdbc, registry));
  }

  @BeforeEach
  void fixture() {
    admin.execute("TRUNCATE organizations, users CASCADE");
    admin.update(
        "INSERT INTO organizations(id,name,slug) VALUES"
            + " (?,'NovaMart','novamart'),(?,'RetailHub','retailhub')",
        A,
        B);
    admin.update(
        "INSERT INTO users(id,email,display_name) VALUES"
            + " (?,'shared@example.test','Shared'),(?,'retail@example.test','Retail importer')",
        SHARED,
        B_IMPORTER);
    admin.update(
        "INSERT INTO organization_memberships(organization_id,user_id,role) VALUES"
            + " (?,?,'OWNER'),(?,?,'MEMBER'),(?,?,'MEMBER')",
        A,
        SHARED,
        B,
        SHARED,
        B,
        B_IMPORTER);
    admin.update(
        "INSERT INTO teams(id,organization_id,name) VALUES (?,?,'Data'),(?,?,'Data')",
        TEAM_A,
        A,
        TEAM_B,
        B);
    admin.update(
        "INSERT INTO team_memberships(organization_id,team_id,user_id,role) VALUES"
            + " (?,?,?,'OWNER'),(?,?,?,'VIEWER'),(?,?,?,'EDITOR')",
        A,
        TEAM_A,
        SHARED,
        B,
        TEAM_B,
        SHARED,
        B,
        TEAM_B,
        B_IMPORTER);
    admin.update(
        "INSERT INTO"
            + " source_connections(id,organization_id,name,platform,ingestion_mode,owner_team_id)"
            + " VALUES"
            + " (?,?,'Snowflake','SNOWFLAKE','IMPORT',?),(?,?,'Snowflake','SNOWFLAKE','IMPORT',?)",
        CONNECTION_A,
        A,
        TEAM_A,
        CONNECTION_B,
        B,
        TEAM_B);
    select(A, SHARED);
    batchA = imports.preview(CONNECTION_A, new byte[] {1});
    imports.commit(batchA);
    assetA = tenants.execute(t -> (UUID) assets.list(t, "", 100, 0).getFirst().id());
    columnA =
        tenants.execute(
            t ->
                jdbc.queryForObject(
                    "SELECT id FROM asset_columns WHERE organization_id = ? AND asset_id = ?",
                    UUID.class,
                    A,
                    assetA));
    select(B, B_IMPORTER);
    batchB = imports.preview(CONNECTION_B, new byte[] {1});
    imports.commit(batchB);
    assetB = tenants.execute(t -> (UUID) assets.list(t, "", 100, 0).getFirst().id());
    columnB =
        tenants.execute(
            t ->
                jdbc.queryForObject(
                    "SELECT id FROM asset_columns WHERE organization_id = ? AND asset_id = ?",
                    UUID.class,
                    B,
                    assetB));
    select(A, SHARED);
  }

  private void select(UUID org, UUID user) {
    selection.set(new TenantIdentityProvider.Selection(org, user));
  }

  private void rejected(Runnable action) {
    assertThrows(DataAccessException.class, action::run);
  }

  private void foreignKeyRejected(Runnable action) {
    var error = assertThrows(DataAccessException.class, action::run);
    assertInstanceOf(java.sql.SQLException.class, error.getRootCause());
    assertEquals("23503", ((java.sql.SQLException) error.getRootCause()).getSQLState());
  }

  private void notFound(Runnable action) {
    assertEquals(
        404, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
  }

  @Test
  void sameNamesAreIndependentAndEachOrganizationSeesOnlyItsOwnAssets() {
    assertNotEquals(assetA, assetB);
    tenants.execute(
        t -> {
          assertEquals(1, registry.countAssets(t));
          assertEquals(assetA, assets.list(t, "", 100, 0).getFirst().id());
          // RLS protects even a deliberately unscoped repository query.
          assertEquals(List.of(assetA), jdbc.queryForList("SELECT id FROM assets", UUID.class));
          return null;
        });
    select(B, B_IMPORTER);
    tenants.execute(
        t -> {
          assertEquals(assetB, assets.list(t, "", 100, 0).getFirst().id());
          assertEquals(List.of(assetB), jdbc.queryForList("SELECT id FROM assets", UUID.class));
          notFound(() -> assets.get(t, assetA));
          return null;
        });
    select(A, SHARED);
    notFound(() -> tenants.execute(t -> assets.get(t, assetB)));
  }

  @Test
  void foreignImportsCannotBePreviewedRetrievedOrCommitted() {
    notFound(() -> imports.get(batchB));
    notFound(() -> imports.commit(batchB));
    notFound(() -> imports.preview(CONNECTION_B, new byte[] {1}));
    assertEquals(batchA, imports.get(batchA).id());
  }

  @Test
  void reimportDeduplicatesOnlyWithinOrganizationAndPreservesIds() {
    var second = imports.preview(CONNECTION_A, new byte[] {2});
    imports.commit(second);
    imports.commit(second); // Idempotent retry.
    assertEquals(assetA, tenants.execute(t -> assets.list(t, "", 100, 0).getFirst().id()));
    assertEquals(2, admin.queryForObject("SELECT count(*) FROM assets", Integer.class));
    assertEquals(2, admin.queryForObject("SELECT count(*) FROM asset_columns", Integer.class));
    assertEquals(3, admin.queryForObject("SELECT count(*) FROM import_batches", Integer.class));
  }

  @Test
  void databaseRejectsCrossOrganizationAssetColumnsLineageAndTeamReferences() {
    foreignKeyRejected(
        () ->
            tenants.execute(
                t ->
                    jdbc.update(
                        "INSERT INTO"
                            + " lineage_edges(organization_id,source_asset_id,target_asset_id,relationship_type,provenance)"
                            + " VALUES (?,?,?,'READS','TEST')",
                        A,
                        assetA,
                        assetB)));
    foreignKeyRejected(
        () ->
            tenants.execute(
                t ->
                    jdbc.update(
                        "INSERT INTO"
                            + " lineage_edges(organization_id,source_asset_id,target_asset_id,relationship_type,provenance)"
                            + " VALUES (?,?,?,'READS','TEST')",
                        A,
                        assetB,
                        assetA)));
    foreignKeyRejected(
        () ->
            tenants.execute(
                t ->
                    jdbc.update(
                        "INSERT INTO asset_columns(organization_id,asset_id,name) VALUES"
                            + " (?,?,'BAD')",
                        A,
                        assetB)));
    foreignKeyRejected(
        () ->
            tenants.execute(
                t ->
                    jdbc.update(
                        "INSERT INTO"
                            + " assets(organization_id,connection_id,external_key,name,asset_type,platform,owner_team_id)"
                            + " VALUES (?,?,'BAD','BAD','DATASET','SNOWFLAKE',?)",
                        A,
                        CONNECTION_B,
                        TEAM_A)));
    foreignKeyRejected(
        () ->
            tenants.execute(
                t ->
                    jdbc.update(
                        "INSERT INTO"
                            + " column_lineage(organization_id,source_column_id,target_column_id,provenance)"
                            + " VALUES (?,?,?,'TEST')",
                        A,
                        columnA,
                        columnB)));
    foreignKeyRejected(
        () ->
            tenants.execute(
                t ->
                    jdbc.update(
                        "INSERT INTO"
                            + " column_lineage(organization_id,source_column_id,target_column_id,provenance)"
                            + " VALUES (?,?,?,'TEST')",
                        A,
                        columnB,
                        columnA)));
    foreignKeyRejected(
        () ->
            tenants.execute(
                t ->
                    jdbc.update(
                        "INSERT INTO"
                            + " column_lineage(organization_id,source_column_id,target_column_id,transformation_asset_id,provenance)"
                            + " VALUES (?,?,?,?,'TEST')",
                        A,
                        columnA,
                        columnA,
                        assetB)));
    foreignKeyRejected(
        () ->
            tenants.execute(
                t ->
                    jdbc.update(
                        "UPDATE assets SET owner_team_id = ? WHERE organization_id = ? AND id = ?",
                        TEAM_B,
                        A,
                        assetA)));
    foreignKeyRejected(
        () ->
            tenants.execute(
                t ->
                    jdbc.update(
                        "INSERT INTO"
                            + " import_batches(organization_id,connection_id,created_by_user_id,owner_team_id,status,preview)"
                            + " VALUES (?,?,?,?,'PREVIEW_READY','{}')",
                        A,
                        CONNECTION_B,
                        SHARED,
                        TEAM_A)));
    rejected(
        () ->
            tenants.execute(
                t ->
                    jdbc.update(
                        "INSERT INTO"
                            + " source_connections(organization_id,name,platform,ingestion_mode,owner_team_id)"
                            + " VALUES (?,'forged','SNOWFLAKE','IMPORT',?)",
                        B,
                        TEAM_B)));
    foreignKeyRejected(
        () ->
            tenants.execute(
                t ->
                    jdbc.update(
                        "UPDATE assets SET last_import_id=? WHERE organization_id=? AND id=?",
                        batchB,
                        A,
                        assetA)));
    foreignKeyRejected(
        () ->
            tenants.execute(
                t ->
                    jdbc.update(
                        "UPDATE asset_columns SET last_import_id=? WHERE organization_id=? AND"
                            + " id=?",
                        batchB,
                        A,
                        columnA)));
    // Constraints also hold for a privileged migration connection, independent of RLS.
    foreignKeyRejected(
        () ->
            admin.update(
                "INSERT INTO asset_columns(organization_id,asset_id,name) VALUES (?,?,'BAD')",
                A,
                assetB));
  }

  @Test
  void missingContextFailsClosedAndPoolReuseClearsCommitAndRollbackContext() {
    int pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM assets", Integer.class));
    selection.set(null);
    assertEquals(
        401,
        assertThrows(
                ResponseStatusException.class,
                () -> tenants.execute(t -> assets.list(t, "", 100, 0)))
            .getStatusCode()
            .value());
    select(A, SHARED);
    tenants.execute(
        t -> {
          assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM assets", Integer.class));
          return null;
        });
    assertNull(jdbc.queryForObject("SELECT trace_current_organization()", UUID.class));
    assertThrows(
        IllegalStateException.class,
        () ->
            tenants.execute(
                t -> {
                  throw new IllegalStateException("rollback");
                }));
    assertNull(jdbc.queryForObject("SELECT trace_current_organization()", UUID.class));
    select(B, B_IMPORTER);
    tenants.execute(
        t -> {
          assertEquals(List.of(assetB), jdbc.queryForList("SELECT id FROM assets", UUID.class));
          return null;
        });
    assertEquals(pid, jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM import_batches", Integer.class));
    rejected(
        () ->
            jdbc.update(
                "INSERT INTO asset_columns(organization_id,asset_id,name) VALUES"
                    + " (?,?,'NO_CONTEXT')",
                A,
                assetA));
    assertThrows(IllegalStateException.class, () -> assets.list(null, "", 100, 0));
  }

  @Test
  void verifiedMembershipRejectsInvalidInactiveAndSuspendedSelections() {
    select(UUID.randomUUID(), SHARED);
    assertEquals(
        403,
        assertThrows(
                ResponseStatusException.class,
                () -> tenants.execute(t -> assets.list(t, "", 100, 0)))
            .getStatusCode()
            .value());
    select(A, B_IMPORTER);
    assertThrows(
        ResponseStatusException.class, () -> tenants.execute(t -> assets.list(t, "", 100, 0)));
    select(A, SHARED);
    admin.update(
        "UPDATE organization_memberships SET status = 'SUSPENDED' WHERE organization_id = ? AND"
            + " user_id = ?",
        A,
        SHARED);
    assertThrows(
        ResponseStatusException.class, () -> tenants.execute(t -> assets.list(t, "", 100, 0)));
    select(B, B_IMPORTER);
    admin.update("UPDATE users SET active = false WHERE id = ?", B_IMPORTER);
    assertThrows(
        ResponseStatusException.class, () -> tenants.execute(t -> assets.list(t, "", 100, 0)));
    assertNull(jdbc.queryForObject("SELECT trace_current_organization()", UUID.class));
  }

  @Test
  void multiOrganizationUserResolvesSelectedOrganizationAndTeamPermissions() {
    tenants.execute(
        t -> {
          assertEquals("OWNER", t.organizationRole());
          assertEquals(Map.of(TEAM_A, "OWNER"), t.teamRoles());
          registry.requireTeamWrite(t, TEAM_A);
          return null;
        });
    select(B, SHARED);
    tenants.execute(
        t -> {
          assertEquals("MEMBER", t.organizationRole());
          assertEquals(Map.of(TEAM_B, "VIEWER"), t.teamRoles());
          assertThrows(ResponseStatusException.class, () -> registry.requireTeamWrite(t, TEAM_B));
          return null;
        });
    assertThrows(
        ResponseStatusException.class, () -> imports.preview(CONNECTION_B, new byte[] {1}));
  }

  @Test
  void runtimeCannotReadGlobalIdentityAlterPoliciesOrGrantItselfMembership() {
    rejected(() -> jdbc.queryForList("SELECT * FROM users"));
    rejected(() -> jdbc.queryForList("SELECT * FROM flyway_schema_history"));
    rejected(() -> jdbc.execute("ALTER TABLE assets DISABLE ROW LEVEL SECURITY"));
    rejected(() -> jdbc.execute("SET ROLE trace_test_admin"));
    rejected(() -> jdbc.execute("CREATE TABLE public.forbidden(id int)"));
    rejected(
        () ->
            tenants.execute(
                t ->
                    jdbc.update(
                        "UPDATE organization_memberships SET role = 'OWNER' WHERE organization_id ="
                            + " ?",
                        A)));
    tenants.execute(
        t -> {
          for (String table :
              List.of(
                  "organizations",
                  "organization_memberships",
                  "teams",
                  "team_memberships",
                  "source_connections",
                  "assets",
                  "asset_columns",
                  "lineage_edges",
                  "column_lineage",
                  "import_batches")) {
            assertEquals(
                Boolean.TRUE,
                jdbc.queryForObject(
                    "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid ="
                        + " ?::regclass",
                    Boolean.class,
                    table));
          }
          assertEquals(
              0,
              jdbc.queryForList("SELECT * FROM trace_active_membership(?, ?)", B, SHARED).size());
          return null;
        });
    assertThrows(
        IllegalStateException.class, () -> new RuntimeDatabaseGuard(admin).afterPropertiesSet());
  }

  @Test
  void clientIdentityHeadersDoNotSelectAnOrganization() throws Exception {
    var closed =
        new TenantConfiguration()
            .tenantIdentityProvider(new org.springframework.mock.web.MockHttpServletRequest());
    var closedTransactions = new TenantTransactions(jdbc, closed, new JdbcTransactionManager(pool));
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new AssetController(
                    new AssetService(closedTransactions, assets, columns),
                    new com.trace.backend.service.BrowseService(
                        new com.trace.backend.repository.BrowseRepository(
                            jdbc, registry, new ObjectMapper()),
                        closedTransactions)))
            .build();
    mvc.perform(
            get("/api/assets")
                .header("organization_id", A)
                .header("X-Organization-Id", A)
                .header("X-User-Id", SHARED)
                .header("X-Role", "OWNER"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void bootStartsWithRestrictedRuntimeAndProtectedEndpointsFailClosed() throws Exception {
    try (var app =
        new SpringApplicationBuilder(TraceBackendApplication.class)
            .run(
                "--spring.datasource.url=" + System.getenv("TRACE_TEST_ADMIN_URL"),
                "--spring.datasource.password=trace-test-runtime-only",
                "--spring.flyway.enabled=false",
                "--server.port=0")) {
      int port = ((WebServerApplicationContext) app).getWebServer().getPort();
      var response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/assets"))
                      .header("X-Organization-Id", A.toString())
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertEquals(401, response.statusCode());
      assertFalse(response.body().contains(assetB.toString()));
    }
  }

  @Test
  void localDevelopmentHttpImportUsesOnlyCsvParserInputAndTenantScopedResources() throws Exception {
    var receivedBytes = new AtomicReference<byte[]>();
    var receivedHeaders = new AtomicReference<com.sun.net.httpserver.Headers>();
    var parserServer =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var mapper = new ObjectMapper();
    parserServer.createContext(
        "/parse/snowflake-columns",
        exchange -> {
          receivedBytes.set(exchange.getRequestBody().readAllBytes());
          receivedHeaders.set(exchange.getRequestHeaders());
          byte[] body = mapper.writeValueAsBytes(PREVIEW);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    parserServer.start();
    try (var app =
        new SpringApplicationBuilder(TraceBackendApplication.class)
            .run(
                "--spring.datasource.url=" + System.getenv("TRACE_TEST_ADMIN_URL"),
                "--spring.datasource.password=trace-test-runtime-only",
                "--spring.flyway.enabled=false",
                "--server.port=0",
                "--spring.profiles.active=local-dev",
                "--trace.processor.url=http://127.0.0.1:" + parserServer.getAddress().getPort())) {
      int port = ((WebServerApplicationContext) app).getWebServer().getPort();
      String base = "http://127.0.0.1:" + port;
      var http = authenticatedClient(base);
      String csv =
          "DATABASE_NAME,SCHEMA_NAME,TABLE_NAME,COLUMN_NAME,DATA_TYPE\nDB,RAW,ORDERS,ID,NUMBER\n";
      String multipart =
          "--trace-boundary\r\nContent-Disposition: form-data; name=\"connectionId\"\r\n\r\n"
              + CONNECTION_A
              + "\r\n"
              + "--trace-boundary\r\n"
              + "Content-Disposition: form-data; name=\"file\"; filename=\"../same.csv\"\r\n"
              + "Content-Type: text/csv\r\n\r\n"
              + csv
              + "\r\n--trace-boundary--\r\n";
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/api/imports"))
                  .header("X-CSRF-TOKEN", csrf(http, base))
                  .header("Content-Type", "multipart/form-data; boundary=trace-boundary")
                  .header("X-Organization-Id", B.toString()) // Deliberately forged; ignored.
                  .POST(HttpRequest.BodyPublishers.ofString(multipart))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(201, response.statusCode(), response.body());
      assertArrayEquals(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), receivedBytes.get());
      assertFalse(receivedHeaders.get().containsKey("X-Organization-Id"));
      assertFalse(receivedHeaders.get().containsKey("X-User-Id"));
      String id = mapper.readTree(response.body()).get("id").asString();
      response =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/api/imports/" + id)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      assertTrue(response.body().contains("RAW.ORDERS"));
      response =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/api/imports/" + id + "/commit"))
                  .header("X-CSRF-TOKEN", csrf(http, base))
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      response =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/api/imports/" + batchB + "/commit"))
                  .header("X-CSRF-TOKEN", csrf(http, base))
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(404, response.statusCode());
      assertFalse(response.body().contains(B.toString()));
      response =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/api/assets")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains(assetA.toString()));
      assertFalse(response.body().contains(assetB.toString()));
      response =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/api/assets"))
                  .header("Forwarded", "for=203.0.113.1")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode()); // Forwarded identity headers confer no authority.
    } finally {
      parserServer.stop(0);
    }
  }

  private ImportService importer(MetadataPreview preview) {
    return new ImportService(
        tenants,
        registry,
        assets,
        columns,
        batches,
        bytes -> preview,
        new SourceConnectionRepository(jdbc, registry));
  }

  @Test
  void previewIsReadOnlyAndCommitReportsIncrementalChangesWithStableIdentities() {
    var p =
        new MetadataPreview(
            List.of(
                new MetadataPreview.Dataset(
                    "DB.RAW.ORDERS",
                    "RAW.ORDERS",
                    List.of(
                        new MetadataPreview.Column("ID", "NUMBER"),
                        new MetadataPreview.Column("TOTAL", "NUMBER", "Revenue")))));
    var service = importer(p);
    var id = service.preview(CONNECTION_A, new byte[] {1});
    assertEquals(1L, tenants.execute(t -> registry.countAssets(t)).longValue());
    assertEquals(2, admin.queryForObject("SELECT count(*) FROM asset_columns", Integer.class));
    var preview = service.get(id);
    assertEquals(1, preview.comparison().newColumns());
    assertEquals(1, preview.comparison().updatedDatasets());
    var committed = service.commit(id);
    assertEquals(1, committed.results().newColumns());
    assertEquals(1, committed.results().unchangedColumns());
    assertEquals(committed.results(), service.commit(id).results());
    assertEquals(committed.committedAt(), service.commit(id).committedAt());
    assertEquals(assetA, tenants.execute(t -> assets.list(t, "", 100, 0).getFirst().id()));
    assertEquals(columnA, tenants.execute(t -> columns.list(t, assetA).getFirst().id()));
    var repeat = service.commit(service.preview(CONNECTION_A, new byte[] {2}));
    assertEquals(0, repeat.results().newColumns());
    assertEquals(1, repeat.results().unchangedDatasets());
    assertEquals(2, repeat.results().unchangedColumns());
  }

  @Test
  void columnTypeUpdatesPreserveHumanDescriptionsOwnershipAndAbsentColumns() {
    admin.update("UPDATE asset_columns SET description='Human maintained' WHERE id=?", columnA);
    var p =
        new MetadataPreview(
            List.of(
                new MetadataPreview.Dataset(
                    "DB.RAW.ORDERS",
                    "RAW.ORDERS",
                    List.of(new MetadataPreview.Column("ID", "VARCHAR", "Imported description")))));
    var service = importer(p);
    var result = service.commit(service.preview(CONNECTION_A, new byte[] {1}));
    assertEquals(1, result.results().updatedColumns());
    var column = tenants.execute(t -> columns.list(t, assetA).getFirst());
    assertEquals(columnA, column.id());
    assertEquals("VARCHAR", column.dataType());
    assertEquals("Human maintained", column.description());
    assertEquals(TEAM_A, tenants.execute(t -> assets.get(t, assetA).ownerTeamId()));
    var partial =
        importer(
            new MetadataPreview(
                List.of(
                    new MetadataPreview.Dataset(
                        "DB.RAW.ORDERS",
                        "RAW.ORDERS",
                        List.of(new MetadataPreview.Column("OTHER", "DATE"))))));
    partial.commit(partial.preview(CONNECTION_A, new byte[] {1}));
    assertEquals(2, tenants.execute(t -> columns.list(t, assetA).size()).intValue());
  }

  @Test
  void expiredMissingAndInvalidPreviewsFailWithoutMutation() {
    var id = imports.preview(CONNECTION_A, new byte[] {1});
    admin.update("UPDATE import_batches SET expires_at=now()-interval '1 second' WHERE id=?", id);
    assertEquals(com.trace.backend.enums.ImportStatus.EXPIRED, imports.get(id).status());
    var e = assertThrows(com.trace.backend.exception.ApiException.class, () -> imports.commit(id));
    assertEquals("IMPORT_EXPIRED", e.code);
    notFound(() -> imports.commit(UUID.randomUUID()));
    var invalid = importer(new MetadataPreview(List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> invalid.preview(CONNECTION_A, new byte[] {1}));
    assertEquals(2, admin.queryForObject("SELECT count(*) FROM assets", Integer.class));
    assertEquals(3, admin.queryForObject("SELECT count(*) FROM import_batches", Integer.class));
  }

  @Test
  void lateDatabaseFailureRollsBackAllCanonicalChangesAndBatchStatus() {
    // This fault injection exists only in the disposable PostgreSQL database.
    admin.execute(
        "CREATE FUNCTION test_fail_column() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF"
            + " NEW.name='FAIL_COLUMN' THEN RAISE EXCEPTION 'injected test failure'; END IF; RETURN"
            + " NEW; END $$");
    admin.execute(
        "CREATE TRIGGER test_fail_column BEFORE INSERT ON asset_columns FOR EACH ROW EXECUTE"
            + " FUNCTION test_fail_column()");
    try {
      var service =
          importer(
              new MetadataPreview(
                  List.of(
                      new MetadataPreview.Dataset(
                          "DB.RAW.ORDERS",
                          "RAW.ORDERS",
                          List.of(new MetadataPreview.Column("ID", "VARCHAR"))),
                      new MetadataPreview.Dataset(
                          "DB.RAW.NEW",
                          "RAW.NEW",
                          List.of(new MetadataPreview.Column("FAIL_COLUMN", "NUMBER"))))));
      var id = service.preview(CONNECTION_A, new byte[] {1});
      assertThrows(DataAccessException.class, () -> service.commit(id));
      assertEquals(com.trace.backend.enums.ImportStatus.PREVIEW_READY, service.get(id).status());
      assertNull(service.get(id).results());
      assertEquals(2, admin.queryForObject("SELECT count(*) FROM assets", Integer.class));
      assertEquals("NUMBER", tenants.execute(t -> columns.list(t, assetA).getFirst().dataType()));
      assertNull(jdbc.queryForObject("SELECT trace_current_organization()", UUID.class));
    } finally {
      admin.execute("DROP TRIGGER test_fail_column ON asset_columns");
      admin.execute("DROP FUNCTION test_fail_column()");
    }
  }

  @Test
  void concurrentBatchesAndRepeatedCommitsPersistOnceWithAccurateResults() throws Exception {
    var config = new HikariConfig();
    config.setJdbcUrl(System.getenv("TRACE_TEST_ADMIN_URL"));
    config.setUsername("trace_runtime");
    config.setPassword("trace-test-runtime-only");
    config.setMaximumPoolSize(4);
    try (var parallelPool = new HikariDataSource(config);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var j = new JdbcTemplate(parallelPool);
      var tx =
          new TenantTransactions(
              j,
              () -> new TenantIdentityProvider.Selection(A, SHARED),
              new JdbcTransactionManager(parallelPool));
      var guard = new RegistryRepository(j);
      var ar = new AssetRepository(j, guard);
      var cr = new AssetColumnRepository(j, guard);
      var br = new MetadataImportRepository(j, guard, new ObjectMapper());
      var p =
          new MetadataPreview(
              List.of(
                  new MetadataPreview.Dataset(
                      "DB.CONCURRENT.TEST",
                      "CONCURRENT.TEST",
                      List.of(new MetadataPreview.Column("ID", "NUMBER")))));
      var service =
          new ImportService(
              tx, guard, ar, cr, br, bytes -> p, new SourceConnectionRepository(j, guard));
      UUID first = service.preview(CONNECTION_A, new byte[] {1}),
          second = service.preview(CONNECTION_A, new byte[] {2});
      var barrier = new java.util.concurrent.CyclicBarrier(2);
      var results =
          executor.invokeAll(
              List.<java.util.concurrent.Callable<ImportResponse>>of(
                  () -> {
                    barrier.await();
                    return service.commit(first);
                  },
                  () -> {
                    barrier.await();
                    return service.commit(second);
                  }));
      var a = results.get(0).get(10, java.util.concurrent.TimeUnit.SECONDS);
      var b = results.get(1).get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(1, a.results().newDatasets() + b.results().newDatasets());
      assertEquals(1, a.results().newColumns() + b.results().newColumns());
      assertEquals(1, a.results().unchangedDatasets() + b.results().unchangedDatasets());
      var repeat =
          executor.invokeAll(
              List.<java.util.concurrent.Callable<ImportResponse>>of(
                  () -> service.commit(first), () -> service.commit(first)));
      assertEquals(a.results(), repeat.get(0).get().results());
      assertEquals(a.results(), repeat.get(1).get().results());
      assertEquals(3, admin.queryForObject("SELECT count(*) FROM assets", Integer.class));
    }
  }

  @Test
  void realPythonProcessorHttpWorkflowSatisfiesAcceptanceCases() throws Exception {
    admin.execute(
        "TRUNCATE assets,asset_columns,import_batches,lineage_edges,column_lineage CASCADE");
    int parserPort;
    try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      parserPort = socket.getLocalPort();
    }
    var processorDir = java.nio.file.Path.of("../processor").toAbsolutePath();
    var log = java.nio.file.Files.createTempFile("trace-python-e2e-", ".log");
    var process =
        new ProcessBuilder(
                processorDir.resolve(".venv/bin/python").toString(),
                "-m",
                "uvicorn",
                "app:app",
                "--host",
                "127.0.0.1",
                "--port",
                String.valueOf(parserPort))
            .directory(processorDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    var http = HttpClient.newHttpClient();
    try {
      boolean ready = false;
      for (int i = 0; i < 100 && process.isAlive(); i++) {
        try {
          ready =
              http.send(
                          HttpRequest.newBuilder(
                                  URI.create("http://127.0.0.1:" + parserPort + "/health"))
                              .GET()
                              .build(),
                          HttpResponse.BodyHandlers.ofString())
                      .statusCode()
                  == 200;
        } catch (java.io.IOException ignored) {
        }
        if (ready) break;
        Thread.sleep(50);
      }
      assertTrue(
          ready, "Real Python processor failed to start: " + java.nio.file.Files.readString(log));
      try (var app =
          new SpringApplicationBuilder(TraceBackendApplication.class)
              .run(
                  "--spring.datasource.url=" + System.getenv("TRACE_TEST_ADMIN_URL"),
                  "--spring.datasource.password=trace-test-runtime-only",
                  "--spring.flyway.password=trace-test-migration-only",
                  "--server.port=0",
                  "--spring.profiles.active=local-dev",
                  "--trace.processor.url=http://127.0.0.1:" + parserPort)) {
        assertEquals(
            "18",
            admin.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank"
                    + " DESC LIMIT 1",
                String.class));
        String base =
            "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
        http = authenticatedClient(base);
        String csv =
            java.nio.file.Files.readString(
                java.nio.file.Path.of("../demo/metadata-imports/snowflake_columns.csv"));
        var mapper = new ObjectMapper();
        var initial = upload(http, base, CONNECTION_A, csv);
        assertEquals(201, initial.statusCode(), initial.body());
        var preview = mapper.readValue(initial.body(), ImportResponse.class);
        assertEquals(2, preview.comparison().newDatasets());
        assertEquals(5, preview.comparison().newColumns());
        assertEquals(0, admin.queryForObject("SELECT count(*) FROM assets", Integer.class));
        var committed = post(http, base + "/api/imports/" + preview.id() + "/commit");
        assertEquals(200, committed.statusCode(), committed.body());
        assertEquals(
            5, mapper.readValue(committed.body(), ImportResponse.class).results().newColumns());
        var ids =
            admin.queryForList(
                "SELECT id FROM assets WHERE organization_id=? ORDER BY id", UUID.class, A);
        assertEquals(2, ids.size());
        assertEquals(
            5,
            admin.queryForObject(
                "SELECT count(*) FROM asset_columns WHERE organization_id=?", Integer.class, A));
        var duplicate =
            mapper.readValue(upload(http, base, CONNECTION_A, csv).body(), ImportResponse.class);
        var duplicateResult =
            mapper.readValue(
                post(http, base + "/api/imports/" + duplicate.id() + "/commit").body(),
                ImportResponse.class);
        assertEquals(0, duplicateResult.results().newDatasets());
        assertEquals(0, duplicateResult.results().newColumns());
        assertEquals(5, duplicateResult.results().unchangedColumns());
        var incremental =
            mapper.readValue(
                upload(
                        http,
                        base,
                        CONNECTION_A,
                        csv + "NOVAMART,RAW,ORDERS,CREATED_AT,TIMESTAMP_NTZ\n")
                    .body(),
                ImportResponse.class);
        var incrementalResult =
            mapper.readValue(
                post(http, base + "/api/imports/" + incremental.id() + "/commit").body(),
                ImportResponse.class);
        assertEquals(1, incrementalResult.results().newColumns());
        assertEquals(1, incrementalResult.results().updatedDatasets());
        assertEquals(
            ids,
            admin.queryForList(
                "SELECT id FROM assets WHERE organization_id=? ORDER BY id", UUID.class, A));
        var invalid =
            upload(http, base, CONNECTION_A, "database,schema,table,column\nDB,RAW,X,ID\n");
        assertEquals(422, invalid.statusCode(), invalid.body());
        assertTrue(invalid.body().contains("data_type"));
        assertEquals(
            6,
            admin.queryForObject(
                "SELECT count(*) FROM asset_columns WHERE organization_id=?", Integer.class, A));
        var status =
            http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/registry/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, status.statusCode());
        assertEquals(2, mapper.readTree(status.body()).get("registered_assets").asInt());
        var catalog =
            http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/assets?q=ORDERS&limit=1&offset=0"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, catalog.statusCode());
        assertEquals(1, mapper.readTree(catalog.body()).size());
        for (var id : ids) {
          var detail =
              http.send(
                  HttpRequest.newBuilder(URI.create(base + "/api/assets/" + id)).GET().build(),
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(200, detail.statusCode());
          assertTrue(detail.body().contains("Snowflake"));
          var cols =
              http.send(
                  HttpRequest.newBuilder(URI.create(base + "/api/assets/" + id + "/columns"))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(200, cols.statusCode());
          assertTrue(mapper.readTree(cols.body()).size() > 0);
        }
        select(B, B_IMPORTER);
        var realParser =
            new com.trace.backend.integration.processor.ProcessorClient(
                "http://127.0.0.1:" + parserPort);
        var retail =
            new ImportService(
                tenants,
                registry,
                assets,
                columns,
                batches,
                realParser,
                new SourceConnectionRepository(jdbc, registry));
        var rb =
            retail.preview(CONNECTION_B, csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        retail.commit(rb);
        var retailIds =
            tenants.execute(
                t ->
                    assets.list(t, "", 100, 0).stream()
                        .map(com.trace.backend.entity.Asset::id)
                        .toList());
        assertEquals(2, retailIds.size());
        assertTrue(java.util.Collections.disjoint(ids, retailIds));
        notFound(() -> retail.get(preview.id()));
        notFound(() -> retail.commit(preview.id()));
        notFound(() -> tenants.execute(t -> assets.get(t, ids.getFirst())));
        select(A, SHARED);
        notFound(() -> imports.get(rb));
        notFound(() -> imports.commit(rb));
        var foreignCols =
            http.send(
                HttpRequest.newBuilder(
                        URI.create(base + "/api/assets/" + retailIds.getFirst() + "/columns"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, foreignCols.statusCode());
        var foreignImport = post(http, base + "/api/imports/" + rb + "/commit");
        assertEquals(404, foreignImport.statusCode());
      }
    } finally {
      process.destroy();
      assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  private HttpResponse<String> upload(HttpClient http, String base, UUID connection, String csv)
      throws Exception {
    String multipart =
        "--trace-e2e\r\nContent-Disposition: form-data; name=\"connectionId\"\r\n\r\n"
            + connection
            + "\r\n"
            + "--trace-e2e\r\n"
            + "Content-Disposition: form-data; name=\"file\";"
            + " filename=\"snowflake_columns.csv\"\r\n"
            + "Content-Type: text/csv\r\n\r\n"
            + csv
            + "\r\n--trace-e2e--\r\n";
    return http.send(
        HttpRequest.newBuilder(URI.create(base + "/api/imports/preview"))
            .header("X-CSRF-TOKEN", csrf(http, base))
            .header("Content-Type", "multipart/form-data; boundary=trace-e2e")
            .POST(HttpRequest.BodyPublishers.ofString(multipart))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(HttpClient http, String url) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(url))
            .header(
                "X-CSRF-TOKEN",
                csrf(http, URI.create(url).resolve("/").toString().replaceAll("/$", "")))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String csrf(HttpClient client, String base) throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    return new ObjectMapper().readTree(response.body()).get("token").asString();
  }

  private HttpClient authenticatedClient(String base) throws Exception {
    String hash =
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(10)
            .encode("Regression testing secret!");
    admin.update(
        "INSERT INTO user_credentials(user_id,password_hash) VALUES(?,?) ON CONFLICT(user_id) DO"
            + " UPDATE SET password_hash=excluded.password_hash",
        SHARED,
        hash);
    var cookies = new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL);
    var client = HttpClient.newBuilder().cookieHandler(cookies).build();
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .header("X-CSRF-TOKEN", csrf(client, base))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "email=shared%40example.test&password=Regression+testing+secret%21"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    response =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/auth/organization"))
                .header("X-CSRF-TOKEN", csrf(client, base))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"organizationId\":\"" + A + "\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return client;
  }

  private org.springframework.context.ConfigurableApplicationContext securityApp(String... extra) {
    var args =
        new java.util.ArrayList<>(
            java.util.List.of(
                "--spring.datasource.url=" + System.getenv("TRACE_TEST_ADMIN_URL"),
                "--spring.datasource.password=trace-test-runtime-only",
                "--spring.flyway.enabled=false",
                "--server.port=0",
                "--spring.profiles.active=local-dev",
                "--trace.security.local-token-delivery=true",
                "--trace.security.bcrypt-strength=10",
                "--trace.security.rate-limit-attempts=200"));
    for (String arg : extra) {
      String key = arg.split("=", 2)[0] + "=";
      args.removeIf(old -> old.startsWith(key));
      args.add(arg);
    }
    return new SpringApplicationBuilder(TraceBackendApplication.class)
        .run(args.toArray(String[]::new));
  }

  private void credential(UUID user, String password) {
    var hash =
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(10).encode(password);
    admin.update(
        "INSERT INTO user_credentials(user_id,password_hash) VALUES(?,?) ON CONFLICT(user_id) DO"
            + " UPDATE SET password_hash=excluded.password_hash",
        user,
        hash);
  }

  private class BrowserSession {
    final String base;
    final java.net.CookieManager cookies =
        new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL);
    final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
    final ObjectMapper mapper = new ObjectMapper();

    BrowserSession(org.springframework.context.ConfigurableApplicationContext app) {
      base = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
    }

    HttpResponse<String> get(String path) throws Exception {
      return http.send(
          HttpRequest.newBuilder(URI.create(base + "/api" + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> send(String path, Object body, String method) throws Exception {
      return send(path, body, method, true);
    }

    HttpResponse<String> send(String path, Object body, String method, boolean csrf)
        throws Exception {
      var b =
          HttpRequest.newBuilder(URI.create(base + "/api" + path))
              .header("Content-Type", "application/json");
      if (csrf) b.header("X-CSRF-TOKEN", TenantIsolationIntegrationTests.this.csrf(http, base));
      return http.send(
          b.method(
                  method,
                  HttpRequest.BodyPublishers.ofString(
                      body == null ? "" : mapper.writeValueAsString(body)))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> login(String email, String password) throws Exception {
      return http.send(
          HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
              .header("X-CSRF-TOKEN", TenantIsolationIntegrationTests.this.csrf(http, base))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      "email="
                          + java.net.URLEncoder.encode(
                              email, java.nio.charset.StandardCharsets.UTF_8)
                          + "&password="
                          + java.net.URLEncoder.encode(
                              password, java.nio.charset.StandardCharsets.UTF_8)))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    void loginA() throws Exception {
      credential(SHARED, "Testing password for A!");
      assertStatus(200, login("shared@example.test", "Testing password for A!"));
      select(A);
    }

    void select(UUID org) throws Exception {
      assertStatus(200, send("/auth/organization", Map.of("organizationId", org), "POST"));
    }

    tools.jackson.databind.JsonNode json(HttpResponse<String> response) {
      return mapper.readTree(response.body());
    }

    String token(HttpResponse<String> delivery) {
      String link = json(delivery).get("link").asString();
      return link.substring(link.lastIndexOf('/') + 1);
    }

    String sessionId() {
      return cookies.getCookieStore().getCookies().stream()
          .filter(c -> c.getName().equals("JSESSIONID"))
          .findFirst()
          .orElseThrow()
          .getValue();
    }
  }

  private void assertStatus(int expected, HttpResponse<String> response) {
    assertEquals(expected, response.statusCode(), response.body());
  }

  @Test
  void httpStagingRegistrationSessionPersistenceAndLogoutLogin() throws Exception {
    credential(SHARED, "Testing password for A!");
    try (var app = securityApp("--spring.profiles.active=staging",
        "--trace.security.local-token-delivery=false", "--trace.security.local-signup-enabled=false")) {
      var browser = new BrowserSession(app);
      var csrf = browser.get("/auth/csrf");
      assertStatus(200, csrf);
      assertTrue(csrf.headers().allValues("set-cookie").stream().anyMatch(c ->
          c.contains("HttpOnly") && c.contains("SameSite=Lax") && c.contains("Path=/") && !c.contains("Secure")));
      assertTrue(new ObjectMapper().readTree(browser.get("/auth/registration").body()).get("enabled").asBoolean());
      var registered = browser.send("/auth/register", Map.of("displayName", "HTTP signup",
          "email", "http-signup@example.test", "password", "HTTP signup password!",
          "confirmPassword", "HTTP signup password!"), "POST");
      assertStatus(201, registered);
      UUID id = UUID.fromString(new ObjectMapper().readTree(registered.body()).get("id").asText());
      assertEquals(0, admin.queryForObject("SELECT count(*) FROM organization_memberships WHERE user_id=?", Integer.class, id));
      String before = browser.sessionId();
      assertStatus(200, browser.login("shared@example.test", "Testing password for A!"));
      assertNotEquals(before, browser.sessionId());
      assertStatus(200, browser.get("/auth/me"));
      browser.select(A);
      assertStatus(200, browser.get("/assets"));
      assertStatus(403, browser.send("/auth/logout", null, "POST", false));
      assertStatus(200, browser.send("/auth/logout", null, "POST"));
      assertStatus(401, browser.get("/auth/me"));
      assertStatus(200, browser.login("shared@example.test", "Testing password for A!"));
      assertStatus(200, browser.get("/auth/me"));
    }
  }

  @Test
  void httpsProxyCookiesRegistrationRotationAndUntrustedForwarding() throws Exception {
    credential(SHARED, "Testing password for A!");
    try (var app = securityApp("--spring.profiles.active=staging",
        "--trace.security.local-token-delivery=false", "--trace.security.local-signup-enabled=false")) {
      String base = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
      // Emulate the TLS terminator: the hop is HTTP, the browser origin is HTTPS.
      // Secure cookies are carried explicitly over this trusted private hop.
      class ProxyBrowser {
        String cookie = "";
        final HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> send(String path, String method, String body, String token) throws Exception {
          var builder = HttpRequest.newBuilder(URI.create(base + path))
              .header("X-Forwarded-Proto", "https")
              .header("X-Forwarded-Host", "changing-tunnel.trycloudflare.com")
              .header("X-Forwarded-For", "203.0.113.8")
              .header("Origin", "https://changing-tunnel.trycloudflare.com");
          if (!cookie.isEmpty()) builder.header("Cookie", cookie);
          if (token != null) builder.header("X-CSRF-TOKEN", token);
          if (body != null) builder.header("Content-Type", body.startsWith("{")
              ? "application/json" : "application/x-www-form-urlencoded");
          var response = client.send(builder.method(method, body == null
              ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
              HttpResponse.BodyHandlers.ofString());
          response.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("JSESSIONID="))
              .findFirst().ifPresent(c -> cookie = c.split(";", 2)[0]);
          return response;
        }
        String token() throws Exception {
          var response = send("/api/auth/csrf", "GET", null, null);
          assertStatus(200, response);
          return new ObjectMapper().readTree(response.body()).get("token").asText();
        }
      }
      var browser = new ProxyBrowser();
      var csrf = browser.send("/api/auth/csrf", "GET", null, null);
      assertStatus(200, csrf);
      assertTrue(csrf.headers().allValues("set-cookie").stream().anyMatch(c ->
          c.contains("Secure") && c.contains("HttpOnly") && c.contains("Path=/") && c.contains("SameSite=Lax")));
      String before = browser.cookie;
      String token = new ObjectMapper().readTree(csrf.body()).get("token").asText();
      var options = browser.send("/api/auth/registration", "GET", null, null);
      assertStatus(200, options);
      assertTrue(new ObjectMapper().readTree(options.body()).get("enabled").asBoolean());
      var registered = browser.send("/api/auth/register", "POST",
          "{\"displayName\":\"HTTPS signup\",\"email\":\"https-signup@example.test\","
              + "\"password\":\"HTTPS signup password!\",\"confirmPassword\":\"HTTPS signup password!\"}", token);
      assertStatus(201, registered);
      UUID registeredUser = UUID.fromString(new ObjectMapper().readTree(registered.body()).get("id").asText());
      assertEquals(0, admin.queryForObject("SELECT count(*) FROM organization_memberships WHERE user_id=?",
          Integer.class, registeredUser));
      assertEquals("USER", admin.queryForObject("SELECT platform_role FROM users WHERE id=?", String.class, registeredUser));
      String login = "email=shared%40example.test&password=Testing+password+for+A%21";
      assertStatus(200, browser.send("/api/auth/login", "POST", login, token));
      assertNotEquals(before, browser.cookie);
      assertStatus(200, browser.send("/api/auth/me", "GET", null, null));
      // CSRF renewal after authentication; a stale token must fail, then recover.
      var stale = browser.send("/api/auth/logout", "POST", null, token);
      assertStatus(403, stale);
      assertTrue(stale.body().contains("CSRF_INVALID"));
      assertStatus(200, browser.send("/api/auth/logout", "POST", null, browser.token()));
      assertStatus(401, browser.send("/api/auth/me", "GET", null, null));
      assertStatus(200, browser.send("/api/auth/login", "POST", login, browser.token()));
      // Staging also supports direct HTTP, with scheme-appropriate cookies.
      var plain = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf"))
          .GET().build(), HttpResponse.BodyHandlers.ofString());
      assertStatus(200, plain);
      assertTrue(plain.headers().allValues("set-cookie").stream().anyMatch(c ->
          c.startsWith("JSESSIONID=") && !c.contains("Secure") && c.contains("HttpOnly")));
    }
    // When the socket peer is not trusted, forged proxy headers cannot enable HTTPS.
    try (var app = securityApp("--spring.profiles.active=staging",
        "--trace.security.local-token-delivery=false", "--trace.security.local-signup-enabled=false",
        "--server.servlet.session.cookie.secure=true",
        "--server.tomcat.remoteip.internal-proxies=192[.]0[.]2[.]1")) {
      String base = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
      var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf"))
          .header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "attacker.example")
          .GET().build(), HttpResponse.BodyHandlers.ofString());
      assertStatus(400, response);
      assertTrue(response.body().contains("HTTPS_REQUIRED"));
    }
  }

  @Test
  void authenticationCsrfFixationLogoutAndDisabledAccounts() throws Exception {
    credential(SHARED, "Testing password for A!");
    try (var app = securityApp()) {
      var s = new BrowserSession(app);
      assertStatus(401, s.get("/assets"));
      assertStatus(403, s.send("/auth/login", Map.of(), "POST", false));
      var csrfResponse = s.get("/auth/csrf");
      assertStatus(200, csrfResponse);
      String before = s.sessionId();
      assertTrue(
          new BrowserSession(app)
              .get("/auth/csrf").headers().allValues("set-cookie").stream()
                  .anyMatch(c -> c.contains("HttpOnly") && c.contains("SameSite=Lax")));
      var wrong = s.login("shared@example.test", "wrong password");
      var unknown = s.login("unknown@example.test", "wrong password");
      assertStatus(401, wrong);
      assertEquals(wrong.body(), unknown.body());
      admin.update("UPDATE users SET active=false WHERE id=?", SHARED);
      assertEquals(wrong.body(), s.login("shared@example.test", "Testing password for A!").body());
      admin.update("UPDATE users SET active=true WHERE id=?", SHARED);
      assertStatus(200, s.login(" SHARED@example.test ", "Testing password for A!"));
      assertNotEquals(before, s.sessionId());
      var me = s.get("/auth/me");
      assertStatus(200, me);
      assertFalse(me.body().contains("password"));
      assertFalse(me.body().contains("$2a$"));
      assertFalse(me.body().contains("securityVersion"));
      assertStatus(403, s.get("/assets"));
      s.select(A);
      assertStatus(200, s.get("/assets"));
      assertStatus(
          403,
          s.send(
              "/connections", Map.of("name", "CSRF test", "ownerTeamId", TEAM_A), "POST", false));
      assertStatus(
          201, s.send("/connections", Map.of("name", "CSRF test", "ownerTeamId", TEAM_A), "POST"));
      String old = s.sessionId();
      assertStatus(200, s.send("/auth/logout", null, "POST"));
      var replay =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(s.base + "/api/assets"))
                      .header("Cookie", "JSESSIONID=" + old)
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertStatus(401, replay);
      assertTrue(
          admin.queryForObject(
                  "SELECT count(*) FROM audit_events WHERE event_type='LOGIN_FAILED'",
                  Integer.class)
              >= 3);
      assertEquals(
          1,
          admin.queryForObject(
              "SELECT count(*) FROM audit_events WHERE event_type='LOGOUT'", Integer.class));
    }
  }

  @Test
  void multiOrganizationRolesHeadersAndLiveRevocation() throws Exception {
    admin.update(
        "UPDATE organization_memberships SET role='ADMIN' WHERE organization_id=? AND user_id=?",
        B,
        SHARED);
    try (var app = securityApp()) {
      var s = new BrowserSession(app);
      s.loginA();
      var me = s.json(s.get("/auth/me"));
      assertEquals(2, me.get("organizations").size());
      assertStatus(
          403, s.send("/auth/organization", Map.of("organizationId", UUID.randomUUID()), "POST"));
      var forged =
          s.http.send(
              HttpRequest.newBuilder(URI.create(s.base + "/api/assets"))
                  .header("X-User-ID", B_IMPORTER.toString())
                  .header("X-Organization-ID", B.toString())
                  .header("X-Role", "PLATFORM_ADMIN")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertStatus(200, forged);
      assertTrue(forged.body().contains(assetA.toString()));
      assertFalse(forged.body().contains(assetB.toString()));
      s.select(B);
      assertTrue(s.get("/assets").body().contains(assetB.toString()));
      assertStatus(404, s.get("/assets/" + assetA));
      assertStatus(404, s.get("/assets/" + assetA + "/columns"));
      assertStatus(404, s.get("/imports/" + batchA));
      assertEquals("ADMIN", s.json(s.get("/auth/me")).get("organizationRole").asString());
      assertStatus(
          403,
          s.send(
              "/organization/members/" + SHARED,
              Map.of("role", "OWNER", "status", "ACTIVE"),
              "PATCH"));
      admin.update(
          "UPDATE organization_memberships SET role='VIEWER' WHERE organization_id=? AND user_id=?",
          B,
          SHARED);
      assertStatus(
          403, s.send("/connections", Map.of("name", "forbidden", "ownerTeamId", TEAM_B), "POST"));
      assertStatus(403, s.get("/organization/members"));
      admin.update(
          "UPDATE organization_memberships SET status='REVOKED' WHERE organization_id=? AND"
              + " user_id=?",
          B,
          SHARED);
      assertStatus(403, s.get("/assets"));
      assertTrue(s.json(s.get("/auth/me")).get("selectedOrganization").isNull());
      assertStatus(403, s.send("/auth/organization", Map.of("organizationId", B), "POST"));
      s.select(A);
      admin.update("UPDATE organizations SET active=false WHERE id=?", A);
      assertStatus(403, s.get("/assets"));
      admin.update("UPDATE organizations SET active=true WHERE id=?", A);
      s.select(A);
      admin.update("UPDATE users SET active=false WHERE id=?", SHARED);
      assertStatus(401, s.get("/auth/me"));
    }
  }

  @Test
  void platformProvisioningDoesNotGrantImplicitTenantDataAccessAndBootstrapIsOneTime()
      throws Exception {
    var migrator =
        new JdbcTemplate(
            new DriverManagerDataSource(
                System.getenv("TRACE_TEST_ADMIN_URL"),
                "trace_migrator",
                "trace-test-migration-only"));
    String hash =
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(10)
            .encode("Bootstrap testing password!");
    UUID platform =
        migrator.queryForObject(
            "SELECT trace_bootstrap_admin(?,?,?)",
            UUID.class,
            "platform@example.test",
            "Platform",
            hash);
    assertThrows(
        DataAccessException.class,
        () ->
            migrator.queryForObject(
                "SELECT trace_bootstrap_admin(?,?,?)",
                UUID.class,
                "other@example.test",
                "Other",
                hash));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.queryForObject(
                "SELECT trace_bootstrap_admin(?,?,?)",
                UUID.class,
                "forged@example.test",
                "Forged",
                hash));
    try (var app = securityApp()) {
      var p = new BrowserSession(app);
      assertStatus(200, p.login("platform@example.test", "Bootstrap testing password!"));
      assertStatus(200, p.get("/platform/organizations"));
      assertStatus(403, p.send("/auth/organization", Map.of("organizationId", A), "POST"));
      assertStatus(403, p.get("/assets"));
      var created =
          p.send(
              "/platform/organizations", Map.of("name", "DataWorks", "slug", "dataworks"), "POST");
      assertStatus(200, created);
      UUID org = UUID.fromString(p.json(created).get("id").asString());
      p.select(org);
      assertEquals(0, p.json(p.get("/assets")).size());
      assertStatus(
          200,
          p.send("/platform/organizations/" + A + "/status", Map.of("active", false), "PATCH"));
      assertFalse(
          admin.queryForObject("SELECT active FROM organizations WHERE id=?", Boolean.class, A));
      var s = new BrowserSession(app);
      credential(SHARED, "Testing password for A!");
      assertStatus(200, s.login("shared@example.test", "Testing password for A!"));
      assertStatus(403, s.send("/auth/organization", Map.of("organizationId", A), "POST"));
    }
  }

  @Test
  void teamPermissionsAndLastOwnerProtectionsAreServerEnforced() throws Exception {
    try (var app = securityApp()) {
      var owner = new BrowserSession(app);
      owner.loginA();
      assertStatus(
          409,
          owner.send(
              "/organization/members/" + SHARED,
              Map.of("role", "ADMIN", "status", "ACTIVE"),
              "PATCH"));
      var team =
          owner.send(
              "/teams",
              Map.of("name", "Finance", "slug", "finance", "description", "Finance governance"),
              "POST");
      assertStatus(200, team);
      String finance = owner.json(team).get("id").asString();
      admin.update(
          "INSERT INTO organization_memberships(organization_id,user_id,role) VALUES(?,?,'MEMBER')",
          A,
          B_IMPORTER);
      assertStatus(
          200,
          owner.send(
              "/teams/" + finance + "/members",
              Map.of("userId", B_IMPORTER, "role", "EDITOR"),
              "POST"));
      credential(B_IMPORTER, "Retail user test password!");
      var editor = new BrowserSession(app);
      assertStatus(200, editor.login("retail@example.test", "Retail user test password!"));
      editor.select(A);
      assertStatus(
          403,
          editor.send(
              "/connections", Map.of("name", "foreign team", "ownerTeamId", TEAM_A), "POST"));
      assertStatus(
          201,
          editor.send(
              "/connections", Map.of("name", "Finance source", "ownerTeamId", finance), "POST"));
      assertStatus(
          403,
          editor.send(
              "/teams/" + finance + "/members",
              Map.of("userId", B_IMPORTER, "role", "OWNER"),
              "POST"));
      assertStatus(
          403,
          editor.send(
              "/organization/invitations",
              Map.of("email", "external@example.test", "role", "MEMBER"),
              "POST"));
      assertStatus(
          200,
          owner.send(
              "/teams/" + finance + "/members/" + B_IMPORTER, Map.of("role", "OWNER"), "PATCH"));
      assertStatus(
          200,
          editor.send(
              "/teams/" + finance + "/members",
              Map.of("userId", SHARED, "role", "VIEWER"),
              "POST"));
      assertStatus(
          409, editor.send("/teams/" + finance + "/members/" + B_IMPORTER, null, "DELETE"));
      assertStatus(
          409,
          owner.send(
              "/organization/members/" + B_IMPORTER,
              Map.of("role", "MEMBER", "status", "REVOKED"),
              "PATCH"));
      assertStatus(
          409,
          owner.send(
              "/teams/" + finance + "/members",
              Map.of("userId", UUID.randomUUID(), "role", "VIEWER"),
              "POST"));
      assertStatus(
          200,
          owner.send("/teams/" + finance + "/members/" + SHARED, Map.of("role", "OWNER"), "PATCH"));
      assertStatus(
          200,
          owner.send(
              "/organization/members/" + B_IMPORTER,
              Map.of("role", "MEMBER", "status", "REVOKED"),
              "PATCH"));
      assertStatus(403, editor.get("/assets"));
    }
  }

  @Test
  void invitationOnboardingHashExpiryReplayAndExistingIdentity() throws Exception {
    try (var app = securityApp()) {
      var owner = new BrowserSession(app);
      owner.loginA();
      var invitation =
          owner.send(
              "/organization/invitations",
              Map.of("email", "New.User@Example.test", "role", "MEMBER"),
              "POST");
      assertStatus(200, invitation);
      String token = owner.token(invitation);
      assertEquals(
          IdentityServiceHash(token),
          admin.queryForObject(
              "SELECT token_hash FROM organization_invitations WHERE email='new.user@example.test'",
              String.class));
      assertFalse(owner.get("/organization/invitations").body().contains(token));
      var invited = new BrowserSession(app);
      assertStatus(
          403,
          invited.send(
              "/auth/invitations/accept",
              Map.of(
                  "token",
                  token,
                  "displayName",
                  "New user",
                  "password",
                  "New user testing password!"),
              "POST",
              false));
      assertStatus(
          200,
          invited.send(
              "/auth/invitations/accept",
              Map.of(
                  "token",
                  token,
                  "displayName",
                  "New user",
                  "password",
                  "New user testing password!"),
              "POST"));
      var me = invited.json(invited.get("/auth/me"));
      assertEquals("new.user@example.test", me.get("user").get("email").asString());
      invited.select(A);
      UUID who = UUID.fromString(me.get("user").get("id").asString());
      String encoded =
          admin.queryForObject(
              "SELECT password_hash FROM user_credentials WHERE user_id=?", String.class, who);
      assertNotEquals("New user testing password!", encoded);
      assertTrue(
          new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
              .matches("New user testing password!", encoded));
      assertStatus(409, invited.send("/auth/invitations/accept", Map.of("token", token), "POST"));
      var existing =
          owner.send(
              "/organization/invitations",
              Map.of("email", "retail@example.test", "role", "MEMBER"),
              "POST");
      String existingToken = owner.token(existing);
      assertStatus(
          409, owner.send("/auth/invitations/accept", Map.of("token", existingToken), "POST"));
      var anonymous = new BrowserSession(app);
      assertStatus(
          409,
          anonymous.send(
              "/auth/invitations/accept",
              Map.of(
                  "token",
                  existingToken,
                  "displayName",
                  "Attack",
                  "password",
                  "Attacker test password!"),
              "POST"));
      credential(B_IMPORTER, "Existing user password!");
      var retail = new BrowserSession(app);
      assertStatus(200, retail.login("retail@example.test", "Existing user password!"));
      assertStatus(
          200, retail.send("/auth/invitations/accept", Map.of("token", existingToken), "POST"));
      retail.select(A);
      var expiry =
          owner.send(
              "/organization/invitations",
              Map.of("email", "expired@example.test", "role", "VIEWER"),
              "POST");
      admin.update(
          "UPDATE organization_invitations SET expires_at=now()-interval '1 minute' WHERE"
              + " email='expired@example.test'");
      assertStatus(
          409,
          anonymous.send(
              "/auth/invitations/accept",
              Map.of(
                  "token",
                  owner.token(expiry),
                  "displayName",
                  "Expired",
                  "password",
                  "Expired test password!"),
              "POST"));
      var revoked =
          owner.send(
              "/organization/invitations",
              Map.of("email", "revoked@example.test", "role", "MEMBER"),
              "POST");
      assertStatus(
          200,
          owner.send(
              "/organization/invitations/" + owner.json(revoked).get("id").asString(),
              null,
              "DELETE"));
      assertStatus(
          409,
          anonymous.send(
              "/auth/invitations/accept",
              Map.of(
                  "token",
                  owner.token(revoked),
                  "displayName",
                  "Revoked",
                  "password",
                  "Revoked test password!"),
              "POST"));
      var stale =
          owner.send(
              "/organization/invitations",
              Map.of("email", "stale@example.test", "role", "MEMBER"),
              "POST");
      admin.update("UPDATE organizations SET active=false WHERE id=?", A);
      assertStatus(
          409,
          anonymous.send(
              "/auth/invitations/accept",
              Map.of(
                  "token",
                  owner.token(stale),
                  "displayName",
                  "Stale",
                  "password",
                  "Stale testing password!"),
              "POST"));
      assertEquals(
          0,
          admin.queryForObject(
              "SELECT count(*) FROM users WHERE email IN"
                  + " ('expired@example.test','revoked@example.test','stale@example.test')",
              Integer.class));
    }
  }

  private String IdentityServiceHash(String token) {
    return com.trace.backend.service.IdentityService.tokenHash(token);
  }

  @Test
  void passwordChangeAndRecoveryRevokeExistingSessions() throws Exception {
    admin.update("UPDATE users SET platform_role='PLATFORM_ADMIN' WHERE id=?", SHARED);
    try (var app = securityApp()) {
      var owner = new BrowserSession(app);
      owner.loginA();
      var other = new BrowserSession(app);
      assertStatus(200, other.login("shared@example.test", "Testing password for A!"));
      other.select(A);
      assertStatus(
          400,
          owner.send(
              "/auth/password/change",
              Map.of("currentPassword", "incorrect", "newPassword", "Replacement password!"),
              "POST"));
      assertStatus(
          200,
          owner.send(
              "/auth/password/change",
              Map.of(
                  "currentPassword",
                  "Testing password for A!",
                  "newPassword",
                  "Replacement password!"),
              "POST"));
      assertStatus(401, owner.get("/auth/me"));
      assertStatus(401, other.get("/assets"));
      assertStatus(401, other.login("shared@example.test", "Testing password for A!"));
      assertStatus(200, owner.login("shared@example.test", "Replacement password!"));
      credential(B_IMPORTER, "Original retail password!");
      var retail = new BrowserSession(app);
      assertStatus(200, retail.login("retail@example.test", "Original retail password!"));
      retail.select(B);
      var recovery =
          owner.send("/platform/users/" + B_IMPORTER + "/password-recovery", null, "POST");
      assertStatus(200, recovery);
      String token = owner.token(recovery);
      assertEquals(
          IdentityServiceHash(token),
          admin.queryForObject(
              "SELECT token_hash FROM password_reset_tokens WHERE user_id=? AND consumed_at IS"
                  + " NULL",
              String.class,
              B_IMPORTER));
      var anonymous = new BrowserSession(app);
      assertStatus(
          200,
          anonymous.send(
              "/auth/password/reset",
              Map.of("token", token, "newPassword", "Recovered retail password!"),
              "POST"));
      assertStatus(401, retail.get("/assets"));
      assertStatus(401, retail.login("retail@example.test", "Original retail password!"));
      assertStatus(200, retail.login("retail@example.test", "Recovered retail password!"));
      assertStatus(
          409,
          anonymous.send(
              "/auth/password/reset",
              Map.of("token", token, "newPassword", "Replayed token password!"),
              "POST"));
      assertEquals(
          1,
          admin.queryForObject(
              "SELECT count(*) FROM audit_events WHERE event_type='PASSWORD_CHANGED'",
              Integer.class));
      assertEquals(
          1,
          admin.queryForObject(
              "SELECT count(*) FROM audit_events WHERE event_type='PASSWORD_RESET'",
              Integer.class));
    }
  }

  @Test
  void loginRateLimitsAndBoundedJsonRequests() throws Exception {
    try (var app = securityApp("--trace.security.rate-limit-attempts=2")) {
      var s = new BrowserSession(app);
      assertStatus(401, s.login("unknown@example.test", "Wrong password!"));
      assertStatus(401, s.login("unknown@example.test", "Wrong password!"));
      assertStatus(429, s.login("unknown@example.test", "Wrong password!"));
      assertStatus(
          413, s.send("/auth/invitations/accept", Map.of("token", "x".repeat(20000)), "POST"));
    }
  }

  @Test
  void expiredSessionCannotAccessProtectedApis() throws Exception {
    try (var app = securityApp()) {
      var s = new BrowserSession(app);
      s.loginA();
      var manager =
          ((org.springframework.boot.tomcat.TomcatWebServer)
                  ((WebServerApplicationContext) app).getWebServer())
              .getTomcat()
              .getHost()
              .findChildren()[0];
      var session = ((org.apache.catalina.Context) manager).getManager().findSession(s.sessionId());
      session.setMaxInactiveInterval(1);
      Thread.sleep(1200);
      assertStatus(401, s.get("/assets"));
    }
  }

  @Test
  void concurrentOwnerDemotionsNeverLeaveOrganizationOwnerless() throws Exception {
    admin.update(
        "INSERT INTO organization_memberships(organization_id,user_id,role) VALUES(?,?,'OWNER')",
        A,
        B_IMPORTER);
    try (var app = securityApp()) {
      var first = new BrowserSession(app);
      first.loginA();
      credential(B_IMPORTER, "Other owner password!");
      var second = new BrowserSession(app);
      assertStatus(200, second.login("retail@example.test", "Other owner password!"));
      second.select(A);
      try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
        var start = new java.util.concurrent.CountDownLatch(1);
        var a =
            executor.submit(
                () -> {
                  start.await();
                  return first.send(
                      "/organization/members/" + SHARED,
                      Map.of("role", "ADMIN", "status", "ACTIVE"),
                      "PATCH");
                });
        var b =
            executor.submit(
                () -> {
                  start.await();
                  return second.send(
                      "/organization/members/" + B_IMPORTER,
                      Map.of("role", "ADMIN", "status", "ACTIVE"),
                      "PATCH");
                });
        start.countDown();
        var results = java.util.List.of(a.get().statusCode(), b.get().statusCode());
        assertTrue(results.contains(200));
        assertTrue(results.contains(409));
      }
      assertEquals(
          1,
          admin.queryForObject(
              "SELECT count(*) FROM organization_memberships WHERE organization_id=? AND"
                  + " role='OWNER' AND status='ACTIVE'",
              Integer.class,
              A));
    }
  }

  @Test
  void concurrentInvitationAcceptanceIsSingleUseAndAtomic() throws Exception {
    try (var app = securityApp()) {
      var owner = new BrowserSession(app);
      owner.loginA();
      var invitation =
          owner.send(
              "/organization/invitations",
              Map.of("email", "concurrent@example.test", "role", "MEMBER"),
              "POST");
      String token = owner.token(invitation);
      var first = new BrowserSession(app);
      var second = new BrowserSession(app);
      try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
        var start = new java.util.concurrent.CountDownLatch(1);
        var body =
            Map.of(
                "token",
                token,
                "displayName",
                "Concurrent",
                "password",
                "Concurrent test password!");
        var a =
            executor.submit(
                () -> {
                  start.await();
                  return first.send("/auth/invitations/accept", body, "POST");
                });
        var b =
            executor.submit(
                () -> {
                  start.await();
                  return second.send("/auth/invitations/accept", body, "POST");
                });
        start.countDown();
        var results = java.util.List.of(a.get().statusCode(), b.get().statusCode());
        assertTrue(results.contains(200));
        assertTrue(results.contains(409));
      }
      assertEquals(
          1,
          admin.queryForObject(
              "SELECT count(*) FROM users WHERE email='concurrent@example.test'", Integer.class));
      assertEquals(
          1,
          admin.queryForObject(
              "SELECT count(*) FROM audit_events WHERE event_type='INVITATION_ACCEPTED'",
              Integer.class));
    }
  }

  @Test
  void criticalMembershipMutationRollsBackIfAuditPersistenceFails() throws Exception {
    try (var app = securityApp()) {
      var owner = new BrowserSession(app);
      owner.loginA();
      admin.update(
          "INSERT INTO organization_memberships(organization_id,user_id,role) VALUES(?,?,'MEMBER')",
          A,
          B_IMPORTER);
      admin.execute(
          "CREATE FUNCTION trace_test_audit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
              + " IF NEW.event_type='ORGANIZATION_MEMBERSHIP_CHANGED' THEN RAISE EXCEPTION"
              + " 'Deliberate test failure'; END IF; RETURN NEW; END $$");
      admin.execute(
          "CREATE TRIGGER trace_test_audit_failure BEFORE INSERT ON audit_events FOR EACH ROW"
              + " EXECUTE FUNCTION trace_test_audit_failure()");
      try {
        assertTrue(
            owner
                    .send(
                        "/organization/members/" + B_IMPORTER,
                        Map.of("role", "ADMIN", "status", "ACTIVE"),
                        "PATCH")
                    .statusCode()
                >= 400);
        assertEquals(
            "MEMBER",
            admin.queryForObject(
                "SELECT role FROM organization_memberships WHERE organization_id=? AND user_id=?",
                String.class,
                A,
                B_IMPORTER));
      } finally {
        admin.execute("DROP TRIGGER trace_test_audit_failure ON audit_events");
        admin.execute("DROP FUNCTION trace_test_audit_failure()");
      }
    }
  }

  @Test
  void permissionDtosAndSourceOwnershipTransfersUseCurrentRoles() throws Exception {
    try (var app = securityApp()) {
      var owner = new BrowserSession(app);
      owner.loginA();
      assertStatus(200, owner.get("/me/organizations"));
      var permissions = owner.get("/me/permissions");
      assertStatus(200, permissions);
      assertTrue(permissions.body().contains("membership:manage"));
      var created =
          owner.send(
              "/teams",
              Map.of("name", "Transfer destination", "slug", "transfer-destination"),
              "POST");
      assertStatus(200, created);
      String team = owner.json(created).get("id").asString();
      assertStatus(
          404,
          owner.send(
              "/connections/" + CONNECTION_B + "/owner-team",
              Map.of("ownerTeamId", team),
              "PATCH"));
      assertStatus(
          404,
          owner.send(
              "/connections/" + CONNECTION_A + "/owner-team",
              Map.of("ownerTeamId", TEAM_B),
              "PATCH"));
      assertStatus(
          200,
          owner.send(
              "/connections/" + CONNECTION_A + "/owner-team",
              Map.of("ownerTeamId", team),
              "PATCH"));
      assertStatus(
          200,
          owner.send("/assets/" + assetA + "/owner-team", Map.of("ownerTeamId", team), "PATCH"));
      assertEquals(
          UUID.fromString(team),
          admin.queryForObject("SELECT owner_team_id FROM assets WHERE id=?", UUID.class, assetA));
      assertEquals(
          2,
          admin.queryForObject(
              "SELECT count(*) FROM audit_events WHERE event_type='RESOURCE_OWNERSHIP_TRANSFERRED'",
              Integer.class));
      admin.update(
          "UPDATE organization_memberships SET role='VIEWER' WHERE organization_id=? AND user_id=?",
          A,
          SHARED);
      assertFalse(owner.get("/me/permissions").body().contains("metadata:import"));
      assertStatus(
          403,
          owner.send(
              "/connections/" + CONNECTION_A + "/owner-team",
              Map.of("ownerTeamId", TEAM_A),
              "PATCH"));
    }
  }

  @Test
  void ownershipTransferAndGlobalAccountStatusChangesAreAudited() throws Exception {
    admin.update("UPDATE users SET platform_role='PLATFORM_ADMIN' WHERE id=?", SHARED);
    admin.update(
        "INSERT INTO organization_memberships(organization_id,user_id,role) VALUES(?,?,'MEMBER')",
        A,
        B_IMPORTER);
    try (var app = securityApp()) {
      var owner = new BrowserSession(app);
      owner.loginA();
      assertStatus(
          409,
          owner.send("/platform/users/" + SHARED + "/status", Map.of("active", false), "PATCH"));
      credential(B_IMPORTER, "Account status test password!");
      var target = new BrowserSession(app);
      assertStatus(200, target.login("retail@example.test", "Account status test password!"));
      target.select(B);
      assertStatus(
          200,
          owner.send(
              "/platform/users/" + B_IMPORTER + "/status", Map.of("active", false), "PATCH"));
      assertStatus(401, target.get("/assets"));
      assertStatus(
          200,
          owner.send("/platform/users/" + B_IMPORTER + "/status", Map.of("active", true), "PATCH"));
      assertStatus(200, owner.send("/organization/transfer/" + B_IMPORTER, null, "POST"));
      assertEquals("ADMIN", owner.json(owner.get("/auth/me")).get("organizationRole").asString());
      assertEquals(
          "OWNER",
          admin.queryForObject(
              "SELECT role FROM organization_memberships WHERE organization_id=? AND user_id=?",
              String.class,
              A,
              B_IMPORTER));
      assertEquals(
          1,
          admin.queryForObject(
              "SELECT count(*) FROM audit_events WHERE event_type='OWNERSHIP_TRANSFERRED'",
              Integer.class));
      assertStatus(
          409,
          owner.send(
              "/platform/users/" + B_IMPORTER + "/status", Map.of("active", false), "PATCH"));
    }
  }

  @Test
  void recoveryExpiryAndInvitationAcceptanceLimitsFailClosed() throws Exception {
    admin.update("UPDATE users SET platform_role='PLATFORM_ADMIN' WHERE id=?", SHARED);
    credential(B_IMPORTER, "Recovery expiry password!");
    try (var app = securityApp("--trace.security.rate-limit-attempts=2")) {
      var owner = new BrowserSession(app);
      owner.loginA();
      var recovery =
          owner.send("/platform/users/" + B_IMPORTER + "/password-recovery", null, "POST");
      assertStatus(200, recovery);
      admin.update("UPDATE password_reset_tokens SET expires_at=now()-interval '1 minute'");
      var anonymous = new BrowserSession(app);
      assertStatus(
          409,
          anonymous.send(
              "/auth/password/reset",
              Map.of("token", owner.token(recovery), "newPassword", "Invalid expired replacement!"),
              "POST"));
      assertStatus(200, anonymous.login("retail@example.test", "Recovery expiry password!"));
      for (int i = 0; i < 2; i++)
        assertStatus(
            400, anonymous.send("/auth/invitations/accept", Map.of("token", "invalid"), "POST"));
      assertStatus(
          429, anonymous.send("/auth/invitations/accept", Map.of("token", "invalid"), "POST"));
    }
  }

  @Test
  void loginAuditFailureDoesNotLeaveAnAuthenticatedSession() throws Exception {
    credential(SHARED, "Login audit test password!");
    try (var app = securityApp()) {
      var user = new BrowserSession(app);
      admin.execute(
          "CREATE FUNCTION trace_test_login_audit_failure() RETURNS trigger LANGUAGE plpgsql AS $$"
              + " BEGIN IF NEW.event_type='LOGIN_SUCCESS' THEN RAISE EXCEPTION 'Deliberate test"
              + " audit failure'; END IF; RETURN NEW; END $$");
      admin.execute(
          "CREATE TRIGGER trace_test_login_audit_failure BEFORE INSERT ON audit_events FOR EACH ROW"
              + " EXECUTE FUNCTION trace_test_login_audit_failure()");
      try {
        assertStatus(503, user.login("shared@example.test", "Login audit test password!"));
        assertStatus(401, user.get("/auth/me"));
      } finally {
        admin.execute("DROP TRIGGER trace_test_login_audit_failure ON audit_events");
        admin.execute("DROP FUNCTION trace_test_login_audit_failure()");
      }
    }
  }

  @Test
  void legacyCredentiallessAccountsRequireDeliberateAdministratorRecovery() throws Exception {
    admin.update("UPDATE users SET platform_role='PLATFORM_ADMIN' WHERE id=?", SHARED);
    try (var app = securityApp()) {
      var owner = new BrowserSession(app);
      owner.loginA();
      assertEquals(
          0,
          admin.queryForObject(
              "SELECT count(*) FROM user_credentials WHERE user_id=?", Integer.class, B_IMPORTER));
      var legacy = new BrowserSession(app);
      assertStatus(401, legacy.login("retail@example.test", "Invented default password!"));
      var issued = owner.send("/platform/users/" + B_IMPORTER + "/password-recovery", null, "POST");
      assertStatus(200, issued);
      assertStatus(
          200,
          legacy.send(
              "/auth/password/reset",
              Map.of(
                  "token",
                  owner.token(issued),
                  "newPassword",
                  "Deliberately activated legacy password!"),
              "POST"));
      assertStatus(
          200, legacy.login("retail@example.test", "Deliberately activated legacy password!"));
      legacy.select(B);
      assertStatus(200, legacy.get("/assets"));
      assertStatus(
          409,
          legacy.send(
              "/auth/password/reset",
              Map.of(
                  "token",
                  owner.token(issued),
                  "newPassword",
                  "Replayed legacy activation password!"),
              "POST"));
    }
  }

  @Test
  void obsoleteDevelopmentPropertiesNeverAuthenticateRequests() throws Exception {
    try (var app =
        securityApp(
            "--trace.dev-tenant.enabled=true",
            "--trace.dev-tenant.organization-id=" + A,
            "--trace.dev-tenant.user-id=" + SHARED)) {
      var anonymous = new BrowserSession(app);
      assertStatus(401, anonymous.get("/assets"));
      assertStatus(401, anonymous.get("/me/organizations"));
    }
  }

  private Map<String, Object> registration(String email) {
    return Map.of(
        "displayName",
        "New Trace User",
        "email",
        email,
        "password",
        "Registration testing password!",
        "confirmPassword",
        "Registration testing password!");
  }

  @Test
  void registrationCreatesOnlyANormalHashedIdentityAndCanSignIn() throws Exception {
    try (var app = securityApp()) {
      var browser = new BrowserSession(app);
      var options = browser.get("/auth/registration");
      assertStatus(200, options);
      assertTrue(browser.json(options).get("enabled").asBoolean());
      var result =
          browser.send("/auth/register", registration("  New.SignUp@Example.test  "), "POST");
      assertStatus(201, result);
      var json = browser.json(result);
      assertEquals("Account created successfully.", json.get("message").asString());
      assertEquals("new.signup@example.test", json.get("email").asString());
      assertFalse(result.body().contains("password"));
      assertFalse(result.body().contains("$2"));
      UUID user = UUID.fromString(json.get("id").asString());
      String hash =
          admin.queryForObject(
              "SELECT password_hash FROM user_credentials WHERE user_id=?", String.class, user);
      assertTrue(hash.startsWith("$2"));
      assertTrue(
          new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
              .matches("Registration testing password!", hash));
      assertEquals(
          "USER",
          admin.queryForObject("SELECT platform_role FROM users WHERE id=?", String.class, user));
      assertEquals(
          0,
          admin.queryForObject(
              "SELECT count(*) FROM organization_memberships WHERE user_id=?",
              Integer.class,
              user));
      assertEquals(
          0,
          admin.queryForObject(
              "SELECT count(*) FROM team_memberships WHERE user_id=?", Integer.class, user));
      assertEquals(
          1,
          admin.queryForObject(
              "SELECT count(*) FROM audit_events WHERE event_type='USER_REGISTERED' AND"
                  + " actor_user_id=? AND resource_id=?",
              Integer.class,
              user,
              user));
      assertFalse(
          admin
              .queryForObject(
                  "SELECT metadata::text FROM audit_events WHERE event_type='USER_REGISTERED' AND"
                      + " actor_user_id=?",
                  String.class,
                  user)
              .contains("password"));
      assertStatus(401, browser.get("/auth/me")); // Signup never silently authenticates.
      assertStatus(200, browser.login("NEW.SIGNUP@example.test", "Registration testing password!"));
      var me = browser.json(browser.get("/auth/me"));
      assertEquals(0, me.get("organizations").size());
      assertTrue(me.get("selectedOrganization").isNull());
      assertStatus(403, browser.get("/assets"));
      assertStatus(403, browser.get("/platform/organizations"));
      assertStatus(200, browser.send("/auth/logout", null, "POST"));
      assertStatus(200, browser.login("new.signup@example.test", "Registration testing password!"));
    }
  }

  @Test
  void registrationValidatesEveryFieldAndCsrfBeforeCreatingAccounts() throws Exception {
    try (var app = securityApp()) {
      var browser = new BrowserSession(app);
      var good = registration("invalid.signup@example.test");
      assertStatus(403, browser.send("/auth/register", good, "POST", false));
      var invalid = new java.util.ArrayList<Map<String, Object>>();
      for (var change :
          List.of(
              Map.entry("displayName", " "),
              Map.entry("displayName", "x".repeat(151)),
              Map.entry("displayName", "Control\nCharacter"),
              Map.entry("email", "not-an-email"),
              Map.entry("email", "bad user@example.test"),
              Map.entry("email", "x".repeat(321)),
              Map.entry("confirmPassword", "Does not match!"))) {
        var body = new java.util.HashMap<>(good);
        body.put(change.getKey(), change.getValue());
        invalid.add(body);
      }
      for (String password : List.of("short", "x".repeat(73), "😀".repeat(20))) {
        var body = new java.util.HashMap<>(good);
        body.put("password", password);
        body.put("confirmPassword", password);
        invalid.add(body);
      }
      var missing = new java.util.HashMap<>(good);
      missing.remove("confirmPassword");
      invalid.add(missing);
      for (var body : invalid) assertStatus(400, browser.send("/auth/register", body, "POST"));
      assertEquals(2, admin.queryForObject("SELECT count(*) FROM users", Integer.class));
      assertEquals(0, admin.queryForObject("SELECT count(*) FROM user_credentials", Integer.class));
    }
  }

  @Test
  void registrationRejectsCaseInsensitiveDuplicatesAndNeverClaimsLegacyIdentities()
      throws Exception {
    try (var app = securityApp()) {
      var browser = new BrowserSession(app);
      assertStatus(
          201,
          browser.send("/auth/register", registration("duplicate.signup@example.test"), "POST"));
      var duplicate =
          browser.send("/auth/register", registration("DUPLICATE.SIGNUP@example.test"), "POST");
      assertStatus(409, duplicate);
      assertEquals("ACCOUNT_EXISTS", browser.json(duplicate).get("code").asString());
      assertStatus(
          409, browser.send("/auth/register", registration("retail@example.test"), "POST"));
      assertEquals(
          0,
          admin.queryForObject(
              "SELECT count(*) FROM user_credentials WHERE user_id=?", Integer.class, B_IMPORTER));
      assertEquals(
          "Retail importer",
          admin.queryForObject(
              "SELECT display_name FROM users WHERE id=?", String.class, B_IMPORTER));
      assertEquals(
          1,
          admin.queryForObject(
              "SELECT count(*) FROM users WHERE email='duplicate.signup@example.test'",
              Integer.class));
    }
  }

  @Test
  void concurrentRegistrationCreatesExactlyOneUserCredentialAndAudit() throws Exception {
    try (var app = securityApp()) {
      var first = new BrowserSession(app);
      var second = new BrowserSession(app);
      try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
        var start = new java.util.concurrent.CountDownLatch(1);
        var a =
            executor.submit(
                () -> {
                  start.await();
                  return first.send(
                      "/auth/register", registration("race.signup@example.test"), "POST");
                });
        var b =
            executor.submit(
                () -> {
                  start.await();
                  return second.send(
                      "/auth/register", registration("RACE.SIGNUP@example.test"), "POST");
                });
        start.countDown();
        var results = List.of(a.get().statusCode(), b.get().statusCode());
        assertTrue(results.contains(201));
        assertTrue(results.contains(409));
      }
      assertEquals(
          1,
          admin.queryForObject(
              "SELECT count(*) FROM users WHERE email='race.signup@example.test'", Integer.class));
      assertEquals(
          1,
          admin.queryForObject(
              "SELECT count(*) FROM user_credentials c JOIN users u ON u.id=c.user_id WHERE"
                  + " u.email='race.signup@example.test'",
              Integer.class));
      assertEquals(
          1,
          admin.queryForObject(
              "SELECT count(*) FROM audit_events WHERE event_type='USER_REGISTERED'",
              Integer.class));
    }
  }

  @Test
  void registrationAuditFailureRollsBackTheAccountAndCredentials() throws Exception {
    try (var app = securityApp()) {
      var browser = new BrowserSession(app);
      admin.execute(
          "CREATE FUNCTION trace_test_register_audit_failure() RETURNS trigger LANGUAGE plpgsql AS"
              + " $$ BEGIN IF NEW.event_type='USER_REGISTERED' THEN RAISE EXCEPTION 'Deliberate"
              + " test failure'; END IF; RETURN NEW; END $$");
      admin.execute(
          "CREATE TRIGGER trace_test_register_audit_failure BEFORE INSERT ON audit_events FOR EACH"
              + " ROW EXECUTE FUNCTION trace_test_register_audit_failure()");
      try {
        assertStatus(
            500,
            browser.send("/auth/register", registration("rollback.signup@example.test"), "POST"));
        assertEquals(
            0,
            admin.queryForObject(
                "SELECT count(*) FROM users WHERE email='rollback.signup@example.test'",
                Integer.class));
        assertEquals(
            0, admin.queryForObject("SELECT count(*) FROM user_credentials", Integer.class));
      } finally {
        admin.execute("DROP TRIGGER trace_test_register_audit_failure ON audit_events");
        admin.execute("DROP FUNCTION trace_test_register_audit_failure()");
      }
    }
  }

  @Test
  void registrationIsDisabledByDefaultAndCannotTrustForwardedRequests() throws Exception {
    try (var app =
        securityApp(
            "--spring.profiles.active=production", "--trace.security.local-token-delivery=false")) {
      var browser = new BrowserSession(app);
      assertFalse(browser.json(browser.get("/auth/registration")).get("enabled").asBoolean());
      assertStatus(
          403,
          browser.send("/auth/register", registration("production.signup@example.test"), "POST"));
    }
    try (var app = securityApp("--trace.security.local-signup-enabled=false")) {
      var browser = new BrowserSession(app);
      assertStatus(
          403,
          browser.send("/auth/register", registration("disabled.signup@example.test"), "POST"));
    }
    try (var app = securityApp()) {
      var browser = new BrowserSession(app);
      for (String header :
          List.of("Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Real-IP")) {
        var response =
            browser.http.send(
                HttpRequest.newBuilder(URI.create(browser.base + "/api/auth/register"))
                    .header(header, "203.0.113.1")
                    .header("X-CSRF-TOKEN", csrf(browser.http, browser.base))
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            new ObjectMapper()
                                .writeValueAsString(registration("forwarded.signup@example.test"))))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertStatus(403, response);
      }
    }
    assertEquals(2, admin.queryForObject("SELECT count(*) FROM users", Integer.class));
  }

  @Test
  void registrationAbuseLimitsAreEnforced() throws Exception {
    try (var app = securityApp("--trace.security.rate-limit-attempts=2")) {
      var browser = new BrowserSession(app);
      assertStatus(
          201, browser.send("/auth/register", registration("limit.one@example.test"), "POST"));
      assertStatus(
          409, browser.send("/auth/register", registration("LIMIT.ONE@example.test"), "POST"));
      assertStatus(
          429, browser.send("/auth/register", registration("limit.two@example.test"), "POST"));
      assertEquals(
          0,
          admin.queryForObject(
              "SELECT count(*) FROM users WHERE email='limit.two@example.test'", Integer.class));
    }
  }

  @Test
  void registeredUserCanAcceptAnInvitationUsingTheirMatchingSession() throws Exception {
    try (var app = securityApp()) {
      var owner = new BrowserSession(app);
      owner.loginA();
      var browser = new BrowserSession(app);
      assertStatus(
          201, browser.send("/auth/register", registration("invited.signup@example.test"), "POST"));
      assertStatus(
          200, browser.login("invited.signup@example.test", "Registration testing password!"));
      var invitation =
          owner.send(
              "/organization/invitations",
              Map.of("email", "invited.signup@example.test", "role", "VIEWER"),
              "POST");
      assertStatus(200, invitation);
      assertStatus(
          200,
          browser.send(
              "/auth/invitations/accept", Map.of("token", owner.token(invitation)), "POST"));
      browser.select(A);
      assertStatus(200, browser.get("/assets"));
      assertStatus(
          403,
          browser.send("/connections", Map.of("name", "forbidden", "ownerTeamId", TEAM_A), "POST"));
      assertStatus(200, owner.get("/organization/members"));
    }
  }

  private String created(BrowserSession s, String path, Object body) throws Exception {
    var response = s.send(path, body, "POST");
    assertTrue(response.statusCode() == 200 || response.statusCode() == 201, response.body());
    return s.json(response).get("id").asString();
  }

  private Map<String, Object> manualAsset(String connection, String name, String type) {
    return Map.of(
        "connectionId",
        connection,
        "ownerTeamId",
        TEAM_A,
        "name",
        name,
        "externalKey",
        name,
        "assetType",
        type,
        "description",
        "Human description",
        "owner",
        "Technical owner",
        "columns",
        List.of(Map.of("name", "ID", "dataType", "NUMBER")));
  }

  @Test
  void step10ConnectionLifecycleCredentialsAndIsolation() throws Exception {
    try (var app =
        securityApp(
            "--trace.connectors.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")) {
      var s = new BrowserSession(app);
      s.loginA();
      for (String type :
          List.of("SNOWFLAKE", "AMAZON_S3", "GITHUB_SQL", "POWER_BI", "MANUAL", "FILE_IMPORT")) {
        String id =
            created(
                s,
                "/connections",
                Map.of(
                    "name",
                    "Step10 " + type,
                    "ownerTeamId",
                    TEAM_A,
                    "connectionType",
                    type,
                    "configuration",
                    Map.of()));
        assertStatus(200, s.get("/connections/" + id));
        assertStatus(
            200,
            s.send(
                "/connections/" + id,
                Map.of("name", "Edited " + type, "ownerTeamId", TEAM_A, "configuration", Map.of()),
                "PATCH"));
        assertStatus(
            200, s.send("/connections/" + id + "/status", Map.of("enabled", false), "PATCH"));
        assertStatus(409, s.send("/connections/" + id + "/test", null, "POST"));
        assertStatus(
            200, s.send("/connections/" + id + "/status", Map.of("enabled", true), "PATCH"));
        if (type.equals("GITHUB_SQL")) {
          assertStatus(
              200,
              s.send(
                  "/connections/" + id + "/credentials",
                  Map.of("token", "provider-secret-one"),
                  "PUT"));
          var first =
              admin.queryForMap(
                  "SELECT ciphertext,nonce FROM connection_secret_storage WHERE connection_id=?",
                  UUID.fromString(id));
          assertFalse(s.get("/connections/" + id).body().contains("provider-secret"));
          assertFalse(
              new String((byte[]) first.get("ciphertext"), java.nio.charset.StandardCharsets.UTF_8)
                  .contains("provider-secret"));
          assertStatus(
              200,
              s.send(
                  "/connections/" + id + "/credentials",
                  Map.of("token", "provider-secret-two"),
                  "PUT"));
          assertFalse(
              java.util.Arrays.equals(
                  (byte[]) first.get("nonce"),
                  (byte[])
                      admin
                          .queryForMap(
                              "SELECT nonce FROM connection_secret_storage WHERE connection_id=?",
                              UUID.fromString(id))
                          .get("nonce")));
          s.select(B);
          assertStatus(404, s.get("/connections/" + id));
          assertStatus(
              404, s.send("/connections/" + id + "/credentials", Map.of("token", "evil"), "PUT"));
          s.select(A);
        }
      }
      assertStatus(
          400,
          s.send(
              "/connections",
              Map.of("name", "invalid", "ownerTeamId", TEAM_A, "connectionType", "ARBITRARY"),
              "POST"));
      assertStatus(
          400,
          s.send(
              "/connections",
              Map.of(
                  "name",
                  "secret config",
                  "ownerTeamId",
                  TEAM_A,
                  "connectionType",
                  "SNOWFLAKE",
                  "configuration",
                  Map.of("password", "unsafe")),
              "POST"));
      assertStatus(
          404, s.send("/connections", Map.of("name", "foreign", "ownerTeamId", TEAM_B), "POST"));
      assertStatus(
          403,
          s.send("/connections", Map.of("name", "csrf", "ownerTeamId", TEAM_A), "POST", false));
      assertStatus(200, s.send("/auth/logout", null, "POST"));
      assertStatus(200, s.login("shared@example.test", "Testing password for A!"));
      s.select(A);
      assertTrue(s.get("/connections").body().contains("Edited MANUAL"));
    }
  }

  @Test
  void step10MissingEncryptionDoesNotBlockManualWork() throws Exception {
    try (var app = securityApp()) {
      var s = new BrowserSession(app);
      s.loginA();
      String manual =
          created(
              s,
              "/connections",
              Map.of("name", "manual", "ownerTeamId", TEAM_A, "connectionType", "MANUAL"));
      String github =
          created(
              s,
              "/connections",
              Map.of("name", "github", "ownerTeamId", TEAM_A, "connectionType", "GITHUB_SQL"));
      assertStatus(
          503, s.send("/connections/" + github + "/credentials", Map.of("token", "secret"), "PUT"));
      assertNotNull(created(s, "/assets", manualAsset(manual, "RAW.ORDERS", "DATASET")));
    }
  }

  @Test
  void step10ManualAssetsMappingsAndCycles() throws Exception {
    try (var app = securityApp()) {
      var s = new BrowserSession(app);
      s.loginA();
      String c =
          created(
              s,
              "/connections",
              Map.of("name", "manual", "ownerTeamId", TEAM_A, "connectionType", "MANUAL"));
      String one = created(s, "/assets", manualAsset(c, "RAW.ORDERS", "DATASET"));
      String two = created(s, "/assets", manualAsset(c, "CURATED.ORDERS", "DATASET"));
      var mapping =
          Map.of(
              "sourceAssetId",
              one,
              "targetAssetId",
              two,
              "relationshipType",
              "DATA_FLOW",
              "evidenceRef",
              "Architecture declaration");
      String edge = created(s, "/lineage/mappings", mapping);
      assertStatus(409, s.send("/lineage/mappings", mapping, "POST"));
      assertStatus(
          409,
          s.send(
              "/lineage/mappings",
              Map.of(
                  "sourceAssetId",
                  two,
                  "targetAssetId",
                  one,
                  "relationshipType",
                  "DATA_FLOW",
                  "evidenceRef",
                  "Cycle"),
              "POST"));
      assertStatus(
          400,
          s.send(
              "/lineage/mappings",
              Map.of(
                  "sourceAssetId",
                  one,
                  "targetAssetId",
                  two,
                  "relationshipType",
                  "REPORT_USES_MODEL",
                  "evidenceRef",
                  "Wrong types"),
              "POST"));
      String sc = s.json(s.get("/assets/" + one + "/columns")).get(0).get("id").asString(),
          tc = s.json(s.get("/assets/" + two + "/columns")).get(0).get("id").asString();
      created(
          s,
          "/lineage/mappings",
          Map.of(
              "sourceColumnId",
              sc,
              "targetColumnId",
              tc,
              "relationshipType",
              "COLUMN_DERIVATION",
              "evidenceRef",
              "Column declaration"));
      var graph = s.get("/lineage/" + one + "/downstream?depth=3");
      assertStatus(200, graph);
      assertEquals(2, s.json(graph).get("edges").size());
      assertEquals(1, s.json(graph).get("columnEdges").size());
      assertStatus(
          200,
          s.send(
              "/assets/" + one,
              Map.of(
                  "name",
                  "Orders curated",
                  "description",
                  "Preserved human description",
                  "revision",
                  1),
              "PATCH"));
      assertStatus(
          409, s.send("/assets/" + one, Map.of("name", "Stale edit", "revision", 1), "PATCH"));
      assertStatus(200, s.send("/lineage/mappings/" + edge + "/revoke", null, "POST"));
      assertEquals(1, s.json(s.get("/lineage/" + one)).get("edges").size());
      assertFalse(s.json(s.get("/lineage/mappings/" + edge)).get("active").asBoolean());
      s.select(B);
      assertStatus(404, s.get("/lineage/" + one));
      assertStatus(404, s.get("/assets/" + one + "/curation"));
    }
  }

  @Test
  void step10FormulasMetricVersionsAndReporting() throws Exception {
    try (var app = securityApp()) {
      var s = new BrowserSession(app);
      s.loginA();
      String c =
          created(
              s,
              "/connections",
              Map.of("name", "manual", "ownerTeamId", TEAM_A, "connectionType", "MANUAL"));
      String report = created(s, "/assets", manualAsset(c, "Executive Sales", "REPORT"));
      String formula =
          created(
              s,
              "/formulas",
              Map.of(
                  "name",
                  "Net revenue",
                  "category",
                  "DAX_MEASURE",
                  "language",
                  "DAX",
                  "ownerTeamId",
                  TEAM_A));
      String v1 =
          created(
              s,
              "/formulas/" + formula + "/versions",
              Map.of(
                  "expression",
                  "SUM(Sales[GrossAmount])",
                  "state",
                  "REGISTERED",
                  "referencedAssets",
                  List.of(report),
                  "referencedColumns",
                  List.of(
                      s.json(s.get("/assets/" + report + "/columns"))
                          .get(0)
                          .get("id")
                          .asString())));
      created(
          s,
          "/formulas/" + formula + "/versions",
          Map.of("expression", "SUM(Sales[GrossAmount])-SUM(Sales[Discount])", "state", "DRAFT"));
      assertEquals(2, s.json(s.get("/formulas/" + formula)).get("versions").size());
      assertStatus(
          404,
          s.send(
              "/formulas/" + formula + "/versions",
              Map.of(
                  "expression", "foreign", "state", "DRAFT", "referencedColumns", List.of(columnB)),
              "POST"));
      assertTrue(s.get("/formulas/" + formula).body().contains("referenced_columns"));
      String metric =
          created(
              s,
              "/metrics",
              Map.of("name", "Net revenue", "businessKey", "NET_REVENUE", "ownerTeamId", TEAM_A));
      String mv = created(s, "/metrics/" + metric + "/versions", Map.of("formulaVersionId", v1));
      created(
          s,
          "/metric-mappings",
          Map.of(
              "metricVersionId",
              mv,
              "assetId",
              report,
              "evidenceRef",
              "Report owner confirmed usage"));
      assertEquals(1, s.json(s.get("/metric-mappings")).size());
      select(A, SHARED);
      assertThrows(
          DataAccessException.class,
          () ->
              tenants.execute(
                  t -> {
                    jdbc.update(
                        "UPDATE formula_versions SET expression='mutated' WHERE organization_id=?"
                            + " AND id=?",
                        A,
                        UUID.fromString(v1));
                    return null;
                  }));
      assertThrows(
          DataAccessException.class,
          () ->
              tenants.execute(
                  t -> {
                    jdbc.update(
                        "UPDATE metric_versions SET version=100 WHERE organization_id=? AND id=?",
                        A,
                        UUID.fromString(mv));
                    return null;
                  }));
      s.select(B);
      assertStatus(404, s.get("/formulas/" + formula));
      assertStatus(404, s.get("/metrics/" + metric));
    }
  }

  @Test
  void step10TeamViewerCannotMutateNewResources() throws Exception {
    try (var app = securityApp()) {
      var s = new BrowserSession(app);
      s.loginA();
      String c =
          created(
              s,
              "/connections",
              Map.of("name", "manual", "ownerTeamId", TEAM_A, "connectionType", "MANUAL"));
      admin.update(
          "UPDATE organization_memberships SET role='MEMBER' WHERE organization_id=? AND user_id=?",
          A,
          SHARED);
      admin.update(
          "INSERT INTO team_memberships(organization_id,team_id,user_id,role)"
              + " VALUES(?,?,?,'VIEWER') ON CONFLICT(organization_id,team_id,user_id) DO UPDATE SET"
              + " role='VIEWER'",
          A,
          TEAM_A,
          SHARED);
      assertStatus(
          403, s.send("/connections/" + c, Map.of("name", "evil", "ownerTeamId", TEAM_A), "PATCH"));
      assertStatus(403, s.send("/connections/" + c + "/status", Map.of("enabled", false), "PATCH"));
      assertStatus(403, s.send("/connections/" + c + "/test", null, "POST"));
      assertStatus(403, s.send("/connections/" + c + "/sync", null, "POST"));
      assertStatus(403, s.send("/assets", manualAsset(c, "evil", "DATASET"), "POST"));
      assertStatus(
          403,
          s.send(
              "/formulas",
              Map.of(
                  "name",
                  "evil",
                  "category",
                  "BUSINESS_RULE",
                  "language",
                  "BUSINESS_RULE",
                  "ownerTeamId",
                  TEAM_A),
              "POST"));
      assertStatus(
          403,
          s.send(
              "/metrics",
              Map.of("name", "evil", "businessKey", "EVIL", "ownerTeamId", TEAM_A),
              "POST"));
    }
  }

  @Test
  void step10NewTablesEnforceForcedRlsAndTenantForeignKeys() {
    for (String table :
        List.of(
            "connection_secret_storage",
            "connection_sync_runs",
            "import_mapping_profiles",
            "formulas",
            "formula_versions",
            "formula_dependencies",
            "metrics",
            "metric_versions",
            "report_mappings",
            "formula_column_dependencies",
            "tags",
            "asset_tags",
            "asset_curation_history",
            "connection_sync_attempts")) {
      assertTrue(
          admin.queryForObject(
              "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname=?",
              Boolean.class,
              table));
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
    }
    select(A, SHARED);
    assertThrows(
        DataAccessException.class,
        () ->
            tenants.execute(
                t -> {
                  jdbc.update(
                      "INSERT INTO"
                          + " formulas(organization_id,owner_team_id,name,category,language,asset_id)"
                          + " VALUES(?,?,'foreign','BUSINESS_RULE','BUSINESS_RULE',?)",
                      A,
                      TEAM_B,
                      assetB);
                  return null;
                }));
  }

  @Test
  void step10RuntimeGuardRejectsMissingSecretRls() {
    try {
      admin.execute("ALTER TABLE connection_secret_storage NO FORCE ROW LEVEL SECURITY");
      assertThrows(
          IllegalStateException.class, () -> new RuntimeDatabaseGuard(jdbc).afterPropertiesSet());
    } finally {
      admin.execute("ALTER TABLE connection_secret_storage FORCE ROW LEVEL SECURITY");
    }
  }

  @Test
  void step10RealPythonGenericJsonSqlAndCommit() throws Exception {
    int port;
    try (var socket = new java.net.ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    var dir = java.nio.file.Path.of("../processor").toAbsolutePath();
    var builder =
        new ProcessBuilder(
                dir.resolve(".venv/bin/python").toString(),
                "-m",
                "uvicorn",
                "app:app",
                "--host",
                "127.0.0.1",
                "--port",
                String.valueOf(port))
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(
                java.nio.file.Files.createTempFile("trace-step10-processor-", ".log").toFile());
    builder.environment().put("TRACE_PROCESSOR_TOKEN", "test-private-processor-token");
    var process = builder.start();
    try {
      var client = HttpClient.newHttpClient();
      boolean ready = false;
      for (int i = 0; i < 100; i++) {
        try {
          ready =
              client
                      .send(
                          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                              .GET()
                              .build(),
                          HttpResponse.BodyHandlers.ofString())
                      .statusCode()
                  == 200;
        } catch (java.io.IOException ignored) {
        }
        if (ready) break;
        Thread.sleep(50);
      }
      assertTrue(ready);
      assertEquals(
          403,
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + port + "/internal/acquire"))
                      .POST(HttpRequest.BodyPublishers.ofString("{}"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
      try (var app =
          securityApp(
              "--trace.processor.url=http://127.0.0.1:" + port,
              "--trace.processor.token=test-private-processor-token")) {
        var s = new BrowserSession(app);
        s.loginA();
        String c =
            created(
                s,
                "/connections",
                Map.of("name", "Files", "connectionType", "FILE_IMPORT", "ownerTeamId", TEAM_A));
        String csv =
            "db,sch,obj,field,type\nSHOP,RAW,ORDERS,ID,NUMBER\nSHOP,RAW,ORDERS,AMOUNT,NUMBER";
        String mapping =
            "{\"db\":\"database\",\"sch\":\"schema\",\"obj\":\"table\",\"field\":\"column\",\"type\":\"data_type\"}";
        var preview = uploadStep10(s, c, "GENERIC_CSV", "generic.csv", csv, mapping);
        assertStatus(201, preview);
        String batch = s.json(preview).get("id").asString();
        assertEquals("FILE_IMPORT", s.json(preview).get("sourceType").asString());
        assertEquals("GENERIC_CSV", s.json(preview).get("format").asString());
        assertStatus(200, s.send("/imports/" + batch + "/commit", null, "POST"));
        assertStatus(200, s.send("/imports/" + batch + "/commit", null, "POST"));
        var retry = uploadStep10(s, c, "GENERIC_CSV", "generic.csv", csv, mapping);
        assertStatus(201, retry);
        assertEquals(1, s.json(retry).get("comparison").get("unchangedDatasets").asInt());
        String curated = s.json(s.get("/assets?q=SHOP.RAW.ORDERS")).get(0).get("id").asString();
        assertStatus(
            200,
            s.send(
                "/assets/" + curated,
                Map.of(
                    "name",
                    "Orders curated",
                    "technicalOwner",
                    "Curated engineering",
                    "businessOwner",
                    "Curated finance",
                    "description",
                    "Human curation",
                    "revision",
                    1),
                "PATCH"));
        assertStatus(
            200,
            s.send("/imports/" + s.json(retry).get("id").asString() + "/commit", null, "POST"));
        var preserved = s.json(s.get("/assets/" + curated + "/curation"));
        assertEquals("Curated engineering", preserved.get("technical_owner").asString());
        assertEquals("Curated finance", preserved.get("business_owner").asString());
        assertEquals("Orders curated", preserved.get("name").asString());
        var metadata =
            uploadStep10(
                s,
                c,
                "METADATA_JSON",
                "report.json",
                "{\"datasets\":[{\"externalKey\":\"report:one\",\"name\":\"Sales"
                    + " report\",\"assetType\":\"REPORT\",\"columns\":[]}]}",
                "{}");
        assertStatus(201, metadata);
        assertStatus(
            200,
            s.send("/imports/" + s.json(metadata).get("id").asString() + "/commit", null, "POST"));
        var sql =
            uploadStep10(s, c, "SQL", "fact_orders.sql", "select id, amount from raw.orders", "{}");
        assertStatus(201, sql);
        assertStatus(
            200, s.send("/imports/" + s.json(sql).get("id").asString() + "/commit", null, "POST"));
        var reporting =
            uploadStep10(
                s,
                c,
                "POWERBI_MAPPING_CSV",
                "mappings.csv",
                "report_name,visual_name,metric_name,measure_name\n"
                    + "Executive Sales,Revenue Card,Net Revenue,Net Revenue",
                "{}");
        assertStatus(201, reporting);
        assertStatus(
            200,
            s.send("/imports/" + s.json(reporting).get("id").asString() + "/commit", null, "POST"));
        assertEquals(
            2,
            admin.queryForObject(
                "SELECT count(*) FROM lineage_edges WHERE organization_id=? AND"
                    + " provenance='IMPORTED_MAPPING'",
                Integer.class,
                A));
        var invalid = uploadStep10(s, c, "GENERIC_CSV", "invalid.csv", "field\nID", "{}");
        assertStatus(422, invalid);
        var validation =
            s.send(
                "/formulas/validate",
                Map.of(
                    "expression",
                    "select id from raw.orders",
                    "language",
                    "SQL",
                    "dialect",
                    "snowflake"),
                "POST");
        assertStatus(200, validation);
        assertEquals("PARSED", s.json(validation).get("status").asString());
        assertTrue(s.get("/assets").body().contains("SQL_TRANSFORMATION"));
        assertEquals(
            6,
            admin.queryForObject(
                "SELECT count(*) FROM assets WHERE connection_id=?",
                Integer.class,
                UUID.fromString(c)));
      }
    } finally {
      process.destroy();
      assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  private HttpResponse<String> uploadStep10(
      BrowserSession s, String c, String format, String filename, String content, String mapping)
      throws Exception {
    var body = new StringBuilder();
    for (var field : Map.of("connectionId", c, "format", format, "mapping", mapping).entrySet())
      body.append("--trace-step10\r\nContent-Disposition: form-data; name=\"")
          .append(field.getKey())
          .append("\"\r\n\r\n")
          .append(field.getValue())
          .append("\r\n");
    body.append("--trace-step10\r\nContent-Disposition: form-data; name=\"file\"; filename=\"")
        .append(filename)
        .append("\"\r\nContent-Type: application/octet-stream\r\n\r\n")
        .append(content)
        .append("\r\n--trace-step10--\r\n");
    return s.http.send(
        HttpRequest.newBuilder(URI.create(s.base + "/api/imports/preview"))
            .header("X-CSRF-TOKEN", csrf(s.http, s.base))
            .header("Content-Type", "multipart/form-data; boundary=trace-step10")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static final String QUEUE_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

  private class AcquisitionFixture implements AutoCloseable {
    final com.sun.net.httpserver.HttpServer server;
    final java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicReference<String> mode =
        new java.util.concurrent.atomic.AtomicReference<>("SUCCESS");
    final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1),
        release = new java.util.concurrent.CountDownLatch(1);

    AcquisitionFixture() throws Exception {
      server =
          com.sun.net.httpserver.HttpServer.create(
              new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
      server.createContext(
          "/internal/acquire",
          e -> {
            try {
              assertEquals(
                  "queue-test-token", e.getRequestHeaders().getFirst("X-Trace-Processor-Token"));
              var input = new ObjectMapper().readTree(e.getRequestBody().readAllBytes());
              int call = calls.incrementAndGet();
              String state = mode.get();
              if (state.equals("BLOCK") || (state.equals("BLOCK_FIRST") && call == 1)) {
                entered.countDown();
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
              }
              String code =
                  state.equals("TRANSIENT_FIRST") && call == 1
                      ? "UNAVAILABLE"
                      : state.equals("AUTH") ? "AUTHENTICATION_FAILED" : "CONNECTED";
              var payload =
                  Map.of(
                      "status",
                      code,
                      "batch",
                      Map.of(
                          "schemaVersion",
                          1,
                          "snapshotComplete",
                          !state.equals("PARTIAL"),
                          "inventoryScope",
                          "f".repeat(64),
                          "datasets",
                          List.of(
                              Map.of(
                                  "externalKey",
                                  "fixture:" + input.path("type").asString(),
                                  "name",
                                  "Controlled provider object",
                                  "assetType",
                                  "DATASET",
                                  "columns",
                                  List.of(Map.of("name", "ID", "dataType", "NUMBER")))),
                          "warnings",
                          state.equals("PARTIAL")
                              ? List.of("Controlled partial inventory")
                              : List.of()));
              byte[] out = new ObjectMapper().writeValueAsBytes(payload);
              e.getResponseHeaders().set("Content-Type", "application/json");
              e.sendResponseHeaders(200, out.length);
              e.getResponseBody().write(out);
            } catch (Exception ignored) {
            } finally {
              e.close();
            }
          });
      server.start();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void close() {
      release.countDown();
      server.stop(0);
    }
  }

  private org.springframework.context.ConfigurableApplicationContext queueApp(
      AcquisitionFixture fixture, boolean worker) {
    return securityApp(
        "--trace.processor.url=" + fixture.url(),
        "--trace.processor.token=queue-test-token",
        "--trace.connectors.encryption-key=" + QUEUE_KEY,
        "--trace.discovery.enabled=" + worker,
        "--trace.discovery.initial-delay-ms=3600000");
  }

  private String queueSource(BrowserSession s, String type, String suffix) throws Exception {
    String c =
        created(
            s,
            "/connections",
            Map.of("name", type + suffix, "ownerTeamId", TEAM_A, "connectionType", type));
    Map<String, String> secret =
        switch (type) {
          case "SNOWFLAKE" -> Map.of("privateKey", "controlled-test-key");
          case "AMAZON_S3" ->
              Map.of("accessKeyId", "controlled-key", "secretAccessKey", "controlled-secret");
          case "POWER_BI" -> Map.of("clientSecret", "controlled-secret");
          default -> Map.of("token", "controlled-secret");
        };
    assertStatus(200, s.send("/connections/" + c + "/credentials", secret, "PUT"));
    return c;
  }

  private Map<String, Object> awaitRun(BrowserSession s, String c, String run, String status)
      throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    HttpResponse<String> response = null;
    while (System.nanoTime() < deadline) {
      response = s.get("/connections/" + c + "/sync-runs/" + run);
      assertStatus(200, response);
      if (s.json(response).get("status").asString().equals(status))
        return new ObjectMapper().readValue(response.body(), Map.class);
      Thread.sleep(30);
    }
    fail("Expected " + status + "; last run: " + (response == null ? "none" : response.body()));
    return null;
  }

  @Test
  void completionQueueClaimsAreExclusiveAndFenced() throws Exception {
    try (var fixture = new AcquisitionFixture();
        var app = queueApp(fixture, false)) {
      var s = new BrowserSession(app);
      s.loginA();
      String c = queueSource(s, "GITHUB_SQL", " lease");
      var accepted = s.send("/connections/" + c + "/sync", null, "POST");
      assertStatus(202, accepted);
      String run = s.json(accepted).get("id").asString();
      assertEquals("PENDING", s.json(accepted).get("status").asString());
      assertEquals(0, fixture.calls.get());
      assertFalse(accepted.body().contains("lease_token"));
      var q = app.getBean(com.trace.backend.repository.DiscoveryQueueRepository.class);
      var owners = List.of(UUID.randomUUID(), UUID.randomUUID());
      var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
      try {
        var tasks =
            owners.stream()
                .map(
                    owner ->
                        (java.util.concurrent.Callable<
                                java.util.Optional<
                                    com.trace.backend.repository.DiscoveryQueueRepository.Lease>>)
                            () -> q.claim(owner, 5))
                .toList();
        var results = pool.invokeAll(tasks);
        var leases =
            new java.util.ArrayList<com.trace.backend.repository.DiscoveryQueueRepository.Lease>();
        for (var result : results) result.get().ifPresent(leases::add);
        assertEquals(1, leases.size());
        var old = leases.getFirst();
        admin.update(
            "UPDATE connection_sync_runs SET lease_expires_at=now()-interval '1 second' WHERE id=?",
            UUID.fromString(run));
        var newer = q.claim(UUID.randomUUID(), 45).orElseThrow();
        assertEquals(old.run(), newer.run());
        assertEquals(2, newer.attempt());
        assertNotEquals(old.token(), newer.token());
        assertFalse(q.finish(old, "FAILED", null, 0, List.of(), "UNAVAILABLE", 1));
        assertEquals("LOST", q.heartbeat(old, 45));
        assertTrue(q.finish(newer, "FAILED", null, 0, List.of(), "AUTHENTICATION_FAILED", 1));
      } finally {
        pool.shutdownNow();
      }
      s.select(B);
      assertStatus(404, s.get("/connections/" + c + "/sync-runs/" + run));
      assertStatus(
          404, s.send("/connections/" + c + "/sync-runs/" + run + "/cancel", null, "POST"));
    }
  }

  @Test
  void completionQueueSurvivesRestartAndPreservesProviderIdentities() throws Exception {
    try (var fixture = new AcquisitionFixture()) {
      String c, run;
      try (var app = queueApp(fixture, false)) {
        var s = new BrowserSession(app);
        s.loginA();
        c = queueSource(s, "GITHUB_SQL", " restart");
        var response = s.send("/connections/" + c + "/sync", null, "POST");
        assertStatus(202, response);
        run = s.json(response).get("id").asString();
      }
      try (var app = queueApp(fixture, true)) {
        var s = new BrowserSession(app);
        s.loginA();
        var worker = app.getBean(com.trace.backend.service.DiscoveryWorker.class);
        worker.tick();
        var result = awaitRun(s, c, run, "COMPLETED");
        String preview = (String) result.get("import_id");
        assertEquals(
            0,
            admin.queryForObject(
                "SELECT count(*) FROM assets WHERE connection_id=?",
                Integer.class,
                UUID.fromString(c)));
        assertStatus(200, s.send("/imports/" + preview + "/commit", null, "POST"));
        UUID identity =
            admin.queryForObject(
                "SELECT id FROM assets WHERE connection_id=?", UUID.class, UUID.fromString(c));
        var again = s.send("/connections/" + c + "/sync", null, "POST");
        assertStatus(202, again);
        worker.tick();
        var repeated = awaitRun(s, c, s.json(again).get("id").asString(), "COMPLETED");
        assertStatus(
            200, s.send("/imports/" + repeated.get("import_id") + "/commit", null, "POST"));
        assertEquals(
            identity,
            admin.queryForObject(
                "SELECT id FROM assets WHERE connection_id=?", UUID.class, UUID.fromString(c)));
        assertEquals(
            1,
            admin.queryForObject(
                "SELECT count(*) FROM assets WHERE connection_id=?",
                Integer.class,
                UUID.fromString(c)));
      }
    }
  }

  @Test
  void completionQueueRetryCancellationAndPermanentErrors() throws Exception {
    try (var fixture = new AcquisitionFixture();
        var app = queueApp(fixture, true)) {
      var s = new BrowserSession(app);
      s.loginA();
      var worker = app.getBean(com.trace.backend.service.DiscoveryWorker.class);
      String c = queueSource(s, "AMAZON_S3", " retry");
      fixture.mode.set("TRANSIENT_FIRST");
      var response = s.send("/connections/" + c + "/sync", null, "POST");
      String run = s.json(response).get("id").asString();
      worker.tick();
      var waiting = awaitRun(s, c, run, "RETRY_WAIT");
      assertEquals(1, ((Number) waiting.get("attempt_count")).intValue());
      assertTrue(
          admin.queryForObject(
              "SELECT next_attempt_at>now() FROM connection_sync_runs WHERE id=?",
              Boolean.class,
              UUID.fromString(run)));
      admin.update(
          "UPDATE connection_sync_runs SET next_attempt_at=now() WHERE id=?", UUID.fromString(run));
      worker.tick();
      awaitRun(s, c, run, "COMPLETED");
      assertEquals(2, fixture.calls.get());
      fixture.mode.set("AUTH");
      var permanent = s.send("/connections/" + c + "/sync", null, "POST");
      String failed = s.json(permanent).get("id").asString();
      worker.tick();
      awaitRun(s, c, failed, "FAILED");
      worker.tick();
      assertEquals(3, fixture.calls.get());
      fixture.mode.set("SUCCESS");
      var retried = s.send("/connections/" + c + "/sync-runs/" + failed + "/retry", null, "POST");
      assertStatus(202, retried);
      String pending = s.json(retried).get("id").asString();
      assertStatus(
          200, s.send("/connections/" + c + "/sync-runs/" + pending + "/cancel", null, "POST"));
      awaitRun(s, c, pending, "CANCELLED");
      worker.tick();
      assertEquals(3, fixture.calls.get());
      fixture.mode.set("BLOCK");
      var running = s.send("/connections/" + c + "/sync", null, "POST");
      String active = s.json(running).get("id").asString();
      worker.tick();
      assertTrue(fixture.entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
      assertStatus(
          200, s.send("/connections/" + c + "/sync-runs/" + active + "/cancel", null, "POST"));
      worker.tick();
      var cancelled = awaitRun(s, c, active, "CANCELLED");
      assertNull(cancelled.get("import_id"));
      fixture.release.countDown();
    }
  }

  @Test
  void completionQueueRecoversInterruptedAcquisition() throws Exception {
    for (String type : List.of("SNOWFLAKE", "AMAZON_S3", "GITHUB_SQL", "POWER_BI")) {
      try (var fixture = new AcquisitionFixture()) {
        fixture.mode.set("BLOCK_FIRST");
        String c, run;
        try (var app = queueApp(fixture, true)) {
          var s = new BrowserSession(app);
          s.loginA();
          c = queueSource(s, type, " interrupted");
          var queued = s.send("/connections/" + c + "/sync", null, "POST");
          run = s.json(queued).get("id").asString();
          app.getBean(com.trace.backend.service.DiscoveryWorker.class).tick();
          assertTrue(fixture.entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        admin.update(
            "UPDATE connection_sync_runs SET lease_expires_at=now()-interval '1 second' WHERE id=?",
            UUID.fromString(run));
        try (var app = queueApp(fixture, true)) {
          var s = new BrowserSession(app);
          s.loginA();
          app.getBean(com.trace.backend.service.DiscoveryWorker.class).tick();
          var recovered = awaitRun(s, c, run, "COMPLETED");
          assertEquals(2, ((Number) recovered.get("attempt_count")).intValue());
          var attempts = s.json(s.get("/browse/sync-attempts?parent=" + run)).path("items");
          assertEquals("INTERRUPTED", attempts.path(0).path("status").asString());
          assertEquals("COMPLETED", attempts.path(1).path("status").asString());
          fixture.release.countDown();
          assertEquals(
              1,
              admin.queryForObject(
                  "SELECT count(*) FROM import_batches WHERE connection_id=?",
                  Integer.class,
                  UUID.fromString(c)));
        }
      }
    }
  }

  @Test
  void completionQueueRechecksOwnershipAndAllProviderOutcomes() throws Exception {
    try (var fixture = new AcquisitionFixture();
        var app = queueApp(fixture, true)) {
      var s = new BrowserSession(app);
      s.loginA();
      var worker = app.getBean(com.trace.backend.service.DiscoveryWorker.class);
      for (String type : List.of("SNOWFLAKE", "AMAZON_S3", "GITHUB_SQL", "POWER_BI")) {
        fixture.mode.set("PARTIAL");
        String c = queueSource(s, type, " matrix");
        var queued = s.send("/connections/" + c + "/sync", null, "POST");
        assertStatus(202, queued);
        worker.tick();
        var run = awaitRun(s, c, s.json(queued).get("id").asString(), "PARTIALLY_COMPLETED");
        assertTrue(run.get("warnings").toString().contains("Controlled partial"));
        assertFalse(run.toString().contains("controlled-secret"));
      }
      String c = queueSource(s, "GITHUB_SQL", " revoked");
      var queued = s.send("/connections/" + c + "/sync", null, "POST");
      String run = s.json(queued).get("id").asString();
      admin.update(
          "UPDATE organization_memberships SET role='VIEWER' WHERE organization_id=? AND user_id=?",
          A,
          SHARED);
      worker.tick();
      awaitRun(s, c, run, "FAILED");
      assertEquals(
          "ACCESS_REVOKED",
          admin.queryForObject(
              "SELECT error_code FROM connection_sync_runs WHERE id=?",
              String.class,
              UUID.fromString(run)));
    }
  }

  @Test
  void completionPaginationBeyondHundredIsStableAndTenantScoped() throws Exception {
    for (int i = 0; i < 125; i++) {
      String name = String.format("Paging %03d", i);
      UUID connection = UUID.randomUUID();
      admin.update(
          "INSERT INTO"
              + " source_connections(id,organization_id,name,platform,ingestion_mode,owner_team_id)"
              + " VALUES(?,?,?,'MANUAL','METADATA_IMPORT',?)",
          connection,
          A,
          name,
          TEAM_A);
      admin.update(
          "INSERT INTO"
              + " assets(organization_id,connection_id,name,external_key,asset_type,platform,owner_team_id)"
              + " VALUES(?,?,?,?, 'DATASET','MANUAL',?)",
          A,
          connection,
          name,
          name,
          TEAM_A);
      admin.update(
          "INSERT INTO formulas(organization_id,owner_team_id,name,category,language)"
              + " VALUES(?,?,?,'BUSINESS_RULE','BUSINESS_RULE')",
          A,
          TEAM_A,
          name);
      admin.update(
          "INSERT INTO metrics(organization_id,owner_team_id,name,business_key) VALUES(?,?,?,?)",
          A,
          TEAM_A,
          name,
          "PAGING_" + i);
      admin.update(
          "INSERT INTO teams(organization_id,name,slug) VALUES(?,?,?)", A, name, "paging-" + i);
      admin.update(
          "INSERT INTO"
              + " import_batches(organization_id,connection_id,created_by_user_id,owner_team_id,status,preview,filename)"
              + " VALUES(?,?,?,?,'PREVIEW_READY','{\"schemaVersion\":1,\"datasets\":[],\"warnings\":[]}',?)",
          A,
          connection,
          SHARED,
          TEAM_A,
          name);
    }
    try (var app = securityApp("--trace.discovery.enabled=false")) {
      var s = new BrowserSession(app);
      s.loginA();
      for (String kind :
          List.of("connections", "assets", "formulas", "metrics", "teams", "imports")) {
        var ids = new java.util.HashSet<String>();
        for (int offset = 0; offset < 125; offset += 25) {
          var response = s.get("/browse/" + kind + "?limit=25&offset=" + offset + "&q=Paging");
          assertStatus(200, response);
          var page = s.json(response);
          assertEquals(125, page.get("total").asInt());
          assertEquals(25, page.get("items").size());
          for (var item : page.get("items")) assertTrue(ids.add(item.get("id").asString()));
          assertEquals(offset < 100, page.get("hasMore").asBoolean());
        }
        assertEquals(125, ids.size());
        assertStatus(400, s.get("/browse/" + kind + "?limit=101"));
        s.select(B);
        assertEquals(0, s.json(s.get("/browse/" + kind + "?q=Paging")).get("total").asInt());
        s.select(A);
      }

      assertEquals(
          125, s.json(s.get("/browse/connections?type=MANUAL&q=Paging")).get("total").asInt());
      assertEquals(
          0, s.json(s.get("/browse/connections?type=SNOWFLAKE&q=Paging")).get("total").asInt());
      var reversed = s.json(s.get("/browse/assets?sort=name-desc&limit=1&q=Paging"));
      var newestName = reversed.get("items").get(0);
      assertEquals("Paging 124", newestName.get("name").asString());
      assertTrue(newestName.get("tags").isArray());
      String source = newestName.get("connection_id").asString();
      assertEquals(
          1, s.json(s.get("/browse/assets?source=" + source + "&q=Paging")).get("total").asInt());
      assertStatus(400, s.get("/browse/assets?sort=unsupported"));
      s.select(B);
      assertEquals(0, s.json(s.get("/browse/assets?source=" + source)).get("total").asInt());
    }
  }

  @Test
  void completionDraftEditsAreOptimisticAndRegisteredVersionsImmutable() throws Exception {
    try (var app = securityApp("--trace.discovery.enabled=false")) {
      var s = new BrowserSession(app);
      s.loginA();
      String f =
          created(
              s,
              "/formulas",
              Map.of(
                  "name",
                  "Editable DAX",
                  "language",
                  "DAX",
                  "category",
                  "DAX_MEASURE",
                  "ownerTeamId",
                  TEAM_A));
      String v =
          created(
              s,
              "/formulas/" + f + "/versions",
              Map.of(
                  "expression",
                  "SUM(Sales[Gross])",
                  "state",
                  "DRAFT",
                  "referencedAssets",
                  List.of(assetA),
                  "referencedColumns",
                  List.of(columnA)));
      var definition =
          Map.of(
              "expression",
              "SUM(Sales[Gross])-SUM(Sales[Discount])",
              "state",
              "DRAFT",
              "referencedAssets",
              List.of(assetA),
              "referencedColumns",
              List.of(columnA));
      assertStatus(
          200,
          s.send(
              "/formulas/" + f + "/versions/" + v,
              Map.of("definition", definition, "expectedRevision", 1),
              "PATCH"));
      assertEquals(1, s.json(s.get("/formulas/" + f)).get("versions").size());
      assertStatus(
          409,
          s.send(
              "/formulas/" + f + "/versions/" + v,
              Map.of("definition", definition, "expectedRevision", 1),
              "PATCH"));
      var registered = new java.util.HashMap<String, Object>(definition);
      registered.put("state", "REGISTERED");
      assertStatus(
          200,
          s.send(
              "/formulas/" + f + "/versions/" + v,
              Map.of("definition", registered, "expectedRevision", 2),
              "PATCH"));
      assertStatus(
          409,
          s.send(
              "/formulas/" + f + "/versions/" + v,
              Map.of("definition", definition, "expectedRevision", 3),
              "PATCH"));
      select(A, SHARED);
      assertThrows(
          DataAccessException.class,
          () ->
              tenants.execute(
                  t -> {
                    jdbc.update(
                        "DELETE FROM formula_dependencies WHERE organization_id=? AND version_id=?",
                        A,
                        UUID.fromString(v));
                    return null;
                  }));
      s.select(B);
      assertStatus(404, s.get("/formulas/" + f + "/versions/" + v));
    }
  }

  @Test
  void completionCuratedOwnersTagsAndHistoryRemainSeparate() throws Exception {
    try (var app = securityApp("--trace.discovery.enabled=false")) {
      var s = new BrowserSession(app);
      s.loginA();
      String tag = created(s, "/tags", Map.of("name", "Finance curated"));
      var body =
          new java.util.HashMap<String, Object>(
              manualAsset(CONNECTION_A.toString(), "Curated ownership", "DATASET"));
      body.put("technicalOwner", "Engineering");
      body.put("businessOwner", "Finance");
      body.put("tagIds", List.of(tag));
      String a = created(s, "/assets", body);
      var edit =
          Map.of(
              "name",
              "Curated ownership",
              "technicalOwner",
              "Data Engineering",
              "businessOwner",
              "Revenue Operations",
              "description",
              "Curated description",
              "tagIds",
              List.of(tag),
              "revision",
              1);
      assertStatus(200, s.send("/assets/" + a, edit, "PATCH"));
      var detail = s.json(s.get("/assets/" + a + "/curation"));
      assertEquals("Data Engineering", detail.get("technical_owner").asString());
      assertEquals("Revenue Operations", detail.get("business_owner").asString());
      assertTrue(detail.get("tag_ids").asString().contains(tag));
      assertTrue(s.json(s.get("/browse/asset-history?parent=" + a)).get("total").asInt() >= 2);
      s.select(B);
      assertEquals(0, s.json(s.get("/browse/tags?q=Finance")).get("total").asInt());
      assertStatus(404, s.get("/assets/" + a + "/curation"));
    }
  }

  @Test
  void completionAllProvidersKeepIdentityAndCancellationIsIsolated() throws Exception {
    for (String type : List.of("SNOWFLAKE", "AMAZON_S3", "GITHUB_SQL", "POWER_BI")) {
      try (var fixture = new AcquisitionFixture();
          var app = queueApp(fixture, true)) {
        var s = new BrowserSession(app);
        s.loginA();
        var worker = app.getBean(com.trace.backend.service.DiscoveryWorker.class);
        String c = queueSource(s, type, " complete matrix");
        UUID stable = null;
        for (int i = 0; i < 2; i++) {
          var response = s.send("/connections/" + c + "/sync", null, "POST");
          assertStatus(202, response);
          worker.tick();
          var done = awaitRun(s, c, s.json(response).get("id").asString(), "COMPLETED");
          assertStatus(200, s.send("/imports/" + done.get("import_id") + "/commit", null, "POST"));
          UUID id =
              admin.queryForObject(
                  "SELECT id FROM assets WHERE connection_id=?", UUID.class, UUID.fromString(c));
          if (stable != null) assertEquals(stable, id);
          stable = id;
        }
        fixture.mode.set("BLOCK");
        var queued = s.send("/connections/" + c + "/sync", null, "POST");
        String run = s.json(queued).get("id").asString();
        worker.tick();
        assertTrue(fixture.entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertStatus(
            200, s.send("/connections/" + c + "/sync-runs/" + run + "/cancel", null, "POST"));
        worker.tick();
        awaitRun(s, c, run, "CANCELLED");
        fixture.release.countDown();
        s.select(B);
        assertStatus(404, s.get("/connections/" + c + "/sync-runs/" + run));
        s.select(A);
        fixture.mode.set("SUCCESS");
        var retry = s.send("/connections/" + c + "/sync-runs/" + run + "/retry", null, "POST");
        assertStatus(202, retry);
        worker.tick();
        awaitRun(s, c, s.json(retry).get("id").asString(), "COMPLETED");
      }
    }
  }

  @Test
  void hardeningRegisteredDependenciesCannotBeInsertedOrMovedAndMetricsRequireRegistration()
      throws Exception {
    try (var app = securityApp()) {
      var s = new BrowserSession(app);
      s.loginA();
      String formula =
          created(
              s,
              "/formulas",
              Map.of(
                  "name",
                  "Immutable",
                  "category",
                  "BUSINESS_RULE",
                  "language",
                  "BUSINESS_RULE",
                  "ownerTeamId",
                  TEAM_A));
      String version =
          created(
              s,
              "/formulas/" + formula + "/versions",
              Map.of(
                  "expression",
                  "net = gross - discount",
                  "state",
                  "REGISTERED",
                  "referencedAssets",
                  List.of(assetA),
                  "referencedColumns",
                  List.of(columnA)));
      UUID extra = UUID.randomUUID();
      admin.update(
          "INSERT INTO asset_columns(id,organization_id,asset_id,name,data_type)"
              + " VALUES(?,?,?,'OTHER','NUMBER')",
          extra,
          A,
          assetA);
      rejected(
          () ->
              tenants.execute(
                  t ->
                      jdbc.update(
                          "INSERT INTO"
                              + " formula_column_dependencies(organization_id,version_id,column_id)"
                              + " VALUES(?,?,?)",
                          A,
                          UUID.fromString(version),
                          extra)));
      rejected(
          () ->
              tenants.execute(
                  t ->
                      jdbc.update(
                          "UPDATE formula_dependencies SET asset_id=? WHERE organization_id=? AND"
                              + " version_id=?",
                          assetA,
                          A,
                          UUID.fromString(version))));
      String draft =
          created(
              s,
              "/formulas/" + formula + "/versions",
              Map.of("expression", "draft", "state", "DRAFT"));
      String metric =
          created(
              s,
              "/metrics",
              Map.of("name", "Metric", "businessKey", "HARDENING_METRIC", "ownerTeamId", TEAM_A));
      rejected(
          () ->
              tenants.execute(
                  t ->
                      jdbc.update(
                          "INSERT INTO"
                              + " metric_versions(organization_id,metric_id,version,formula_version_id)"
                              + " VALUES(?,?,1,?)",
                          A,
                          UUID.fromString(metric),
                          UUID.fromString(draft))));
      assertStatus(
          200,
          s.send("/metrics/" + metric + "/versions", Map.of("formulaVersionId", version), "POST"));
    }
  }

  private MetadataPreview snapshot(
      String key,
      String name,
      List<MetadataPreview.Column> fields,
      boolean complete,
      String scope,
      List<MetadataPreview.Relationship> relationships,
      List<MetadataPreview.Dataset> other) {
    var datasets = new java.util.ArrayList<MetadataPreview.Dataset>();
    datasets.add(
        new MetadataPreview.Dataset(
            key, name, fields, "DATASET", Map.of("provenance", "LIVE_DISCOVERY")));
    datasets.addAll(other);
    return new MetadataPreview(
        datasets,
        List.of("Informational provider capability notice"),
        1,
        relationships,
        complete,
        scope);
  }

  @Test
  void hardeningCompleteInventoriesRespectScopePreserveIdsAndRecordColumnChanges() {
    String scope = "a".repeat(64), other = "b".repeat(64);
    var p =
        snapshot(
            "LIVE.ONE",
            "Live",
            List.of(
                new MetadataPreview.Column("ID", "NUMBER", null, false, 1),
                new MetadataPreview.Column("GONE", "NUMBER")),
            true,
            scope,
            List.of(),
            List.of());
    UUID first = imports.previewNormalized(CONNECTION_A, p, "live.json", 0);
    imports.commit(first);
    UUID asset = tenants.execute(t -> assets.find(t, CONNECTION_A, "LIVE.ONE").orElseThrow().id());
    var changed =
        snapshot(
            "LIVE.ONE",
            "Live",
            List.of(new MetadataPreview.Column("ID", "VARCHAR", null, true, 2)),
            true,
            scope,
            List.of(),
            List.of());
    UUID update = imports.previewNormalized(CONNECTION_A, changed, "live.json", 0);
    var outcome = imports.commit(update);
    assertEquals(1, outcome.results().updatedColumns());
    assertEquals(
        asset, tenants.execute(t -> assets.find(t, CONNECTION_A, "LIVE.ONE").orElseThrow().id()));
    assertEquals(
        "NOT_SEEN_IN_LATEST_COMPLETE_INVENTORY",
        admin.queryForObject(
            "SELECT inventory_state FROM asset_columns WHERE asset_id=? AND name='GONE'",
            String.class,
            asset));
    assertTrue(
        admin.queryForObject(
                "SELECT count(*) FROM metadata_change_history WHERE asset_id=? AND import_id=?",
                Long.class,
                asset,
                update)
            > 0);
    // A complete inventory in a different configured scope cannot retire this scope's assets.
    UUID outside =
        imports.previewNormalized(
            CONNECTION_A,
            snapshot("OTHER", "Other", List.of(), true, other, List.of(), List.of()),
            "live.json",
            0);
    imports.commit(outside);
    assertEquals(
        "CURRENT",
        admin.queryForObject("SELECT inventory_state FROM assets WHERE id=?", String.class, asset));
    // An incomplete inventory cannot retire anything, even without a warning.
    UUID partial =
        imports.previewNormalized(
            CONNECTION_A,
            new MetadataPreview(List.of(), List.of(), 1, List.of(), false, scope),
            "live.json",
            0);
    imports.commit(partial);
    assertEquals(
        "CURRENT",
        admin.queryForObject("SELECT inventory_state FROM assets WHERE id=?", String.class, asset));
    UUID empty =
        imports.previewNormalized(
            CONNECTION_A,
            new MetadataPreview(List.of(), List.of("Capability notice"), 1, List.of(), true, scope),
            "live.json",
            0);
    imports.commit(empty);
    assertEquals(
        "NOT_SEEN_IN_LATEST_COMPLETE_INVENTORY",
        admin.queryForObject("SELECT inventory_state FROM assets WHERE id=?", String.class, asset));
  }

  @Test
  void hardeningColumnCurationAndUnchangedTagsSurviveReimportWithoutFakeHistory() throws Exception {
    try (var app = securityApp()) {
      var s = new BrowserSession(app);
      s.loginA();
      assertStatus(
          200,
          s.send(
              "/assets/" + assetA + "/columns/" + columnA,
              Map.of(
                  "name",
                  "ID",
                  "dataType",
                  "NUMBER",
                  "description",
                  "Curated",
                  "nullable",
                  true,
                  "ordinalPosition",
                  7),
              "PATCH"));
      var p =
          new MetadataPreview(
              List.of(
                  new MetadataPreview.Dataset(
                      "DB.RAW.ORDERS",
                      "RAW.ORDERS",
                      List.of(new MetadataPreview.Column("ID", "NUMBER", "Imported", false, 1)))),
              List.of(),
              1);
      imports.commit(imports.previewNormalized(CONNECTION_A, p, "file.json", 1, "METADATA_JSON"));
      var col = tenants.execute(t -> columns.list(t, assetA).getFirst());
      assertEquals(true, col.nullable());
      assertEquals(7, col.ordinalPosition());
      assertEquals("Curated", col.description());
      String tag = created(s, "/tags", Map.of("name", "No churn"));
      assertStatus(
          200,
          s.send(
              "/assets/" + assetA,
              Map.of("name", "RAW.ORDERS", "revision", 1, "tagIds", List.of(tag)),
              "PATCH"));
      long events =
          admin.queryForObject(
              "SELECT count(*) FROM asset_curation_history WHERE asset_id=?", Long.class, assetA);
      assertStatus(
          200,
          s.send(
              "/assets/" + assetA,
              Map.of("name", "RAW.ORDERS", "revision", 2, "tagIds", List.of(tag)),
              "PATCH"));
      assertEquals(
          events,
          admin.queryForObject(
              "SELECT count(*) FROM asset_curation_history WHERE asset_id=?", Long.class, assetA));
    }
  }

  @Test
  void hardeningChangedConfigurationAndOlderDiscoveryPreviewsCannotOverwriteCurrentMetadata() {
    UUID stale = imports.preview(CONNECTION_A, new byte[] {1});
    admin.update(
        "UPDATE source_connections SET updated_at=clock_timestamp() WHERE id=?", CONNECTION_A);
    assertEquals(
        "CONFIGURATION_CHANGED",
        assertThrows(com.trace.backend.exception.ApiException.class, () -> imports.commit(stale))
            .code);
    assertEquals("PREVIEW_READY", imports.get(stale).status().name());
    String scope = "c".repeat(64);
    UUID older =
        imports.previewNormalized(
            CONNECTION_A,
            snapshot("ORDERED", "Old", List.of(), true, scope, List.of(), List.of()),
            "live.json",
            0);
    UUID newer =
        imports.previewNormalized(
            CONNECTION_A,
            snapshot("ORDERED", "New", List.of(), true, scope, List.of(), List.of()),
            "live.json",
            0);
    imports.commit(newer);
    assertEquals(
        "STALE_PREVIEW",
        assertThrows(com.trace.backend.exception.ApiException.class, () -> imports.commit(older))
            .code);
    assertEquals(
        "New", tenants.execute(t -> assets.find(t, CONNECTION_A, "ORDERED").orElseThrow().name()));
    assertEquals(newer, imports.commit(newer).id());
  }

  @Test
  void hardeningLiveReportAssociationChangesRetireOnlyOldAcquiredEdges() {
    String scope = "d".repeat(64);
    var models =
        List.of(
            new MetadataPreview.Dataset(
                "MODEL1",
                "One",
                List.of(),
                "SEMANTIC_MODEL",
                Map.of("provenance", "LIVE_DISCOVERY")),
            new MetadataPreview.Dataset(
                "MODEL2",
                "Two",
                List.of(),
                "SEMANTIC_MODEL",
                Map.of("provenance", "LIVE_DISCOVERY")),
            new MetadataPreview.Dataset(
                "REPORT",
                "Report",
                List.of(),
                "REPORT",
                Map.of("provenance", "LIVE_DISCOVERY", "modelAssociationObserved", true)));
    for (String key : List.of("MODEL1", "MODEL2")) {
      var p =
          new MetadataPreview(
              models,
              List.of("Capability notice"),
              1,
              List.of(
                  new MetadataPreview.Relationship(
                      key,
                      "SEMANTIC_MODEL",
                      "REPORT",
                      "REPORT",
                      "REPORT_USES_MODEL",
                      "Provider report.datasetId")),
              true,
              scope);
      imports.commit(imports.previewNormalized(CONNECTION_A, p, "live.json", 0));
    }
    assertEquals(
        1,
        admin.queryForObject(
            "SELECT count(*) FROM lineage_edges WHERE organization_id=? AND active AND"
                + " provenance='LIVE_DISCOVERY'",
            Integer.class,
            A));
    assertEquals(
        1,
        admin.queryForObject(
            "SELECT count(*) FROM lineage_edges WHERE organization_id=? AND NOT active AND"
                + " revoked_at IS NOT NULL",
            Integer.class,
            A));
  }

  @Test
  void hardeningExpiredLeaseCannotFinishInsideAnAlreadyOpenTransaction() {
    var repository =
        new ConnectionRepository(jdbc, new RegistryRepository(jdbc), new ObjectMapper());
    tenants.execute(t -> repository.run(t, CONNECTION_A));
    var queue = new DiscoveryQueueRepository(jdbc, new ObjectMapper());
    var lease = queue.claim(UUID.randomUUID(), 5).orElseThrow();
    admin.update(
        "UPDATE connection_sync_runs SET lease_expires_at=clock_timestamp()+interval '200"
            + " milliseconds' WHERE id=?",
        lease.run());
    tenants.executeForRun(
        A,
        SHARED,
        lease.run(),
        lease.owner(),
        lease.token(),
        t -> {
          jdbc.execute("SELECT pg_sleep(0.3)");
          assertFalse(queue.finish(lease, "FAILED", null, 0, List.of(), "UNAVAILABLE", 1));
          return null;
        });
  }

  @Test
  void connectionLimitAndTestEvidenceSurviveRotationAndProviderFailures() throws Exception {
    try (var fixture = new AcquisitionFixture();
        var app = queueApp(fixture, false)) {
      var s = new BrowserSession(app);
      s.loginA();
      var invalid =
          s.send(
              "/connections",
              Map.of(
                  "name",
                  "Invalid limit",
                  "ownerTeamId",
                  TEAM_A,
                  "connectionType",
                  "AMAZON_S3",
                  "configuration",
                  Map.of(
                      "region", "ap-south-1", "bucket", "scoped-bucket", "maxObjects", 10000000)),
              "POST");
      assertStatus(400, invalid);
      assertEquals("DISCOVERY_LIMIT_INVALID", s.json(invalid).get("code").asString());
      String c = queueSource(s, "AMAZON_S3", " test evidence");
      assertStatus(
          400,
          s.send("/connections/" + c + "/credentials", Map.of("accessKeyId", "incomplete"), "PUT"));
      var success = s.send("/connections/" + c + "/test", null, "POST");
      assertStatus(200, success);
      assertEquals("CONNECTED", s.json(success).get("status").asString());
      assertFalse(s.json(success).get("last_successful_test_at").isNull());
      assertFalse(s.json(success).get("last_test_diagnostic_id").isNull());
      String lastSuccess = s.json(success).get("last_successful_test_at").asString();
      assertStatus(
          200,
          s.send(
              "/connections/" + c + "/credentials",
              Map.of("accessKeyId", "rotated-key", "secretAccessKey", "never-echo-rotated-secret"),
              "PUT"));
      assertEquals("NOT_TESTED", s.json(s.get("/connections/" + c)).get("status").asString());
      fixture.mode.set("AUTH");
      var denied = s.send("/connections/" + c + "/test", null, "POST");
      assertStatus(200, denied);
      assertEquals("AUTHENTICATION_FAILED", s.json(denied).get("status").asString());
      assertEquals(lastSuccess, s.json(denied).get("last_successful_test_at").asString());
      assertFalse(denied.body().contains("never-echo-rotated-secret"));
      assertFalse(denied.body().contains("ciphertext"));
      s.select(B);
      assertStatus(404, s.get("/connections/" + c));
      assertStatus(404, s.send("/connections/" + c + "/test", null, "POST"));
    }
  }

  @Test
  void connectionAcquisitionRateBudgetIsTenantScopedAndBounded() throws Exception {
    try (var fixture = new AcquisitionFixture();
        var app = queueApp(fixture, false)) {
      var s = new BrowserSession(app);
      s.loginA();
      String c = queueSource(s, "GITHUB_SQL", " bounded requests");
      admin.update(
          "INSERT INTO"
              + " audit_events(organization_id,actor_user_id,event_type,resource_type,resource_id,outcome)"
              + " SELECT ?,?,'CONNECTION_TEST_REQUESTED','CONNECTION',?,'SUCCESS' FROM"
              + " generate_series(1,20)",
          A,
          SHARED,
          UUID.fromString(c));
      var limited = s.send("/connections/" + c + "/test", null, "POST");
      assertStatus(429, limited);
      assertEquals("CONNECTION_RATE_LIMITED", s.json(limited).get("code").asString());
      assertEquals(0, fixture.calls.get());
    }
  }

  @Test
  void connectionProcessorOutageIsPersistedAsUnavailableInsteadOfStaleSuccess() throws Exception {
    try (var fixture = new AcquisitionFixture();
        var app = queueApp(fixture, false)) {
      var s = new BrowserSession(app);
      s.loginA();
      String c = queueSource(s, "GITHUB_SQL", " processor outage");
      assertStatus(200, s.send("/connections/" + c + "/test", null, "POST"));
      fixture.server.stop(0);
      var failed = s.send("/connections/" + c + "/test", null, "POST");
      assertStatus(200, failed);
      assertEquals("UNAVAILABLE", s.json(failed).get("status").asString());
      assertEquals("PROCESSOR_UNAVAILABLE", s.json(failed).get("reason_code").asString());
      assertFalse(s.json(failed).get("last_test_diagnostic_id").isNull());
    }
  }

  @Test
  void hardeningConnectionTestsCannotPublishSuccessAfterCredentialRotation() throws Exception {
    try (var fixture = new AcquisitionFixture();
        var app = queueApp(fixture, false)) {
      var s = new BrowserSession(app);
      s.loginA();
      String c = queueSource(s, "GITHUB_SQL", " stale test");
      fixture.mode.set("BLOCK");
      var task =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try {
                  return s.send("/connections/" + c + "/test", null, "POST");
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
      assertTrue(fixture.entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
      admin.update(
          "UPDATE source_connections SET updated_at=clock_timestamp(),status='NOT_TESTED' WHERE"
              + " id=?",
          UUID.fromString(c));
      fixture.release.countDown();
      assertStatus(409, task.get());
      assertEquals("NOT_TESTED", s.json(s.get("/connections/" + c)).get("status").asString());
    }
  }

  @Test
  void hardeningMetricMetadataUsesRevisionsAndRevokedUsageLeavesImpact() throws Exception {
    try (var app = securityApp()) {
      var s = new BrowserSession(app);
      s.loginA();
      String f =
          created(
              s,
              "/formulas",
              Map.of(
                  "name",
                  "Rule",
                  "category",
                  "BUSINESS_RULE",
                  "language",
                  "BUSINESS_RULE",
                  "ownerTeamId",
                  TEAM_A));
      String fv =
          created(
              s,
              "/formulas/" + f + "/versions",
              Map.of(
                  "expression",
                  "Rule",
                  "state",
                  "REGISTERED",
                  "referencedAssets",
                  List.of(assetA)));
      String m =
          created(
              s,
              "/metrics",
              Map.of(
                  "name", "Before", "businessKey", "MUTABLE_DESCRIPTION", "ownerTeamId", TEAM_A));
      var definition =
          Map.of(
              "name",
              "After",
              "businessKey",
              "MUTABLE_DESCRIPTION",
              "ownerTeamId",
              TEAM_A,
              "description",
              "Revised business definition");
      assertStatus(
          200,
          s.send(
              "/metrics/" + m, Map.of("definition", definition, "expectedRevision", 1), "PATCH"));
      assertStatus(
          409,
          s.send(
              "/metrics/" + m, Map.of("definition", definition, "expectedRevision", 1), "PATCH"));
      String mv = created(s, "/metrics/" + m + "/versions", Map.of("formulaVersionId", fv));
      String report =
          created(
              s,
              "/assets",
              Map.of(
                  "connectionId",
                  CONNECTION_A,
                  "ownerTeamId",
                  TEAM_A,
                  "name",
                  "Report",
                  "assetType",
                  "REPORT",
                  "externalKey",
                  "HARDENING.REPORT"));
      String usage =
          created(
              s,
              "/metric-mappings",
              Map.of(
                  "metricVersionId",
                  mv,
                  "assetId",
                  report,
                  "evidenceRef",
                  "Explicit test declaration"));
      assertTrue(
          s.get("/lineage/" + assetA + "?depth=5&direction=downstream")
              .body()
              .contains("METRIC_USED_BY_REPORT"));
      assertStatus(200, s.send("/metric-mappings/" + usage + "/revoke", null, "POST"));
      assertFalse(
          s.get("/lineage/" + assetA + "?depth=5&direction=downstream")
              .body()
              .contains("METRIC_USED_BY_REPORT"));
      assertTrue(s.get("/browse/metric-mappings").body().contains("\"active\":false"));
      s.select(B);
      assertStatus(404, s.send("/metric-mappings/" + usage + "/revoke", null, "POST"));
    }
  }

  @Test
  void hardeningReadinessSeparatesLivenessDatabaseAndProcessorAndCorrelatesFailures()
      throws Exception {
    try (var app = securityApp("--trace.processor.url=http://127.0.0.1:1")) {
      String base =
          "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
      var client = HttpClient.newHttpClient();
      for (var endpoint :
          Map.of("liveness", 200, "database", 200, "processor", 503, "readiness", 503).entrySet()) {
        var response =
            client.send(
                HttpRequest.newBuilder(URI.create(base + "/actuator/health/" + endpoint.getKey()))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertStatus(endpoint.getValue(), response);
        assertFalse(response.body().contains("password"));
        assertTrue(
            response.headers().firstValue("X-Request-ID").orElse("").matches("[a-f0-9-]{36}"));
      }
    }
  }

  @Test
  void hardeningRepresentativeCatalogAndGraphStayBounded() throws Exception {
    admin.update(
        "INSERT INTO"
            + " assets(organization_id,connection_id,owner_team_id,name,external_key,asset_type,platform)"
            + " SELECT ?,?,?, 'Scale '||lpad(i::text,5,'0'),'SCALE.'||i,'DATASET','SNOWFLAKE' FROM"
            + " generate_series(1,5000) i",
        A,
        CONNECTION_A,
        TEAM_A);
    admin.update(
        "INSERT INTO"
            + " lineage_edges(organization_id,source_asset_id,target_asset_id,relationship_type,provenance)"
            + " SELECT ?,?,id,'DATA_FLOW','MANUAL_DECLARATION' FROM assets WHERE organization_id=?"
            + " AND external_key LIKE 'SCALE.%' ORDER BY external_key LIMIT 500",
        A, assetA, A);
    try (var app = securityApp()) {
      var s = new BrowserSession(app);
      s.loginA();
      long start = System.nanoTime();
      var listing = s.get("/browse/assets?q=Scale&limit=25");
      long listMs = (System.nanoTime() - start) / 1_000_000;
      assertStatus(200, listing);
      assertEquals(5000, s.json(listing).get("total").asInt());
      assertEquals(25, s.json(listing).get("items").size());
      start = System.nanoTime();
      var graph = s.get("/lineage/" + assetA + "?depth=5&direction=downstream");
      long graphMs = (System.nanoTime() - start) / 1_000_000;
      assertStatus(200, graph);
      var data = s.json(graph);
      assertTrue(data.get("nodes").size() <= data.get("limits").get("maximumNodes").asInt());
      assertTrue(data.get("edges").size() <= 200);
      assertTrue(data.get("truncated").asBoolean());
      for (var edge : data.get("edges"))
        for (String field : List.of("source_asset_id", "target_asset_id")) {
          boolean present = false;
          for (var node : data.get("nodes"))
            if (node.get("id").asString().equals(edge.get(field).asString())) present = true;
          assertTrue(present, "Every rendered edge has both endpoints");
        }
      System.out.println(
          "PERFORMANCE synthetic assets=5000 page=25 listMs="
              + listMs
              + " fanout=500 graphMs="
              + graphMs
              + " nodes="
              + data.get("nodes").size()
              + " edges="
              + data.get("edges").size());
    }
  }

  @Test
  void hardeningForeignUuidMatrixIsHiddenByHttpAndRestrictedSql() throws Exception {
    try (var fixture = new AcquisitionFixture();
        var app = queueApp(fixture, false)) {
      var s = new BrowserSession(app);
      s.loginA();
      String c = queueSource(s, "GITHUB_SQL", " private");
      String run = s.json(s.send("/connections/" + c + "/sync", null, "POST")).get("id").asString();
      String formula =
          created(
              s,
              "/formulas",
              Map.of(
                  "name",
                  "Private formula",
                  "ownerTeamId",
                  TEAM_A,
                  "category",
                  "BUSINESS_RULE",
                  "language",
                  "BUSINESS_RULE"));
      String version =
          created(
              s,
              "/formulas/" + formula + "/versions",
              Map.of("expression", "private rule", "state", "REGISTERED"));
      String metric =
          created(
              s,
              "/metrics",
              Map.of(
                  "name",
                  "Private metric",
                  "businessKey",
                  "PRIVATE_MATRIX",
                  "ownerTeamId",
                  TEAM_A));
      String metricVersion =
          created(s, "/metrics/" + metric + "/versions", Map.of("formulaVersionId", version));
      String report =
          created(
              s,
              "/assets",
              Map.of(
                  "name",
                  "Private report",
                  "assetType",
                  "REPORT",
                  "externalKey",
                  "PRIVATE.REPORT",
                  "connectionId",
                  CONNECTION_A,
                  "ownerTeamId",
                  TEAM_A));
      String mapping =
          created(
              s,
              "/lineage/mappings",
              Map.of(
                  "sourceAssetId",
                  assetA,
                  "targetAssetId",
                  report,
                  "relationshipType",
                  "DATA_FLOW",
                  "evidenceRef",
                  "private declaration"));
      String usage =
          created(
              s,
              "/metric-mappings",
              Map.of(
                  "metricVersionId",
                  metricVersion,
                  "assetId",
                  report,
                  "evidenceRef",
                  "private usage"));
      created(s, "/tags", Map.of("name", "Private tag"));
      created(
          s,
          "/import-mappings",
          Map.of(
              "connectionId",
              CONNECTION_A,
              "name",
              "Private profile",
              "mapping",
              Map.of("id", "external_key")));
      s.select(B);
      for (String path :
          List.of(
              "/connections/" + c,
              "/connections/" + c + "/sync-runs/" + run,
              "/imports/" + batchA,
              "/assets/" + assetA,
              "/assets/" + assetA + "/columns",
              "/assets/" + report,
              "/lineage/mappings/" + mapping,
              "/formulas/" + formula,
              "/formulas/" + formula + "/versions/" + version,
              "/metrics/" + metric,
              "/metrics/" + metric + "/versions",
              "/teams/" + TEAM_A)) assertStatus(404, s.get(path));
      assertStatus(
          404, s.send("/connections/" + c + "/credentials", Map.of("token", "denied"), "PUT"));
      assertStatus(404, s.send("/metric-mappings/" + usage + "/revoke", null, "POST"));
      for (String kind : List.of("profiles", "tags", "metric-mappings", "formulas", "metrics"))
        assertFalse(s.get("/browse/" + kind).body().contains("Private"), kind);
      select(B, SHARED);
      tenants.execute(
          t -> {
            for (String table :
                List.of(
                    "source_connections",
                    "connection_secret_storage",
                    "connection_sync_runs",
                    "connection_sync_attempts",
                    "import_batches",
                    "import_mapping_profiles",
                    "assets",
                    "asset_columns",
                    "lineage_edges",
                    "column_lineage",
                    "formulas",
                    "formula_versions",
                    "formula_dependencies",
                    "formula_column_dependencies",
                    "metrics",
                    "metric_versions",
                    "report_mappings",
                    "teams",
                    "tags",
                    "asset_tags",
                    "asset_curation_history",
                    "metadata_change_history",
                    "audit_events"))
              assertEquals(
                  0,
                  jdbc.queryForObject(
                      "SELECT count(*) FROM " + table + " WHERE organization_id=?",
                      Integer.class,
                      A),
                  table);
            return null;
          });
    }
  }

  @Test
  void hardeningEveryPersonaUsesFreshServerPermissionsAcrossCoreWrites() throws Exception {
    admin.update(
        "INSERT INTO organization_memberships(organization_id,user_id,role) VALUES(?,?,'MEMBER')",
        A,
        B_IMPORTER);
    admin.update(
        "INSERT INTO team_memberships(organization_id,team_id,user_id,role) VALUES(?,?,?,'EDITOR')",
        A,
        TEAM_A,
        B_IMPORTER);
    credential(B_IMPORTER, "Persona synthetic password!");
    try (var app = securityApp()) {
      var owner = new BrowserSession(app);
      owner.loginA();
      String formula =
          created(
              owner,
              "/formulas",
              Map.of(
                  "name",
                  "Persona formula",
                  "category",
                  "BUSINESS_RULE",
                  "language",
                  "BUSINESS_RULE",
                  "ownerTeamId",
                  TEAM_A));
      var user = new BrowserSession(app);
      assertStatus(200, user.login("retail@example.test", "Persona synthetic password!"));
      user.select(A);
      int row = 0;
      for (var persona :
          List.of(
              new String[] {"OWNER", "VIEWER", "yes"},
              new String[] {"ADMIN", "VIEWER", "yes"},
              new String[] {"MEMBER", "OWNER", "yes"},
              new String[] {"MEMBER", "EDITOR", "yes"},
              new String[] {"MEMBER", "VIEWER", "no"},
              new String[] {"VIEWER", "EDITOR", "no"},
              new String[] {"VIEWER", "VIEWER", "no"})) {
        row++;
        admin.update(
            "UPDATE organization_memberships SET role=? WHERE organization_id=? AND user_id=?",
            persona[0],
            A,
            B_IMPORTER);
        admin.update(
            "UPDATE team_memberships SET role=? WHERE organization_id=? AND team_id=? AND"
                + " user_id=?",
            persona[1],
            A,
            TEAM_A,
            B_IMPORTER);
        int expected = persona[2].equals("yes") ? 200 : 403;
        assertStatus(
            expected == 200 ? 201 : expected,
            user.send(
                "/connections",
                Map.of(
                    "name",
                    "Persona source " + row,
                    "ownerTeamId",
                    TEAM_A,
                    "connectionType",
                    "MANUAL"),
                "POST"));
        assertStatus(
            expected,
            user.send(
                "/assets",
                Map.of(
                    "name",
                    "Persona asset " + row,
                    "externalKey",
                    "PERSONA." + row,
                    "assetType",
                    "DATASET",
                    "ownerTeamId",
                    TEAM_A,
                    "connectionId",
                    CONNECTION_A),
                "POST"));
        assertStatus(
            expected,
            user.send(
                "/assets/" + assetA + "/columns",
                Map.of("name", "PERSONA_" + row, "dataType", "NUMBER"),
                "POST"));
        assertStatus(
            expected,
            user.send(
                "/formulas/" + formula + "/versions",
                Map.of("expression", "persona rule " + row, "state", "DRAFT"),
                "POST"));
        assertStatus(
            expected,
            user.send(
                "/metrics",
                Map.of(
                    "name",
                    "Persona metric " + row,
                    "businessKey",
                    "PERSONA_" + row,
                    "ownerTeamId",
                    TEAM_A),
                "POST"));
        assertStatus(
            expected,
            user.send(
                "/import-mappings",
                Map.of(
                    "name",
                    "Persona profile " + row,
                    "connectionId",
                    CONNECTION_A,
                    "mapping",
                    Map.of("key", "external_key")),
                "POST"));
      }
    }
  }

  @Test
  void hardeningOwnerPromotionSerializesWithAccountDeactivation() throws Exception {
    UUID platform = UUID.randomUUID();
    admin.update(
        "INSERT INTO users(id,email,display_name,platform_role)"
            + " VALUES(?,'race-admin@example.test','Race admin','PLATFORM_ADMIN')",
        platform);
    admin.update(
        "INSERT INTO organization_memberships(organization_id,user_id,role) VALUES(?,?,'MEMBER')",
        A,
        B_IMPORTER);
    var ds =
        new DriverManagerDataSource(
            System.getenv("TRACE_TEST_ADMIN_URL"), "trace_test_admin", "trace-test-admin-only");
    var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
    try (var blocker = ds.getConnection()) {
      blocker.setAutoCommit(false);
      blocker.createStatement().execute("SELECT pg_advisory_xact_lock(908002)");
      var pid = new java.util.concurrent.atomic.AtomicInteger();
      var entered = new java.util.concurrent.CountDownLatch(1);
      var transfer =
          pool.submit(
              () -> {
                try (var connection = ds.getConnection()) {
                  connection.setAutoCommit(false);
                  var sql =
                      connection.prepareStatement(
                          "SELECT set_config('trace.organization_id',?,true)");
                  sql.setString(1, A.toString());
                  sql.execute();
                  try (var rs =
                      connection.createStatement().executeQuery("SELECT pg_backend_pid()")) {
                    rs.next();
                    pid.set(rs.getInt(1));
                  }
                  entered.countDown();
                  var call = connection.prepareStatement("SELECT trace_transfer_organization(?,?)");
                  call.setObject(1, SHARED);
                  call.setObject(2, B_IMPORTER);
                  call.execute();
                  connection.commit();
                }
                return true;
              });
      assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
      boolean waiting = false;
      while (System.nanoTime() < deadline && !transfer.isDone()) {
        waiting =
            Boolean.TRUE.equals(
                admin.queryForObject(
                    "SELECT wait_event='advisory' FROM pg_stat_activity WHERE pid=?",
                    Boolean.class,
                    pid.get()));
        if (waiting) break;
        Thread.sleep(10);
      }
      assertTrue(
          waiting, "Owner promotion must honor the deactivation lock before reading owner state");
      blocker.commit();
      assertTrue(transfer.get(3, java.util.concurrent.TimeUnit.SECONDS));
      rejected(
          () ->
              admin.queryForObject(
                  "SELECT trace_user_state(?,?,false)", Object.class, platform, B_IMPORTER));
      assertEquals(
          true,
          admin.queryForObject("SELECT active FROM users WHERE id=?", Boolean.class, B_IMPORTER));
      assertEquals(
          1,
          admin.queryForObject(
              "SELECT count(*) FROM organization_memberships m JOIN users u ON u.id=m.user_id WHERE"
                  + " m.organization_id=? AND m.role='OWNER' AND m.status='ACTIVE' AND u.active",
              Integer.class,
              A));
    } finally {
      pool.shutdownNow();
    }
  }

  @AfterAll
  void closePool() {
    if (pool != null) pool.close();
  }
}
