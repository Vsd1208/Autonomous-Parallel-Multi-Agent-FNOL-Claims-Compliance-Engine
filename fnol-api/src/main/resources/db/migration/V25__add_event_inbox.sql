-- Accepted claim events. A 202 means a row here was committed, so a crash cannot lose accepted work.
-- branch_a_json and branch_b_json hold finished branch results between attempts, and are cleared on completion: branch B's text is
-- already redacted, and it is kept only while a retry might need it.
CREATE TABLE fnol_event_inbox (
    id               VARCHAR(40)  PRIMARY KEY,
    event_id         VARCHAR(128) NOT NULL,
    event_type       VARCHAR(64)  NOT NULL,
    aggregate_id     VARCHAR(32)  NOT NULL,
    event_time       TIMESTAMP WITH TIME ZONE,
    payload          TEXT         NOT NULL,
    payload_hash     VARCHAR(64)  NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    attempt_count    INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_owner      VARCHAR(64),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    last_error       VARCHAR(500),
    branch_a_json    TEXT,
    branch_b_json    TEXT,
    received_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at     TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_fnol_event_inbox_event UNIQUE (event_id),
    CONSTRAINT ck_fnol_event_inbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'RETRY_PENDING', 'DEAD_LETTER', 'CANCELLED'))
);
CREATE INDEX idx_fnol_event_inbox_due ON fnol_event_inbox (status, next_attempt_at);
CREATE INDEX idx_fnol_event_inbox_lease ON fnol_event_inbox (status, lease_expires_at);
CREATE INDEX idx_fnol_event_inbox_aggregate ON fnol_event_inbox (aggregate_id);
