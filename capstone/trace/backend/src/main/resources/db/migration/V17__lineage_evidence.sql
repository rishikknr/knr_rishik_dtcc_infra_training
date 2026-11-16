-- Additive immutable SQL evidence; existing mappings and registered snapshots remain intact.
CREATE TABLE lineage_evidence (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),organization_id uuid NOT NULL,
 target_asset_id uuid NOT NULL,definition text NOT NULL CHECK(length(definition) BETWEEN 1 AND 100000),
 definition_hash varchar(64) NOT NULL,dialect varchar(30) NOT NULL,source_ref text NOT NULL,
 analysis jsonb NOT NULL,created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,id),
 FOREIGN KEY(organization_id,target_asset_id) REFERENCES assets(organization_id,id),
 FOREIGN KEY(organization_id,created_by) REFERENCES organization_memberships(organization_id,user_id)
);
ALTER TABLE lineage_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE lineage_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY organization_isolation ON lineage_evidence
 USING(organization_id=public.trace_current_organization()) WITH CHECK(organization_id=public.trace_current_organization());
GRANT SELECT,INSERT ON lineage_evidence TO trace_runtime;
CREATE TRIGGER lineage_evidence_immutable BEFORE UPDATE OR DELETE ON lineage_evidence
 FOR EACH ROW EXECUTE FUNCTION trace_immutable_version();
ALTER TABLE lineage_edges ADD COLUMN evidence_id uuid;
ALTER TABLE lineage_edges ADD FOREIGN KEY(organization_id,evidence_id) REFERENCES lineage_evidence(organization_id,id);
ALTER TABLE column_lineage ADD COLUMN evidence_id uuid;
ALTER TABLE column_lineage ADD FOREIGN KEY(organization_id,evidence_id) REFERENCES lineage_evidence(organization_id,id);
CREATE INDEX lineage_evidence_target ON lineage_evidence(organization_id,target_asset_id,created_at DESC);
CREATE INDEX lineage_active_target ON lineage_edges(organization_id,target_asset_id,id) WHERE active;
CREATE INDEX lineage_active_source ON lineage_edges(organization_id,source_asset_id,id) WHERE active;
-- This additive capability does not change the minimum V16 runtime compatibility contract.
-- New versions capture business metadata at registration; legacy snapshots remain explicitly unknown.
ALTER TABLE metric_versions ADD COLUMN definition_snapshot jsonb;
