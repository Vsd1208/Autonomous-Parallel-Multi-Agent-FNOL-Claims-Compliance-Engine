-- The full AI analysis (branch A and B results, notes, flags) so any adjuster's browser can show it.
-- Stored without the redacted description text and without audit records (those are in audit_trail).
ALTER TABLE claims ADD COLUMN analysis_json TEXT;
