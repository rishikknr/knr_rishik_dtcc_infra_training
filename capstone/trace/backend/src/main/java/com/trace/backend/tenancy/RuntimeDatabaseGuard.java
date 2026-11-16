package com.trace.backend.tenancy;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuse a privileged datasource even if RLS policies exist. Migration credentials are separate.
 */
@Component
@DependsOnDatabaseInitialization
public class RuntimeDatabaseGuard implements InitializingBean {
  private final JdbcTemplate jdbc;

  public RuntimeDatabaseGuard(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void afterPropertiesSet() {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT to_regprocedure('public.trace_schema_version()') IS NOT NULL", Boolean.class)))
      throw new IllegalStateException(
          "TRACE schema upgrade required: apply migrations through V16 using the operator rollout"
              + " before startup");
    Integer version = jdbc.queryForObject("SELECT public.trace_schema_version()", Integer.class);
    if (version == null || version != 15)
      throw new IllegalStateException(
          "TRACE schema and application release are incompatible; use the matching immutable"
              + " artifact");
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT count(*)=2 FROM information_schema.columns WHERE table_schema='public' AND"
                + " table_name='source_connections' AND column_name IN"
                + " ('last_successful_test_at','last_test_diagnostic_id')",
            Boolean.class)))
      throw new IllegalStateException(
          "TRACE connection workflow upgrade required: apply additive migration V16 before"
              + " startup");
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT to_regclass('public.lineage_evidence') IS NOT NULL AND EXISTS (SELECT 1 FROM"
                + " information_schema.columns WHERE table_schema='public' AND"
                + " table_name='metric_versions' AND column_name='definition_snapshot')",
            Boolean.class)))
      throw new IllegalStateException(
          "TRACE lineage upgrade required: apply additive migration V17 before startup");
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT to_regclass('public.governance_reviews') IS NOT NULL AND"
                + " to_regclass('public.governance_events') IS NOT NULL",
            Boolean.class)))
      throw new IllegalStateException(
          "TRACE governance upgrade required: apply additive migration V18 before startup");
    Boolean unsafe =
        jdbc.queryForObject(
"""
SELECT r.rolsuper OR r.rolbypassrls OR r.rolcreaterole OR r.rolcreatedb OR r.rolreplication
    OR current_user <> 'trace_runtime'
    OR session_user <> current_user
    OR has_schema_privilege(current_user, 'public', 'CREATE')
    OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relkind = 'r'
        AND pg_has_role(current_user, c.relowner, 'MEMBER'))
    OR EXISTS (SELECT 1 FROM pg_roles other WHERE other.oid <> r.oid
        AND pg_has_role(current_user, other.oid, 'MEMBER'))
    OR has_table_privilege(current_user, 'public.users', 'SELECT')
    OR has_table_privilege(current_user, 'public.user_credentials', 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE')
    OR has_table_privilege(current_user, 'public.password_reset_tokens', 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE')
    OR has_table_privilege(current_user, 'public.flyway_schema_history', 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
    OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relname IN
            ('users', 'organizations', 'organization_memberships', 'teams', 'team_memberships', 'organization_invitations', 'audit_events')
        AND has_table_privilege(current_user, c.oid, 'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'))
    OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relname IN ('organizations', 'organization_memberships',
            'teams', 'team_memberships', 'source_connections', 'assets', 'asset_columns',
            'lineage_edges', 'column_lineage', 'import_batches', 'organization_invitations', 'audit_events',
            'connection_secret_storage','connection_sync_runs','import_mapping_profiles','formulas','formula_versions',
            'formula_dependencies','formula_column_dependencies','metrics','metric_versions','report_mappings','tags','asset_tags','asset_curation_history','connection_sync_attempts','metadata_change_history','lineage_evidence','governance_reviews','governance_events')
        AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity))
FROM pg_roles r WHERE r.rolname = current_user
""",
            Boolean.class);
    if (!Boolean.FALSE.equals(unsafe)) {
      throw new IllegalStateException(
          "Unsafe runtime database role; provision isolated trace_runtime credentials (see"
              + " docs/tenancy.md)");
    }
  }
}
