package com.trace.backend.repository;

import com.trace.backend.entity.Asset;
import com.trace.backend.tenancy.TenantContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AssetRepository {
  private final JdbcTemplate jdbc;
  private final RegistryRepository guard;

  public AssetRepository(JdbcTemplate jdbc, RegistryRepository guard) {
    this.jdbc = jdbc;
    this.guard = guard;
  }

  private static final String SELECT =
"""
SELECT a.*, c.name AS connection_name, t.name AS team_name,
  (SELECT count(*) FROM asset_columns ac WHERE ac.organization_id=a.organization_id AND ac.asset_id=a.id) AS column_count
FROM assets a JOIN source_connections c ON c.organization_id=a.organization_id AND c.id=a.connection_id
LEFT JOIN teams t ON t.organization_id=a.organization_id AND t.id=a.owner_team_id
""";
  private final RowMapper<Asset> mapper =
      (r, n) ->
          new Asset(
              r.getObject("id", UUID.class),
              r.getString("name"),
              r.getString("asset_type"),
              r.getString("platform"),
              r.getString("external_key"),
              r.getObject("connection_id", UUID.class),
              r.getString("connection_name"),
              r.getObject("owner_team_id", UUID.class),
              r.getString("team_name"),
              r.getString("owner"),
              r.getTimestamp("last_seen_at") == null
                  ? null
                  : r.getTimestamp("last_seen_at").toInstant(),
              r.getTimestamp("created_at").toInstant(),
              r.getLong("column_count"));

  public List<Asset> list(TenantContext t, String query, int limit, int offset) {
    guard.requireTransaction(t);
    return jdbc.query(
        SELECT
            + " WHERE a.organization_id=? AND (strpos(lower(a.name),lower(?))>0 OR"
            + " strpos(lower(a.external_key),lower(?))>0) ORDER BY a.name,a.id LIMIT ? OFFSET ?",
        mapper,
        t.organizationId(),
        query,
        query,
        limit,
        offset);
  }

  public Asset get(TenantContext t, UUID id) {
    guard.requireTransaction(t);
    return jdbc
        .query(SELECT + " WHERE a.organization_id=? AND a.id=?", mapper, t.organizationId(), id)
        .stream()
        .findFirst()
        .orElseThrow(RegistryRepository::missing);
  }

  public Optional<Asset> find(TenantContext t, UUID connection, String key) {
    return find(t, connection, key, "DATASET");
  }

  public Optional<Asset> find(TenantContext t, UUID connection, String key, String type) {
    guard.requireTransaction(t);
    return jdbc
        .query(
            SELECT
                + " WHERE a.organization_id=? AND a.connection_id=? AND a.external_key=? AND"
                + " a.asset_type=?",
            mapper,
            t.organizationId(),
            connection,
            key,
            type)
        .stream()
        .findFirst();
  }

  public void importRelationships(
      TenantContext t,
      UUID connection,
      UUID batch,
      com.trace.backend.dto.response.MetadataPreview preview,
      boolean live) {
    guard.requireTransaction(t);
    if (live && preview.snapshotComplete()) {
      for (var d : preview.datasets()) {
        if (!"REPORT".equals(d.assetType())
            || !Boolean.TRUE.equals(d.metadata().get("modelAssociationObserved"))) continue;
        var target =
            find(t, connection, d.externalKey(), d.assetType())
                .orElseThrow(RegistryRepository::missing);
        guard.requireTeamWrite(t, target.ownerTeamId());
        var retained = new HashSet<UUID>();
        for (var edge : preview.relationships())
          if ("REPORT_USES_MODEL".equals(edge.relationshipType())
              && d.externalKey().equals(edge.targetKey()))
            retained.add(
                find(t, connection, edge.sourceKey(), edge.sourceType())
                    .orElseThrow(RegistryRepository::missing)
                    .id());
        for (var old :
            jdbc.queryForList(
                "SELECT id,source_asset_id FROM lineage_edges WHERE organization_id=? AND"
                    + " target_asset_id=? AND active AND provenance='LIVE_DISCOVERY' AND"
                    + " relationship_type='REPORT_USES_MODEL'",
                t.organizationId(),
                target.id()))
          if (!retained.contains(old.get("source_asset_id"))) {
            jdbc.update(
                "UPDATE lineage_edges SET active=false,revoked_at=now() WHERE organization_id=? AND"
                    + " id=?",
                t.organizationId(),
                old.get("id"));
            jdbc.queryForObject(
                "SELECT trace_audit(?,?,'DISCOVERED_MAPPING_RETIRED','MAPPING',?,'SUCCESS')",
                Object.class,
                t.userId(),
                t.organizationId(),
                old.get("id"));
          }
      }
    }
    for (var r : preview.relationships()) {
      var source =
          find(t, connection, r.sourceKey(), r.sourceType())
              .orElseThrow(RegistryRepository::missing);
      var target =
          find(t, connection, r.targetKey(), r.targetType())
              .orElseThrow(RegistryRepository::missing);
      guard.requireTeamWrite(t, source.ownerTeamId());
      guard.requireTeamWrite(t, target.ownerTeamId());
      jdbc.update(
          """
          INSERT INTO lineage_edges(organization_id,source_asset_id,target_asset_id,relationship_type,provenance,evidence_ref,created_by)
          VALUES(?,?,?,?,?,?,?) ON CONFLICT(organization_id,source_asset_id,target_asset_id,relationship_type) WHERE active DO NOTHING
          """,
          t.organizationId(),
          source.id(),
          target.id(),
          r.relationshipType(),
          live ? "LIVE_DISCOVERY" : "IMPORTED_MAPPING",
          batch + ":" + r.evidenceRef(),
          t.userId());
    }
  }

  public void inventory(TenantContext t, UUID connection, UUID batch, String scope) {
    guard.requireTransaction(t);
    jdbc.update(
        "UPDATE assets SET inventory_state='NOT_SEEN_IN_LATEST_COMPLETE_INVENTORY' WHERE"
            + " organization_id=? AND connection_id=? AND provenance='LIVE_DISCOVERY' AND"
            + " last_import_id IS DISTINCT FROM ? AND metadata->>'inventoryScope'=?",
        t.organizationId(),
        connection,
        batch,
        scope);
  }

  public boolean changed(
      TenantContext t, UUID id, String name, Map<String, Object> metadata, boolean live) {
    guard.requireTransaction(t);
    return jdbc.queryForObject(
        "SELECT (NOT name_curated AND provenance<>'MANUAL_DECLARATION' AND name<>?) OR NOT(metadata"
            + (live ? " = ?::jsonb)" : " @> ?::jsonb)")
            + " FROM assets WHERE organization_id=? AND id=?",
        Boolean.class,
        name,
        new tools.jackson.databind.ObjectMapper().writeValueAsString(metadata),
        t.organizationId(),
        id);
  }

  public UUID upsert(
      TenantContext t, UUID connection, UUID team, UUID batch, String key, String name) {
    return upsert(t, connection, team, batch, key, name, "DATASET", java.util.Map.of());
  }

  public UUID upsert(
      TenantContext t,
      UUID connection,
      UUID team,
      UUID batch,
      String key,
      String name,
      String type,
      java.util.Map<String, Object> metadata) {
    guard.requireTransaction(t);
    return jdbc.queryForObject(
"""
INSERT INTO assets(organization_id,connection_id,external_key,name,asset_type,platform,owner_team_id,last_seen_at,last_import_id,metadata,provenance)
VALUES (?,?,?,?,?,(SELECT platform FROM source_connections WHERE organization_id=? AND id=?),?,now(),?,?::jsonb,?)
ON CONFLICT (organization_id,connection_id,external_key,asset_type)
DO UPDATE SET name=CASE WHEN (assets.provenance='MANUAL_DECLARATION' OR assets.name_curated) THEN assets.name ELSE EXCLUDED.name END,last_seen_at=now(),inventory_state='CURRENT',last_import_id=EXCLUDED.last_import_id,metadata=CASE WHEN EXCLUDED.metadata->>'provenance'='LIVE_DISCOVERY' THEN EXCLUDED.metadata ELSE assets.metadata || EXCLUDED.metadata END RETURNING id
""",
        UUID.class,
        t.organizationId(),
        connection,
        key,
        name,
        type,
        t.organizationId(),
        connection,
        team,
        batch,
        new tools.jackson.databind.ObjectMapper().writeValueAsString(metadata),
        "LIVE_DISCOVERY".equals(metadata.get("provenance"))
            ? "LIVE_DISCOVERY"
            : "IMPORTED_METADATA");
  }
}
