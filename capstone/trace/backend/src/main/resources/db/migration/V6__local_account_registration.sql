-- The runtime still cannot INSERT users/credentials directly. This narrow bridge
-- creates only a normal account; existing email identities are never overwritten.
-- Public HTTP availability is independently gated to loopback-only local-dev.
CREATE FUNCTION trace_register_user(login_email text, label text, encoded text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog
AS $$
DECLARE who uuid;
BEGIN
    IF encoded IS NULL OR encoded !~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$' THEN
        RAISE EXCEPTION 'Adaptive password hash required' USING ERRCODE = '23514';
    END IF;
    INSERT INTO public.users(email, display_name, active, platform_role)
    VALUES (lower(trim(login_email)), label, true, 'USER')
    RETURNING id INTO who;
    INSERT INTO public.user_credentials(user_id, password_hash)
    VALUES (who, encoded);
    PERFORM public.trace_audit(who, null, 'USER_REGISTERED', 'USER', who, 'SUCCESS');
    RETURN who;
END;
$$;
REVOKE ALL ON FUNCTION trace_register_user(text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION trace_register_user(text, text, text) TO trace_runtime;
