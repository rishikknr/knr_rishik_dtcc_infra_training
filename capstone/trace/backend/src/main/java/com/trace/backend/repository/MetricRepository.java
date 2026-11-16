package com.trace.backend.repository;

import com.trace.backend.dto.request.MetadataRequests.*;
import com.trace.backend.tenancy.TenantContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MetricRepository {
  private final JdbcTemplate jdbc;
  private final RegistryRepository guard;

  public MetricRepository(JdbcTemplate jdbc, RegistryRepository guard) {
    this.jdbc = jdbc;
    this.guard = guard;
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }

  public List<Map<String, Object>> list(TenantContext t) {
    guard.requireTransaction(t);
    return jdbc.queryForList(
        "SELECT * FROM metrics WHERE organization_id=? ORDER BY name,id LIMIT 100",
        t.organizationId());
  }

  public Map<String, Object> get(TenantContext t, UUID id, boolean lock) {
    guard.requireTransaction(t);
    return RegistryRepository.one(
        jdbc.queryForList(
            "SELECT * FROM metrics WHERE organization_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
            t.organizationId(),
            id));
  }

  public UUID create(TenantContext t, Metric r) {
    guard.requireTeamWrite(t, r.ownerTeamId());
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " metrics(id,organization_id,owner_team_id,name,business_key,description,unit,aggregation,grain,business_owner,technical_owner)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        id,
        t.organizationId(),
        r.ownerTeamId(),
        r.name(),
        r.businessKey(),
        safe(r.description()),
        safe(r.unit()),
        safe(r.aggregation()),
        safe(r.grain()),
        safe(r.businessOwner()),
        safe(r.technicalOwner()));
    audit(t, id, "METRIC_CREATED");
    return id;
  }

  public void update(TenantContext t, UUID id, MetricEdit edit) {
    guard.requireTransaction(t);
    var r = edit.definition();
    int count =
        jdbc.update(
            "UPDATE metrics SET"
                + " name=?,description=?,unit=?,aggregation=?,grain=?,business_owner=?,technical_owner=?,revision=revision+1"
                + " WHERE organization_id=? AND id=? AND revision=?",
            r.name(),
            safe(r.description()),
            safe(r.unit()),
            safe(r.aggregation()),
            safe(r.grain()),
            safe(r.businessOwner()),
            safe(r.technicalOwner()),
            t.organizationId(),
            id,
            edit.expectedRevision());
    if (count != 1)
      throw new com.trace.backend.exception.ApiException(
          org.springframework.http.HttpStatus.CONFLICT,
          "REVISION_CONFLICT",
          "This metric changed. Reload before saving.");
    audit(t, id, "METRIC_UPDATED");
  }

  public Map<String, Object> mapping(TenantContext t, UUID id) {
    guard.requireTransaction(t);
    return RegistryRepository.one(
        jdbc.queryForList(
            "SELECT * FROM report_mappings WHERE organization_id=? AND id=?",
            t.organizationId(),
            id));
  }

  public void revoke(TenantContext t, UUID id) {
    guard.requireTransaction(t);
    if (jdbc.update(
            "UPDATE report_mappings SET active=false,revoked_at=now() WHERE organization_id=? AND"
                + " id=? AND active",
            t.organizationId(),
            id)
        > 0) audit(t, id, "METRIC_USAGE_REVOKED");
  }

  public List<Map<String, Object>> versions(TenantContext t, UUID id) {
    guard.requireTransaction(t);
    return jdbc.queryForList(
        "SELECT * FROM metric_versions WHERE organization_id=? AND metric_id=? ORDER BY version"
            + " DESC LIMIT 100",
        t.organizationId(),
        id);
  }

  public UUID version(TenantContext t, UUID id, UUID formulaVersion) {
    guard.requireTransaction(t);
    UUID version = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " metric_versions(id,organization_id,metric_id,version,formula_version_id,definition_snapshot)"
            + " SELECT ?,?,?,COALESCE(max(version),0)+1,?,(SELECT to_jsonb(m)-'organization_id'"
            + " FROM metrics m WHERE m.organization_id=? AND m.id=?) FROM metric_versions WHERE"
            + " organization_id=? AND metric_id=?",
        version,
        t.organizationId(),
        id,
        formulaVersion,
        t.organizationId(),
        id,
        t.organizationId(),
        id);
    audit(t, id, "METRIC_VERSION_REGISTERED");
    return version;
  }

  public Map<String, Object> metricForVersion(TenantContext t, UUID version) {
    guard.requireTransaction(t);
    return RegistryRepository.one(
        jdbc.queryForList(
            "SELECT m.* FROM metrics m JOIN metric_versions v ON"
                + " v.organization_id=m.organization_id AND v.metric_id=m.id WHERE"
                + " m.organization_id=? AND v.id=?",
            t.organizationId(),
            version));
  }

  public UUID mapping(TenantContext t, MetricMapping r) {
    guard.requireTransaction(t);
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " report_mappings(id,organization_id,metric_version_id,asset_id,evidence_ref,created_by)"
            + " VALUES(?,?,?,?,?,?)",
        id,
        t.organizationId(),
        r.metricVersionId(),
        r.assetId(),
        r.evidenceRef(),
        t.userId());
    audit(t, id, "METRIC_USAGE_DECLARED");
    return id;
  }

  public List<Map<String, Object>> mappings(TenantContext t) {
    guard.requireTransaction(t);
    return jdbc.queryForList(
        "SELECT r.*,a.name AS asset_name,a.asset_type,m.name AS metric_name,v.version FROM"
            + " report_mappings r JOIN assets a ON a.organization_id=r.organization_id AND"
            + " a.id=r.asset_id JOIN metric_versions v ON v.organization_id=r.organization_id AND"
            + " v.id=r.metric_version_id JOIN metrics m ON m.organization_id=v.organization_id AND"
            + " m.id=v.metric_id WHERE r.organization_id=? ORDER BY r.created_at DESC,r.id LIMIT"
            + " 100",
        t.organizationId());
  }

  public void audit(TenantContext t, UUID id, String event) {
    jdbc.queryForObject(
        "SELECT trace_audit(?,?,?,'METRIC',?,'SUCCESS')",
        Object.class,
        t.userId(),
        t.organizationId(),
        event,
        id);
  }
}
