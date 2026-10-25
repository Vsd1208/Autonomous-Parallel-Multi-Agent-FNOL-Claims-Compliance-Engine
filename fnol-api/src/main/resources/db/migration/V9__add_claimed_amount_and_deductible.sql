-- What the claimant asked for, and the deductible applied to the recommended payment (reserve).
ALTER TABLE claims ADD COLUMN claimed_amount NUMERIC(14, 2);
ALTER TABLE claims ADD COLUMN deductible NUMERIC(14, 2);
ALTER TABLE claims ADD CONSTRAINT chk_claims_claimed_amount_non_negative CHECK (claimed_amount IS NULL OR claimed_amount >= 0);
