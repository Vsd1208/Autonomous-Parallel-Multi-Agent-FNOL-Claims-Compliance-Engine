ALTER TABLE claims
  ADD COLUMN adjuster_decision VARCHAR(64),
  ADD COLUMN adjuster_id VARCHAR(128),
  ADD COLUMN adjuster_notes VARCHAR(2000),
  ADD COLUMN reviewed_at TIMESTAMPTZ;
