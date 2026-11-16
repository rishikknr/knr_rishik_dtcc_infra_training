-- Extend the existing identities; no passwords are assigned to legacy users.
ALTER TABLE users ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
 ADD COLUMN last_login_at timestamptz,
 ADD COLUMN platform_role varchar(20) NOT NULL DEFAULT 'USER' CHECK (platform_role IN ('USER','PLATFORM_ADMIN')),
 ADD COLUMN security_version bigint NOT NULL DEFAULT 0;
UPDATE users SET email=lower(trim(email));
ALTER TABLE users ADD CHECK (email=lower(trim(email)));
ALTER TABLE organizations ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE organization_memberships DROP CONSTRAINT organization_memberships_role_check;
ALTER TABLE organization_memberships ADD CHECK (role IN ('OWNER','ADMIN','MEMBER','VIEWER'));
ALTER TABLE organization_memberships DROP CONSTRAINT organization_memberships_status_check;
ALTER TABLE organization_memberships ADD CHECK (status IN ('ACTIVE','INVITED','SUSPENDED','REVOKED'));
ALTER TABLE organization_memberships ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE teams ADD COLUMN slug varchar(100), ADD COLUMN description varchar(2000) NOT NULL DEFAULT '',
 ADD COLUMN active boolean NOT NULL DEFAULT true, ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
UPDATE teams SET slug=id::text;
ALTER TABLE teams ALTER COLUMN slug SET NOT NULL;
ALTER TABLE teams ALTER COLUMN slug SET DEFAULT gen_random_uuid()::text;
CREATE UNIQUE INDEX teams_org_slug ON teams(organization_id,slug);
ALTER TABLE team_memberships ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
CREATE TABLE user_credentials (
 user_id uuid PRIMARY KEY REFERENCES users(id), password_hash varchar(100) NOT NULL,
 password_changed_at timestamptz NOT NULL DEFAULT now(), must_change_password boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE organization_invitations (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL REFERENCES organizations(id),
 email varchar(320) NOT NULL CHECK(email=lower(trim(email))),
 role varchar(20) NOT NULL CHECK(role IN ('OWNER','ADMIN','MEMBER','VIEWER')),
 token_hash varchar(64) NOT NULL UNIQUE, expires_at timestamptz NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACCEPTED','REVOKED')),
 invited_by uuid NOT NULL REFERENCES users(id), accepted_by uuid REFERENCES users(id),
 created_at timestamptz NOT NULL DEFAULT now(), accepted_at timestamptz
);
CREATE UNIQUE INDEX invitation_pending_email ON organization_invitations(organization_id,email) WHERE status='PENDING';
CREATE TABLE password_reset_tokens (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES users(id),
 token_hash varchar(64) NOT NULL UNIQUE, expires_at timestamptz NOT NULL,
 consumed_at timestamptz, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE audit_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), actor_user_id uuid REFERENCES users(id),
 organization_id uuid REFERENCES organizations(id), event_type varchar(80) NOT NULL,
 resource_type varchar(80) NOT NULL, resource_id uuid, outcome varchar(20) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), metadata jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(metadata)='object')
);
CREATE INDEX audit_org_date ON audit_events(organization_id,created_at DESC);
ALTER TABLE organization_invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_invitations FORCE ROW LEVEL SECURITY;
CREATE POLICY organization_isolation ON organization_invitations
 USING (organization_id=trace_current_organization()) WITH CHECK (organization_id=trace_current_organization());
ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_events FORCE ROW LEVEL SECURITY;
CREATE POLICY organization_isolation ON audit_events
 USING (organization_id=trace_current_organization()) WITH CHECK (organization_id=trace_current_organization());
-- Only narrowly scoped definer functions use these discovery policies. Business RLS stays unchanged.
CREATE POLICY identity_discovery ON organization_memberships FOR SELECT TO trace_migrator
 USING (user_id=nullif(current_setting('trace.auth_user_id',true),'')::uuid);
