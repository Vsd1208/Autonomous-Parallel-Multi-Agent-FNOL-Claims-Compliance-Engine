-- V5: Hash-chained audit trail.
--   chain_hash = SHA-256(previous chain_hash for the claim + input_hash + output_hash), hex.
--   seq        = 1-based position of the event within its claim's chain.
--   actor      = who caused the event: SYSTEM, or the adjuster ID for review events.
-- Rows written before V5 have no chain and report chainValid=false.
ALTER TABLE audit_trail ADD COLUMN IF NOT EXISTS chain_hash VARCHAR(64);
ALTER TABLE audit_trail ADD COLUMN IF NOT EXISTS seq INTEGER;
ALTER TABLE audit_trail ADD COLUMN IF NOT EXISTS actor VARCHAR(128);
CREATE INDEX IF NOT EXISTS idx_audit_trail_claim_seq ON audit_trail(claim_id, seq);
