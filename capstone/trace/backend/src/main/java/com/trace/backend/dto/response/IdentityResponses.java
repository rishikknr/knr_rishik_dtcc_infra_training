package com.trace.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.*;

public final class IdentityResponses {
  private IdentityResponses() {}

  public record AvailableOrganization(UUID id, String name, String slug, String role) {}

  public record Organization(UUID id, String name, String slug, boolean active) {}

  public record Member(
      @JsonProperty("user_id") UUID userId,
      String email,
      @JsonProperty("display_name") String displayName,
      boolean active,
      String role,
      String status) {}

  public record Team(UUID id, String name, String slug, String description, boolean active) {}

  public record TeamMember(
      @JsonProperty("user_id") UUID userId,
      String email,
      @JsonProperty("display_name") String displayName,
      String role) {}

  public record Resource(UUID id, String name) {}

  public record TeamDetail(
      UUID id,
      String name,
      String slug,
      String description,
      boolean active,
      List<TeamMember> members,
      List<Resource> connections,
      List<Resource> assets) {}

  public record Invitation(
      UUID id,
      String email,
      String role,
      String status,
      @JsonProperty("expires_at") Instant expiresAt,
      @JsonProperty("created_at") Instant createdAt) {}

  public record Audit(
      UUID id,
      @JsonProperty("actor_user_id") UUID actorUserId,
      @JsonProperty("event_type") String eventType,
      @JsonProperty("resource_type") String resourceType,
      @JsonProperty("resource_id") UUID resourceId,
      String outcome,
      @JsonProperty("created_at") Instant createdAt) {}

  public record TeamPermissions(UUID teamId, String role, List<String> permissions) {}

  public record Permissions(
      UUID organizationId,
      String organizationRole,
      List<String> permissions,
      List<TeamPermissions> teamPermissions) {}
}
