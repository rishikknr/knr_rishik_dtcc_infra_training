-- User deactivation already holds 908002. All transitions that can establish
-- an organization/team owner take that lock BEFORE organization/identity rows.
-- This closes deactivation-versus-promotion races without lock-order inversion.
-- Identity administration is rare; metadata reads/writes and discovery do not use it.
CREATE OR REPLACE FUNCTION trace_create_organization(actor uuid,label text,org_slug text) RETURNS uuid
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ DECLARE org uuid; BEGIN
 PERFORM pg_advisory_xact_lock(908002); -- Same owner-transition lock as trace_user_state.
 IF NOT EXISTS(SELECT 1 FROM public.users WHERE id=actor AND active AND platform_role='PLATFORM_ADMIN') THEN RAISE EXCEPTION 'Forbidden' USING ERRCODE='42501'; END IF;
 org:=gen_random_uuid(); PERFORM set_config('trace.organization_id',org::text,true);
 INSERT INTO public.organizations(id,name,slug) VALUES(org,label,org_slug);
 INSERT INTO public.organization_memberships(organization_id,user_id,role) VALUES(org,actor,'OWNER');
 PERFORM public.trace_audit(actor,org,'ORGANIZATION_MEMBERSHIP_CHANGED','USER',actor,'SUCCESS',jsonb_build_object('role','OWNER','status','ACTIVE'));
 PERFORM public.trace_audit(actor,org,'ORGANIZATION_CREATED','ORGANIZATION',org,'SUCCESS'); RETURN org;
 END $$;

CREATE OR REPLACE FUNCTION trace_membership_save(actor uuid,target uuid,new_role text,new_status text) RETURNS void
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ DECLARE actor_role text; old_role text; BEGIN
 PERFORM pg_advisory_xact_lock(908002); -- Same owner-transition lock as trace_user_state.
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

CREATE OR REPLACE FUNCTION trace_team_save(actor uuid,team uuid,label text,team_slug text,details text) RETURNS uuid
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ DECLARE r text; BEGIN
 PERFORM pg_advisory_xact_lock(908002); -- Same owner-transition lock as trace_user_state.
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

CREATE OR REPLACE FUNCTION trace_team_member_save(actor uuid,team uuid,target uuid,new_role text) RETURNS void
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ DECLARE r text; old_role text; BEGIN
 PERFORM pg_advisory_xact_lock(908002); -- Same owner-transition lock as trace_user_state.
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

CREATE OR REPLACE FUNCTION trace_invitation_accept(hashed text,actor uuid,label text,encoded text) RETURNS uuid
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ DECLARE inv public.organization_invitations; who uuid; BEGIN
 PERFORM pg_advisory_xact_lock(908002); -- Same owner-transition lock as trace_user_state.
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

CREATE OR REPLACE FUNCTION trace_transfer_organization(actor uuid,target uuid) RETURNS void
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ BEGIN
 PERFORM pg_advisory_xact_lock(908002); -- Same owner-transition lock as trace_user_state.
 IF public.trace_require_admin(actor)<>'OWNER' THEN RAISE EXCEPTION 'Forbidden' USING ERRCODE='42501'; END IF;
 IF actor=target OR NOT EXISTS(SELECT 1 FROM public.organization_memberships m JOIN public.users u ON u.id=m.user_id AND u.active WHERE m.organization_id=public.trace_current_organization() AND m.user_id=target AND m.status='ACTIVE') THEN RAISE EXCEPTION 'Active target member required' USING ERRCODE='23514'; END IF;
 UPDATE public.organization_memberships SET role='OWNER',updated_at=now() WHERE organization_id=public.trace_current_organization() AND user_id=target;
 UPDATE public.organization_memberships SET role='ADMIN',updated_at=now() WHERE organization_id=public.trace_current_organization() AND user_id=actor;
 PERFORM public.trace_audit(actor,public.trace_current_organization(),'OWNERSHIP_TRANSFERRED','USER',target,'SUCCESS',jsonb_build_object('previousOwner',actor,'newOwner',target)); END $$;
CREATE OR REPLACE FUNCTION trace_schema_version() RETURNS integer LANGUAGE sql IMMUTABLE
 SET search_path=pg_catalog AS $$ SELECT 15 $$;
