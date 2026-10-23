-- V4: Failed intakes (compliance block, uncovered policy, timeout) are saved for human attention.
-- failure_reason holds the error message; the raw description and PII are never stored.
ALTER TABLE claims ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(500);