CREATE POLICY identity_discovery ON organizations FOR SELECT TO trace_migrator
 USING (id IN (SELECT organization_id FROM organization_memberships
 WHERE user_id=nullif(current_setting('trace.auth_user_id',true),'')::uuid));
CREATE POLICY platform_directory ON organizations FOR ALL TO trace_migrator
 USING (EXISTS(SELECT 1 FROM users WHERE id=nullif(current_setting('trace.platform_actor',true),'')::uuid AND active AND platform_role='PLATFORM_ADMIN'))
 WITH CHECK (EXISTS(SELECT 1 FROM users WHERE id=nullif(current_setting('trace.platform_actor',true),'')::uuid AND active AND platform_role='PLATFORM_ADMIN'));
CREATE POLICY token_lookup ON organization_invitations FOR SELECT TO trace_migrator
 USING (token_hash=current_setting('trace.invitation_hash',true));
CREATE POLICY global_audit_append ON audit_events FOR INSERT TO trace_migrator WITH CHECK (organization_id IS NULL);
GRANT SELECT ON organization_invitations,audit_events TO trace_runtime;

CREATE FUNCTION trace_profile(who uuid) RETURNS TABLE(id uuid,email varchar,display_name varchar,active boolean,platform_role varchar,security_version bigint)
 LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT id,email,display_name,active,platform_role,security_version FROM public.users WHERE id=who $$;
CREATE FUNCTION trace_credentials(login_email text) RETURNS TABLE(id uuid,email varchar,password_hash varchar,active boolean,security_version bigint)
 LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT u.id,u.email,c.password_hash,u.active,u.security_version FROM public.users u JOIN public.user_credentials c ON c.user_id=u.id
 WHERE u.email=lower(trim(login_email)) $$;
CREATE FUNCTION trace_my_organizations(who uuid) RETURNS TABLE(id uuid,name varchar,slug varchar,role varchar)
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ BEGIN
 PERFORM set_config('trace.auth_user_id',who::text,true);
 RETURN QUERY SELECT o.id,o.name,o.slug,m.role FROM public.organizations o JOIN public.organization_memberships m ON m.organization_id=o.id
 JOIN public.users u ON u.id=m.user_id AND u.active WHERE m.user_id=who AND m.status='ACTIVE' AND o.active ORDER BY o.name;
 END $$;
CREATE FUNCTION trace_audit(actor uuid,org uuid,event text,kind text,resource uuid,result text,details jsonb DEFAULT '{}'::jsonb) RETURNS void
 LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 INSERT INTO public.audit_events(actor_user_id,organization_id,event_type,resource_type,resource_id,outcome,metadata)
 VALUES(actor,org,event,kind,resource,result,details) $$;
CREATE FUNCTION trace_login_success(who uuid) RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 UPDATE public.users SET last_login_at=now() WHERE id=who AND active;
 SELECT public.trace_audit(who,null,'LOGIN_SUCCESS','USER',who,'SUCCESS') $$;
CREATE FUNCTION trace_password_set(who uuid,encoded text) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ BEGIN
 UPDATE public.user_credentials SET password_hash=encoded,password_changed_at=now(),must_change_password=false,updated_at=now() WHERE user_id=who;
 IF NOT FOUND THEN RAISE EXCEPTION 'Local credentials required' USING ERRCODE='23514'; END IF;
 UPDATE public.users SET security_version=security_version+1,updated_at=now() WHERE id=who;
 UPDATE public.password_reset_tokens SET consumed_at=now() WHERE user_id=who AND consumed_at IS NULL;
 END $$;
