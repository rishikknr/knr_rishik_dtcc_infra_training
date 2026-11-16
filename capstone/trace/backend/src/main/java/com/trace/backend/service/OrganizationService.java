package com.trace.backend.service;

import com.trace.backend.dto.request.IdentityRequests;
import com.trace.backend.dto.request.IdentityRequests.Membership;
import com.trace.backend.dto.response.IdResponse;
import com.trace.backend.dto.response.IdentityResponses.*;
import com.trace.backend.repository.*;
import com.trace.backend.security.*;
import com.trace.backend.tenancy.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrganizationService {
  private final OrganizationRepository organizations;
  private final IdentityRepository identities;
  private final TenantTransactions tenants;
  private final AuthorizationPolicy policy;
  private final boolean localDelivery;
  private final int invitationHours, resetMinutes;

  public OrganizationService(
      OrganizationRepository organizations,
      IdentityRepository identities,
      TenantTransactions tenants,
      AuthorizationPolicy policy,
      Environment env,
      @Value("${trace.security.local-token-delivery:false}") boolean localDelivery,
      @Value("${trace.security.invitation-hours:48}") int invitationHours,
      @Value("${trace.security.reset-minutes:30}") int resetMinutes) {
    this.organizations = organizations;
    this.identities = identities;
    this.tenants = tenants;
    this.policy = policy;
    this.localDelivery = localDelivery;
    this.invitationHours = invitationHours;
    this.resetMinutes = resetMinutes;
    if (localDelivery
        && (!Set.of(env.getActiveProfiles()).equals(Set.of("local-dev"))
            || !"127.0.0.1".equals(env.getProperty("server.address"))))
      throw new IllegalStateException(
          "Local token delivery requires only local-dev and loopback binding");
    if (invitationHours < 1 || invitationHours > 168 || resetMinutes < 1 || resetMinutes > 60)
      throw new IllegalArgumentException("Invalid token lifetime");
  }

  public List<Organization> platform() {
    policy.platform();
    return organizations.platform(CurrentIdentity.require().id());
  }

  public IdResponse create(IdentityRequests.Organization body) {
    policy.platform();
    return new IdResponse(
        organizations.create(
            CurrentIdentity.require().id(),
            PasswordPolicy.label(body.name(), 150),
            PasswordPolicy.slug(body.slug())));
  }

  public void state(UUID org, boolean active) {
    policy.platform();
    organizations.state(CurrentIdentity.require().id(), org, active);
  }

  public void userState(UUID user, boolean active) {
    policy.platform();
    organizations.userState(CurrentIdentity.require().id(), user, active);
  }

  public Organization current() {
    return tenants.execute(organizations::organization);
  }

  public void rename(IdentityRequests.Organization body) {
    tenants.execute(
        t -> {
          policy.administrator(t);
          organizations.rename(t, PasswordPolicy.label(body.name(), 150));
          return null;
        });
  }

  public List<Member> members() {
    return tenants.execute(
        t -> {
          policy.administrator(t);
          return organizations.members(t);
        });
  }

  public void member(UUID user, Membership body) {
    String role = role(body.role(), Set.of("OWNER", "ADMIN", "MEMBER", "VIEWER"));
    String status = role(body.status(), Set.of("ACTIVE", "SUSPENDED", "REVOKED"));
    tenants.execute(
        t -> {
          policy.administrator(t);
          organizations.member(t, user, role, status);
          return null;
        });
  }

  public void transfer(UUID user) {
    tenants.execute(
        t -> {
          organizations.transfer(t, user);
          return null;
        });
  }

  public List<Team> teams() {
    return tenants.execute(organizations::teams);
  }

  public List<Member> candidates(UUID id) {
    return tenants.execute(
        t -> {
          organizations.team(t, id);
          if (!Set.of("OWNER", "ADMIN").contains(t.organizationRole())
              && !(t.organizationRole().equals("MEMBER") && "OWNER".equals(t.teamRoles().get(id))))
            AuthorizationPolicy.deny();
          return organizations.members(t);
        });
  }

  public TeamDetail team(UUID id) {
    return tenants.execute(t -> organizations.team(t, id));
  }

  public IdResponse saveTeam(UUID id, IdentityRequests.Team body) {
    String details = body.description() == null ? "" : body.description();
    if (details.length() > 2000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Description is too long");
    return tenants.execute(
        t ->
            new IdResponse(
                organizations.saveTeam(
                    t,
                    id,
                    PasswordPolicy.label(body.name(), 150),
                    PasswordPolicy.slug(body.slug()),
                    details)));
  }

  public void teamMember(UUID team, UUID user, String role) {
    if (role != null) role(role, Set.of("OWNER", "EDITOR", "VIEWER"));
    if (user == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Member required");
    tenants.execute(
        t -> {
          organizations.teamMember(t, team, user, role);
          return null;
        });
  }

  public List<Invitation> invitations() {
    return tenants.execute(
        t -> {
          policy.administrator(t);
          return organizations.invitations(t);
        });
  }

  public record Delivery(UUID id, String link, Instant expiresAt) {}

  private void requireDelivery() {
    if (!localDelivery)
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Invitation and recovery delivery is not configured. An operator must configure a"
              + " delivery channel");
  }

  public Delivery invite(IdentityRequests.Invitation body) {
    requireDelivery();
    String email = PasswordPolicy.email(body.email());
    String role = role(body.role(), Set.of("OWNER", "ADMIN", "MEMBER", "VIEWER"));
    String token = IdentityService.token();
    Instant expiry = Instant.now().plus(Duration.ofHours(invitationHours));
    return tenants.execute(
        t -> {
          policy.administrator(t);
          UUID id = organizations.invite(t, email, role, IdentityService.tokenHash(token), expiry);
          return new Delivery(id, "#accept-invitation/" + token, expiry);
        });
  }

  public void revoke(UUID id) {
    tenants.execute(
        t -> {
          policy.administrator(t);
          organizations.revoke(t, id);
          return null;
        });
  }

  public Delivery recover(UUID user) {
    policy.platform();
    requireDelivery();
    String token = IdentityService.token();
    Instant expiry = Instant.now().plus(Duration.ofMinutes(resetMinutes));
    identities.issueReset(
        CurrentIdentity.require().id(), user, IdentityService.tokenHash(token), expiry);
    return new Delivery(null, "#reset-password/" + token, expiry);
  }

  public List<Audit> audit() {
    return tenants.execute(
        t -> {
          policy.administrator(t);
          return organizations.audit(t);
        });
  }

  private String role(String role, Set<String> allowed) {
    if (role == null || !allowed.contains(role))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid role or status");
    return role;
  }
}
