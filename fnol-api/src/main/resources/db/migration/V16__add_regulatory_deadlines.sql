-- Regulatory deadlines from the cited rule set (claim-regulations.json) and when the written acknowledgement was filed.
ALTER TABLE claims ADD COLUMN acknowledge_due DATE;
ALTER TABLE claims ADD COLUMN decision_due DATE;
ALTER TABLE claims ADD COLUMN acknowledged_at TIMESTAMP WITH TIME ZONE;
