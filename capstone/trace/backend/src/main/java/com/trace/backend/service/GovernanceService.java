package com.trace.backend.service;

import com.trace.backend.exception.ApiException;
import com.trace.backend.repository.RegistryRepository;
import com.trace.backend.tenancy.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class GovernanceService {
  private final TenantTransactions tenants;
  private final JdbcTemplate jdbc;
  private final RegistryRepository guard;
  private final ObjectMapper json;

  public GovernanceService(
      TenantTransactions tenants, JdbcTemplate jdbc, RegistryRepository guard, ObjectMapper json) {
    this.tenants = tenants;
    this.jdbc = jdbc;
    this.guard = guard;
    this.json = json;
  }

  public record Submit(String targetType, UUID targetId, String reason) {}

  public record Decision(String status, String reason, int expectedRevision) {}

  private boolean reviewer(TenantContext t, UUID team) {
    return Set.of("OWNER", "ADMIN").contains(t.organizationRole())
        || (!"VIEWER".equals(t.organizationRole()) && "OWNER".equals(t.teamRoles().get(team)));
  }

  private Map<String, Object> review(TenantContext t, UUID id, boolean lock) {
    return RegistryRepository.one(
        jdbc.queryForList(
            "SELECT"
                + " r.id,r.organization_id,r.target_type,r.target_id,r.owner_team_id,r.title,r.status,r.requester_id,r.reviewer_id,r.revision,r.created_at,r.updated_at,r.snapshot::text"
                + " AS snapshot,r.previous_snapshot::text AS previous_snapshot FROM"
                + " governance_reviews r WHERE organization_id=? AND id=?"
                + (lock ? " FOR UPDATE" : ""),
            t.organizationId(),
            id));
  }

  private String referencesSql() {
    return "COALESCE((SELECT jsonb_agg(fd.asset_id ORDER BY fd.asset_id) FROM formula_dependencies"
        + " fd WHERE fd.organization_id=fv.organization_id AND"
        + " fd.version_id=fv.id),'[]')::text AS referenced_assets,COALESCE((SELECT"
        + " jsonb_agg(cd.column_id ORDER BY cd.column_id) FROM formula_column_dependencies"
        + " cd WHERE cd.organization_id=fv.organization_id AND"
        + " cd.version_id=fv.id),'[]')::text AS referenced_columns";
  }

  private Map<String, Object> version(TenantContext t, String kind, UUID id) {
    if ("FORMULA_VERSION".equals(kind))
      return RegistryRepository.one(
          jdbc.queryForList(
              "SELECT"
                  + " fv.id,fv.organization_id,fv.formula_id,fv.version,fv.expression,fv.state,fv.dialect,fv.validation::text"
                  + " AS validation,fv.created_by,fv.created_at,f.name,f.owner_team_id,f.language,f.description,"
                  + referencesSql()
                  + " FROM formula_versions fv"
                  + " JOIN formulas f ON f.organization_id=fv.organization_id AND"
                  + " f.id=fv.formula_id WHERE fv.organization_id=? AND fv.id=? AND"
                  + " fv.state='REGISTERED'",
              t.organizationId(),
              id));
    if ("METRIC_VERSION".equals(kind))
      return RegistryRepository.one(
          jdbc.queryForList(
              "SELECT"
                  + " mv.id,mv.organization_id,mv.metric_id,mv.version,mv.formula_version_id,mv.created_at,COALESCE(mv.definition_snapshot->>'name',m.name)"
                  + " AS name,m.owner_team_id,fv.expression,mv.definition_snapshot::text AS"
                  + " definition_snapshot,"
                  + referencesSql()
                  + " FROM metric_versions mv JOIN metrics m ON"
                  + " m.organization_id=mv.organization_id AND m.id=mv.metric_id JOIN"
                  + " formula_versions fv ON fv.organization_id=mv.organization_id AND"
                  + " fv.id=mv.formula_version_id WHERE mv.organization_id=? AND mv.id=?",
              t.organizationId(),
              id));
    ConnectionService.invalid("Select an immutable formula or metric version");
    return null;
  }

  public Map<String, Object> submit(Submit r) {
    ManualMetadataService.text(r.reason(), 4000, "Review rationale");
    if (r.targetId() == null) ConnectionService.invalid("Version required");
    return tenants.execute(
        t -> {
          var snapshot = version(t, r.targetType(), r.targetId());
          UUID team = (UUID) snapshot.get("owner_team_id");
          guard.requireTeamWrite(t, team);
          // Serialize concurrent submissions for the same version before checking the pending
          // queue.
          jdbc.queryForObject(
              "SELECT pg_advisory_xact_lock(hashtextextended(?::text,18))",
              Object.class,
              r.targetId().toString());
          if (!jdbc.queryForList(
                  "SELECT id FROM governance_reviews WHERE organization_id=? AND target_type=? AND"
                      + " target_id=? AND status IN"
                      + " ('SUBMITTED','UNDER_REVIEW','CHANGES_REQUESTED')",
                  t.organizationId(),
                  r.targetType(),
                  r.targetId())
              .isEmpty())
            throw new ApiException(
                HttpStatus.CONFLICT,
                "REVIEW_EXISTS",
                "This version already has a pending review. Open the existing request.");
          String table =
              "FORMULA_VERSION".equals(r.targetType()) ? "formula_versions" : "metric_versions";
          String parent = "FORMULA_VERSION".equals(r.targetType()) ? "formula_id" : "metric_id";
          var previous =
              jdbc.queryForList(
                  "SELECT v.id FROM "
                      + table
                      + " v WHERE v.organization_id=? AND v."
                      + parent
                      + "=? AND v.version<?"
                      + ("FORMULA_VERSION".equals(r.targetType())
                          ? " AND v.state='REGISTERED'"
                          : "")
                      + " ORDER BY version DESC LIMIT 1",
                  t.organizationId(),
                  snapshot.get(parent),
                  snapshot.get("version"));
          UUID id = UUID.randomUUID();
          jdbc.update(
              "INSERT INTO"
                  + " governance_reviews(id,organization_id,target_type,target_id,owner_team_id,title,snapshot,previous_snapshot,requester_id)"
                  + " VALUES(?,?,?,?,?,?,?::jsonb,?::jsonb,?)",
              id,
              t.organizationId(),
              r.targetType(),
              r.targetId(),
              team,
              snapshot.get("name") + " · v" + snapshot.get("version"),
              json.writeValueAsString(snapshot),
              previous.isEmpty()
                  ? null
                  : json.writeValueAsString(
                      version(t, r.targetType(), (UUID) previous.getFirst().get("id"))),
              t.userId());
          event(t, id, null, "SUBMITTED", r.reason());
          return Map.of("id", id);
        });
  }

  public Map<String, Object> list(String status, String mine, int limit, int offset) {
    if (limit < 1
        || limit > 100
        || offset < 0
        || offset > 1000000
        || !Set.of("", "SUBMITTED", "UNDER_REVIEW", "CHANGES_REQUESTED", "APPROVED", "REJECTED")
            .contains(status)
        || !Set.of("", "submitted", "reviews").contains(mine))
      ConnectionService.invalid("Invalid review filter");
    return tenants.executeSnapshot(
        t -> {
          var args = new ArrayList<Object>();
          args.add(t.organizationId());
          String where = " WHERE r.organization_id=?";
          if (!status.isBlank()) {
            where += " AND r.status=?";
            args.add(status);
          }
          if ("submitted".equals(mine)) {
            where += " AND r.requester_id=?";
            args.add(t.userId());
          }
          if ("reviews".equals(mine)) {
            where +=
                " AND r.requester_id<>? AND r.status IN"
                    + " ('SUBMITTED','UNDER_REVIEW','CHANGES_REQUESTED')";
            args.add(t.userId());
            if (!Set.of("OWNER", "ADMIN").contains(t.organizationRole())) {
              where +=
                  " AND EXISTS(SELECT 1 FROM team_memberships tm WHERE"
                      + " tm.organization_id=r.organization_id AND tm.team_id=r.owner_team_id AND"
                      + " tm.user_id=? AND tm.role='OWNER')";
              args.add(t.userId());
            }
            if ("VIEWER".equals(t.organizationRole())) where += " AND false";
          }
          long total =
              jdbc.queryForObject(
                  "SELECT count(*) FROM governance_reviews r" + where, Long.class, args.toArray());
          args.add(limit);
          args.add(offset);
          var rows =
              jdbc.queryForList(
                  "SELECT"
                      + " r.id,r.title,r.status,r.target_type,r.target_id,r.owner_team_id,r.requester_id,r.revision,r.created_at,r.updated_at,u.display_name"
                      + " AS requester_name,t.name AS owner_team_name FROM governance_reviews r"
                      + " CROSS JOIN LATERAL trace_profile(r.requester_id) u JOIN teams t ON"
                      + " t.organization_id=r.organization_id AND t.id=r.owner_team_id"
                      + where
                      + " ORDER BY r.updated_at DESC,r.id LIMIT ? OFFSET ?",
                  args.toArray());
          return Map.of("items", rows, "total", total, "hasMore", offset + rows.size() < total);
        });
  }

  public Map<String, Object> get(UUID id) {
    return tenants.executeSnapshot(
        t -> {
          var r = review(t, id, false);
          r.put(
              "events",
              jdbc.queryForList(
                  "SELECT e.*,u.display_name AS actor_name FROM governance_events e CROSS JOIN"
                      + " LATERAL trace_profile(e.actor_id) u WHERE e.organization_id=? AND"
                      + " e.review_id=? ORDER BY e.created_at,e.id",
                  t.organizationId(),
                  id));
          r.put(
              "canReview",
              reviewer(t, (UUID) r.get("owner_team_id"))
                  && !t.userId().equals(r.get("requester_id")));
          r.put(
              "canResubmit",
              t.userId().equals(r.get("requester_id")) && !"VIEWER".equals(t.organizationRole()));
          return r;
        });
  }

  public Map<String, Object> decide(UUID id, Decision d) {
    ManualMetadataService.text(d.reason(), 4000, "Decision reason");
    return tenants.execute(
        t -> {
          var r = review(t, id, true);
          String before = (String) r.get("status");
          if (((Number) r.get("revision")).intValue() != d.expectedRevision())
            throw new ApiException(
                HttpStatus.CONFLICT,
                "REVISION_CONFLICT",
                "This request changed. Reload before deciding.");
          boolean resubmit =
              "CHANGES_REQUESTED".equals(before)
                  && "SUBMITTED".equals(d.status())
                  && t.userId().equals(r.get("requester_id"));
          if (resubmit) guard.requireTeamWrite(t, (UUID) r.get("owner_team_id"));
          else if (!reviewer(t, (UUID) r.get("owner_team_id"))
              || t.userId().equals(r.get("requester_id")))
            throw new ApiException(
                HttpStatus.FORBIDDEN,
                "REVIEW_PERMISSION",
                "A different organization administrator or owning-team owner must review this"
                    + " request.");
          boolean valid =
              resubmit
                  || ("SUBMITTED".equals(before) && "UNDER_REVIEW".equals(d.status()))
                  || (Set.of("SUBMITTED", "UNDER_REVIEW").contains(before)
                      && Set.of("APPROVED", "REJECTED", "CHANGES_REQUESTED")
                          .contains(d.status() == null ? "" : d.status()));
          if (!valid)
            ConnectionService.invalid(
                "This review transition is not allowed. Final decisions are immutable.");
          jdbc.update(
              "UPDATE governance_reviews SET status=?,reviewer_id=CASE WHEN ? THEN reviewer_id ELSE"
                  + " ? END,revision=revision+1,updated_at=now() WHERE organization_id=? AND id=?",
              d.status(),
              resubmit,
              t.userId(),
              t.organizationId(),
              id);
          event(t, id, before, d.status(), d.reason());
          return Map.of("status", d.status());
        });
  }

  private void event(TenantContext t, UUID id, String before, String after, String reason) {
    jdbc.update(
        "INSERT INTO"
            + " governance_events(organization_id,review_id,actor_id,from_status,to_status,reason)"
            + " VALUES(?,?,?,?,?,?)",
        t.organizationId(),
        id,
        t.userId(),
        before,
        after,
        reason);
    jdbc.queryForObject(
        "SELECT trace_audit(?,?,?,'GOVERNANCE',?,'SUCCESS')",
        Object.class,
        t.userId(),
        t.organizationId(),
        "REVIEW_" + after,
        id);
  }
}
