-- Extend existing imports without altering tenant ownership, RLS, grants, or identities.
ALTER TABLE import_batches ADD COLUMN comparison JSONB;
ALTER TABLE import_batches ADD COLUMN results JSONB;
ALTER TABLE import_batches ADD COLUMN dataset_changes JSONB CHECK (dataset_changes IS NULL OR jsonb_typeof(dataset_changes)='array');
ALTER TABLE import_batches ADD COLUMN filename VARCHAR(255);
ALTER TABLE import_batches ADD COLUMN file_size BIGINT NOT NULL DEFAULT 0 CHECK (file_size BETWEEN 0 AND 5242880);
ALTER TABLE import_batches ADD COLUMN expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '24 hours');
ALTER TABLE import_batches ADD CHECK (comparison IS NULL OR jsonb_typeof(comparison) = 'object');
ALTER TABLE import_batches ADD CHECK (results IS NULL OR jsonb_typeof(results) = 'object');
ALTER TABLE assets ADD COLUMN last_import_id UUID;
ALTER TABLE assets ADD FOREIGN KEY (organization_id,last_import_id) REFERENCES import_batches(organization_id,id);
ALTER TABLE asset_columns ADD COLUMN last_import_id UUID;
ALTER TABLE asset_columns ADD FOREIGN KEY (organization_id,last_import_id) REFERENCES import_batches(organization_id,id);
CREATE INDEX assets_catalog ON assets(organization_id,name,id);
CREATE INDEX assets_import ON assets(organization_id,last_import_id);
CREATE INDEX columns_import ON asset_columns(organization_id,last_import_id);