-- Operator-only function. Runtime is deliberately not granted EXECUTE.
CREATE FUNCTION trace_bootstrap_admin(login_email text,label text,encoded text) RETURNS uuid
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ DECLARE who uuid; BEGIN
 PERFORM pg_advisory_xact_lock(908001);
 IF EXISTS(SELECT 1 FROM public.users WHERE platform_role='PLATFORM_ADMIN') THEN RAISE EXCEPTION 'Bootstrap already completed'; END IF;
 INSERT INTO public.users(email,display_name,platform_role) VALUES(lower(trim(login_email)),label,'PLATFORM_ADMIN') RETURNING id INTO who;
 INSERT INTO public.user_credentials(user_id,password_hash) VALUES(who,encoded);
 PERFORM public.trace_audit(who,null,'ADMIN_BOOTSTRAPPED','USER',who,'SUCCESS'); RETURN who;
 END $$;
CREATE FUNCTION trace_platform_organizations(actor uuid) RETURNS TABLE(id uuid,name varchar,slug varchar,active boolean)
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM public.users WHERE users.id=actor AND users.active AND platform_role='PLATFORM_ADMIN') THEN RAISE EXCEPTION 'Forbidden' USING ERRCODE='42501'; END IF;
 PERFORM set_config('trace.platform_actor',actor::text,true);
 RETURN QUERY SELECT o.id,o.name,o.slug,o.active FROM public.organizations o ORDER BY o.name;
 END $$;
CREATE FUNCTION trace_create_organization(actor uuid,label text,org_slug text) RETURNS uuid
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ DECLARE org uuid; BEGIN
 IF NOT EXISTS(SELECT 1 FROM public.users WHERE id=actor AND active AND platform_role='PLATFORM_ADMIN') THEN RAISE EXCEPTION 'Forbidden' USING ERRCODE='42501'; END IF;
 org:=gen_random_uuid(); PERFORM set_config('trace.organization_id',org::text,true);
 INSERT INTO public.organizations(id,name,slug) VALUES(org,label,org_slug);
 INSERT INTO public.organization_memberships(organization_id,user_id,role) VALUES(org,actor,'OWNER');
 PERFORM public.trace_audit(actor,org,'ORGANIZATION_MEMBERSHIP_CHANGED','USER',actor,'SUCCESS',jsonb_build_object('role','OWNER','status','ACTIVE'));
 PERFORM public.trace_audit(actor,org,'ORGANIZATION_CREATED','ORGANIZATION',org,'SUCCESS'); RETURN org;
 END $$;
CREATE FUNCTION trace_suspend_organization(actor uuid,org uuid,enabled boolean) RETURNS void
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM public.users WHERE id=actor AND active AND platform_role='PLATFORM_ADMIN') THEN RAISE EXCEPTION 'Forbidden' USING ERRCODE='42501'; END IF;
 PERFORM set_config('trace.platform_actor',actor::text,true); PERFORM set_config('trace.organization_id',org::text,true);
 UPDATE public.organizations SET active=enabled,updated_at=now() WHERE id=org;
 IF NOT FOUND THEN RAISE EXCEPTION 'Not found' USING ERRCODE='P0002'; END IF;
 PERFORM public.trace_audit(actor,org,CASE WHEN enabled THEN 'ORGANIZATION_ACTIVATED' ELSE 'ORGANIZATION_SUSPENDED' END,'ORGANIZATION',org,'SUCCESS');
 END $$;
-- Lock the organization before any membership/ownership mutation. All such writes use these functions.
CREATE FUNCTION trace_require_admin(actor uuid) RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ DECLARE r text; BEGIN
 PERFORM 1 FROM public.organizations WHERE id=public.trace_current_organization() AND active FOR UPDATE;
 SELECT organization_role INTO r FROM public.trace_active_membership(public.trace_current_organization(),actor);
 IF r IS NULL OR r NOT IN ('OWNER','ADMIN') THEN RAISE EXCEPTION 'Forbidden' USING ERRCODE='42501'; END IF;
 RETURN r; END $$;
