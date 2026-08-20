CREATE TABLE claims (
  claim_id VARCHAR(32) PRIMARY KEY,
  policy_number VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  incident_date DATE NOT NULL,
  state CHAR(2) NOT NULL,
  estimated_damage NUMERIC(14,2) NOT NULL CHECK (estimated_damage >= 0),
  reserve NUMERIC(14,2) NOT NULL CHECK (reserve >= 0),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_claims_policy_number ON claims(policy_number);
CREATE TABLE audit_trail (
  event_id VARCHAR(64) PRIMARY KEY,
  claim_id VARCHAR(32) NOT NULL,
  agent_name VARCHAR(128) NOT NULL,
  timestamp TIMESTAMPTZ NOT NULL,
  input_hash VARCHAR(64) NOT NULL,
  output_hash VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL
);
CREATE INDEX idx_audit_trail_claim_id ON audit_trail(claim_id);
