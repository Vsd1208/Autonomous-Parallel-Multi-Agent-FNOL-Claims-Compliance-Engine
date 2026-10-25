-- V8: Evidence documents attached to a claim, so the adjuster can see the photos the AI assessed.
--   content     image bytes for uploaded photos (data: URIs); NULL for references (https / mock:// URIs)
--   source_uri  the reference for non-uploaded evidence; NULL for uploads (the base64 is not kept twice)
--   sha256      hex digest of the stored bytes, also written to the audit chain
-- Photos can show people or plates: they are served only to adjusters (X-Adjuster-Token).
CREATE TABLE IF NOT EXISTS claim_documents (
  document_id   VARCHAR(64)  PRIMARY KEY,
  claim_id      VARCHAR(32)  NOT NULL,
  name          VARCHAR(255) NOT NULL,
  content_type  VARCHAR(64),
  size_bytes    INTEGER,
  sha256        VARCHAR(64),
  source        VARCHAR(16)  NOT NULL,
  source_uri    VARCHAR(2000),
  content       BYTEA,
  uploaded_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_claim_documents_claim_id ON claim_documents(claim_id);
