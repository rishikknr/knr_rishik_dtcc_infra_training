-- Build dependencies while DRAFT, then register in the same transaction.
-- Lock the parent to serialize dependency writes with registration.
CREATE OR REPLACE FUNCTION trace_draft_dependencies_only() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE parent_org uuid; parent_version uuid; BEGIN
 parent_org=CASE WHEN TG_OP='INSERT' THEN NEW.organization_id ELSE OLD.organization_id END;
 parent_version=CASE WHEN TG_OP='INSERT' THEN NEW.version_id ELSE OLD.version_id END;
 PERFORM 1 FROM public.formula_versions v WHERE v.organization_id=parent_org
   AND v.id=parent_version AND v.state='DRAFT' FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Registered dependencies are immutable' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND (NEW.organization_id,NEW.version_id) IS DISTINCT FROM (OLD.organization_id,OLD.version_id) THEN
   RAISE EXCEPTION 'Dependency parent is immutable' USING ERRCODE='23514';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
DROP TRIGGER immutable_asset_dependency ON formula_dependencies;
DROP TRIGGER immutable_column_dependency ON formula_column_dependencies;
CREATE TRIGGER immutable_asset_dependency BEFORE INSERT OR UPDATE OR DELETE ON formula_dependencies
 FOR EACH ROW EXECUTE FUNCTION trace_draft_dependencies_only();
CREATE TRIGGER immutable_column_dependency BEFORE INSERT OR UPDATE OR DELETE ON formula_column_dependencies
 FOR EACH ROW EXECUTE FUNCTION trace_draft_dependencies_only();
CREATE FUNCTION trace_registered_metric_formula() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog AS $$ BEGIN
 PERFORM 1 FROM public.formula_versions WHERE organization_id=NEW.organization_id
   AND id=NEW.formula_version_id AND state='REGISTERED' FOR KEY SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Registered formula required' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER metric_registered_formula BEFORE INSERT ON metric_versions
 FOR EACH ROW EXECUTE FUNCTION trace_registered_metric_formula();
REVOKE ALL ON FUNCTION trace_registered_metric_formula() FROM PUBLIC;
-- A release contract independent of runtime access to Flyway history.
CREATE FUNCTION trace_schema_version() RETURNS integer LANGUAGE sql IMMUTABLE
 SET search_path=pg_catalog AS $$ SELECT 12 $$;
REVOKE ALL ON FUNCTION trace_schema_version() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION trace_schema_version() TO trace_runtime;
