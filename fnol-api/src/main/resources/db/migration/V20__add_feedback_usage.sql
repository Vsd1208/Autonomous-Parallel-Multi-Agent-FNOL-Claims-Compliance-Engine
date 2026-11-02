-- Evidence from people using the workspace: how long a decision took and how useful the AI advisory was. Only signed-in
-- people's decisions carry these, so scripts and integrations do not count.
ALTER TABLE ai_feedback ADD COLUMN handling_seconds INTEGER;
ALTER TABLE ai_feedback ADD COLUMN advisory_rating INTEGER;
