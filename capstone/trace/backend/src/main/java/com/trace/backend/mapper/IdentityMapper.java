package com.trace.backend.mapper;

import com.trace.backend.dto.response.IdentityResponses.*;
import java.sql.*;
import java.util.UUID;

public final class IdentityMapper {
  private IdentityMapper() {}

  public static Organization organization(ResultSet r, int n) throws SQLException {
    return new Organization(
        r.getObject("id", UUID.class),
        r.getString("name"),
        r.getString("slug"),
        r.getBoolean("active"));
  }

  public static Team team(ResultSet r, int n) throws SQLException {
    return new Team(
        r.getObject("id", UUID.class),
        r.getString("name"),
        r.getString("slug"),
        r.getString("description"),
        r.getBoolean("active"));
  }

  public static Member member(ResultSet r, int n) throws SQLException {
    return new Member(
        r.getObject("user_id", UUID.class),
        r.getString("email"),
        r.getString("display_name"),
        r.getBoolean("active"),
        r.getString("role"),
        r.getString("status"));
  }

  public static TeamMember teamMember(ResultSet r, int n) throws SQLException {
    return new TeamMember(
        r.getObject("user_id", UUID.class),
        r.getString("email"),
        r.getString("display_name"),
        r.getString("role"));
  }

  public static Resource resource(ResultSet r, int n) throws SQLException {
    return new Resource(r.getObject("id", UUID.class), r.getString("name"));
  }

  public static Invitation invitation(ResultSet r, int n) throws SQLException {
    return new Invitation(
        r.getObject("id", UUID.class),
        r.getString("email"),
        r.getString("role"),
        r.getString("status"),
        r.getTimestamp("expires_at").toInstant(),
        r.getTimestamp("created_at").toInstant());
  }

  public static Audit audit(ResultSet r, int n) throws SQLException {
    return new Audit(
        r.getObject("id", UUID.class),
        r.getObject("actor_user_id", UUID.class),
        r.getString("event_type"),
        r.getString("resource_type"),
        r.getObject("resource_id", UUID.class),
        r.getString("outcome"),
        r.getTimestamp("created_at").toInstant());
  }
}
