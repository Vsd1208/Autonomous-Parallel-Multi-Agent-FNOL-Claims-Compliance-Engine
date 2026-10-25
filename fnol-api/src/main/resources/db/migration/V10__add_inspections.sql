-- Damage inspections (appraisals): an adjuster assigns an inspector, who reports the cost of the damage
-- caused by this accident (excluding earlier damage). One open inspection per claim at a time.
CREATE TABLE inspections (
    inspection_id            VARCHAR(64)    PRIMARY KEY,
    claim_id                 VARCHAR(32)    NOT NULL REFERENCES claims (claim_id),
    inspector_id             VARCHAR(32)    NOT NULL,
    inspector_name           VARCHAR(100)   NOT NULL,
    inspection_type          VARCHAR(16)    NOT NULL,   -- FIELD | DESK | SHOP
    reason                   VARCHAR(500)   NOT NULL,
    status                   VARCHAR(16)    NOT NULL,   -- ASSIGNED | COMPLETED | CANCELLED
    assigned_by              VARCHAR(64)    NOT NULL,
    assigned_at              TIMESTAMPTZ    NOT NULL,
    due_by                   DATE           NOT NULL,
    completed_at             TIMESTAMPTZ,
    accident_related_amount  NUMERIC(14, 2) CHECK (accident_related_amount IS NULL OR accident_related_amount >= 0),
    prior_damage_found       BOOLEAN,
    findings                 VARCHAR(2000),
    recommendation           VARCHAR(16),               -- APPROVE | REDUCE | DENY | INVESTIGATE
    recommended_payment      NUMERIC(14, 2)
);

CREATE INDEX idx_inspections_claim_id ON inspections (claim_id);
