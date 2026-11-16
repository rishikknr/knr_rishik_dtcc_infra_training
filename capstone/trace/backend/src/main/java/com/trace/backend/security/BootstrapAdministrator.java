package com.trace.backend.security;

/**
 * Deliberate operator command: no Spring context, web server, runtime identity or HTTP endpoint.
 */
public final class BootstrapAdministrator {
  private BootstrapAdministrator() {}

  public static void run() {
    var console = System.console();
    if (console == null)
      throw new IllegalStateException("Bootstrap requires an interactive terminal");
    String email = PasswordPolicy.email(console.readLine("Administrator email: "));
    String name = PasswordPolicy.label(console.readLine("Display name: "), 150);
    char[] secret = console.readPassword("Administrator password (12–72 UTF-8 bytes): ");
    char[] migration = console.readPassword("Migration-role database password: ");
    try {
      String password = new PasswordPolicy(12).validate(new String(secret));
      String hash =
          new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode(password);
      String url =
          System.getenv()
              .getOrDefault("TRACE_DATABASE_URL", "jdbc:postgresql://localhost:5433/trace");
      try (var db =
              java.sql.DriverManager.getConnection(url, "trace_migrator", new String(migration));
          var statement = db.prepareStatement("SELECT trace_bootstrap_admin(?,?,?)")) {
        statement.setString(1, email);
        statement.setString(2, name);
        statement.setString(3, hash);
        statement.execute();
        console.printf("Administrator provisioned. Sign in through Trace.%n");
      } catch (java.sql.SQLException e) {
        throw new IllegalStateException(
            "Bootstrap rejected. Check migrations, operator credentials, email uniqueness, and"
                + " whether bootstrap has already completed.");
      }
    } finally {
      java.util.Arrays.fill(secret, '\0');
      java.util.Arrays.fill(migration, '\0');
    }
  }
}
