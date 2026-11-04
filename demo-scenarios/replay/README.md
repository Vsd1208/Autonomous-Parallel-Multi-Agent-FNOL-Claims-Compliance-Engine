# Replay set

Real recorded model answers, so a review with no model and no API key still shows the trust score and the vision checks working on
real output. **It is not live analysis.** Only these 66 photos have answers; any other photo gets a placeholder and goes to a person.

| File | What it holds |
|---|---|
| `manifest.json` | Source, licence, model, prompt SHA, thinking budget, recording dates |
| `answers.json` | For each photo: Gemini's structured answer as returned, the second-opinion model's out-of-fold output, the ground-truth label, licence and source. No usage, latency, request or account data |
| `photos/` | Downscaled copies (longest side 512 px, JPEG quality 75) of the CC0 photos. An answer is found by the SHA-256 of these bytes |
| `scenario-*.json` | The five photos `run-all.sh` and the console's "Recorded" sample sets use, chosen by rule from the answers |

Regenerate with `cd tools/vision-eval && python -P export_replay.py --run v3-subject`. It needs the local evaluation data, which git ignores, and gives the same bytes each time. Switch: `fnol.vision.provider=replay`, set by `docker-compose.lite.yml`; it refuses to start with Gemini enabled.

The second-opinion figures are out of fold: the model that judged a photo was not trained on it. The Gemini answers are the ones the trust score's weights were fitted on, so a trust score shown in replay is in sample. See [metrics §2](../../docs/metrics.md#2-trust-score-calibrated-ai-confidence-vehicle-claims).
