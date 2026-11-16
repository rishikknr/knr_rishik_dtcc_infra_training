-- Additive Step 10; existing identities and imported metadata are preserved.
ALTER TABLE source_connections ADD COLUMN description text NOT NULL DEFAULT '';
ALTER TABLE source_connections ADD COLUMN configuration jsonb NOT NULL DEFAULT '{}';
ALTER TABLE source_connections ADD COLUMN status varchar(30) NOT NULL DEFAULT 'NOT_TESTED'
 CHECK(status IN ('DRAFT','NOT_TESTED','CONNECTED','AUTHENTICATION_FAILED','PERMISSION_DENIED','UNAVAILABLE','CONFIGURATION_ERROR','DISABLED'));
ALTER TABLE source_connections ADD COLUMN reason_code varchar(80);
ALTER TABLE source_connections ADD COLUMN last_tested_at timestamptz;
ALTER TABLE source_connections ADD COLUMN last_successful_sync_at timestamptz;
ALTER TABLE source_connections ADD COLUMN created_by uuid;
ALTER TABLE source_connections ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE source_connections ADD FOREIGN KEY(organization_id,created_by) REFERENCES organization_memberships(organization_id,user_id);
CREATE TABLE connection_secret_storage (
 organization_id uuid NOT NULL, connection_id uuid NOT NULL, ciphertext bytea NOT NULL,
 nonce bytea NOT NULL CHECK(octet_length(nonce)=12), key_version integer NOT NULL DEFAULT 1,
 updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(organization_id,connection_id),
 FOREIGN KEY(organization_id,connection_id) REFERENCES source_connections(organization_id,id)
);
CREATE TABLE connection_sync_runs (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, connection_id uuid NOT NULL,
 initiated_by uuid NOT NULL, status varchar(30) NOT NULL CHECK(status IN ('RUNNING','COMPLETED','PARTIALLY_COMPLETED','FAILED','CANCELLED')),
 started_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz, records_discovered integer NOT NULL DEFAULT 0,
 error_code varchar(80), import_id uuid, UNIQUE(organization_id,id),
 FOREIGN KEY(organization_id,connection_id) REFERENCES source_connections(organization_id,id),
 FOREIGN KEY(organization_id,initiated_by) REFERENCES organization_memberships(organization_id,user_id),
 FOREIGN KEY(organization_id,import_id) REFERENCES import_batches(organization_id,id)
);
CREATE INDEX sync_connection ON connection_sync_runs(organization_id,connection_id,started_at DESC);
CREATE UNIQUE INDEX sync_one_running ON connection_sync_runs(organization_id,connection_id) WHERE status='RUNNING';
CREATE TABLE import_mapping_profiles (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, connection_id uuid NOT NULL,
 name varchar(150) NOT NULL, mapping jsonb NOT NULL, UNIQUE(organization_id,connection_id,name),
 FOREIGN KEY(organization_id,connection_id) REFERENCES source_connections(organization_id,id)
);
ALTER TABLE assets ADD COLUMN description text NOT NULL DEFAULT '';
ALTER TABLE assets ADD COLUMN provenance varchar(40) NOT NULL DEFAULT 'IMPORTED_METADATA';
ALTER TABLE assets ADD COLUMN revision integer NOT NULL DEFAULT 1;
ALTER TABLE asset_columns ADD COLUMN nullable boolean;
ALTER TABLE asset_columns ADD COLUMN ordinal_position integer;
ALTER TABLE lineage_edges ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE lineage_edges ADD COLUMN description text NOT NULL DEFAULT '';
ALTER TABLE lineage_edges ADD COLUMN created_by uuid;
ALTER TABLE lineage_edges ADD COLUMN revoked_at timestamptz;
ALTER TABLE lineage_edges ADD COLUMN supersedes uuid;
ALTER TABLE lineage_edges ADD FOREIGN KEY(organization_id,supersedes) REFERENCES lineage_edges(organization_id,id);
CREATE UNIQUE INDEX lineage_active_unique ON lineage_edges(organization_id,source_asset_id,target_asset_id,relationship_type) WHERE active;
ALTER TABLE column_lineage ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE column_lineage ADD COLUMN evidence_ref text;
ALTER TABLE column_lineage ADD COLUMN created_by uuid;
ALTER TABLE column_lineage ADD COLUMN revoked_at timestamptz;
CREATE UNIQUE INDEX column_lineage_active_unique ON column_lineage(organization_id,source_column_id,target_column_id) WHERE active;
CREATE TABLE formulas (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, owner_team_id uuid NOT NULL,
 name varchar(255) NOT NULL, description text NOT NULL DEFAULT '', category varchar(40) NOT NULL
 CHECK(category IN ('SQL_TRANSFORMATION','COLUMN_FORMULA','BUSINESS_METRIC','DAX_MEASURE','BUSINESS_RULE')),
 language varchar(30) NOT NULL CHECK(language IN ('SQL','DAX','BUSINESS_RULE')),
 asset_id uuid, UNIQUE(organization_id,id),
 FOREIGN KEY(organization_id,owner_team_id) REFERENCES teams(organization_id,id),
 FOREIGN KEY(organization_id,asset_id) REFERENCES assets(organization_id,id)
);
CREATE TABLE formula_versions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, formula_id uuid NOT NULL,
 version integer NOT NULL, expression text NOT NULL CHECK(length(expression) BETWEEN 1 AND 100000),
 state varchar(20) NOT NULL CHECK(state IN ('DRAFT','REGISTERED')), dialect varchar(30) NOT NULL DEFAULT 'snowflake',
 validation jsonb NOT NULL DEFAULT '{}', created_by uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,id), UNIQUE(organization_id,formula_id,version),
 FOREIGN KEY(organization_id,formula_id) REFERENCES formulas(organization_id,id),
 FOREIGN KEY(organization_id,created_by) REFERENCES organization_memberships(organization_id,user_id)
);
CREATE TABLE formula_dependencies (
 organization_id uuid NOT NULL, version_id uuid NOT NULL, asset_id uuid NOT NULL,
 PRIMARY KEY(organization_id,version_id,asset_id),
 FOREIGN KEY(organization_id,version_id) REFERENCES formula_versions(organization_id,id),
 FOREIGN KEY(organization_id,asset_id) REFERENCES assets(organization_id,id)
);
CREATE TABLE metrics (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, owner_team_id uuid NOT NULL,
 name varchar(255) NOT NULL, business_key varchar(100) NOT NULL, description text NOT NULL DEFAULT '',
 unit varchar(100) NOT NULL DEFAULT '', aggregation varchar(100) NOT NULL DEFAULT '', grain varchar(255) NOT NULL DEFAULT '',
 business_owner varchar(150) NOT NULL DEFAULT '', technical_owner varchar(150) NOT NULL DEFAULT '',
 UNIQUE(organization_id,id), UNIQUE(organization_id,business_key),
 FOREIGN KEY(organization_id,owner_team_id) REFERENCES teams(organization_id,id)
);
CREATE TABLE metric_versions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, metric_id uuid NOT NULL,
 version integer NOT NULL, formula_version_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,id), UNIQUE(organization_id,metric_id,version),
 FOREIGN KEY(organization_id,metric_id) REFERENCES metrics(organization_id,id),
 FOREIGN KEY(organization_id,formula_version_id) REFERENCES formula_versions(organization_id,id)
);
CREATE TABLE report_mappings (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, metric_version_id uuid NOT NULL,
 asset_id uuid NOT NULL, provenance varchar(40) NOT NULL DEFAULT 'MANUAL_DECLARATION', evidence_ref text NOT NULL,
 created_by uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,metric_version_id,asset_id),
 FOREIGN KEY(organization_id,metric_version_id) REFERENCES metric_versions(organization_id,id),
 FOREIGN KEY(organization_id,asset_id) REFERENCES assets(organization_id,id),
 FOREIGN KEY(organization_id,created_by) REFERENCES organization_memberships(organization_id,user_id)
);
-- Every new tenant relation enforces RLS even for the owner role.
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['connection_secret_storage','connection_sync_runs','import_mapping_profiles','formulas','formula_versions','formula_dependencies','metrics','metric_versions','report_mappings'] LOOP
 EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
 EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
 EXECUTE format('CREATE POLICY organization_isolation ON %I USING (organization_id=public.trace_current_organization()) WITH CHECK(organization_id=public.trace_current_organization())',t);
 EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I TO trace_runtime',t);
 END LOOP;
