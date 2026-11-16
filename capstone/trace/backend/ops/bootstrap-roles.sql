-- Run as the existing PostgreSQL administrator against trace, before Flyway.
-- psql reads these secrets from the process environment; never commit passwords.
\set ON_ERROR_STOP on
\getenv migration_password TRACE_MIGRATION_PASSWORD
\getenv runtime_password TRACE_RUNTIME_PASSWORD
-- Validate secrets before ANY database changes. Do not invent or print passwords.
\if :{?migration_password}
\else
  DO $$ BEGIN RAISE EXCEPTION 'Export TRACE_MIGRATION_PASSWORD before provisioning'; END $$;
\endif
\if :{?runtime_password}
\else
  DO $$ BEGIN RAISE EXCEPTION 'Export TRACE_RUNTIME_PASSWORD before provisioning'; END $$;
\endif
SELECT length(:'migration_password') > 0 AND length(:'runtime_password') > 0
  AND :'migration_password' <> :'runtime_password' AS secrets_valid \gset
\if :secrets_valid
\else
  DO $$ BEGIN RAISE EXCEPTION 'Provisioning requires distinct, non-empty migration and runtime secrets'; END $$;
\endif
BEGIN;
SELECT 'CREATE ROLE trace_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'trace_migrator') \gexec
SELECT 'CREATE ROLE trace_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'trace_runtime') \gexec
ALTER ROLE trace_migrator PASSWORD :'migration_password';
ALTER ROLE trace_runtime PASSWORD :'runtime_password';
ALTER ROLE trace_migrator NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE trace_runtime NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO trace_migrator;
GRANT USAGE ON SCHEMA public TO trace_runtime;
SELECT format('GRANT CONNECT ON DATABASE %I TO trace_migrator, trace_runtime', current_database()) \gexec
-- Transfer only TRACE tables in an existing V1 installation, preserving rows and UUIDs.
SELECT format('ALTER TABLE public.%I OWNER TO trace_migrator', tablename)
FROM pg_tables WHERE schemaname = 'public' AND tablename IN
    ('flyway_schema_history', 'source_connections', 'assets', 'asset_columns', 'lineage_edges', 'column_lineage') \gexec
-- Both roles must have no role memberships. Startup rejects inherited privileges.
-- This script intentionally does not change the existing trace admin or port 5433.

COMMIT;
