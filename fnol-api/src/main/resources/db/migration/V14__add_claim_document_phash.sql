-- Perceptual hash (dHash) of each uploaded photo, to recognise a photo reused on a later claim (PHOTO_REUSED).
ALTER TABLE claim_documents ADD COLUMN phash VARCHAR(16);
CREATE INDEX idx_claim_documents_sha256 ON claim_documents (sha256);
