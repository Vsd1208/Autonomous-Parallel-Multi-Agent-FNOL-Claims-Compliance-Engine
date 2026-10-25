-- Which view of the loss each evidence photo shows (standard set: front, rear, left, right, close-up).
ALTER TABLE claim_documents ADD COLUMN angle VARCHAR(16);
