package com.trace.backend.service;

import com.trace.backend.dto.request.NewConnection;
import com.trace.backend.dto.response.*;
import com.trace.backend.mapper.RegistryMapper;
import com.trace.backend.repository.*;
import com.trace.backend.tenancy.TenantTransactions;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RegistryService {
  private final TenantTransactions tenants;
  private final RegistryRepository registry;
  private final SourceConnectionRepository connections;

  public RegistryService(
      TenantTransactions tenants,
      RegistryRepository registry,
      SourceConnectionRepository connections) {
    this.tenants = tenants;
    this.registry = registry;
    this.connections = connections;
  }

  public RegistryStatusResponse status() {
    return tenants.execute(
        t ->
            new RegistryStatusResponse(
                "Trace", "connected", registry.countAssets(t), registry.organizationName(t)));
  }

  public List<ConnectionResponse> connections() {
    return tenants.execute(
        t -> connections.connections(t).stream().map(RegistryMapper::connection).toList());
  }

  public IdResponse create(NewConnection r) {
    if (r.name() == null
        || r.name().isBlank()
        || r.name().length() > 150
        || r.ownerTeamId() == null
        || r.name().chars().anyMatch(c -> c < 32))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name and owner team required");
    return tenants.execute(
        t -> new IdResponse(connections.createConnection(t, r.name().trim(), r.ownerTeamId())));
  }
}
