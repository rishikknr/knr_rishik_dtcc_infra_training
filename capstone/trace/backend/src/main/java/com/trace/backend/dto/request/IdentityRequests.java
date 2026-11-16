package com.trace.backend.dto.request;

import java.util.UUID;

public final class IdentityRequests {
  private IdentityRequests() {}

  public record Registration(
      String displayName, String email, String password, String confirmPassword) {}

  public record Organization(String name, String slug) {}

  public record Selection(UUID organizationId) {}

  public record Membership(String role, String status) {}

  public record Team(String name, String slug, String description) {}

  public record TeamMember(UUID userId, String role) {}

  public record Invitation(String email, String role) {}

  public record Acceptance(String token, String displayName, String password) {}

  public record PasswordChange(String currentPassword, String newPassword) {}

  public record Reset(String token, String newPassword) {}

  public record Profile(String displayName) {}

  public record State(boolean active) {}
}
