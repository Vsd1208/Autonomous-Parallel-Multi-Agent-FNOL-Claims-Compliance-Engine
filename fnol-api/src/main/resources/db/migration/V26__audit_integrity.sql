-- Audit v2: a versioned canonical event binds every important field, not only the input and output hashes.
-- Existing rows are not rewritten: hash_algorithm stays NULL and they keep verifying under the v1 rule, and new events chain on from them.
ALTER TABLE audit_trail ADD COLUMN IF NOT EXISTS hash_algorithm VARCHAR(32);
ALTER TABLE audit_trail ADD COLUMN IF NOT EXISTS previous_hash VARCHAR(64);
ALTER TABLE audit_trail ADD COLUMN IF NOT EXISTS actor_type VARCHAR(20);
ALTER TABLE audit_trail ADD COLUMN IF NOT EXISTS actor_role VARCHAR(32);
ALTER TABLE audit_trail ADD COLUMN IF NOT EXISTS model_version VARCHAR(160);
ALTER TABLE audit_trail ADD COLUMN IF NOT EXISTS ruleset_version VARCHAR(64);

-- Two appends can never take the same position in a chain, whichever instance they run on.
CREATE UNIQUE INDEX IF NOT EXISTS uq_audit_trail_claim_seq ON audit_trail (claim_id, seq);

-- One row per claim. Appending locks it, which serialises a claim's chain across instances, and it records the head, so a deleted last event shows.
CREATE TABLE audit_chain_heads (
    claim_id  VARCHAR(32) PRIMARY KEY,
    last_seq  INTEGER     NOT NULL,
    head_hash VARCHAR(64) NOT NULL
);
