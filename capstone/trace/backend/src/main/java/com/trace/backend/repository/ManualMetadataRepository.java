package com.trace.backend.repository;

import com.trace.backend.dto.request.MetadataRequests.*;
import com.trace.backend.tenancy.TenantContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ManualMetadataRepository {
  private final JdbcTemplate jdbc;
  private final RegistryRepository guard;
  private final ObjectMapper json;

  public ManualMetadataRepository(JdbcTemplate jdbc, RegistryRepository guard, ObjectMapper json) {
    this.jdbc = jdbc;
    this.guard = guard;
    this.json = json;
  }

  public Map<String, Object> asset(TenantContext t, UUID id) {
    guard.requireTransaction(t);
    return RegistryRepository.one(
        jdbc.queryForList(
            "SELECT"
                + " id,connection_id,name,asset_type,external_key,owner_team_id,description,owner,owner"
                + " AS technical_owner,business_owner,(SELECT"
                + " COALESCE(jsonb_agg(at.tag_id),'[]')::text FROM asset_tags at WHERE"
                + " at.organization_id=assets.organization_id AND at.asset_id=assets.id) AS"
                + " tag_ids,provenance,revision,inventory_state,last_import_id,last_seen_at,metadata::text"
                + " FROM assets WHERE organization_id=? AND id=?",
            t.organizationId(),
            id));
  }

  public Map<String, Object> graphAsset(TenantContext t, UUID id) {
    guard.requireTransaction(t);
    jdbc.execute("SET LOCAL statement_timeout='5s'");
    return RegistryRepository.one(
        jdbc.queryForList(
            "SELECT"
                + " id,name,asset_type,external_key,platform,connection_id,owner_team_id,inventory_state,provenance"
                + " FROM assets WHERE organization_id=? AND id=?",
            t.organizationId(),
            id));
  }

  public UUID create(TenantContext t, Asset r, String platform) {
    guard.requireTeamWrite(t, r.ownerTeamId());
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " assets(id,organization_id,connection_id,name,asset_type,external_key,platform,owner_team_id,description,owner,business_owner,provenance)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,'MANUAL_DECLARATION')",
        id,
        t.organizationId(),
        r.connectionId(),
        r.name(),
        r.assetType(),
        r.externalKey(),
        platform,
        r.ownerTeamId(),
        r.description() == null ? "" : r.description(),
        r.technicalOwner() == null ? r.owner() : r.technicalOwner(),
        r.businessOwner() == null ? "" : r.businessOwner());
    tags(t, id, r.tagIds());
    if (r.columns() != null) for (var c : r.columns()) column(t, id, null, c);
    audit(t, id, "ASSET_REGISTERED");
    return id;
  }

  public boolean update(TenantContext t, UUID id, Asset r) {
    guard.requireTransaction(t);
    int n =
        jdbc.update(
            "UPDATE assets SET"
                + " name=?,description=?,owner=?,business_owner=COALESCE(?,business_owner),revision=revision+1,name_curated=true"
                + " WHERE organization_id=? AND id=? AND revision=?",
            r.name(),
            r.description() == null ? "" : r.description(),
            r.technicalOwner() == null ? r.owner() : r.technicalOwner(),
            r.businessOwner(),
            t.organizationId(),
            id,
            r.revision());
    if (n == 1) {
      tags(t, id, r.tagIds());
      audit(t, id, "ASSET_CURATED");
    }
    return n == 1;
  }

  public UUID column(TenantContext t, UUID asset, UUID id, Column r) {
    guard.requireTransaction(t);
    if (id == null) {
      id = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO"
              + " asset_columns(id,organization_id,asset_id,name,data_type,description,nullable,ordinal_position,nullable_curated,ordinal_curated)"
              + " VALUES(?,?,?,?,?,?,?,?,?,?)",
          id,
          t.organizationId(),
          asset,
          r.name(),
          r.dataType(),
          r.description(),
          r.nullable(),
          r.ordinalPosition(),
          r.nullable() != null,
          r.ordinalPosition() != null);
    } else if (jdbc.update(
            "UPDATE asset_columns SET"
                + " description=?,nullable=?,ordinal_position=?,nullable_curated=true,ordinal_curated=true"
                + " WHERE organization_id=? AND asset_id=? AND id=?",
            r.description(),
            r.nullable(),
            r.ordinalPosition(),
            t.organizationId(),
            asset,
            id)
        != 1) throw RegistryRepository.missing();
    audit(t, asset, "COLUMN_CURATED");
    return id;
  }

  public void tags(TenantContext t, UUID asset, List<UUID> tags) {
    guard.requireTransaction(t);
    if (tags == null) return;
    var existing =
        new HashSet<>(
            jdbc.queryForList(
                "SELECT tag_id FROM asset_tags WHERE organization_id=? AND asset_id=?",
                UUID.class,
                t.organizationId(),
                asset));
    var desired = new HashSet<>(tags);
    for (UUID tag : existing)
      if (!desired.contains(tag))
        jdbc.update(
            "DELETE FROM asset_tags WHERE organization_id=? AND asset_id=? AND tag_id=?",
            t.organizationId(),
            asset,
            tag);
    for (UUID tag : desired)
      if (!existing.contains(tag))
        jdbc.update(
            "INSERT INTO asset_tags(organization_id,asset_id,tag_id) VALUES(?,?,?)",
            t.organizationId(),
            asset,
            tag);
  }

  public UUID tag(TenantContext t, String name) {
    guard.requireTransaction(t);
    UUID id =
        jdbc.queryForObject(
            "INSERT INTO tags(organization_id,name) VALUES(?,?) RETURNING id",
            UUID.class,
            t.organizationId(),
            name);
    audit(t, id, "TAG_CREATED");
    return id;
  }

  public List<Map<String, Object>> profiles(TenantContext t) {
    guard.requireTransaction(t);
    return jdbc.queryForList(
        "SELECT id,connection_id,name,mapping::text FROM import_mapping_profiles WHERE"
            + " organization_id=? ORDER BY name,id LIMIT 100",
        t.organizationId());
  }

  public UUID profile(TenantContext t, MappingProfile r) {
    guard.requireTransaction(t);
    return jdbc.queryForObject(
        "INSERT INTO import_mapping_profiles(organization_id,connection_id,name,mapping)"
            + " VALUES(?,?,?,?::jsonb) ON CONFLICT(organization_id,connection_id,name) DO UPDATE"
            + " SET mapping=EXCLUDED.mapping RETURNING id",
        UUID.class,
        t.organizationId(),
        r.connectionId(),
        r.name(),
        json.writeValueAsString(r.mapping()));
  }

  public List<Map<String, Object>> history(TenantContext t) {
    guard.requireTransaction(t);
    return jdbc.queryForList(
        "SELECT id,connection_id,status,filename,created_at,committed_at FROM import_batches WHERE"
            + " organization_id=? AND created_by_user_id=? ORDER BY created_at DESC,id LIMIT 100",
        t.organizationId(),
        t.userId());
  }

  public void audit(TenantContext t, UUID id, String event) {
    guard.requireTransaction(t);
    jdbc.queryForObject(
        "SELECT trace_audit(?,?,?,'METADATA',?,'SUCCESS')",
        Object.class,
        t.userId(),
        t.organizationId(),
        event,
        id);
  }
}
