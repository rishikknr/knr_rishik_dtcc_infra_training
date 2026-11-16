package com.trace.backend.config;

/** Operator migration command: no HTTP server or security context is started. */
public final class DatabaseMigrationCommand {
  private DatabaseMigrationCommand() {}

  public static void run() {
    String url =
        System.getenv()
            .getOrDefault("TRACE_DATABASE_URL", "jdbc:postgresql://localhost:5433/trace");
    String migration = required("TRACE_MIGRATION_PASSWORD");
    String runtime = required("TRACE_RUNTIME_PASSWORD");
    if (migration.equals(runtime))
      throw new IllegalArgumentException("Migration and runtime secrets must differ");
    org.flywaydb.core.Flyway.configure()
        .dataSource(url, "trace_migrator", migration)
        .locations("classpath:db/migration")
        .load()
        .migrate();
    var source =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            url, "trace_runtime", runtime);
    new com.trace.backend.tenancy.RuntimeDatabaseGuard(
            new org.springframework.jdbc.core.JdbcTemplate(source))
        .afterPropertiesSet();
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Export " + name + " first");
    return value;
  }
}
