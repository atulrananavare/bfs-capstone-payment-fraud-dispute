-- Phase 2 schema: deterministic core.
-- Synthetic training data only. No raw card/account columns exist anywhere.

CREATE TABLE fraud_case (
    case_id              VARCHAR(32)  NOT NULL PRIMARY KEY,
    masked_customer_ref  VARCHAR(64)  NOT NULL,
    home_region          VARCHAR(100) NOT NULL,
    report_channel       VARCHAR(32)  NOT NULL,
    reported_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    status               VARCHAR(32)  NOT NULL,
    created_by           VARCHAR(64)  NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE transaction_claim (
    claim_id             VARCHAR(32)   NOT NULL PRIMARY KEY,
    case_id              VARCHAR(32)   NOT NULL UNIQUE
                        REFERENCES fraud_case (case_id),
    transaction_ref      VARCHAR(40)   NOT NULL,
    issue_type           VARCHAR(40)   NOT NULL,
    statement            VARCHAR(2000) NOT NULL,
    card_possession      VARCHAR(32)   NOT NULL,
    merchant_recognised  VARCHAR(32)   NOT NULL,
    amount               DECIMAL(15,2) NOT NULL,
    currency             VARCHAR(3)    NOT NULL,
    occurred_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    transaction_status   VARCHAR(32)   NOT NULL,
    transaction_channel  VARCHAR(32)   NOT NULL,
    masked_merchant      VARCHAR(80)   NOT NULL
);

CREATE TABLE evidence_item (
    evidence_id          VARCHAR(64)  NOT NULL PRIMARY KEY,
    case_id              VARCHAR(32)  NOT NULL
                        REFERENCES fraud_case (case_id),
    investigation_id     VARCHAR(40),
    evidence_type        VARCHAR(32)  NOT NULL,
    severity             VARCHAR(16)  NOT NULL,
    value_summary        VARCHAR(500) NOT NULL,
    provenance           VARCHAR(40)  NOT NULL,
    observed_at          TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_evidence_case ON evidence_item (case_id);

CREATE TABLE tool_trace (
    trace_id             VARCHAR(40)  NOT NULL PRIMARY KEY,
    case_id              VARCHAR(32)  NOT NULL
                        REFERENCES fraud_case (case_id),
    investigation_id     VARCHAR(40),
    tool_name            VARCHAR(64)  NOT NULL,
    request_hash         VARCHAR(64)  NOT NULL,
    status               VARCHAR(24)  NOT NULL,
    duration_ms          BIGINT       NOT NULL,
    result_summary       VARCHAR(500),
    executed_at          TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_trace_case ON tool_trace (case_id);

CREATE TABLE recommendation (
    recommendation_id    VARCHAR(40)   NOT NULL PRIMARY KEY,
    case_id              VARCHAR(32)   NOT NULL
                        REFERENCES fraud_case (case_id),
    investigation_id     VARCHAR(40),
    mode                 VARCHAR(16)   NOT NULL,
    classification       VARCHAR(48)   NOT NULL,
    route                VARCHAR(48)   NOT NULL,
    risk                 VARCHAR(16)   NOT NULL,
    confidence           DOUBLE        NOT NULL,
    human_review_required BOOLEAN      NOT NULL,
    rationale            VARCHAR(2000) NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_recommendation_case ON recommendation (case_id);

CREATE TABLE recommendation_evidence (
    recommendation_id    VARCHAR(40) NOT NULL
                        REFERENCES recommendation (recommendation_id),
    evidence_id          VARCHAR(64) NOT NULL
                        REFERENCES evidence_item (evidence_id),
    PRIMARY KEY (recommendation_id, evidence_id)
);

CREATE TABLE recommendation_rule_ids (
    recommendation_id    VARCHAR(40) NOT NULL
                        REFERENCES recommendation (recommendation_id),
    rule_id              VARCHAR(16) NOT NULL,
    PRIMARY KEY (recommendation_id, rule_id)
);

CREATE TABLE recommendation_trace_ids (
    recommendation_id    VARCHAR(40) NOT NULL
                        REFERENCES recommendation (recommendation_id),
    trace_id             VARCHAR(40) NOT NULL
                        REFERENCES tool_trace (trace_id),
    PRIMARY KEY (recommendation_id, trace_id)
);

CREATE TABLE recommendation_missing_info (
    recommendation_id    VARCHAR(40)  NOT NULL
                        REFERENCES recommendation (recommendation_id),
    missing_item         VARCHAR(255) NOT NULL,
    PRIMARY KEY (recommendation_id, missing_item)
);

-- Prepared for the RAG phase. Rules-mode recommendations may legitimately
-- have zero rows here; policy is never fabricated.
CREATE TABLE policy_citation (
    citation_id          VARCHAR(40)  NOT NULL PRIMARY KEY,
    recommendation_id    VARCHAR(40)  NOT NULL
                        REFERENCES recommendation (recommendation_id),
    document_id          VARCHAR(64)  NOT NULL,
    version              VARCHAR(32)  NOT NULL,
    section              VARCHAR(128) NOT NULL,
    chunk_id             VARCHAR(64)  NOT NULL,
    score                DOUBLE       NOT NULL
);
CREATE INDEX idx_citation_recommendation ON policy_citation (recommendation_id);

-- Prepared for the review-workflow phase.
CREATE TABLE review_disposition (
    review_id            VARCHAR(40)   NOT NULL PRIMARY KEY,
    case_id              VARCHAR(32)   NOT NULL
                        REFERENCES fraud_case (case_id),
    reviewer_id          VARCHAR(64)   NOT NULL,
    disposition          VARCHAR(32)   NOT NULL,
    reason               VARCHAR(2000) NOT NULL,
    recorded_at          TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_review_case ON review_disposition (case_id);

-- Append-only audit trail. Application code only ever INSERTs; no ordinary
-- update/delete paths exist (enforced additionally in the entity).
CREATE TABLE audit_event (
    event_id             VARCHAR(40)  NOT NULL PRIMARY KEY,
    case_id              VARCHAR(32)  NOT NULL
                        REFERENCES fraud_case (case_id),
    event_type           VARCHAR(48)  NOT NULL,
    actor_type           VARCHAR(24)  NOT NULL,
    payload_hash         VARCHAR(64)  NOT NULL,
    recorded_at          TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_audit_case ON audit_event (case_id);
