-- Supplementary photo requests: the adjuster asks the claimant for more photos through a one-time link.
-- Only the SHA-256 of the link's token is stored, so the table cannot be used to upload.
CREATE TABLE upload_requests (
    request_id        VARCHAR(64)    PRIMARY KEY,
    claim_id          VARCHAR(32)    NOT NULL REFERENCES claims (claim_id),
    token_sha256      VARCHAR(64)    NOT NULL UNIQUE,
    requested_angles  VARCHAR(200)   NOT NULL,   -- comma-separated: FRONT, REAR, LEFT, RIGHT, CLOSE_UP, ...
    message           VARCHAR(500),
    requested_by      VARCHAR(64)    NOT NULL,
    status            VARCHAR(16)    NOT NULL,   -- OPEN | USED | CANCELLED
    created_at        TIMESTAMPTZ    NOT NULL,
    expires_at        TIMESTAMPTZ    NOT NULL,
    used_at           TIMESTAMPTZ,
    photos_received   INTEGER
);

CREATE INDEX idx_upload_requests_claim_id ON upload_requests (claim_id);