CREATE FUNCTION trace_membership_save(actor uuid,target uuid,new_role text,new_status text) RETURNS void
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ DECLARE actor_role text; old_role text; BEGIN
 actor_role:=public.trace_require_admin(actor);
 SELECT role INTO old_role FROM public.organization_memberships WHERE organization_id=public.trace_current_organization() AND user_id=target FOR UPDATE;
 IF old_role IS NULL THEN RAISE EXCEPTION 'Not found' USING ERRCODE='P0002'; END IF;
 IF actor_role='ADMIN' AND (old_role='OWNER' OR new_role='OWNER') THEN RAISE EXCEPTION 'Forbidden' USING ERRCODE='42501'; END IF;
 IF old_role='OWNER' AND (new_role<>'OWNER' OR new_status<>'ACTIVE') AND
 (SELECT count(*) FROM public.organization_memberships m JOIN public.users u ON u.id=m.user_id AND u.active WHERE m.organization_id=public.trace_current_organization() AND m.role='OWNER' AND m.status='ACTIVE')<=1
 THEN RAISE EXCEPTION 'Last owner must be preserved' USING ERRCODE='23514'; END IF;
 IF new_status<>'ACTIVE' AND EXISTS(
 SELECT 1 FROM public.team_memberships tm WHERE tm.organization_id=public.trace_current_organization() AND tm.user_id=target AND tm.role='OWNER'
 AND NOT EXISTS(SELECT 1 FROM public.team_memberships other JOIN public.organization_memberships om USING(organization_id,user_id) JOIN public.users u ON u.id=om.user_id AND u.active
 WHERE other.organization_id=tm.organization_id AND other.team_id=tm.team_id AND other.user_id<>target AND other.role='OWNER' AND om.status='ACTIVE'))
 THEN RAISE EXCEPTION 'Transfer team ownership first' USING ERRCODE='23514'; END IF;
 UPDATE public.organization_memberships SET role=new_role,status=new_status,updated_at=now() WHERE organization_id=public.trace_current_organization() AND user_id=target;
 PERFORM public.trace_audit(actor,public.trace_current_organization(),'ORGANIZATION_MEMBERSHIP_CHANGED','USER',target,'SUCCESS',jsonb_build_object('previousRole',old_role,'role',new_role,'status',new_status));
 IF old_role<>new_role THEN PERFORM public.trace_audit(actor,public.trace_current_organization(),'ORGANIZATION_ROLE_CHANGED','USER',target,'SUCCESS'); END IF;
 END $$;
CREATE FUNCTION trace_organization_update(actor uuid,label text) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ BEGIN
 PERFORM public.trace_require_admin(actor);
 UPDATE public.organizations SET name=label,updated_at=now() WHERE id=public.trace_current_organization();
 PERFORM public.trace_audit(actor,public.trace_current_organization(),'ORGANIZATION_UPDATED','ORGANIZATION',public.trace_current_organization(),'SUCCESS'); END $$;
CREATE FUNCTION trace_team_save(actor uuid,team uuid,label text,team_slug text,details text) RETURNS uuid
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ DECLARE r text; BEGIN
 PERFORM 1 FROM public.organizations WHERE id=public.trace_current_organization() AND active FOR UPDATE;
 SELECT organization_role INTO r FROM public.trace_active_membership(public.trace_current_organization(),actor);
 IF r IS NULL OR r='VIEWER' OR (r='MEMBER' AND (team IS NULL OR NOT EXISTS(SELECT 1 FROM public.team_memberships WHERE organization_id=public.trace_current_organization() AND team_id=team AND user_id=actor AND role='OWNER'))) THEN RAISE EXCEPTION 'Forbidden' USING ERRCODE='42501'; END IF;
 IF team IS NULL THEN
 team:=gen_random_uuid(); INSERT INTO public.teams(id,organization_id,name,slug,description) VALUES(team,public.trace_current_organization(),label,team_slug,details);
 INSERT INTO public.team_memberships(organization_id,team_id,user_id,role) VALUES(public.trace_current_organization(),team,actor,'OWNER');
 PERFORM public.trace_audit(actor,public.trace_current_organization(),'TEAM_CREATED','TEAM',team,'SUCCESS');
 PERFORM public.trace_audit(actor,public.trace_current_organization(),'TEAM_MEMBERSHIP_CHANGED','USER',actor,'SUCCESS',jsonb_build_object('teamId',team,'role','OWNER'));
 ELSE
 UPDATE public.teams SET name=label,slug=team_slug,description=details,updated_at=now() WHERE organization_id=public.trace_current_organization() AND id=team AND active;
 IF NOT FOUND THEN RAISE EXCEPTION 'Not found' USING ERRCODE='P0002'; END IF;
 PERFORM public.trace_audit(actor,public.trace_current_organization(),'TEAM_UPDATED','TEAM',team,'SUCCESS'); END IF;
 RETURN team; END $$;
