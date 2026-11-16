package com.trace.backend.service;

import com.trace.backend.dto.request.MetadataRequests.Mapping;
import com.trace.backend.repository.*;
import com.trace.backend.tenancy.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MappingSuggestionService {
  private final TenantTransactions tenants;
  private final JdbcTemplate jdbc;
  private final RegistryRepository guard;
  private final LineageRepository lineage;

  public MappingSuggestionService(
      TenantTransactions tenants,
      JdbcTemplate jdbc,
      RegistryRepository guard,
      LineageRepository lineage) {
    this.tenants = tenants;
    this.jdbc = jdbc;
    this.guard = guard;
    this.lineage = lineage;
  }

  private List<Map<String, Object>> candidates(TenantContext t, UUID connection) {
    return candidates(t, connection, null, null);
  }

  private List<Map<String, Object>> candidates(
      TenantContext t, UUID connection, UUID sourceId, UUID targetId) {
    jdbc.execute("SET LOCAL statement_timeout='5s'");
    return jdbc.queryForList(
        """
        WITH files AS (SELECT a.*,lower(regexp_replace(regexp_replace(external_key,'^.*/',''),'\\.[^.]+$','')) AS stem FROM assets a WHERE a.organization_id=? AND a.asset_type='FILE' AND a.platform='AMAZON_S3' AND a.inventory_state<>'NOT_SEEN_IN_LATEST_COMPLETE_INVENTORY'),
        targets AS (SELECT a.*,lower(split_part(external_key,'.',3)) AS stem FROM assets a WHERE a.organization_id=? AND a.asset_type IN ('DATASET','TABLE') AND a.platform='SNOWFLAKE' AND upper(split_part(external_key,'.',2))='RAW' AND a.inventory_state<>'NOT_SEEN_IN_LATEST_COMPLETE_INVENTORY')
        SELECT s.id AS source_id,s.name AS source_name,s.external_key AS source_key,s.owner_team_id AS source_team_id,
        d.id AS target_id,d.name AS target_name,d.external_key AS target_key,d.owner_team_id AS target_team_id,
        (SELECT count(*) FROM files f WHERE f.stem=s.stem) AS source_matches,
        (SELECT count(*) FROM targets x WHERE x.stem=d.stem) AS target_matches,
        (SELECT count(*) FROM asset_columns sc JOIN asset_columns dc ON dc.organization_id=sc.organization_id AND lower(dc.name)=lower(sc.name) AND dc.asset_id=d.id WHERE sc.organization_id=s.organization_id AND sc.asset_id=s.id) AS matching_column_names
        FROM files s JOIN targets d ON s.stem=d.stem
        WHERE (?::uuid IS NULL OR s.connection_id=? OR d.connection_id=?)
        AND (?::uuid IS NULL OR s.id=?) AND (?::uuid IS NULL OR d.id=?)
        AND NOT EXISTS(SELECT 1 FROM lineage_edges e WHERE e.organization_id=s.organization_id AND e.source_asset_id=s.id AND e.target_asset_id=d.id AND e.active)
        ORDER BY s.external_key,d.external_key LIMIT 101
        """,
        t.organizationId(),
        t.organizationId(),
        connection,
        connection,
        connection,
        sourceId,
        sourceId,
        targetId,
        targetId);
  }

  public Map<String, Object> list(UUID connection) {
    return tenants.executeSnapshot(
        t -> {
          if (connection != null
              && jdbc.queryForObject(
                      "SELECT count(*) FROM source_connections WHERE organization_id=? AND id=?",
                      Integer.class,
                      t.organizationId(),
                      connection)
                  == 0) throw RegistryRepository.missing();
          var rows = candidates(t, connection);
          return Map.of(
              "items",
              rows.subList(0, Math.min(100, rows.size())),
              "hasMore",
              rows.size() > 100,
              "basis",
              "Exact CSV basename matches a registered Snowflake RAW table name. This is a"
                  + " candidate, not load-history evidence.");
        });
  }

  public record Confirm(UUID sourceId, UUID targetId) {}

  public Map<String, Object> confirm(Confirm r) {
    return tenants.execute(
        t -> {
          lineage.lock(t);
          if (r.sourceId() == null || r.targetId() == null)
            ConnectionService.invalid("Select a source and target");
          for (UUID asset : List.of(r.sourceId(), r.targetId()))
            guard.requireTeamWrite(
                t,
                (UUID)
                    RegistryRepository.one(
                            jdbc.queryForList(
                                "SELECT owner_team_id FROM assets WHERE organization_id=? AND id=?",
                                t.organizationId(),
                                asset))
                        .get("owner_team_id"));
          var existing =
              jdbc.queryForList(
                  "SELECT id,provenance FROM lineage_edges WHERE organization_id=? AND"
                      + " source_asset_id=? AND target_asset_id=? AND active ORDER BY id LIMIT 1",
                  t.organizationId(),
                  r.sourceId(),
                  r.targetId());
          if (!existing.isEmpty())
            return Map.of(
                "id",
                existing.getFirst().get("id"),
                "unchanged",
                true,
                "provenance",
                existing.getFirst().get("provenance"));
          var rows = candidates(t, null, r.sourceId(), r.targetId());
          var match =
              rows.stream()
                  .filter(
                      m ->
                          Objects.equals(r.sourceId(), m.get("source_id"))
                              && Objects.equals(r.targetId(), m.get("target_id")))
                  .findFirst()
                  .orElseThrow(RegistryRepository::missing);
          if (((Number) match.get("source_matches")).intValue() != 1
              || ((Number) match.get("target_matches")).intValue() != 1)
            ConnectionService.invalid(
                "Ambiguous name match. Use an evidence-backed manual mapping to choose"
                    + " identities.");
          guard.requireTeamWrite(t, (UUID) match.get("source_team_id"));
          guard.requireTeamWrite(t, (UUID) match.get("target_team_id"));
          if (lineage.cycle(t, r.sourceId(), r.targetId(), false))
            ConnectionService.invalid("This declaration would introduce a cycle");
          String evidence =
              "Exact basename match reviewed and explicitly confirmed: "
                  + match.get("source_key")
                  + " → "
                  + match.get("target_key")
                  + ". Human declaration; load execution and refresh are not verified.";
          UUID id =
              lineage.create(
                  t,
                  new Mapping(
                      r.sourceId(),
                      r.targetId(),
                      null,
                      null,
                      "DATA_FLOW",
                      evidence,
                      "Name-based ingestion candidate explicitly confirmed by a user",
                      null));
          return Map.of("id", id, "provenance", "MANUAL_DECLARATION");
        });
  }
}
