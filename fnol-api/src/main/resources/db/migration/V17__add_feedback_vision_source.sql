-- Which model, prompt and settings produced the AI answer an adjuster decided on. A trust-score refit learns only from
-- feedback on the prompt it was measured with (tools/vision-eval/refit_from_feedback.py); null = mock or fallback.
ALTER TABLE ai_feedback ADD COLUMN vision_source VARCHAR(80);
