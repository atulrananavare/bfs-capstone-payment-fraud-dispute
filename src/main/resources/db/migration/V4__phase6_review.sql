-- Phase 6: mandatory human-review workflow.
-- Extends the Phase 2 review_disposition skeleton with recommendation
-- traceability, the human target route, acknowledgement checklist and the
-- idempotency reference; adds optimistic locking on fraud_case.

ALTER TABLE review_disposition ADD COLUMN recommendation_id VARCHAR(40);
ALTER TABLE review_disposition ADD COLUMN target_route VARCHAR(40);
ALTER TABLE review_disposition ADD COLUMN ack_evidence BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE review_disposition ADD COLUMN ack_policy BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE review_disposition ADD COLUMN ack_missing_info BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE review_disposition ADD COLUMN ack_contradictions BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE review_disposition ADD COLUMN client_reference VARCHAR(128);

-- Exactly one effective disposition per recommendation.
ALTER TABLE review_disposition ALTER COLUMN recommendation_id SET NOT NULL;
ALTER TABLE review_disposition ADD CONSTRAINT uq_review_recommendation UNIQUE (recommendation_id);

-- Idempotent retry: the same (case, client_reference) replays the stored
-- disposition instead of creating a second one.
ALTER TABLE review_disposition ADD CONSTRAINT uq_review_client_ref UNIQUE (case_id, client_reference);

-- Optimistic concurrency for review (and any future) state transitions.
ALTER TABLE fraud_case ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
