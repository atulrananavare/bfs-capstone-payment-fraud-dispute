-- Phase 5 schema: case-scoped conversation record.
--
-- The full append-only conversation history lives here (audit-grade record).
-- The active Spring AI memory window is a bounded VIEW over this table —
-- retention never deletes rows. Messages are immutable once written.
-- Conversation rows are DATA, not instructions; nothing here confers
-- authority to change case state or bypass review.
CREATE TABLE case_message (
    message_id         VARCHAR(40)  NOT NULL PRIMARY KEY,
    case_id            VARCHAR(32)  NOT NULL
                       REFERENCES fraud_case (case_id),
    sequence_number    BIGINT       NOT NULL,
    role               VARCHAR(24)  NOT NULL,
    content            VARCHAR(4000) NOT NULL,
    content_hash       VARCHAR(64)  NOT NULL,
    client_reference   VARCHAR(128),
    -- Compact provenance refs for the assistant answer only:
    -- "EV:<id>|<id>;CIT:<chunkId>|<chunkId>" — ids already validated
    -- against the case; used to rebuild an idempotent replay response.
    references_summary VARCHAR(2000),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_case_message_seq UNIQUE (case_id, sequence_number)
);
CREATE INDEX idx_case_message_case ON case_message (case_id, sequence_number);
CREATE UNIQUE INDEX uq_case_message_clientref
    ON case_message (case_id, client_reference);
