package com.trace.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.trace.backend.repository.DiscoveryQueueRepository;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/** Every case creates its own disposable database on the explicitly supplied test server. */
@EnabledIfEnvironmentVariable(named = "TRACE_TEST_ADMIN_URL", matches = ".*/trace_tenancy_test")
class MigrationUpgradeIntegrationTests {
  @ParameterizedTest
  @ValueSource(ints = {5, 7, 8, 10})
  void historicalUpgradePreservesRowsAndRecoversOnlyUnleasedJobs(int baseline) {
    String base = System.getenv("TRACE_TEST_ADMIN_URL");
    assertEquals("true", System.getenv("TRACE_TEST_ALLOW_RESET"));
    assertFalse(base.matches(".*:(5433|55433)/.*"));
    String database = "trace_hardening_upgrade_" + baseline;
    var control =
        new JdbcTemplate(
            new DriverManagerDataSource(base, "trace_test_admin", "trace-test-admin-only"));
    // Refuse to reuse an existing database; tests never drop someone else's fixtures.
    control.execute("CREATE DATABASE " + database);
    String url = base.substring(0, base.lastIndexOf('/') + 1) + database;
    var ownerSource = new DriverManagerDataSource(url, "trace_test_admin", "trace-test-admin-only");
    var admin = new JdbcTemplate(ownerSource);
    try {
      admin.execute(
          "DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='trace_migrator') THEN"
              + " CREATE ROLE trace_migrator LOGIN PASSWORD 'trace-test-migration-only' NOSUPERUSER"
              + " NOBYPASSRLS; END IF; IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE"
              + " rolname='trace_runtime') THEN CREATE ROLE trace_runtime LOGIN PASSWORD"
              + " 'trace-test-runtime-only' NOSUPERUSER NOBYPASSRLS; END IF; END $$");
      admin.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
      admin.execute("GRANT USAGE,CREATE ON SCHEMA public TO trace_migrator");
      admin.execute("GRANT USAGE ON SCHEMA public TO trace_runtime");
      var migrator =
          new DriverManagerDataSource(url, "trace_migrator", "trace-test-migration-only");
      Flyway.configure().dataSource(migrator).target(Integer.toString(baseline)).load().migrate();
      UUID org = UUID.randomUUID(),
          user = UUID.randomUUID(),
          team = UUID.randomUUID(),
          connection = UUID.randomUUID(),
          asset = UUID.randomUUID(),
          run = UUID.randomUUID();
      admin.update("INSERT INTO organizations(id,name,slug) VALUES(?,'Upgrade','upgrade')", org);
      admin.update(
          "INSERT INTO users(id,email,display_name) VALUES(?,'upgrade@example.test','Upgrade')",
          user);
      admin.update(
          "INSERT INTO user_credentials(user_id,password_hash) VALUES(?,'opaque-existing-hash')",
          user);
      admin.update(
          "INSERT INTO organization_memberships(organization_id,user_id,role) VALUES(?,?,'OWNER')",
          org,
          user);
      admin.update("INSERT INTO teams(id,organization_id,name) VALUES(?,?,'Upgrade')", team, org);
      admin.update(
          "INSERT INTO team_memberships(organization_id,team_id,user_id,role)"
              + " VALUES(?,?,?,'OWNER')",
          org,
          team,
          user);
      admin.update(
          "INSERT INTO"
              + " source_connections(id,organization_id,owner_team_id,name,platform,ingestion_mode)"
              + " VALUES(?,?,?,'Upgrade','GITHUB_SQL','METADATA_IMPORT')",
          connection,
          org,
          team);
      admin.update(
          "INSERT INTO"
              + " assets(id,organization_id,connection_id,owner_team_id,name,external_key,asset_type,platform)"
              + " VALUES(?,?,?,?,'Keep','KEEP','DATASET','GITHUB_SQL')",
          asset,
          org,
          connection,
          team);

      UUID targetAsset = UUID.randomUUID(),
          sourceColumn = UUID.randomUUID(),
          targetColumn = UUID.randomUUID(),
          batch = UUID.randomUUID(),
          oldAudit = UUID.randomUUID();
      admin.update(
          "INSERT INTO"
              + " import_batches(id,organization_id,connection_id,created_by_user_id,owner_team_id,status,preview,filename,committed_at)"
              + " VALUES(?,?,?,?,?,'COMMITTED','{\"datasets\":[]}'::jsonb,'preserved.csv',now())",
          batch,
          org,
          connection,
          user,
          team);
      admin.update("UPDATE assets SET last_import_id=? WHERE id=?", batch, asset);
      admin.update(
          "INSERT INTO"
              + " assets(id,organization_id,connection_id,owner_team_id,name,external_key,asset_type,platform,last_import_id)"
              + " VALUES(?,?,?,?,'Target','TARGET','DATASET','GITHUB_SQL',?)",
          targetAsset,
          org,
          connection,
          team,
          batch);
      admin.update(
          "INSERT INTO"
              + " asset_columns(id,organization_id,asset_id,name,data_type,description,last_import_id)"
              + " VALUES(?,?,?,'ID','NUMBER','Preserved source',?),(?,?,?,'ID','NUMBER','Preserved"
              + " target',?)",
          sourceColumn,
          org,
          asset,
          batch,
          targetColumn,
          org,
          targetAsset,
          batch);
      admin.update(
          "INSERT INTO"
              + " lineage_edges(organization_id,source_asset_id,target_asset_id,relationship_type,provenance,evidence_ref)"
              + " VALUES(?,?,?,'DATA_FLOW','MANUAL_DECLARATION','Preserved historical evidence')",
          org,
          asset,
          targetAsset);
      admin.update(
          "INSERT INTO column_lineage(organization_id,source_column_id,target_column_id,provenance)"
              + " VALUES(?,?,?,'MANUAL_DECLARATION')",
          org,
          sourceColumn,
          targetColumn);
      admin.update(
          "INSERT INTO"
              + " audit_events(id,actor_user_id,organization_id,event_type,resource_type,resource_id,outcome)"
              + " VALUES(?,?,?,'PRESERVED_HISTORY','ASSET',?,'SUCCESS')",
          oldAudit,
          user,
          org,
          asset);
      String historicalAudit =
          admin.queryForObject(
              "SELECT to_jsonb(a)::text FROM audit_events a WHERE id=?", String.class, oldAudit);
      if (baseline >= 7) {
        admin.update(
            "INSERT INTO connection_secret_storage(organization_id,connection_id,ciphertext,nonce)"
                + " VALUES(?,?,decode('001122','hex'),decode('000000000000000000000000','hex'))",
            org,
            connection);
        admin.update(
            "INSERT INTO connection_sync_runs(id,organization_id,connection_id,initiated_by,status)"
                + " VALUES(?,?,?,?,'RUNNING')",
            run,
            org,
            connection,
            user);
        if (baseline == 10)
          admin.update(
              "UPDATE connection_sync_runs SET"
                  + " attempt_count=1,lease_owner=gen_random_uuid(),lease_token=gen_random_uuid(),lease_expires_at=now()+interval"
                  + " '1 hour',configuration_updated_at=(SELECT updated_at FROM source_connections"
                  + " WHERE id=?) WHERE id=?",
              connection,
              run);
      }

      if (baseline >= 7) {
        UUID formula = UUID.randomUUID(), version = UUID.randomUUID(), metric = UUID.randomUUID();
        admin.update(
            "INSERT INTO formulas(id,organization_id,owner_team_id,name,category,language)"
                + " VALUES(?,?,?,'Preserved formula','BUSINESS_RULE','BUSINESS_RULE')",
            formula,
            org,
            team);
        admin.update(
            "INSERT INTO"
                + " formula_versions(id,organization_id,formula_id,version,expression,state,created_by)"
                + " VALUES(?,?,?,1,'Preserved immutable expression','REGISTERED',?)",
            version,
            org,
            formula,
            user);
        admin.update(
            "INSERT INTO formula_dependencies(organization_id,version_id,asset_id) VALUES(?,?,?)",
            org,
            version,
            asset);
        admin.update(
            "INSERT INTO metrics(id,organization_id,owner_team_id,name,business_key)"
                + " VALUES(?,?,?,'Preserved metric','PRESERVED_METRIC')",
            metric,
            org,
            team);
        admin.update(
            "INSERT INTO metric_versions(organization_id,metric_id,version,formula_version_id)"
                + " VALUES(?,?,1,?)",
            org,
            metric,
            version);
      }
      // Fingerprint the exact baseline columns so additive columns do not create false differences.
      var projections = new LinkedHashMap<String, String>();
      var before = new LinkedHashMap<String, String>();
      var preservedTables =
          new java.util.ArrayList<>(
              List.of(
                  "organizations",
                  "users",
                  "user_credentials",
                  "organization_memberships",
                  "teams",
                  "team_memberships",
                  "source_connections",
                  "assets",
                  "asset_columns",
                  "import_batches",
                  "lineage_edges",
                  "column_lineage",
                  "audit_events"));
      if (baseline >= 7)
        preservedTables.addAll(
            List.of(
                "formulas",
                "formula_versions",
                "formula_dependencies",
                "metrics",
                "metric_versions"));
      for (String table : preservedTables) {
        String columns =
            String.join(
                ",",
                admin.queryForList(
                    "SELECT quote_ident(column_name) FROM information_schema.columns WHERE"
                        + " table_schema='public' AND table_name=? ORDER BY ordinal_position",
                    String.class,
                    table));
        projections.put(table, columns);
        before.put(table, fingerprint(admin, table, columns));
      }
      UUID legacyAtTen = UUID.randomUUID();
      if (baseline == 10) {
        UUID legacyConnection = UUID.randomUUID();
        admin.update(
            "INSERT INTO"
                + " source_connections(id,organization_id,owner_team_id,name,platform,ingestion_mode)"
                + " VALUES(?,?,?,'Unleased legacy','GITHUB_SQL','METADATA_IMPORT')",
            legacyConnection,
            org,
            team);
        admin.update(
            "INSERT INTO connection_sync_runs(id,organization_id,connection_id,initiated_by,status)"
                + " VALUES(?,?,?,?,'RUNNING')",
            legacyAtTen,
            org,
            legacyConnection,
            user);
        // Include the new connection in the preservation fingerprint.
        before.put(
            "source_connections",
            fingerprint(admin, "source_connections", projections.get("source_connections")));
      }
      String encrypted =
          baseline >= 7
              ? admin.queryForObject(
                  "SELECT encode(ciphertext,'hex') FROM connection_secret_storage WHERE"
                      + " connection_id=?",
                  String.class,
                  connection)
              : null;
      Flyway.configure().dataSource(migrator).load().migrate();
      Flyway.configure().dataSource(migrator).load().validate();
      for (var entry : projections.entrySet()) {
        if (entry.getKey().equals("audit_events"))
          continue; // Recovery intentionally appends one event.
        assertEquals(
            before.get(entry.getKey()),
            fingerprint(admin, entry.getKey(), entry.getValue()),
            entry.getKey());
      }
      assertEquals(
          historicalAudit,
          admin.queryForObject(
              "SELECT to_jsonb(a)::text FROM audit_events a WHERE id=?", String.class, oldAudit),
          "Historical audit row preserved while recovery may append new events");
      if (baseline >= 7) {
        assertEquals(
            encrypted,
            admin.queryForObject(
                "SELECT encode(ciphertext,'hex') FROM connection_secret_storage WHERE"
                    + " connection_id=?",
                String.class,
                connection));
        assertEquals(
            baseline == 10 ? "RUNNING" : "PENDING",
            admin.queryForObject(
                "SELECT status FROM connection_sync_runs WHERE id=?", String.class, run));
        assertNotNull(
            admin.queryForObject(
                "SELECT configuration_updated_at FROM connection_sync_runs WHERE id=?",
                java.sql.Timestamp.class,
                run));
        var runtime =
            new JdbcTemplate(
                new DriverManagerDataSource(url, "trace_runtime", "trace-test-runtime-only"));
        new com.trace.backend.tenancy.RuntimeDatabaseGuard(runtime).afterPropertiesSet();
        var lease =
            new DiscoveryQueueRepository(runtime, new ObjectMapper()).claim(UUID.randomUUID(), 5);
        assertTrue(lease.isPresent());
        assertEquals(baseline == 10 ? legacyAtTen : run, lease.get().run());
        if (baseline == 10)
          assertEquals(
              "RUNNING",
              admin.queryForObject(
                  "SELECT status FROM connection_sync_runs WHERE id=?", String.class, run));
      }
      assertEquals(
          18,
          admin.queryForObject(
              "SELECT max(version::int) FROM flyway_schema_history WHERE success", Integer.class));
      System.out.println(
          "UPGRADE V"
              + baseline
              + " -> V18: baseline fingerprints preserved; legacy recovery verified");
    } finally {
      // Only the database this case successfully created is removed.
      control.execute("DROP DATABASE " + database + " WITH (FORCE)");
    }
  }