CREATE FUNCTION trace_team_member_save(actor uuid,team uuid,target uuid,new_role text) RETURNS void
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ DECLARE r text; old_role text; BEGIN
 PERFORM 1 FROM public.organizations WHERE id=public.trace_current_organization() AND active FOR UPDATE;
 SELECT organization_role INTO r FROM public.trace_active_membership(public.trace_current_organization(),actor);
 IF NOT EXISTS(SELECT 1 FROM public.teams WHERE id=team AND organization_id=public.trace_current_organization() AND active) THEN RAISE EXCEPTION 'Not found' USING ERRCODE='P0002'; END IF;
 IF r IS NULL OR r='VIEWER' OR (r='MEMBER' AND NOT EXISTS(SELECT 1 FROM public.team_memberships WHERE organization_id=public.trace_current_organization() AND team_id=team AND user_id=actor AND role='OWNER')) THEN RAISE EXCEPTION 'Forbidden' USING ERRCODE='42501'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.organization_memberships m JOIN public.users u ON u.id=m.user_id AND u.active WHERE m.organization_id=public.trace_current_organization() AND m.user_id=target AND m.status='ACTIVE') THEN RAISE EXCEPTION 'Active member required' USING ERRCODE='23514'; END IF;
 SELECT role INTO old_role FROM public.team_memberships WHERE organization_id=public.trace_current_organization() AND team_id=team AND user_id=target;
 IF old_role='OWNER' AND (new_role IS NULL OR new_role<>'OWNER') AND
 (SELECT count(*) FROM public.team_memberships tm JOIN public.organization_memberships m USING(organization_id,user_id) JOIN public.users u ON u.id=m.user_id AND u.active WHERE tm.organization_id=public.trace_current_organization() AND tm.team_id=team AND tm.role='OWNER' AND m.status='ACTIVE')<=1 THEN RAISE EXCEPTION 'Last team owner must be preserved' USING ERRCODE='23514'; END IF;
 IF new_role IS NULL THEN DELETE FROM public.team_memberships WHERE organization_id=public.trace_current_organization() AND team_id=team AND user_id=target;
 ELSE INSERT INTO public.team_memberships(organization_id,team_id,user_id,role) VALUES(public.trace_current_organization(),team,target,new_role)
 ON CONFLICT(organization_id,team_id,user_id) DO UPDATE SET role=excluded.role,updated_at=now(); END IF;
 PERFORM public.trace_audit(actor,public.trace_current_organization(),'TEAM_MEMBERSHIP_CHANGED','USER',target,'SUCCESS',jsonb_build_object('teamId',team,'previousRole',old_role,'role',new_role));
 IF old_role IS DISTINCT FROM new_role THEN PERFORM public.trace_audit(actor,public.trace_current_organization(),'TEAM_ROLE_CHANGED','USER',target,'SUCCESS'); END IF;
 END $$;
