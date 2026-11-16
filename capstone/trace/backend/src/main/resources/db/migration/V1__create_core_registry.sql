
-- TRACE: Core Metadata Registry
-- Migration V1

CREATE TABLE source_connections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(150) NOT NULL UNIQUE,
    platform VARCHAR(40) NOT NULL,
    ingestion_mode VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE assets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id UUID NOT NULL
        REFERENCES source_connections(id),

    external_key VARCHAR(500) NOT NULL,
    name VARCHAR(255) NOT NULL,
    asset_type VARCHAR(40) NOT NULL,
    platform VARCHAR(40) NOT NULL,

    owner VARCHAR(150),
    refresh_cadence VARCHAR(100),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,

    last_seen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE(connection_id, external_key, asset_type)
);

CREATE TABLE asset_columns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    asset_id UUID NOT NULL
        REFERENCES assets(id),

    name VARCHAR(255) NOT NULL,
    data_type VARCHAR(100),
    description TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE(asset_id, name)
);

CREATE TABLE lineage_edges (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    source_asset_id UUID NOT NULL
        REFERENCES assets(id),

    target_asset_id UUID NOT NULL
        REFERENCES assets(id),

    relationship_type VARCHAR(50) NOT NULL,
    provenance VARCHAR(40) NOT NULL,
    evidence_ref TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT no_self_lineage
        CHECK(source_asset_id <> target_asset_id)
);

CREATE TABLE column_lineage (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    source_column_id UUID NOT NULL
        REFERENCES asset_columns(id),

    target_column_id UUID NOT NULL
        REFERENCES asset_columns(id),

    transformation_asset_id UUID
        REFERENCES assets(id),

    expression TEXT,
    provenance VARCHAR(40) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for graph traversal
CREATE INDEX idx_lineage_source
    ON lineage_edges(source_asset_id);

CREATE INDEX idx_lineage_target
    ON lineage_edges(target_asset_id);

CREATE INDEX idx_column_lineage_source
    ON column_lineage(source_column_id);

CREATE INDEX idx_column_lineage_target
    ON column_lineage(target_column_id);
