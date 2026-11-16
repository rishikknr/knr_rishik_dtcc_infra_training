package com.trace.backend.dto.response;

public record RegistryStatusResponse(
    String application,
    String database,
    long registered_assets,
    String organization,
    int integrationVersion) {
  public RegistryStatusResponse(
      String application, String database, long registered_assets, String organization) {
    this(application, database, registered_assets, organization, 3);
  }
}
