-- The runtime role must be provisioned before Flyway (ops/bootstrap-roles.sql).
-- No table access is granted to PUBLIC or the runtime on the global identity table.
DO $$ BEGIN
    IF NOT has_schema_privilege('trace_runtime', 'public', 'USAGE') THEN
        RAISE EXCEPTION 'Provision roles and schema grants using ops/bootstrap-roles.sql before Flyway';
    END IF;
END $$;
REVOKE ALL ON users FROM PUBLIC;
CREATE FUNCTION trace_current_organization() RETURNS UUID
LANGUAGE sql STABLE SET search_path = pg_catalog
AS $$ SELECT NULLIF(current_setting('trace.organization_id', true), '')::uuid $$;
REVOKE ALL ON FUNCTION trace_current_organization() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION trace_current_organization() TO trace_runtime;

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['organizations', 'organization_memberships', 'teams', 'team_memberships',
        'source_connections', 'assets', 'asset_columns', 'lineage_edges', 'column_lineage', 'import_batches'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY organization_isolation ON %I USING (%I = public.trace_current_organization()) WITH CHECK (%I = public.trace_current_organization())',
            t, CASE WHEN t = 'organizations' THEN 'id' ELSE 'organization_id' END,
            CASE WHEN t = 'organizations' THEN 'id' ELSE 'organization_id' END);
    END LOOP;
END $$;

-- Narrow read-only identity bridge. It returns only the selected active membership,
-- never a global user listing. Its owner is the migration role; FORCE RLS still applies.
CREATE FUNCTION trace_active_membership(selected_organization UUID, selected_user UUID)
RETURNS TABLE (organization_role VARCHAR)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog
AS $$
    SELECT m.role FROM public.organization_memberships m
    JOIN public.users u ON u.id = m.user_id AND u.active
    JOIN public.organizations o ON o.id = m.organization_id AND o.active
    WHERE m.organization_id = selected_organization AND m.user_id = selected_user
      AND m.status = 'ACTIVE' AND selected_organization = public.trace_current_organization()
$$;
REVOKE ALL ON FUNCTION trace_active_membership(UUID, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION trace_active_membership(UUID, UUID) TO trace_runtime;
GRANT SELECT ON organizations, organization_memberships, teams, team_memberships TO trace_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON source_connections, assets, asset_columns,
    lineage_edges, column_lineage, import_batches TO trace_runtime;
-- No DDL, identity mutation, membership mutation, TRUNCATE, or Flyway-history access.
