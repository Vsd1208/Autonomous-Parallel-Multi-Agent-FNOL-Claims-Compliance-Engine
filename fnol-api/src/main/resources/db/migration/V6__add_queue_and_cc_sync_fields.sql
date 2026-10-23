-- V6: Fields for the adjuster queue and ClaimCenter synchronisation.
--   severity_gate  LOW | MEDIUM | HIGH from the WHY panel (shown in the queue)
--   input_flags    comma-separated Human Review Point 1 flags, e.g. MISSING_EVIDENCE
--   cc_sync_status SYNCED | FAILED | NOT_FILED: whether ClaimCenter has the latest advisory/decision.
--                  FAILED rows are picked up by a retry job.
ALTER TABLE claims ADD COLUMN IF NOT EXISTS severity_gate VARCHAR(16);
ALTER TABLE claims ADD COLUMN IF NOT EXISTS input_flags VARCHAR(500);
ALTER TABLE claims ADD COLUMN IF NOT EXISTS cc_sync_status VARCHAR(16);
CREATE INDEX IF NOT EXISTS idx_claims_status_created ON claims(status, created_at);
CREATE INDEX IF NOT EXISTS idx_claims_cc_sync_status ON claims(cc_sync_status);
