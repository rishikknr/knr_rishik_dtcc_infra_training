package com.trace.backend.service;

import com.trace.backend.dto.request.MetadataRequests.*;
import com.trace.backend.dto.response.IdResponse;
import com.trace.backend.repository.*;
import com.trace.backend.tenancy.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MetricService {
  private final TenantTransactions tenants;
  private final MetricRepository repository;
  private final FormulaRepository formulas;
  private final ManualMetadataRepository assets;
  private final RegistryRepository guard;

  public MetricService(
      TenantTransactions tenants,
      MetricRepository repository,
      FormulaRepository formulas,
      ManualMetadataRepository assets,
      RegistryRepository guard) {
    this.tenants = tenants;
    this.repository = repository;
    this.formulas = formulas;
    this.assets = assets;
    this.guard = guard;
  }

  public List<Map<String, Object>> list() {
    return tenants.execute(repository::list);
  }

  public Map<String, Object> get(UUID id) {
    return tenants.execute(
        t -> {
          var m = repository.get(t, id, false);
          m.put("versions", repository.versions(t, id));
          return m;
        });
  }

  private void validate(Metric r) {
    if (r == null) ConnectionService.invalid("Metric definition required");
    ManualMetadataService.text(r.name(), 255, "Name");
    ManualMetadataService.text(r.businessKey(), 100, "Business key");
    if (!r.businessKey().matches("[A-Z][A-Z0-9_]{0,99}"))
      ConnectionService.invalid("Business key must use uppercase letters, digits and underscores");
    ManualMetadataService.optional(r.description(), 4000);
    ManualMetadataService.optional(r.unit(), 100);
    ManualMetadataService.optional(r.aggregation(), 100);
    ManualMetadataService.optional(r.grain(), 255);
    ManualMetadataService.optional(r.businessOwner(), 150);
    ManualMetadataService.optional(r.technicalOwner(), 150);
  }

  public IdResponse create(Metric r) {
    validate(r);
    return tenants.execute(t -> new IdResponse(repository.create(t, r)));
  }

  public Map<String, Object> update(UUID id, MetricEdit edit) {
    validate(edit.definition());
    return tenants.execute(
        t -> {
          var old = repository.get(t, id, true);
          guard.requireTeamWrite(t, (UUID) old.get("owner_team_id"));
          var r = edit.definition();
          if (!Objects.equals(old.get("business_key"), r.businessKey())
              || !Objects.equals(old.get("owner_team_id"), r.ownerTeamId()))
            ConnectionService.invalid("Metric business key and owning team are stable identities");
          repository.update(t, id, edit);
          return repository.get(t, id, false);
        });
  }

  public Map<String, Boolean> revoke(UUID id) {
    return tenants.execute(
        t -> {
          var mapping = repository.mapping(t, id);
          var metric = repository.metricForVersion(t, (UUID) mapping.get("metric_version_id"));
          guard.requireTeamWrite(t, (UUID) metric.get("owner_team_id"));
          var asset = assets.asset(t, (UUID) mapping.get("asset_id"));
          guard.requireTeamWrite(t, (UUID) asset.get("owner_team_id"));
          repository.revoke(t, id);
          return Map.of("revoked", true);
        });
  }

  public IdResponse version(UUID id, MetricVersion r) {
    return tenants.execute(
        t -> {
          var m = repository.get(t, id, true);
          guard.requireTeamWrite(t, (UUID) m.get("owner_team_id"));
          var f = formulas.formulaForVersion(t, r.formulaVersionId());
          guard.requireTeamWrite(t, (UUID) f.get("owner_team_id"));
          if (!"REGISTERED".equals(f.get("state")))
            ConnectionService.invalid("Select an immutable REGISTERED formula version");
          return new IdResponse(repository.version(t, id, r.formulaVersionId()));
        });
  }

  public List<Map<String, Object>> mappings() {
    return tenants.execute(repository::mappings);
  }

  public IdResponse mapping(MetricMapping r) {
    ManualMetadataService.text(r.evidenceRef(), 2000, "Evidence reference");
    return tenants.execute(
        t -> {
          var m = repository.metricForVersion(t, r.metricVersionId());
          guard.requireTeamWrite(t, (UUID) m.get("owner_team_id"));
          var a = assets.asset(t, r.assetId());
          guard.requireTeamWrite(t, (UUID) a.get("owner_team_id"));
          if (!Set.of("MEASURE", "REPORT", "VISUAL", "SEMANTIC_MODEL")
              .contains(a.get("asset_type")))
            ConnectionService.invalid("Metric usage must target a reporting asset or measure");
          return new IdResponse(repository.mapping(t, r));
        });
  }
}
