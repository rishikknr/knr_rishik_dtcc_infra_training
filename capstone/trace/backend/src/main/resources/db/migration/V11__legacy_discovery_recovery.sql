-- V9 ran its data updates before creating the migrator's forced-RLS queue policy.
-- Repair only legacy envelopes; preserve IDs, outcomes, attempts and all metadata.
DO $$ DECLARE job public.connection_sync_runs; BEGIN
 FOR job IN SELECT * FROM public.connection_sync_runs
   WHERE configuration_updated_at IS NULL OR
     (status='RUNNING' AND attempt_count=0 AND lease_token IS NULL)
   ORDER BY organization_id,id FOR UPDATE LOOP
  PERFORM set_config('trace.organization_id',job.organization_id::text,true);
  UPDATE public.connection_sync_runs r SET configuration_updated_at=c.updated_at
   FROM public.source_connections c WHERE r.id=job.id AND r.configuration_updated_at IS NULL
     AND c.organization_id=job.organization_id AND c.id=job.connection_id;
  IF job.status='RUNNING' AND job.attempt_count=0 AND job.lease_token IS NULL THEN
   UPDATE public.connection_sync_runs SET status='PENDING',phase='RECOVERING',
     lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=now(),finished_at=NULL
     WHERE id=job.id;
   PERFORM public.trace_audit(job.initiated_by,job.organization_id,
     'DISCOVERY_LEGACY_RECOVERED','CONNECTION',job.connection_id,'SUCCESS',
     jsonb_build_object('runId',job.id,'reason','Legacy run had no lease'));
  END IF;
 END LOOP;
END $$;

-- Lease validity uses wall-clock time even inside a long preview transaction.
CREATE OR REPLACE FUNCTION trace_claim_discovery(worker uuid,seconds integer)
RETURNS TABLE(run_id uuid,organization_id uuid,user_id uuid,connection_id uuid,token uuid,attempt integer)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE picked public.connection_sync_runs; expired public.connection_sync_runs; BEGIN
 IF worker IS NULL OR seconds NOT BETWEEN 5 AND 300 THEN RAISE EXCEPTION 'Invalid lease' USING ERRCODE='22023'; END IF;
 FOR expired IN UPDATE public.connection_sync_runs r SET status=CASE WHEN r.cancel_requested_at IS NOT NULL THEN 'CANCELLED' ELSE 'FAILED' END,
  phase='FINISHED',error_code=CASE WHEN r.cancel_requested_at IS NOT NULL THEN 'CANCELLED' ELSE 'RETRY_EXHAUSTED' END,finished_at=now(),lease_token=NULL,lease_owner=NULL,lease_expires_at=NULL
 WHERE r.status='RUNNING' AND r.lease_expires_at<=clock_timestamp() AND (r.attempt_count>=3 OR r.cancel_requested_at IS NOT NULL) RETURNING r.* LOOP
  PERFORM set_config('trace.organization_id',expired.organization_id::text,true);
  UPDATE public.connection_sync_attempts a SET status=expired.status,finished_at=now(),error_code=expired.error_code WHERE a.organization_id=expired.organization_id AND a.run_id=expired.id AND a.attempt=expired.attempt_count AND a.status='RUNNING';
  PERFORM public.trace_audit(expired.initiated_by,expired.organization_id,'DISCOVERY_'||expired.status,'CONNECTION',expired.connection_id,CASE WHEN expired.status='FAILED' THEN 'FAILURE' ELSE 'SUCCESS' END);
 END LOOP;
 SELECT * INTO picked FROM public.connection_sync_runs r WHERE
  ((r.status IN ('PENDING','RETRY_WAIT') AND r.next_attempt_at<=now()) OR (r.status='RUNNING' AND r.lease_expires_at<=clock_timestamp()))
  AND r.cancel_requested_at IS NULL AND r.attempt_count<3
 ORDER BY r.next_attempt_at,r.queued_at,r.id FOR UPDATE SKIP LOCKED LIMIT 1;
 IF NOT FOUND THEN RETURN; END IF;
 PERFORM set_config('trace.organization_id',picked.organization_id::text,true);
 UPDATE public.connection_sync_attempts a SET status='INTERRUPTED',finished_at=now(),error_code='LEASE_EXPIRED' WHERE a.organization_id=picked.organization_id AND a.run_id=picked.id AND a.attempt=picked.attempt_count AND a.status='RUNNING';
 UPDATE public.connection_sync_runs r SET status='RUNNING',started_at=now(),finished_at=NULL,
  attempt_count=r.attempt_count+1,lease_owner=worker,lease_token=gen_random_uuid(),lease_expires_at=clock_timestamp()+make_interval(secs=>seconds),
  phase=CASE WHEN picked.status='RUNNING' THEN 'RECOVERING' ELSE 'ACQUIRING' END
 WHERE r.id=picked.id RETURNING * INTO picked;
 INSERT INTO public.connection_sync_attempts(organization_id,run_id,attempt,status) VALUES(picked.organization_id,picked.id,picked.attempt_count,'RUNNING');
 RETURN QUERY SELECT picked.id,picked.organization_id,picked.initiated_by,picked.connection_id,picked.lease_token,picked.attempt_count;
END $$;

