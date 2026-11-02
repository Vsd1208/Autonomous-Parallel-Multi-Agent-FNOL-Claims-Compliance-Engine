-- A decision whose reserve is above the person's authority waits here, with nothing posted, for someone with enough.
CREATE TABLE reserve_approvals (
    approval_id           VARCHAR(40)    PRIMARY KEY,
    claim_id              VARCHAR(32)    NOT NULL REFERENCES claims (claim_id),
    decision              VARCHAR(40)    NOT NULL,
    amount                NUMERIC(14, 2) NOT NULL,
    previous_status       VARCHAR(40)    NOT NULL,
    requested_by          VARCHAR(64)    NOT NULL,
    requested_role        VARCHAR(20),
    requested_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    assigned_to           VARCHAR(40),
    notes                 TEXT,
    adjusted_reserve      NUMERIC(14, 2),
    override_reason       VARCHAR(500),
    confirmed_damage_type VARCHAR(40),
    final_repair_cost     NUMERIC(14, 2),
    repair_cost_source    VARCHAR(20),
    handling_seconds      INTEGER,
    advisory_rating       INTEGER,
    status                VARCHAR(16)    NOT NULL,
    decided_by            VARCHAR(40),
    decided_at            TIMESTAMP WITH TIME ZONE,
    decision_note         TEXT
);
CREATE INDEX idx_reserve_approvals_claim ON reserve_approvals (claim_id);
CREATE INDEX idx_reserve_approvals_assignee ON reserve_approvals (assigned_to, status);
