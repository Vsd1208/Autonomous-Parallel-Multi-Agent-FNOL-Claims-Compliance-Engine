# Vision evaluation - `v2-multiphoto`

Model `gemini-2.5-flash`, thinking budget 0, 18 images (0 API errors). Dataset: Humans in the Loop car parts & damages (CC0).

## Accuracy

| Metric | Value |
|---|---|
| Damaged zone named correctly (lenient) | **94.4%** (17/18) |
| Zone with the most damage named (strict) | 72.2% |
| Damage seen at all (not UNKNOWN) | 100.0% |
| Latency p50 / p95 (includes retries after HTTP 503) | 3.2s / 33.6s |
| Calls slower than the app's 8s read timeout | 2 (11%) |

## Per true zone

| True primary zone | n | Correct | Most common answers |
|---|---|---|---|
| FRONT_BUMPER | 5 | 80% | FRONT_BUMPER 3, MULTIPLE 1, SIDE_PANEL 1 |
| REAR_BUMPER | 5 | 100% | REAR_BUMPER 3, SIDE_PANEL 2 |
| SIDE_PANEL | 4 | 100% | SIDE_PANEL 4 |
| WINDSHIELD | 4 | 100% | WINDSHIELD 3, MULTIPLE 1 |

## Which signal tells right from wrong answers?

| Gemini answered | n | Correct (lenient) | Correct (strict) | Self-reported confidence | Probability margin |
|---|---|---|---|---|---|
| a specific zone | 16 | 100% | 81% | 0.80-0.90 | 0.60-0.85 |
| MULTIPLE / UNKNOWN | 2 | 50% | 0% | 0.90-0.90 | 0.00-0.20 |

Separation (AUC, 0.5 = no information, 1.0 = perfect): self-reported confidence **0.47**, probability margin **0.94**.

## Calibration (signal: probability margin; leave-one-out over 18 images)

| Probability margin | Calibrated confidence | Images |
|---|---|---|
| 0.00-0.20 | 0.80 | 2 |
| 0.60-0.60 | 0.96 | 1 |
| 0.65-0.65 | 0.96 | 1 |
| 0.70-0.85 | 0.99 | 14 |

| Out of sample | Gemini's raw confidence | Calibrated |
|---|---|---|
| Calibration error per signal group (lower is better) | 0.050 | 0.067 |
| Brier score (lower is better) | 0.056 | 0.062 |
| Claims passing the 0.85 confidence factor | 17/18 | 16/18 |
| ...of which Gemini was right | 94% | 94% |
| Wrong answers let through | 1 | 1 |

Verdict: calibrated map does NOT beat raw confidence out of sample (Brier 0.062 vs 0.056); 18 images (below the 50-image minimum).