  @org.junit.jupiter.api.Test
  void reproducesPreV15OwnerDeactivationRaceOnDisposableHistoricalSchema() throws Exception {
    assertEquals("true", System.getenv("TRACE_TEST_ALLOW_RESET"));
    String base = System.getenv("TRACE_TEST_ADMIN_URL");
    assertFalse(base.matches(".*:(5433|55433)/.*"));
    var control =
        new JdbcTemplate(
            new DriverManagerDataSource(base, "trace_test_admin", "trace-test-admin-only"));
    String name = "trace_hardening_owner_repro";
    control.execute("CREATE DATABASE " + name);
    String url = base.substring(0, base.lastIndexOf('/') + 1) + name;
    var ownerSource = new DriverManagerDataSource(url, "trace_test_admin", "trace-test-admin-only");
    var admin = new JdbcTemplate(ownerSource);
    var threads = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      admin.execute(
          "DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='trace_migrator') THEN"
              + " CREATE ROLE trace_migrator LOGIN PASSWORD 'trace-test-migration-only' NOSUPERUSER"
              + " NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; END IF; IF NOT EXISTS(SELECT 1"
              + " FROM pg_roles WHERE rolname='trace_runtime') THEN CREATE ROLE trace_runtime LOGIN"
              + " PASSWORD 'trace-test-runtime-only' NOSUPERUSER NOCREATEDB NOCREATEROLE"
              + " NOREPLICATION NOBYPASSRLS; END IF; END $$");

      admin.execute(
          "REVOKE CREATE ON SCHEMA public FROM PUBLIC; GRANT USAGE,CREATE ON SCHEMA public TO"
              + " trace_migrator; GRANT USAGE ON SCHEMA public TO trace_runtime");
      var migrator =
          new DriverManagerDataSource(url, "trace_migrator", "trace-test-migration-only");
      Flyway.configure().dataSource(migrator).target("14").load().migrate();
      UUID org = UUID.randomUUID(),
          owner = UUID.randomUUID(),
          target = UUID.randomUUID(),
          platform = UUID.randomUUID();
      admin.update(
          "INSERT INTO organizations(id,name,slug) VALUES(?,'Owner race','owner-race')", org);
      admin.update(
          "INSERT INTO users(id,email,display_name,platform_role)"
              + " VALUES(?,'owner@example.test','Owner','USER'),(?,'target@example.test','Target','USER'),(?,'platform@example.test','Platform','PLATFORM_ADMIN')",
          owner,
          target,
          platform);
      admin.update(
          "INSERT INTO organization_memberships(organization_id,user_id,role)"
              + " VALUES(?,?,'OWNER'),(?,?,'MEMBER')",
          org,
          owner,
          org,
          target);
      try (var held = ownerSource.getConnection()) {
        held.setAutoCommit(false);
        var context = held.prepareStatement("SELECT set_config('trace.organization_id',?,true)");
        context.setString(1, org.toString());
        context.execute();
        var lock = held.prepareStatement("SELECT id FROM organizations WHERE id=? FOR UPDATE");
        lock.setObject(1, org);
        lock.execute();
        var pid = new java.util.concurrent.atomic.AtomicInteger();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var disable =
            threads.submit(
                () -> {
                  try (var connection = ownerSource.getConnection()) {
                    try (var rs =
                        connection.createStatement().executeQuery("SELECT pg_backend_pid()")) {
                      rs.next();
                      pid.set(rs.getInt(1));
                    }
                    entered.countDown();
                    var call = connection.prepareStatement("SELECT trace_user_state(?,?,false)");
                    call.setObject(1, platform);
                    call.setObject(2, target);
                    call.execute();
                  }
                  return true;
                });
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
        boolean blocked = false;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline && !disable.isDone()) {
          blocked =
              Boolean.TRUE.equals(
                  admin.queryForObject(
                      "SELECT wait_event_type='Lock' FROM pg_stat_activity WHERE pid=?",
                      Boolean.class,
                      pid.get()));
          if (blocked) break;
          Thread.sleep(10);
        }
        assertTrue(
            blocked,
            "Historical deactivation reached the organization row lock after its first owner"
                + " check");
        var transfer = held.prepareStatement("SELECT trace_transfer_organization(?,?)");
        transfer.setObject(1, owner);
        transfer.setObject(2, target);
        transfer.execute();
        held.commit();
        assertTrue(disable.get(3, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(
            0,
            admin.queryForObject(
                "SELECT count(*) FROM organization_memberships m JOIN users u ON u.id=m.user_id"
                    + " WHERE m.organization_id=? AND m.role='OWNER' AND m.status='ACTIVE' AND"
                    + " u.active",
                Integer.class,
                org));
        System.out.println(
            "REPRODUCED V14: concurrent deactivation and ownership transfer can leave zero active"
                + " owners; V15 serialization regression covers the correction");
      }
      Flyway.configure().dataSource(migrator).load().migrate();
      assertEquals(15, admin.queryForObject("SELECT trace_schema_version()", Integer.class));
    } finally {
      threads.shutdownNow();
      control.execute("DROP DATABASE " + name + " WITH (FORCE)");
    }
  }

  private String fingerprint(JdbcTemplate jdbc, String table, String columns) {
    return jdbc.queryForObject(
        "SELECT count(*)::text||':'||COALESCE(md5(string_agg(row_data::text,'' ORDER BY"
            + " row_data::text)),'empty') FROM (SELECT row("
            + columns
            + ") AS row_data FROM "
            + table
            + ") q",
        String.class);
  }
}
