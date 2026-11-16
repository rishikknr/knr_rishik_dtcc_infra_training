-- Preserve human column curation and distinguish absence from deletion.
ALTER TABLE asset_columns ADD COLUMN nullable_curated boolean NOT NULL DEFAULT false;
ALTER TABLE asset_columns ADD COLUMN ordinal_curated boolean NOT NULL DEFAULT false;
ALTER TABLE asset_columns ADD COLUMN inventory_state varchar(50) NOT NULL DEFAULT 'CURRENT'
 CHECK(inventory_state IN ('CURRENT','NOT_SEEN_IN_LATEST_COMPLETE_INVENTORY'));
ALTER TABLE import_batches ADD COLUMN configuration_updated_at timestamptz;
-- Existing previews lack an acquisition configuration snapshot; require a fresh preview.
CREATE TABLE metadata_change_history (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),organization_id uuid NOT NULL,
 asset_id uuid NOT NULL,column_id uuid,import_id uuid,actor_user_id uuid,
 event_type varchar(40) NOT NULL,previous_value jsonb NOT NULL,new_value jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(organization_id,asset_id) REFERENCES assets(organization_id,id),
 FOREIGN KEY(organization_id,column_id) REFERENCES asset_columns(organization_id,id),
 FOREIGN KEY(organization_id,import_id) REFERENCES import_batches(organization_id,id),
 FOREIGN KEY(organization_id,actor_user_id) REFERENCES organization_memberships(organization_id,user_id)
);
ALTER TABLE metadata_change_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE metadata_change_history FORCE ROW LEVEL SECURITY;
CREATE POLICY organization_isolation ON metadata_change_history
 USING(organization_id=public.trace_current_organization()) WITH CHECK(organization_id=public.trace_current_organization());
GRANT SELECT,INSERT ON metadata_change_history TO trace_runtime;
CREATE INDEX metadata_history_asset ON metadata_change_history(organization_id,asset_id,created_at DESC,id);
CREATE FUNCTION trace_metadata_change_history() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE before_value jsonb;after_value jsonb;parent uuid;child uuid; BEGIN
 IF TG_TABLE_NAME='assets' THEN
  parent=NEW.id;
  before_value=jsonb_build_object('metadata',OLD.metadata,'inventoryState',OLD.inventory_state);
  after_value=jsonb_build_object('metadata',NEW.metadata,'inventoryState',NEW.inventory_state);
 ELSE
  parent=NEW.asset_id;child=NEW.id;
  before_value=jsonb_build_object('dataType',OLD.data_type,'nullable',OLD.nullable,'ordinalPosition',OLD.ordinal_position,'description',OLD.description,'inventoryState',OLD.inventory_state);
  after_value=jsonb_build_object('dataType',NEW.data_type,'nullable',NEW.nullable,'ordinalPosition',NEW.ordinal_position,'description',NEW.description,'inventoryState',NEW.inventory_state);
 END IF;
 IF before_value IS DISTINCT FROM after_value THEN
  INSERT INTO public.metadata_change_history(organization_id,asset_id,column_id,import_id,actor_user_id,event_type,previous_value,new_value)
  VALUES(NEW.organization_id,parent,child,NULLIF(current_setting('trace.import_id',true),'')::uuid,NULLIF(current_setting('trace.user_id',true),'')::uuid,
   CASE WHEN TG_TABLE_NAME='assets' THEN 'SOURCE_METADATA_CHANGED' ELSE 'COLUMN_METADATA_CHANGED' END,before_value,after_value);
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER asset_metadata_history AFTER UPDATE ON assets FOR EACH ROW EXECUTE FUNCTION trace_metadata_change_history();
CREATE TRIGGER column_metadata_history AFTER UPDATE ON asset_columns FOR EACH ROW EXECUTE FUNCTION trace_metadata_change_history();
REVOKE ALL ON FUNCTION trace_metadata_change_history() FROM PUBLIC;
CREATE OR REPLACE FUNCTION trace_schema_version() RETURNS integer LANGUAGE sql IMMUTABLE
 SET search_path=pg_catalog AS $$ SELECT 13 $$;

-- Version-specific usage is revocable without erasing historical evidence.
ALTER TABLE report_mappings ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE report_mappings ADD COLUMN revoked_at timestamptz;
ALTER TABLE report_mappings DROP CONSTRAINT report_mappings_organization_id_metric_version_id_asset_id_key;
CREATE UNIQUE INDEX report_mapping_active ON report_mappings(organization_id,metric_version_id,asset_id) WHERE active;
ALTER TABLE metrics ADD COLUMN revision integer NOT NULL DEFAULT 1;
