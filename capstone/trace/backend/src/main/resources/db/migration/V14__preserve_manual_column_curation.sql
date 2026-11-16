-- Older manually registered columns predate per-field curation markers.
-- A temporary owner-only migration policy reaches those tenant rows under FORCE RLS.
-- It is removed in this transaction; runtime policies and privileges never change.
CREATE POLICY curation_migration_read ON assets FOR SELECT TO trace_migrator USING(true);
CREATE POLICY curation_migration_write ON asset_columns TO trace_migrator USING(true) WITH CHECK(true);
UPDATE asset_columns c SET nullable_curated=true,ordinal_curated=true
 FROM assets a WHERE a.organization_id=c.organization_id AND a.id=c.asset_id
 AND a.provenance='MANUAL_DECLARATION' AND c.last_import_id IS NULL;
DROP POLICY curation_migration_read ON assets;
DROP POLICY curation_migration_write ON asset_columns;
CREATE OR REPLACE FUNCTION trace_schema_version() RETURNS integer LANGUAGE sql IMMUTABLE
 SET search_path=pg_catalog AS $$ SELECT 14 $$;