CREATE FUNCTION trace_invite(actor uuid,invite_email text,invite_role text,hashed text,deadline timestamptz) RETURNS uuid
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ DECLARE r text; invitation uuid; previous_inv uuid; BEGIN
 r:=public.trace_require_admin(actor);
 IF r='ADMIN' AND invite_role='OWNER' THEN RAISE EXCEPTION 'Forbidden' USING ERRCODE='42501'; END IF;
 FOR previous_inv IN UPDATE public.organization_invitations SET status='REVOKED' WHERE organization_id=public.trace_current_organization() AND email=invite_email AND status='PENDING' RETURNING id LOOP
 PERFORM public.trace_audit(actor,public.trace_current_organization(),'INVITATION_REVOKED','INVITATION',previous_inv,'SUCCESS',jsonb_build_object('reason','replaced')); END LOOP;
 INSERT INTO public.organization_invitations(organization_id,email,role,token_hash,expires_at,invited_by)
 VALUES(public.trace_current_organization(),invite_email,invite_role,hashed,deadline,actor) RETURNING id INTO invitation;
 PERFORM public.trace_audit(actor,public.trace_current_organization(),'INVITATION_CREATED','INVITATION',invitation,'SUCCESS',jsonb_build_object('role',invite_role)); RETURN invitation;
 END $$;
CREATE FUNCTION trace_invitation_revoke(actor uuid,invitation uuid) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ BEGIN
 PERFORM public.trace_require_admin(actor);
 UPDATE public.organization_invitations SET status='REVOKED' WHERE organization_id=public.trace_current_organization() AND id=invitation AND status='PENDING';
 IF NOT FOUND THEN RAISE EXCEPTION 'Not found' USING ERRCODE='P0002'; END IF;
 PERFORM public.trace_audit(actor,public.trace_current_organization(),'INVITATION_REVOKED','INVITATION',invitation,'SUCCESS'); END $$;
CREATE FUNCTION trace_invitation_accept(hashed text,actor uuid,label text,encoded text) RETURNS uuid
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ DECLARE inv public.organization_invitations; who uuid; BEGIN
 PERFORM set_config('trace.invitation_hash',hashed,true);
 SELECT * INTO inv FROM public.organization_invitations WHERE token_hash=hashed;
 IF inv.id IS NULL OR inv.status<>'PENDING' OR inv.expires_at<=now() THEN RAISE EXCEPTION 'Invalid invitation' USING ERRCODE='23514'; END IF;
 PERFORM set_config('trace.organization_id',inv.organization_id::text,true);
 PERFORM 1 FROM public.organizations WHERE id=inv.organization_id AND active FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Invalid invitation' USING ERRCODE='23514'; END IF;
 SELECT * INTO inv FROM public.organization_invitations WHERE token_hash=hashed FOR UPDATE;
 IF inv.status<>'PENDING' OR inv.expires_at<=now() THEN RAISE EXCEPTION 'Invalid invitation' USING ERRCODE='23514'; END IF;
 SELECT id INTO who FROM public.users WHERE email=inv.email FOR UPDATE;
 IF who IS NULL THEN
 IF actor IS NOT NULL OR encoded IS NULL OR label IS NULL THEN RAISE EXCEPTION 'Invalid invitation identity' USING ERRCODE='23514'; END IF;
 INSERT INTO public.users(email,display_name) VALUES(inv.email,label) RETURNING id INTO who;
 INSERT INTO public.user_credentials(user_id,password_hash) VALUES(who,encoded);
 ELSE
 IF actor IS DISTINCT FROM who OR NOT EXISTS(SELECT 1 FROM public.users WHERE id=who AND active) THEN RAISE EXCEPTION 'Sign in with the invited identity' USING ERRCODE='23514'; END IF;
 END IF;
 IF EXISTS(SELECT 1 FROM public.organization_memberships WHERE organization_id=inv.organization_id AND user_id=who AND status='ACTIVE') THEN RAISE EXCEPTION 'Already a member' USING ERRCODE='23514'; END IF;
 INSERT INTO public.organization_memberships(organization_id,user_id,role,status) VALUES(inv.organization_id,who,inv.role,'ACTIVE')
 ON CONFLICT(organization_id,user_id) DO UPDATE SET role=excluded.role,status='ACTIVE',updated_at=now();
 UPDATE public.organization_invitations SET status='ACCEPTED',accepted_by=who,accepted_at=now() WHERE id=inv.id;
 PERFORM public.trace_audit(who,inv.organization_id,'ORGANIZATION_MEMBERSHIP_CHANGED','USER',who,'SUCCESS',jsonb_build_object('role',inv.role,'status','ACTIVE'));
 PERFORM public.trace_audit(who,inv.organization_id,'INVITATION_ACCEPTED','INVITATION',inv.id,'SUCCESS'); RETURN who;
 END $$;
