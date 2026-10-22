-- V3: Add explanation_json (WHY panel persistence) and channel (intake source tracking) to claims
ALTER TABLE claims ADD COLUMN IF NOT EXISTS explanation_json TEXT;
ALTER TABLE claims ADD COLUMN IF NOT EXISTS channel VARCHAR(32);
