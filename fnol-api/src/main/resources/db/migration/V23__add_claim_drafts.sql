-- Text the drafting agent (fnol.drafting-agent.enabled, off by default) or its template wrote, for a person to accept or discard.
-- Built only from redacted tool outputs, and refused if it holds raw personal data. Nothing is sent until a person accepts it.
CREATE TABLE claim_drafts (
    draft_id        VARCHAR(40)  PRIMARY KEY,
    claim_id        VARCHAR(32)  NOT NULL REFERENCES claims (claim_id),
    kind            VARCHAR(30)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    body            TEXT         NOT NULL,
    from_model      BOOLEAN      NOT NULL,
    fallback_reason VARCHAR(500),
    tool_calls      VARCHAR(500),
    requested_by    VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_by      VARCHAR(64),
    decided_at      TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_claim_drafts_claim ON claim_drafts (claim_id);
