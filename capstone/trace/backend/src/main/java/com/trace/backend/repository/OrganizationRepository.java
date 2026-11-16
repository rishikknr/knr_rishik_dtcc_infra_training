package com.trace.backend.repository;

import com.trace.backend.dto.response.IdentityResponses.*;
import com.trace.backend.mapper.IdentityMapper;
import com.trace.backend.tenancy.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrganizationRepository {
  private final JdbcTemplate jdbc;
  private final RegistryRepository guard;

  public OrganizationRepository(JdbcTemplate jdbc, RegistryRepository guard) {
    this.jdbc = jdbc;
    this.guard = guard;
  }

  public List<Organization> platform(UUID actor) {
    return jdbc.query(
        "SELECT * FROM trace_platform_organizations(?)", IdentityMapper::organization, actor);
  }

  public UUID create(UUID actor, String name, String slug) {
    return jdbc.queryForObject(
        "SELECT trace_create_organization(?,?,?)", UUID.class, actor, name, slug);
  }

  public void state(UUID actor, UUID org, boolean active) {
    jdbc.queryForObject(
        "SELECT trace_suspend_organization(?,?,?)", Object.class, actor, org, active);
  }

  public void userState(UUID actor, UUID target, boolean active) {
    jdbc.queryForObject("SELECT trace_user_state(?,?,?)", Object.class, actor, target, active);
  }

  public List<Member> members(TenantContext t) {
    guard.requireTransaction(t);
    return jdbc.query(
        "SELECT m.user_id,p.email,p.display_name,p.active,m.role,m.status FROM"
            + " organization_memberships m CROSS JOIN LATERAL trace_profile(m.user_id) p WHERE"
            + " m.organization_id=? ORDER BY p.email",
        IdentityMapper::member,
        t.organizationId());
  }

  public Organization organization(TenantContext t) {
    guard.requireTransaction(t);
    return jdbc
        .query(
            "SELECT id,name,slug,active FROM organizations WHERE id=?",
            IdentityMapper::organization,
            t.organizationId())
        .stream()
        .findFirst()
        .orElseThrow(RegistryRepository::missing);
  }

  public void rename(TenantContext t, String name) {
    guard.requireTransaction(t);
    jdbc.queryForObject("SELECT trace_organization_update(?,?)", Object.class, t.userId(), name);
  }

  public void member(TenantContext t, UUID user, String role, String status) {
    guard.requireTransaction(t);
    jdbc.queryForObject(
        "SELECT trace_membership_save(?,?,?,?)", Object.class, t.userId(), user, role, status);
  }

  public void transfer(TenantContext t, UUID user) {
    guard.requireTransaction(t);
    jdbc.queryForObject("SELECT trace_transfer_organization(?,?)", Object.class, t.userId(), user);
  }

  public List<Team> teams(TenantContext t) {
    guard.requireTransaction(t);
    return jdbc.query(
        "SELECT id,name,slug,description,active FROM teams WHERE organization_id=? ORDER BY name",
        IdentityMapper::team,
        t.organizationId());
  }

  public TeamDetail team(TenantContext t, UUID id) {
    guard.requireTransaction(t);
    var team =
        jdbc
            .query(
                "SELECT id,name,slug,description,active FROM teams WHERE organization_id=? AND"
                    + " id=?",
                IdentityMapper::team,
                t.organizationId(),
                id)
            .stream()
            .findFirst()
            .orElseThrow(RegistryRepository::missing);
    var members =
        jdbc.query(
            "SELECT m.user_id,p.email,p.display_name,m.role FROM team_memberships m CROSS JOIN"
                + " LATERAL trace_profile(m.user_id) p JOIN organization_memberships om ON"
                + " om.user_id=m.user_id AND om.organization_id=m.organization_id WHERE"
                + " m.organization_id=? AND m.team_id=? AND om.status='ACTIVE' AND p.active ORDER"
                + " BY p.email",
            IdentityMapper::teamMember,
            t.organizationId(),
            id);
    var connections =
        jdbc.query(
            "SELECT id,name FROM source_connections WHERE organization_id=? AND owner_team_id=?"
                + " ORDER BY name",
            IdentityMapper::resource,
            t.organizationId(),
            id);
    var assets =
        jdbc.query(
            "SELECT id,name FROM assets WHERE organization_id=? AND owner_team_id=? ORDER BY name"
                + " LIMIT 100",
            IdentityMapper::resource,
            t.organizationId(),
            id);
    return new TeamDetail(
        team.id(),
        team.name(),
        team.slug(),
        team.description(),
        team.active(),
        members,
        connections,
        assets);
  }

  public UUID saveTeam(TenantContext t, UUID id, String name, String slug, String details) {
    guard.requireTransaction(t);
    return jdbc.queryForObject(
        "SELECT trace_team_save(?,?,?,?,?)", UUID.class, t.userId(), id, name, slug, details);
  }

  public void teamMember(TenantContext t, UUID team, UUID user, String role) {
    guard.requireTransaction(t);
    jdbc.queryForObject(
        "SELECT trace_team_member_save(?,?,?,?)", Object.class, t.userId(), team, user, role);
  }

  public List<Invitation> invitations(TenantContext t) {
    guard.requireTransaction(t);
    return jdbc.query(
        "SELECT id,email,role,status,expires_at,created_at FROM organization_invitations WHERE"
            + " organization_id=? ORDER BY created_at DESC LIMIT 200",
        IdentityMapper::invitation,
        t.organizationId());
  }

  public UUID invite(TenantContext t, String email, String role, String hash, Instant expiry) {
    guard.requireTransaction(t);
    return jdbc.queryForObject(
        "SELECT trace_invite(?,?,?,?,?)",
        UUID.class,
        t.userId(),
        email,
        role,
        hash,
        Timestamp.from(expiry));
  }

  public void revoke(TenantContext t, UUID id) {
    guard.requireTransaction(t);
    jdbc.queryForObject("SELECT trace_invitation_revoke(?,?)", Object.class, t.userId(), id);
  }

  public List<Audit> audit(TenantContext t) {
    guard.requireTransaction(t);
    return jdbc.query(
        "SELECT id,actor_user_id,event_type,resource_type,resource_id,outcome,created_at FROM"
            + " audit_events WHERE organization_id=? ORDER BY created_at DESC LIMIT 200",
        IdentityMapper::audit,
        t.organizationId());
  }
}
