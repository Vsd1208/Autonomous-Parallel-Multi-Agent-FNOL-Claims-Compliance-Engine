-- Which segment a claim falls in, and the group and person it is assigned to.
ALTER TABLE claims ADD COLUMN segment VARCHAR(40);
ALTER TABLE claims ADD COLUMN assignment_group VARCHAR(80);
ALTER TABLE claims ADD COLUMN assigned_to VARCHAR(40);
ALTER TABLE claims ADD COLUMN assigned_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX idx_claims_assigned_to ON claims (assigned_to, status);
CREATE INDEX idx_claims_segment ON claims (segment, status);