END $$;
-- Registered versions and all metric versions are append-only, including to runtime.
CREATE FUNCTION trace_immutable_version() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
 BEGIN RAISE EXCEPTION 'Registered version is immutable' USING ERRCODE='23514'; END $$;
CREATE TRIGGER formula_registered_immutable BEFORE UPDATE OR DELETE ON formula_versions
 FOR EACH ROW WHEN(OLD.state='REGISTERED') EXECUTE FUNCTION trace_immutable_version();
CREATE TRIGGER metric_version_immutable BEFORE UPDATE OR DELETE ON metric_versions
 FOR EACH ROW EXECUTE FUNCTION trace_immutable_version();
REVOKE ALL ON FUNCTION trace_immutable_version() FROM PUBLIC;

ALTER TABLE import_batches ADD COLUMN source_type varchar(40) NOT NULL DEFAULT 'SNOWFLAKE';
ALTER TABLE import_batches ADD COLUMN format varchar(40) NOT NULL DEFAULT 'CSV';

ALTER TABLE assets ADD COLUMN name_curated boolean NOT NULL DEFAULT false;

ALTER TABLE assets ADD COLUMN inventory_state varchar(50) NOT NULL DEFAULT 'CURRENT' CHECK(inventory_state IN ('CURRENT','NOT_SEEN_IN_LATEST_COMPLETE_INVENTORY'));
