package com.trace.backend.service;

import com.trace.backend.dto.request.MetadataRequests.*;
import com.trace.backend.dto.response.IdResponse;
import com.trace.backend.integration.processor.IntegrationProcessor;
import com.trace.backend.repository.*;
import com.trace.backend.tenancy.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class FormulaService {
  private final TenantTransactions tenants;
  private final FormulaRepository repository;
  private final ManualMetadataRepository assets;
  private final RegistryRepository guard;
  private final IntegrationProcessor processor;

  public FormulaService(
      TenantTransactions tenants,
      FormulaRepository repository,
      ManualMetadataRepository assets,
      RegistryRepository guard,
      IntegrationProcessor processor) {
    this.tenants = tenants;
    this.repository = repository;
    this.assets = assets;
    this.guard = guard;
    this.processor = processor;
  }

  public List<Map<String, Object>> list() {
    return tenants.execute(repository::list);
  }

  public Map<String, Object> get(UUID id) {
    return tenants.execute(
        t -> {
          var f = repository.get(t, id, false);
          f.put("versions", repository.versions(t, id));
          f.put("versionsTotal", repository.versionCount(t, id));
          return f;
        });
  }

  public IdResponse create(Formula r) {
    ManualMetadataService.text(r.name(), 255, "Name");
    ManualMetadataService.optional(r.description(), 4000);
    if (!Set.of("SQL", "DAX", "BUSINESS_RULE").contains(r.language() == null ? "" : r.language())
        || !Set.of(
                "SQL_TRANSFORMATION",
                "COLUMN_FORMULA",
                "BUSINESS_METRIC",
                "DAX_MEASURE",
                "BUSINESS_RULE")
            .contains(r.category() == null ? "" : r.category()))
      ConnectionService.invalid("Choose a supported formula category and language");
    if (("SQL_TRANSFORMATION".equals(r.category()) && !"SQL".equals(r.language()))
        || ("DAX_MEASURE".equals(r.category()) && !"DAX".equals(r.language())))
      ConnectionService.invalid("Formula category and language do not match");
    return tenants.execute(
        t -> {
          guard.requireTeamWrite(t, r.ownerTeamId());
          if (r.assetId() != null) {
            var a = assets.asset(t, r.assetId());
            guard.requireTeamWrite(t, (UUID) a.get("owner_team_id"));
          }
          return new IdResponse(repository.create(t, r));
        });
  }

  public Object validate(Validation r) {
    tenants.execute(t -> true);
    ManualMetadataService.text(r.expression(), 100000, "Expression");
    if ("SQL".equals(r.language()))
      return processor.call(
          "sql/validate",
          Map.of(
              "expression",
              r.expression(),
              "dialect",
              r.dialect() == null ? "snowflake" : r.dialect()));
    if (!Set.of("DAX", "BUSINESS_RULE").contains(r.language() == null ? "" : r.language()))
      ConnectionService.invalid("Unsupported expression language");
    return Map.of(
        "status",
        "STORED_TEXT",
        "warnings",
        List.of("Text and size checks only; syntax and execution are not verified"),
        "inputs",
        List.of(),
        "outputs",
        List.of());
  }

  public IdResponse version(UUID id, FormulaVersion r) {
    var identity =
        tenants.execute(
            t -> {
              var f = repository.get(t, id, false);
              guard.requireTeamWrite(t, (UUID) f.get("owner_team_id"));
              return List.of(t.organizationId(), t.userId(), f.get("language"));
            });
    if (!Set.of("DRAFT", "REGISTERED").contains(r.state() == null ? "" : r.state()))
      ConnectionService.invalid("Choose DRAFT or REGISTERED; registration does not imply approval");
    if (r.referencedAssets() != null && r.referencedAssets().size() > 100)
      ConnectionService.invalid("Too many referenced assets");
    if (r.referencedColumns() != null && r.referencedColumns().size() > 100)
      ConnectionService.invalid("Too many referenced columns");
    Object validation =
        validate(new Validation(r.expression(), (String) identity.get(2), r.dialect()));
    requireRegistrable(r, validation);
    return tenants.execute(
        t -> {
          if (!identity.subList(0, 2).equals(List.of(t.organizationId(), t.userId())))
            throw RegistryRepository.missing();
          var f = repository.get(t, id, true);
          guard.requireTeamWrite(t, (UUID) f.get("owner_team_id"));
          if (r.referencedAssets() != null)
            for (UUID asset : r.referencedAssets()) assets.asset(t, asset);
          if (r.referencedColumns() != null)
            for (UUID column : r.referencedColumns()) repository.column(t, column);
          return new IdResponse(repository.version(t, id, r, validation));
        });
  }

  public Map<String, Object> snapshot(UUID formula, UUID version) {
    return tenants.execute(
        t -> {
          repository.get(t, formula, false);
          return repository.snapshot(t, formula, version);
        });
  }

  public Map<String, Object> editDraft(UUID formula, UUID version, DraftEdit edit) {
    if (edit.definition() == null) ConnectionService.invalid("Draft definition required");
    var r = edit.definition();
    var identity =
        tenants.execute(
            t -> {
              var f = repository.get(t, formula, false);
              guard.requireTeamWrite(t, (UUID) f.get("owner_team_id"));
              var old = repository.snapshot(t, formula, version);
              if (!"DRAFT".equals(old.get("state")))
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Registered snapshots are immutable");
              return List.of(t.organizationId(), t.userId(), f.get("language"));
            });
    if (!Set.of("DRAFT", "REGISTERED").contains(r.state() == null ? "" : r.state())
        || (r.referencedAssets() != null && r.referencedAssets().size() > 100)
        || (r.referencedColumns() != null && r.referencedColumns().size() > 100))
      ConnectionService.invalid("Invalid draft state or dependency limit");
    Object result = validate(new Validation(r.expression(), (String) identity.get(2), r.dialect()));
    requireRegistrable(r, result);
    return tenants.execute(
        t -> {
          if (!identity.subList(0, 2).equals(List.of(t.organizationId(), t.userId())))
            throw RegistryRepository.missing();
          var f = repository.get(t, formula, true);
          guard.requireTeamWrite(t, (UUID) f.get("owner_team_id"));
          if (r.referencedAssets() != null) for (UUID a : r.referencedAssets()) assets.asset(t, a);
          if (r.referencedColumns() != null)
            for (UUID c : r.referencedColumns()) repository.column(t, c);
          repository.editDraft(t, formula, version, edit, result);
          return repository.snapshot(t, formula, version);
        });
  }

  private void requireRegistrable(FormulaVersion r, Object validation) {
    if (!"REGISTERED".equals(r.state())) return;
    String status =
        validation instanceof tools.jackson.databind.JsonNode n
            ? n.path("status").asText()
            : String.valueOf(((Map<?, ?>) validation).get("status"));
    if (Set.of("INVALID", "UNSUPPORTED", "UNRESOLVED").contains(status))
      ConnectionService.invalid(
          "Resolve invalid, unsupported or unresolved SQL before registration. Save a draft to"
              + " retain work.");
  }
}
