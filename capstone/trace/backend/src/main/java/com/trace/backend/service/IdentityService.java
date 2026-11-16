package com.trace.backend.service;

import com.trace.backend.config.TenantConfiguration;
import com.trace.backend.dto.request.IdentityRequests.*;
import com.trace.backend.dto.response.IdentityResponses.AvailableOrganization;
import com.trace.backend.dto.response.IdentityResponses.Permissions;
import com.trace.backend.dto.response.IdentityResponses.TeamPermissions;
import com.trace.backend.dto.response.RegistrationOptions;
import com.trace.backend.dto.response.RegistrationResponse;
import com.trace.backend.exception.ApiException;
import com.trace.backend.repository.*;
import com.trace.backend.security.*;
import com.trace.backend.tenancy.TenantTransactions;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IdentityService {
  private final IdentityRepository users;
  private final TenantTransactions tenants;
  private final PasswordEncoder encoder;
  private final PasswordPolicy passwords;
  private final TransactionTemplate tx;
  private final LocalSignupPolicy signup;

  public IdentityService(
      IdentityRepository users,
      TenantTransactions tenants,
      PasswordEncoder encoder,
      PasswordPolicy passwords,
      PlatformTransactionManager manager,
      LocalSignupPolicy signup) {
    this.signup = signup;
    this.users = users;
    this.tenants = tenants;
    this.encoder = encoder;
    this.passwords = passwords;
    tx = new TransactionTemplate(manager);
  }

  public RegistrationOptions registrationOptions(HttpServletRequest request) {
    return new RegistrationOptions(signup.available(request), passwords.minimumLength(), 72);
  }

  public RegistrationResponse register(Registration registration, HttpServletRequest request) {
    signup.require(request);
    String displayName = PasswordPolicy.label(registration.displayName(), 150);
    String email = PasswordPolicy.email(registration.email());
    String password = passwords.validate(registration.password());
    if (!password.equals(registration.confirmPassword())) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH", "Passwords do not match.");
    }
    String encoded = encoder.encode(password);
    try {
      UUID id = tx.execute(status -> users.register(email, displayName, encoded));
      return new RegistrationResponse(id, displayName, email, "Account created successfully.");
    } catch (org.springframework.dao.DuplicateKeyException conflict) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "ACCOUNT_EXISTS",
          "An account with this email already exists. Sign in or contact your administrator.");
    }
  }

  public record PublicUser(
      UUID id, String email, String displayName, boolean active, String platformRole) {}

  private PublicUser publicUser(UUID id) {
    var u = users.profile(id);
    return new PublicUser(u.id(), u.email(), u.displayName(), u.active(), u.platformRole());
  }

  public record Me(
      PublicUser user,
      List<AvailableOrganization> organizations,
      UUID selectedOrganization,
      String organizationRole,
      Map<UUID, String> teamRoles,
      Permissions permissions) {}

  public Me me(HttpServletRequest request) {
    var p = CurrentIdentity.require();
    var organizations = users.organizations(p.id());
    var session = request.getSession(false);
    UUID selected =
        session == null ? null : (UUID) session.getAttribute(TenantConfiguration.ORGANIZATION);
    final UUID org = selected;
    if (selected != null && organizations.stream().noneMatch(o -> org.equals(o.id()))) {
      session.removeAttribute(TenantConfiguration.ORGANIZATION);
      selected = null;
    }
    if (selected == null)
      return new Me(
          publicUser(p.id()),
          organizations,
          null,
          null,
          Map.of(),
          new Permissions(null, null, List.of(), List.of()));
    return tenants.execute(
        t ->
            new Me(
                publicUser(p.id()),
                organizations,
                t.organizationId(),
                t.organizationRole(),
                t.teamRoles(),
                permissions(t)));
  }

  public Me select(UUID organization, HttpServletRequest request) {
    var p = CurrentIdentity.require();
    if (organization == null
        || users.organizations(p.id()).stream().noneMatch(o -> organization.equals(o.id())))
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Active organization membership required");
    request.getSession().setAttribute(TenantConfiguration.ORGANIZATION, organization);
    tenants.execute(
        t -> {
          users.audit(
              p.id(),
              organization,
              "ORGANIZATION_SELECTED",
              "ORGANIZATION",
              organization,
              "SUCCESS");
          return null;
        });
    return me(request);
  }

  public Permissions permissions() {
    return tenants.execute(this::permissions);
  }

  private Permissions permissions(com.trace.backend.tenancy.TenantContext t) {
    boolean admin = java.util.Set.of("OWNER", "ADMIN").contains(t.organizationRole());
    var base =
        new java.util.ArrayList<>(
            java.util.List.of("catalog:read", "connection:read", "team:read"));
    if (admin)
      base.addAll(
          java.util.List.of(
              "organization:manage",
              "membership:manage",
              "invitation:create",
              "team:create",
              "metadata:import",
              "connection:create",
              "resource:transfer"));
    var teams =
        t.teamRoles().entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByKey())
            .map(
                e -> {
                  var permissions = new java.util.ArrayList<>(java.util.List.of("resource:read"));
                  if (!t.organizationRole().equals("VIEWER")
                      && java.util.Set.of("OWNER", "EDITOR").contains(e.getValue()))
                    permissions.addAll(
                        java.util.List.of("connection:create", "metadata:import", "asset:edit"));
                  if (!t.organizationRole().equals("VIEWER") && e.getValue().equals("OWNER"))
                    permissions.addAll(java.util.List.of("team:manage", "resource:transfer"));
                  return new TeamPermissions(e.getKey(), e.getValue(), permissions);
                })
            .toList();
    return new Permissions(t.organizationId(), t.organizationRole(), base, teams);
  }

  public java.util.List<AvailableOrganization> organizations() {
    return users.organizations(CurrentIdentity.require().id());
  }

  public void change(PasswordChange change, HttpServletRequest request) {
    var p = CurrentIdentity.require();
    var c = users.credentials(p.getUsername()).orElseThrow(RegistryRepository::missing);
    if (change.currentPassword() == null
        || change.currentPassword().length() > 72
        || !encoder.matches(change.currentPassword(), c.passwordHash()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
    String hash = encoder.encode(passwords.validate(change.newPassword()));
    tx.executeWithoutResult(
        s -> {
          users.password(p.id(), hash);
          users.audit(p.id(), null, "PASSWORD_CHANGED", "USER", p.id(), "SUCCESS");
        });
    invalidate(request);
  }

  public void rename(String name) {
    users.rename(CurrentIdentity.require().id(), PasswordPolicy.label(name, 150));
  }

  public void accept(
      Acceptance acceptance,
      HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response) {
    var current = CurrentIdentity.optional();
    String encoded =
        current == null ? encoder.encode(passwords.validate(acceptance.password())) : null;
    String name = current == null ? PasswordPolicy.label(acceptance.displayName(), 150) : null;
    UUID who =
        tx.execute(
            s ->
                users.accept(
                    tokenHash(acceptance.token()),
                    current == null ? null : current.id(),
                    name,
                    encoded));
    var profile = users.profile(who);
    if (request.getSession(false) != null) request.getSession(false).invalidate();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new TracePrincipal(who, profile.email(), "", true, profile.securityVersion()),
            null,
            List.of()));
    SecurityContextHolder.setContext(context);
    new HttpSessionSecurityContextRepository().saveContext(context, request, response);
  }

  public void reset(Reset reset, HttpServletRequest request) {
    String encoded = encoder.encode(passwords.validate(reset.newPassword()));
    tx.execute(s -> users.reset(tokenHash(reset.token()), encoded));
    invalidate(request);
  }

  public static void invalidate(HttpServletRequest request) {
    var session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
  }

  public static String token() {
    byte[] raw = new byte[32];
    new SecureRandom().nextBytes(raw);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  public static String tokenHash(String raw) {
    if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired link");
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
