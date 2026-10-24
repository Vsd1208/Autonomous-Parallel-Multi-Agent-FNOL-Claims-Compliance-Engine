-- V7: Fields for the architecture-diagram alignment.
--   gate_flags               reconciliation-gate consistency flags and DUPLICATE_SUBMISSION (comma-separated)
--   adjuster_reserve         reserve set by the adjuster when modifying the AI finding (reserve keeps the AI value)
--   override_reason          why the adjuster changed the AI finding
ALTER TABLE claims ADD COLUMN IF NOT EXISTS gate_flags VARCHAR(500);
ALTER TABLE claims ADD COLUMN IF NOT EXISTS adjuster_reserve NUMERIC(14,2) CHECK (adjuster_reserve >= 0);
ALTER TABLE claims ADD COLUMN IF NOT EXISTS override_reason VARCHAR(500);