-- Recovery is platform-admin assisted and delivered only by a deliberately enabled local channel.
CREATE FUNCTION trace_reset_issue(actor uuid,target uuid,hashed text,deadline timestamptz) RETURNS void
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM public.users WHERE id=actor AND active AND platform_role='PLATFORM_ADMIN') THEN RAISE EXCEPTION 'Forbidden' USING ERRCODE='42501'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.users WHERE id=target AND active) THEN RAISE EXCEPTION 'Not found' USING ERRCODE='P0002'; END IF;
 UPDATE public.password_reset_tokens SET consumed_at=now() WHERE user_id=target AND consumed_at IS NULL;
 INSERT INTO public.password_reset_tokens(user_id,token_hash,expires_at) VALUES(target,hashed,deadline);
 PERFORM public.trace_audit(actor,null,'PASSWORD_RESET_ISSUED','USER',target,'SUCCESS'); END $$;
CREATE FUNCTION trace_reset_accept(hashed text,encoded text) RETURNS uuid
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ DECLARE target uuid; BEGIN
 SELECT user_id INTO target FROM public.password_reset_tokens WHERE token_hash=hashed AND consumed_at IS NULL AND expires_at>now() FOR UPDATE;
 IF target IS NULL OR NOT EXISTS(SELECT 1 FROM public.users WHERE id=target AND active) THEN RAISE EXCEPTION 'Invalid reset' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM public.user_credentials WHERE user_id=target) THEN
 PERFORM public.trace_password_set(target,encoded);
 ELSE
 INSERT INTO public.user_credentials(user_id,password_hash) VALUES(target,encoded);
 UPDATE public.users SET security_version=security_version+1,updated_at=now() WHERE id=target;
 UPDATE public.password_reset_tokens SET consumed_at=now() WHERE user_id=target AND consumed_at IS NULL;
 END IF;
 PERFORM public.trace_audit(target,null,'PASSWORD_RESET','USER',target,'SUCCESS'); RETURN target; END $$;
CREATE FUNCTION trace_profile_update(actor uuid,label text) RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 UPDATE public.users SET display_name=label,updated_at=now() WHERE id=actor AND active;
 SELECT public.trace_audit(actor,null,'PROFILE_UPDATED','USER',actor,'SUCCESS') $$;
-- Default function EXECUTE is public in PostgreSQL; revoke it explicitly for every bridge.
DO $$ DECLARE f record; BEGIN
 FOR f IN SELECT p.oid::regprocedure AS signature FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
 WHERE n.nspname='public' AND p.proname IN (
 'trace_profile','trace_credentials','trace_my_organizations','trace_audit','trace_login_success','trace_password_set',
 'trace_bootstrap_admin','trace_platform_organizations','trace_create_organization','trace_suspend_organization',
 'trace_require_admin','trace_membership_save','trace_organization_update','trace_team_save','trace_team_member_save',
 'trace_invite','trace_invitation_revoke','trace_invitation_accept','trace_reset_issue','trace_reset_accept','trace_profile_update') LOOP
 EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC',f.signature);
 IF f.signature::text NOT LIKE 'trace_bootstrap_admin(%' AND f.signature::text NOT LIKE 'trace_require_admin(%' THEN
 EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO trace_runtime',f.signature); END IF;
 END LOOP;
