package com.trace.backend.repository;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Only the dedicated SECURITY DEFINER dispatch functions may cross tenant envelopes. */
@Repository
public class DiscoveryQueueRepository {
  public record Lease(
      UUID run, UUID org, UUID user, UUID connection, UUID owner, UUID token, int attempt) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public DiscoveryQueueRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Optional<Lease> claim(UUID owner, int seconds) {
    return jdbc
        .query(
            "SELECT * FROM trace_claim_discovery(?,?)",
            (r, n) ->
                new Lease(
                    r.getObject("run_id", UUID.class),
                    r.getObject("organization_id", UUID.class),
                    r.getObject("user_id", UUID.class),
                    r.getObject("connection_id", UUID.class),
                    owner,
                    r.getObject("token", UUID.class),
                    r.getInt("attempt")),
            owner,
            seconds)
        .stream()
        .findFirst();
  }

  public String heartbeat(Lease lease, int seconds) {
    return jdbc.queryForObject(
        "SELECT trace_heartbeat_discovery(?,?,?)",
        String.class,
        lease.owner(),
        lease.token(),
        seconds);
  }

  public boolean finish(
      Lease lease,
      String status,
      UUID preview,
      int records,
      List<String> warnings,
      String code,
      int delay) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT trace_finish_discovery(?,?,?,?,?,?::jsonb,?,?)",
            Boolean.class,
            lease.owner(),
            lease.token(),
            status,
            preview,
            records,
            json.writeValueAsString(warnings),
            code,
            delay));
  }
}
