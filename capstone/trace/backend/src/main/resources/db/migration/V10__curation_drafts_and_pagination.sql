ALTER TABLE assets ADD COLUMN business_owner varchar(150) NOT NULL DEFAULT '';
ALTER TABLE formula_versions ADD COLUMN revision integer NOT NULL DEFAULT 1;
ALTER TABLE formula_versions ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE formula_versions ADD COLUMN updated_by uuid;
ALTER TABLE formula_versions ADD FOREIGN KEY(organization_id,updated_by) REFERENCES organization_memberships(organization_id,user_id);
CREATE TABLE tags (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),organization_id uuid NOT NULL REFERENCES organizations(id),
 name varchar(80) NOT NULL CHECK(length(trim(name))>0),created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,id)
);
CREATE UNIQUE INDEX tags_name ON tags(organization_id,lower(name));
CREATE TABLE asset_tags (
 organization_id uuid NOT NULL,asset_id uuid NOT NULL,tag_id uuid NOT NULL,
 PRIMARY KEY(organization_id,asset_id,tag_id),
 FOREIGN KEY(organization_id,asset_id) REFERENCES assets(organization_id,id),
 FOREIGN KEY(organization_id,tag_id) REFERENCES tags(organization_id,id)
);
CREATE TABLE asset_curation_history (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),organization_id uuid NOT NULL,asset_id uuid NOT NULL,
 actor_user_id uuid,created_at timestamptz NOT NULL DEFAULT now(),event_type varchar(40) NOT NULL,
 previous_value jsonb,new_value jsonb,
 FOREIGN KEY(organization_id,asset_id) REFERENCES assets(organization_id,id),
 FOREIGN KEY(organization_id,actor_user_id) REFERENCES organization_memberships(organization_id,user_id)
);
DO $$ DECLARE t text;BEGIN FOREACH t IN ARRAY ARRAY['tags','asset_tags','asset_curation_history'] LOOP
 EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
 EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
 EXECUTE format('CREATE POLICY organization_isolation ON %I USING(organization_id=public.trace_current_organization()) WITH CHECK(organization_id=public.trace_current_organization())',t);
 END LOOP;END $$;
GRANT SELECT,INSERT ON tags,asset_curation_history TO trace_runtime;
GRANT SELECT,INSERT,DELETE ON asset_tags TO trace_runtime;
GRANT DELETE ON formula_dependencies,formula_column_dependencies TO trace_runtime;
CREATE FUNCTION trace_draft_dependencies_only() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM public.formula_versions v WHERE v.organization_id=OLD.organization_id AND v.id=OLD.version_id AND v.state='DRAFT') THEN RAISE EXCEPTION 'Registered dependencies are immutable' USING ERRCODE='23514'; END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER immutable_asset_dependency BEFORE UPDATE OR DELETE ON formula_dependencies FOR EACH ROW EXECUTE FUNCTION trace_draft_dependencies_only();
CREATE TRIGGER immutable_column_dependency BEFORE UPDATE OR DELETE ON formula_column_dependencies FOR EACH ROW EXECUTE FUNCTION trace_draft_dependencies_only();
CREATE FUNCTION trace_asset_curation_history() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE before_value jsonb;after_value jsonb; BEGIN
 before_value=jsonb_build_object('name',OLD.name,'technicalOwner',OLD.owner,'businessOwner',OLD.business_owner,'owningTeam',OLD.owner_team_id,'description',OLD.description);
 after_value=jsonb_build_object('name',NEW.name,'technicalOwner',NEW.owner,'businessOwner',NEW.business_owner,'owningTeam',NEW.owner_team_id,'description',NEW.description);
 IF before_value IS DISTINCT FROM after_value THEN
  INSERT INTO public.asset_curation_history(organization_id,asset_id,actor_user_id,event_type,previous_value,new_value)
  VALUES(NEW.organization_id,NEW.id,NULLIF(current_setting('trace.user_id',true),'')::uuid,'CURATED_FIELDS_CHANGED',before_value,after_value);
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER asset_curation_audit AFTER UPDATE ON assets FOR EACH ROW EXECUTE FUNCTION trace_asset_curation_history();
CREATE FUNCTION trace_tag_history() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE row_data public.asset_tags;BEGIN
 row_data=CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
 INSERT INTO public.asset_curation_history(organization_id,asset_id,actor_user_id,event_type,previous_value,new_value)
 VALUES(row_data.organization_id,row_data.asset_id,NULLIF(current_setting('trace.user_id',true),'')::uuid,
 CASE WHEN TG_OP='DELETE' THEN 'TAG_REMOVED' ELSE 'TAG_ADDED' END,
 CASE WHEN TG_OP='DELETE' THEN jsonb_build_object('tagId',row_data.tag_id) ELSE NULL END,
 CASE WHEN TG_OP='INSERT' THEN jsonb_build_object('tagId',row_data.tag_id) ELSE NULL END);
 RETURN row_data;
END $$;
CREATE TRIGGER tag_history AFTER INSERT OR DELETE ON asset_tags FOR EACH ROW EXECUTE FUNCTION trace_tag_history();
REVOKE ALL ON FUNCTION trace_draft_dependencies_only(),trace_asset_curation_history(),trace_tag_history() FROM PUBLIC;
-- Deterministic tenant/name/ID and tenant/time/ID access paths used by bounded browse APIs.
CREATE INDEX connections_browse ON source_connections(organization_id,name,id);
CREATE INDEX assets_browse ON assets(organization_id,name,id);
CREATE INDEX teams_browse ON teams(organization_id,name,id);
CREATE INDEX formulas_browse ON formulas(organization_id,name,id);
CREATE INDEX metrics_browse ON metrics(organization_id,name,id);
CREATE INDEX imports_browse ON import_batches(organization_id,created_by_user_id,created_at DESC,id);
CREATE INDEX history_browse ON asset_curation_history(organization_id,asset_id,created_at DESC,id);
CREATE INDEX lineage_browse ON lineage_edges(organization_id,created_at DESC,id);
CREATE INDEX column_lineage_browse ON column_lineage(organization_id,created_at DESC,id);
CREATE INDEX formula_asset_lookup ON formula_dependencies(organization_id,asset_id,version_id);
CREATE INDEX formula_column_lookup ON formula_column_dependencies(organization_id,column_id,version_id);
