package com.trace.backend.repository;

import com.trace.backend.dto.request.MetadataRequests.*;
import com.trace.backend.tenancy.TenantContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class FormulaRepository {
  private final JdbcTemplate jdbc;
  private final RegistryRepository guard;
  private final ObjectMapper json;

  public FormulaRepository(JdbcTemplate jdbc, RegistryRepository guard, ObjectMapper json) {
    this.jdbc = jdbc;
    this.guard = guard;
    this.json = json;
  }

  public List<Map<String, Object>> list(TenantContext t) {
    guard.requireTransaction(t);
    return jdbc.queryForList(
        "SELECT * FROM formulas WHERE organization_id=? ORDER BY name,id LIMIT 100",
        t.organizationId());
  }

  public Map<String, Object> get(TenantContext t, UUID id, boolean lock) {
    guard.requireTransaction(t);
    return RegistryRepository.one(
        jdbc.queryForList(
            "SELECT * FROM formulas WHERE organization_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
            t.organizationId(),
            id));
  }

  public UUID create(TenantContext t, Formula r) {
    guard.requireTeamWrite(t, r.ownerTeamId());
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " formulas(id,organization_id,owner_team_id,name,description,category,language,asset_id)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        id,
        t.organizationId(),
        r.ownerTeamId(),
        r.name(),
        r.description() == null ? "" : r.description(),
        r.category(),
        r.language(),
        r.assetId());
    audit(t, id, "FORMULA_CREATED");
    return id;
  }

  public List<Map<String, Object>> versions(TenantContext t, UUID id) {
    guard.requireTransaction(t);
    return jdbc.queryForList(
        "SELECT v.*,COALESCE((SELECT jsonb_agg(c.column_id) FROM formula_column_dependencies c"
            + " WHERE c.organization_id=v.organization_id AND c.version_id=v.id),'[]'::jsonb)::text"
            + " AS referenced_columns,COALESCE((SELECT jsonb_agg(d.asset_id) FROM"
            + " formula_dependencies d WHERE d.organization_id=v.organization_id AND"
            + " d.version_id=v.id),'[]'::jsonb)::text AS referenced_assets FROM formula_versions v"
            + " WHERE v.organization_id=? AND v.formula_id=? ORDER BY version DESC LIMIT 100",
        t.organizationId(),
        id);
  }

  public UUID version(TenantContext t, UUID formula, FormulaVersion r, Object validation) {
    guard.requireTransaction(t);
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " formula_versions(id,organization_id,formula_id,version,expression,state,dialect,validation,created_by)"
            + " SELECT ?,?,?,COALESCE(max(version),0)+1,?,?,?,?::jsonb,? FROM formula_versions"
            + " WHERE organization_id=? AND formula_id=?",
        id,
        t.organizationId(),
        formula,
        r.expression(),
        "DRAFT",
        r.dialect() == null ? "snowflake" : r.dialect(),
        json.writeValueAsString(validation),
        t.userId(),
        t.organizationId(),
        formula);
    if (r.referencedAssets() != null)
      for (UUID asset : new HashSet<>(r.referencedAssets()))
        jdbc.update(
            "INSERT INTO formula_dependencies(organization_id,version_id,asset_id) VALUES(?,?,?)",
            t.organizationId(),
            id,
            asset);
    if (r.referencedColumns() != null)
      for (UUID column : new HashSet<>(r.referencedColumns()))
        jdbc.update(
            "INSERT INTO formula_column_dependencies(organization_id,version_id,column_id)"
                + " VALUES(?,?,?)",
            t.organizationId(),
            id,
            column);
    if ("REGISTERED".equals(r.state()))
      jdbc.update("UPDATE formula_versions SET state='REGISTERED' WHERE organization_id=? AND id=?",
          t.organizationId(), id);
    audit(t, formula, "FORMULA_VERSION_CREATED");
    return id;
  }

  public long versionCount(TenantContext t, UUID formula) {
    guard.requireTransaction(t);
    return jdbc.queryForObject(
        "SELECT count(*) FROM formula_versions WHERE organization_id=? AND formula_id=?",
        Long.class,
        t.organizationId(),
        formula);
  }

  public Map<String, Object> snapshot(TenantContext t, UUID formula, UUID version) {
    guard.requireTransaction(t);
    return RegistryRepository.one(
        jdbc.queryForList(
            "SELECT v.*,COALESCE((SELECT jsonb_agg(d.asset_id) FROM formula_dependencies d WHERE"
                + " d.organization_id=v.organization_id AND d.version_id=v.id),'[]')::text AS"
                + " referenced_assets,COALESCE((SELECT jsonb_agg(d.column_id) FROM"
                + " formula_column_dependencies d WHERE d.organization_id=v.organization_id AND"
                + " d.version_id=v.id),'[]')::text AS referenced_columns FROM formula_versions v"
                + " WHERE v.organization_id=? AND v.formula_id=? AND v.id=?",
            t.organizationId(),
            formula,
            version));
  }

  public void editDraft(
      TenantContext t, UUID formula, UUID version, DraftEdit edit, Object validation) {
    guard.requireTransaction(t);
    var old = snapshot(t, formula, version);
    if (!"DRAFT".equals(old.get("state"))
        || !Objects.equals(old.get("revision"), edit.expectedRevision()))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "Draft changed or snapshot is registered; reload before editing");
    jdbc.update(
        "DELETE FROM formula_dependencies WHERE organization_id=? AND version_id=?",
        t.organizationId(),
        version);
    jdbc.update(
        "DELETE FROM formula_column_dependencies WHERE organization_id=? AND version_id=?",
        t.organizationId(),
        version);
    var r = edit.definition();
    if (r.referencedAssets() != null)
      for (UUID a : new HashSet<>(r.referencedAssets()))
        jdbc.update(
            "INSERT INTO formula_dependencies(organization_id,version_id,asset_id) VALUES(?,?,?)",
            t.organizationId(),
            version,
            a);
    if (r.referencedColumns() != null)
      for (UUID c : new HashSet<>(r.referencedColumns()))
        jdbc.update(
            "INSERT INTO formula_column_dependencies(organization_id,version_id,column_id)"
                + " VALUES(?,?,?)",
            t.organizationId(),
            version,
            c);
    if (jdbc.update(
            "UPDATE formula_versions SET"
                + " expression=?,dialect=?,state=?,validation=?::jsonb,revision=revision+1,updated_at=now(),updated_by=?"
                + " WHERE organization_id=? AND formula_id=? AND id=? AND state='DRAFT' AND"
                + " revision=?",
            r.expression(),
            r.dialect() == null ? "snowflake" : r.dialect(),
            r.state(),
            json.writeValueAsString(validation),
            t.userId(),
            t.organizationId(),
            formula,
            version,
            edit.expectedRevision())
        != 1) throw new IllegalStateException("Draft revision lost");
    audit(t, formula, "FORMULA_DRAFT_EDITED");
  }

  public UUID column(TenantContext t, UUID id) {
    guard.requireTransaction(t);
    return (UUID)
        RegistryRepository.one(
                jdbc.queryForList(
                    "SELECT asset_id FROM asset_columns WHERE organization_id=? AND id=?",
                    t.organizationId(),
                    id))
            .get("asset_id");
  }

  public Map<String, Object> formulaForVersion(TenantContext t, UUID version) {
    guard.requireTransaction(t);
    return RegistryRepository.one(
        jdbc.queryForList(
            "SELECT f.*,v.state FROM formulas f JOIN formula_versions v ON"
                + " v.organization_id=f.organization_id AND v.formula_id=f.id WHERE"
                + " f.organization_id=? AND v.id=?",
            t.organizationId(),
            version));
  }

  public void audit(TenantContext t, UUID id, String event) {
    jdbc.queryForObject(
        "SELECT trace_audit(?,?,?,'FORMULA',?,'SUCCESS')",
        Object.class,
        t.userId(),
        t.organizationId(),
        event,
        id);
  }
}
