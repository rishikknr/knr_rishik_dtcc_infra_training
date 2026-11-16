-- Existing UUIDs are preserved. Legacy records are quarantined in an organization
-- with no members; an operator must explicitly provision access after migration.
CREATE TABLE organizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(150) NOT NULL,
    slug VARCHAR(100) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(320) NOT NULL,
    display_name VARCHAR(150) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_email_unique ON users (lower(email));
CREATE TABLE organization_memberships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    user_id UUID NOT NULL REFERENCES users(id),
    role VARCHAR(20) NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INVITED', 'SUSPENDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, user_id)
);
CREATE INDEX organization_memberships_user ON organization_memberships(user_id, status);
CREATE TABLE teams (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    name VARCHAR(150) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, name)
);
CREATE TABLE team_memberships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    team_id UUID NOT NULL,
    user_id UUID NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, team_id, user_id),
    FOREIGN KEY (organization_id, team_id) REFERENCES teams(organization_id, id),
    FOREIGN KEY (organization_id, user_id) REFERENCES organization_memberships(organization_id, user_id)
);
CREATE INDEX team_memberships_user ON team_memberships(organization_id, user_id);

INSERT INTO organizations(id, name, slug)
VALUES ('00000000-0000-0000-0000-000000000001', 'Legacy registry (unassigned)', 'legacy-unassigned');

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['source_connections', 'assets', 'asset_columns', 'lineage_edges', 'column_lineage'] LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN organization_id UUID REFERENCES organizations(id)', t);
        EXECUTE format('UPDATE %I SET organization_id = %L', t, '00000000-0000-0000-0000-000000000001');
        EXECUTE format('ALTER TABLE %I ALTER COLUMN organization_id SET NOT NULL', t);
        EXECUTE format('ALTER TABLE %I ADD UNIQUE (organization_id, id)', t);
    END LOOP;
END $$;

ALTER TABLE source_connections DROP CONSTRAINT source_connections_name_key;
ALTER TABLE source_connections ADD UNIQUE (organization_id, name);
ALTER TABLE source_connections ADD COLUMN owner_team_id UUID;
ALTER TABLE source_connections ADD FOREIGN KEY (organization_id, owner_team_id) REFERENCES teams(organization_id, id);
ALTER TABLE source_connections ADD CHECK (owner_team_id IS NOT NULL OR organization_id = '00000000-0000-0000-0000-000000000001');
ALTER TABLE assets DROP CONSTRAINT assets_connection_id_fkey;
ALTER TABLE assets DROP CONSTRAINT assets_connection_id_external_key_asset_type_key;
ALTER TABLE assets ADD UNIQUE (organization_id, connection_id, external_key, asset_type);
ALTER TABLE assets ADD FOREIGN KEY (organization_id, connection_id) REFERENCES source_connections(organization_id, id);
ALTER TABLE assets ADD COLUMN owner_team_id UUID;
ALTER TABLE assets ADD FOREIGN KEY (organization_id, owner_team_id) REFERENCES teams(organization_id, id);
ALTER TABLE assets ADD CHECK (owner_team_id IS NOT NULL OR organization_id = '00000000-0000-0000-0000-000000000001');
COMMENT ON COLUMN assets.owner IS 'Descriptive legacy metadata only; never an authorization relationship';
ALTER TABLE asset_columns DROP CONSTRAINT asset_columns_asset_id_fkey;
ALTER TABLE asset_columns DROP CONSTRAINT asset_columns_asset_id_name_key;
ALTER TABLE asset_columns ADD UNIQUE (organization_id, asset_id, name);
ALTER TABLE asset_columns ADD FOREIGN KEY (organization_id, asset_id) REFERENCES assets(organization_id, id);
ALTER TABLE lineage_edges DROP CONSTRAINT lineage_edges_source_asset_id_fkey;
ALTER TABLE lineage_edges DROP CONSTRAINT lineage_edges_target_asset_id_fkey;
ALTER TABLE lineage_edges ADD FOREIGN KEY (organization_id, source_asset_id) REFERENCES assets(organization_id, id);
ALTER TABLE lineage_edges ADD FOREIGN KEY (organization_id, target_asset_id) REFERENCES assets(organization_id, id);
ALTER TABLE column_lineage DROP CONSTRAINT column_lineage_source_column_id_fkey;
ALTER TABLE column_lineage DROP CONSTRAINT column_lineage_target_column_id_fkey;
ALTER TABLE column_lineage DROP CONSTRAINT column_lineage_transformation_asset_id_fkey;
ALTER TABLE column_lineage ADD FOREIGN KEY (organization_id, source_column_id) REFERENCES asset_columns(organization_id, id);
ALTER TABLE column_lineage ADD FOREIGN KEY (organization_id, target_column_id) REFERENCES asset_columns(organization_id, id);
ALTER TABLE column_lineage ADD FOREIGN KEY (organization_id, transformation_asset_id) REFERENCES assets(organization_id, id);

CREATE TABLE import_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    connection_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    owner_team_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PREVIEW_READY', 'COMMITTED')),
    preview JSONB NOT NULL CHECK (jsonb_typeof(preview) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    committed_at TIMESTAMPTZ,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, connection_id) REFERENCES source_connections(organization_id, id),
    FOREIGN KEY (organization_id, created_by_user_id) REFERENCES organization_memberships(organization_id, user_id),
    FOREIGN KEY (organization_id, owner_team_id) REFERENCES teams(organization_id, id),
    CHECK ((status = 'COMMITTED') = (committed_at IS NOT NULL))
);
CREATE INDEX imports_connection ON import_batches(organization_id, connection_id, created_at);
CREATE INDEX imports_creator ON import_batches(organization_id, created_by_user_id);
CREATE INDEX connections_owner ON source_connections(organization_id, owner_team_id);
CREATE INDEX assets_owner ON assets(organization_id, owner_team_id);
CREATE INDEX lineage_source_org ON lineage_edges(organization_id, source_asset_id);
CREATE INDEX lineage_target_org ON lineage_edges(organization_id, target_asset_id);
CREATE INDEX column_lineage_source_org ON column_lineage(organization_id, source_column_id);
CREATE INDEX column_lineage_target_org ON column_lineage(organization_id, target_column_id);
CREATE INDEX column_lineage_transform_org ON column_lineage(organization_id, transformation_asset_id);
