package com.trace.backend.repository;

import com.trace.backend.dto.response.IdentityResponses.AvailableOrganization;
import com.trace.backend.entity.Identity;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityRepository {
  private final JdbcTemplate jdbc;

  public IdentityRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Credentials(
      UUID id, String email, String passwordHash, boolean active, long version) {}

  public Optional<Credentials> credentials(String email) {
    return jdbc
        .query(
            "SELECT * FROM trace_credentials(?)",
            (rs, n) ->
                new Credentials(
                    rs.getObject("id", UUID.class),
                    rs.getString("email"),
                    rs.getString("password_hash"),
                    rs.getBoolean("active"),
                    rs.getLong("security_version")),
            email)
        .stream()
        .findFirst();
  }

  public Identity profile(UUID id) {
    return jdbc
        .query(
            "SELECT * FROM trace_profile(?)",
            (rs, n) ->
                new Identity(
                    rs.getObject("id", UUID.class),
                    rs.getString("email"),
                    rs.getString("display_name"),
                    rs.getBoolean("active"),
                    rs.getString("platform_role"),
                    rs.getLong("security_version")),
            id)
        .stream()
        .findFirst()
        .orElseThrow(RegistryRepository::missing);
  }

  public List<AvailableOrganization> organizations(UUID id) {
    return jdbc.query(
        "SELECT * FROM trace_my_organizations(?)",
        (rs, n) ->
            new AvailableOrganization(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("slug"),
                rs.getString("role")),
        id);
  }

  public void login(UUID id) {
    jdbc.queryForObject("SELECT trace_login_success(?)", Object.class, id);
  }

  public void audit(
      UUID actor, UUID org, String event, String kind, UUID resource, String outcome) {
    jdbc.queryForObject(
        "SELECT trace_audit(?,?,?,?,?,?)",
        Object.class,
        actor,
        org,
        event,
        kind,
        resource,
        outcome);
  }

  public void auditOwnership(
      UUID actor, UUID org, UUID resource, boolean connection, UUID previousTeam, UUID team) {
    jdbc.queryForObject(
        "SELECT"
            + " trace_audit(?,?,'RESOURCE_OWNERSHIP_TRANSFERRED',?,?,'SUCCESS',jsonb_build_object('previousTeamId',?::uuid,'ownerTeamId',?::uuid))",
        Object.class,
        actor,
        org,
        connection ? "CONNECTION" : "ASSET",
        resource,
        previousTeam,
        team);
  }

  public void password(UUID id, String hash) {
    jdbc.queryForObject("SELECT trace_password_set(?,?)", Object.class, id, hash);
  }

  public void rename(UUID id, String label) {
    jdbc.queryForObject("SELECT trace_profile_update(?,?)", Object.class, id, label);
  }

  public UUID accept(String hash, UUID actor, String name, String passwordHash) {
    return jdbc.queryForObject(
        "SELECT trace_invitation_accept(?,?,?,?)", UUID.class, hash, actor, name, passwordHash);
  }

  public UUID reset(String hash, String passwordHash) {
    return jdbc.queryForObject("SELECT trace_reset_accept(?,?)", UUID.class, hash, passwordHash);
  }

  public void issueReset(UUID actor, UUID target, String hash, java.time.Instant expiry) {
    jdbc.queryForObject(
        "SELECT trace_reset_issue(?,?,?,?)",
        Object.class,
        actor,
        target,
        hash,
        java.sql.Timestamp.from(expiry));
  }

  public UUID register(String email, String displayName, String hash) {
    return jdbc.queryForObject(
        "SELECT trace_register_user(?,?,?)", UUID.class, email, displayName, hash);
  }

  public UUID bootstrap(String email, String name, String hash) {
    return jdbc.queryForObject(
        "SELECT trace_bootstrap_admin(?,?,?)", UUID.class, email, name, hash);
  }
}
