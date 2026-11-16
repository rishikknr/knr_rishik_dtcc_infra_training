package com.trace.backend.repository;

import com.trace.backend.dto.response.MetadataPreview.Column;
import com.trace.backend.entity.AssetColumn;
import com.trace.backend.tenancy.TenantContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AssetColumnRepository {
  private final JdbcTemplate jdbc;
  private final RegistryRepository guard;

  public AssetColumnRepository(JdbcTemplate jdbc, RegistryRepository guard) {
    this.jdbc = jdbc;
    this.guard = guard;
  }

  public List<AssetColumn> list(TenantContext t, UUID asset) {
    guard.requireTransaction(t);
    return jdbc.query(
        "SELECT"
            + " id,asset_id,name,data_type,description,nullable,ordinal_position,nullable_curated,ordinal_curated,inventory_state"
            + " FROM asset_columns WHERE organization_id=? AND asset_id=? ORDER BY name,id",
        (r, n) ->
            new AssetColumn(
                r.getObject("id", UUID.class),
                r.getObject("asset_id", UUID.class),
                r.getString("name"),
                r.getString("data_type"),
                r.getString("description"),
                (Boolean) r.getObject("nullable"),
                (Integer) r.getObject("ordinal_position"),
                r.getBoolean("nullable_curated"),
                r.getBoolean("ordinal_curated"),
                r.getString("inventory_state")),
        t.organizationId(),
        asset);
  }

  public void inventory(TenantContext t, UUID asset, UUID batch) {
    guard.requireTransaction(t);
    jdbc.update(
        "UPDATE asset_columns SET inventory_state='NOT_SEEN_IN_LATEST_COMPLETE_INVENTORY' WHERE"
            + " organization_id=? AND asset_id=? AND last_import_id IS NOT NULL AND last_import_id"
            + " IS DISTINCT FROM ?",
        t.organizationId(),
        asset,
        batch);
  }

  public void upsert(TenantContext t, UUID asset, UUID batch, Column c) {
    guard.requireTransaction(t);
    jdbc.update(
"""
INSERT INTO asset_columns(organization_id,asset_id,name,data_type,description,last_import_id,nullable,ordinal_position) VALUES (?,?,?,?,?,?,?,?)
ON CONFLICT (organization_id,asset_id,name) DO UPDATE SET data_type=EXCLUDED.data_type,
description=COALESCE(asset_columns.description,EXCLUDED.description),last_import_id=EXCLUDED.last_import_id,inventory_state='CURRENT',
nullable=CASE WHEN asset_columns.nullable_curated THEN asset_columns.nullable ELSE COALESCE(EXCLUDED.nullable,asset_columns.nullable) END,
ordinal_position=CASE WHEN asset_columns.ordinal_curated THEN asset_columns.ordinal_position ELSE COALESCE(EXCLUDED.ordinal_position,asset_columns.ordinal_position) END
""",
        t.organizationId(),
        asset,
        c.name(),
        c.dataType(),
        c.description(),
        batch,
        c.nullable(),
        c.ordinalPosition());
  }
}