CREATE OR REPLACE FUNCTION trace_finish_discovery(worker uuid,token uuid,outcome text,preview_id uuid,records integer,notes jsonb,code text,delay_seconds integer)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE r public.connection_sync_runs; final_state text; BEGIN
 SELECT * INTO r FROM public.connection_sync_runs q WHERE q.lease_owner=worker AND q.lease_token=token AND q.status='RUNNING' AND q.lease_expires_at>clock_timestamp() FOR UPDATE;
 IF NOT FOUND THEN RETURN false; END IF;
 IF outcome NOT IN ('COMPLETED','PARTIALLY_COMPLETED','FAILED','CANCELLED','RETRY_WAIT') OR records NOT BETWEEN 0 AND 1000 OR jsonb_typeof(notes)<>'array' OR jsonb_array_length(notes)>100 OR (code IS NOT NULL AND code !~ '^[A-Z_]{1,80}$') THEN RAISE EXCEPTION 'Invalid run result' USING ERRCODE='22023'; END IF;
 final_state=CASE WHEN r.cancel_requested_at IS NOT NULL THEN 'CANCELLED' ELSE outcome END;
 PERFORM set_config('trace.organization_id',r.organization_id::text,true);
 IF final_state IN ('COMPLETED','PARTIALLY_COMPLETED') AND NOT EXISTS(SELECT 1 FROM public.import_batches b WHERE b.organization_id=r.organization_id AND b.id=preview_id AND b.connection_id=r.connection_id AND b.created_by_user_id=r.initiated_by AND b.status='PREVIEW_READY') THEN RAISE EXCEPTION 'Preview required' USING ERRCODE='23514'; END IF;
 IF final_state='RETRY_WAIT' AND (r.attempt_count>=3 OR code NOT IN ('NETWORK_TIMEOUT','UNAVAILABLE','RATE_LIMITED','PROCESSOR_UNAVAILABLE','PROCESSOR_ERROR')) THEN final_state='FAILED'; END IF;
 UPDATE public.connection_sync_runs SET status=final_state,phase=CASE WHEN final_state='RETRY_WAIT' THEN 'WAITING_TO_RETRY' WHEN final_state IN ('COMPLETED','PARTIALLY_COMPLETED') THEN 'PREVIEW_READY' ELSE 'FINISHED' END,
  finished_at=CASE WHEN final_state='RETRY_WAIT' THEN NULL ELSE now() END,
  next_attempt_at=now()+make_interval(secs=>greatest(1,least(delay_seconds,300))),
  records_discovered=records,warnings=notes,error_code=CASE WHEN final_state='CANCELLED' THEN 'CANCELLED' ELSE code END,
  import_id=CASE WHEN final_state IN ('COMPLETED','PARTIALLY_COMPLETED') THEN preview_id ELSE NULL END,
  lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL WHERE id=r.id;
 UPDATE public.connection_sync_attempts SET status=final_state,finished_at=now(),error_code=CASE WHEN final_state='CANCELLED' THEN 'CANCELLED' ELSE code END WHERE organization_id=r.organization_id AND run_id=r.id AND attempt=r.attempt_count;
 IF final_state IN ('COMPLETED','PARTIALLY_COMPLETED') THEN
  UPDATE public.source_connections SET last_successful_sync_at=now() WHERE organization_id=r.organization_id AND id=r.connection_id;
 END IF;
 PERFORM public.trace_audit(r.initiated_by,r.organization_id,'DISCOVERY_'||final_state,'CONNECTION',r.connection_id,CASE WHEN final_state='FAILED' THEN 'FAILURE' ELSE 'SUCCESS' END);
 RETURN true;
END $$;

CREATE OR REPLACE FUNCTION trace_heartbeat_discovery(worker uuid,token uuid,seconds integer) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE r public.connection_sync_runs; BEGIN
 IF seconds NOT BETWEEN 5 AND 300 THEN RAISE EXCEPTION 'Invalid lease' USING ERRCODE='22023'; END IF;
 SELECT * INTO r FROM public.connection_sync_runs q WHERE q.lease_owner=worker AND q.lease_token=token AND q.status='RUNNING' AND q.lease_expires_at>clock_timestamp() FOR UPDATE;
 IF NOT FOUND THEN RETURN 'LOST'; END IF;
 IF r.cancel_requested_at IS NOT NULL THEN
  PERFORM public.trace_finish_discovery(worker,token,'CANCELLED',NULL,0,'[]','CANCELLED',1);RETURN 'CANCELLED';
 END IF;
 UPDATE public.connection_sync_runs SET lease_expires_at=clock_timestamp()+make_interval(secs=>seconds) WHERE id=r.id;
 RETURN 'LIVE';
END $$;


CREATE OR REPLACE FUNCTION trace_lock_discovery(org uuid,actor uuid,run uuid,worker uuid,token uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ BEGIN
 IF org IS DISTINCT FROM public.trace_current_organization() THEN RETURN false; END IF;
 PERFORM 1 FROM public.connection_sync_runs r WHERE r.organization_id=org AND r.initiated_by=actor AND r.id=run AND r.lease_owner=worker AND r.lease_token=token AND r.status='RUNNING' AND r.cancel_requested_at IS NULL AND r.lease_expires_at>clock_timestamp() FOR UPDATE;
 RETURN FOUND;
END $$;