END $$;

CREATE FUNCTION trace_transfer_organization(actor uuid,target uuid) RETURNS void
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ BEGIN
 IF public.trace_require_admin(actor)<>'OWNER' THEN RAISE EXCEPTION 'Forbidden' USING ERRCODE='42501'; END IF;
 IF actor=target OR NOT EXISTS(SELECT 1 FROM public.organization_memberships m JOIN public.users u ON u.id=m.user_id AND u.active WHERE m.organization_id=public.trace_current_organization() AND m.user_id=target AND m.status='ACTIVE') THEN RAISE EXCEPTION 'Active target member required' USING ERRCODE='23514'; END IF;
 UPDATE public.organization_memberships SET role='OWNER',updated_at=now() WHERE organization_id=public.trace_current_organization() AND user_id=target;
 UPDATE public.organization_memberships SET role='ADMIN',updated_at=now() WHERE organization_id=public.trace_current_organization() AND user_id=actor;
 PERFORM public.trace_audit(actor,public.trace_current_organization(),'OWNERSHIP_TRANSFERRED','USER',target,'SUCCESS',jsonb_build_object('previousOwner',actor,'newOwner',target)); END $$;
CREATE FUNCTION trace_user_state(actor uuid,target uuid,enabled boolean) RETURNS void
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ DECLARE scoped_org uuid; BEGIN
 PERFORM pg_advisory_xact_lock(908002);
 IF NOT EXISTS(SELECT 1 FROM public.users WHERE id=actor AND active AND platform_role='PLATFORM_ADMIN') THEN RAISE EXCEPTION 'Forbidden' USING ERRCODE='42501'; END IF;
 IF NOT enabled AND EXISTS(SELECT 1 FROM public.users WHERE id=target AND platform_role='PLATFORM_ADMIN') AND
 (SELECT count(*) FROM public.users WHERE active AND platform_role='PLATFORM_ADMIN')<=1 THEN RAISE EXCEPTION 'Last platform administrator' USING ERRCODE='23514'; END IF;
 -- Refuse disabling any organization owner: transfer or demote them within each organization first.
 PERFORM set_config('trace.auth_user_id',target::text,true);
 IF NOT enabled AND EXISTS(SELECT 1 FROM public.organization_memberships WHERE user_id=target AND role='OWNER' AND status='ACTIVE') THEN RAISE EXCEPTION 'Transfer organization ownership first' USING ERRCODE='23514'; END IF;
 IF NOT enabled THEN
 FOR scoped_org IN SELECT organization_id FROM public.organization_memberships WHERE user_id=target AND status='ACTIVE' ORDER BY organization_id LOOP
   PERFORM set_config('trace.organization_id',scoped_org::text,true);
   PERFORM 1 FROM public.organizations WHERE id=scoped_org FOR UPDATE;
   IF EXISTS(SELECT 1 FROM public.team_memberships WHERE organization_id=scoped_org AND user_id=target AND role='OWNER') THEN RAISE EXCEPTION 'Transfer team ownership first' USING ERRCODE='23514'; END IF;
 END LOOP; END IF;
 UPDATE public.users SET active=enabled,security_version=security_version+1,updated_at=now() WHERE id=target;
 IF NOT FOUND THEN RAISE EXCEPTION 'Not found' USING ERRCODE='P0002'; END IF;
 PERFORM public.trace_audit(actor,null,'USER_STATUS_CHANGED','USER',target,'SUCCESS',jsonb_build_object('active',enabled)); END $$;
REVOKE ALL ON FUNCTION trace_transfer_organization(uuid,uuid),trace_user_state(uuid,uuid,boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION trace_transfer_organization(uuid,uuid),trace_user_state(uuid,uuid,boolean) TO trace_runtime;
