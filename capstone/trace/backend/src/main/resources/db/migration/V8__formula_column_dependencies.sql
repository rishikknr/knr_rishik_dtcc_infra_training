CREATE TABLE formula_column_dependencies (
 organization_id uuid NOT NULL, version_id uuid NOT NULL, column_id uuid NOT NULL,
 PRIMARY KEY(organization_id,version_id,column_id),
 FOREIGN KEY(organization_id,version_id) REFERENCES formula_versions(organization_id,id),
 FOREIGN KEY(organization_id,column_id) REFERENCES asset_columns(organization_id,id)
);
ALTER TABLE formula_column_dependencies ENABLE ROW LEVEL SECURITY;
ALTER TABLE formula_column_dependencies FORCE ROW LEVEL SECURITY;
CREATE POLICY organization_isolation ON formula_column_dependencies
 USING(organization_id=public.trace_current_organization()) WITH CHECK(organization_id=public.trace_current_organization());
GRANT SELECT,INSERT ON formula_column_dependencies TO trace_runtime;
-- Formula dependencies are immutable snapshots; only their initial creation is supported.
REVOKE UPDATE ON formula_dependencies FROM trace_runtime;
