-- Reviews reference immutable registered versions; no existing version state is rewritten.
CREATE TABLE governance_reviews (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL,
 target_type text NOT NULL CHECK(target_type IN ('FORMULA_VERSION','METRIC_VERSION')),
 target_id uuid NOT NULL, owner_team_id uuid NOT NULL, title varchar(255) NOT NULL,
 status text NOT NULL DEFAULT 'SUBMITTED' CHECK(status IN ('SUBMITTED','UNDER_REVIEW','CHANGES_REQUESTED','APPROVED','REJECTED')),
 snapshot jsonb NOT NULL, previous_snapshot jsonb, requester_id uuid NOT NULL,
 reviewer_id uuid, revision integer NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,id),
 FOREIGN KEY(organization_id,owner_team_id) REFERENCES teams(organization_id,id),
 FOREIGN KEY(organization_id,requester_id) REFERENCES organization_memberships(organization_id,user_id),
 FOREIGN KEY(organization_id,reviewer_id) REFERENCES organization_memberships(organization_id,user_id)
);
CREATE UNIQUE INDEX one_pending_version_review ON governance_reviews(organization_id,target_type,target_id) WHERE status IN ('SUBMITTED','UNDER_REVIEW','CHANGES_REQUESTED');
CREATE INDEX governance_queue ON governance_reviews(organization_id,status,created_at DESC);
CREATE TABLE governance_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, review_id uuid NOT NULL,
 actor_id uuid NOT NULL, from_status text, to_status text NOT NULL, reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 4000),
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(organization_id,review_id) REFERENCES governance_reviews(organization_id,id),
 FOREIGN KEY(organization_id,actor_id) REFERENCES organization_memberships(organization_id,user_id)
);
ALTER TABLE governance_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE governance_reviews FORCE ROW LEVEL SECURITY;
ALTER TABLE governance_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE governance_events FORCE ROW LEVEL SECURITY;
CREATE POLICY organization_isolation ON governance_reviews USING(organization_id=public.trace_current_organization()) WITH CHECK(organization_id=public.trace_current_organization());
CREATE POLICY organization_isolation ON governance_events USING(organization_id=public.trace_current_organization()) WITH CHECK(organization_id=public.trace_current_organization());
GRANT SELECT,INSERT,UPDATE ON governance_reviews TO trace_runtime;
GRANT SELECT,INSERT ON governance_events TO trace_runtime;
CREATE TRIGGER governance_events_immutable BEFORE UPDATE OR DELETE ON governance_events FOR EACH ROW EXECUTE FUNCTION trace_immutable_version();
CREATE FUNCTION trace_review_snapshot_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.organization_id,NEW.target_type,NEW.target_id,NEW.owner_team_id,NEW.snapshot,NEW.previous_snapshot,NEW.requester_id,NEW.created_at,NEW.title)
 IS DISTINCT FROM (OLD.organization_id,OLD.target_type,OLD.target_id,OLD.owner_team_id,OLD.snapshot,OLD.previous_snapshot,OLD.requester_id,OLD.created_at,OLD.title)
 THEN RAISE EXCEPTION 'Review snapshot is immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER review_snapshot_immutable BEFORE UPDATE ON governance_reviews FOR EACH ROW EXECUTE FUNCTION trace_review_snapshot_immutable();
