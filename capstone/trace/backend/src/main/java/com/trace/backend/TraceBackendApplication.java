package com.trace.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TraceBackendApplication {

  public static void main(String[] args) {
    if (java.util.Arrays.asList(args).contains("--bootstrap-admin")) {
      com.trace.backend.security.BootstrapAdministrator.run();
      return;
    }
    if (java.util.Arrays.asList(args).contains("--migrate-only")) {
      com.trace.backend.config.DatabaseMigrationCommand.run();
      return;
    }
    SpringApplication.run(TraceBackendApplication.class, args);
  }
}
