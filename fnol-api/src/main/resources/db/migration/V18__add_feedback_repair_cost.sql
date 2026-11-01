-- Real repair costs next to what the AI estimated, so the cost model can learn from this insurer's own claims
-- (tools/cost-eval/fit_costs.py). final_repair_cost: an inspection's accident-related amount, or the repair cost the
-- adjuster confirmed (cost_source says which); null = not known when the decision was recorded.
ALTER TABLE ai_feedback ADD COLUMN final_repair_cost NUMERIC(14, 2) CHECK (final_repair_cost IS NULL OR final_repair_cost >= 0);
ALTER TABLE ai_feedback ADD COLUMN cost_source VARCHAR(20);
ALTER TABLE ai_feedback ADD COLUMN ai_estimate NUMERIC(14, 2);
ALTER TABLE ai_feedback ADD COLUMN extent_band VARCHAR(10);
ALTER TABLE ai_feedback ADD COLUMN published_estimate NUMERIC(14, 2);
