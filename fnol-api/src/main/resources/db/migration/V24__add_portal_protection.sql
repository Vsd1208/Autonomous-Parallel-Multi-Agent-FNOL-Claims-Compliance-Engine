-- Failed attempts on public portal and upload routes, in the database so a limit holds across instances without new infrastructure.
-- Keys are hashes: neither a client address nor a claim number is stored here.
CREATE TABLE portal_attempts (
    key_hash      VARCHAR(64) PRIMARY KEY,
    failures      INTEGER     NOT NULL,
    window_start  TIMESTAMP WITH TIME ZONE NOT NULL,
    blocked_until TIMESTAMP WITH TIME ZONE
);

-- Short-lived claim-access tokens, revocable. Only a hash is stored, and the token itself holds no claim data.
CREATE TABLE portal_access_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    claim_id   VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_portal_access_tokens_claim ON portal_access_tokens (claim_id);
