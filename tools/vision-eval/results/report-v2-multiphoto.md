# Vision evaluation - `v2-multiphoto`

Model `gemini-2.5-flash`, thinking budget 0, 33 images (0 API errors). Dataset: Humans in the Loop car parts & damages (CC0).

## Accuracy

| Metric | Value |
|---|---|
| Damaged zone named correctly (lenient) | **90.9%** (30/33) |
| Zone with the most damage named (strict) | 66.7% |
| Damage seen at all (not UNKNOWN) | 100.0% |
| Photo subject | not asked by this prompt |
| Latency p50 / p95 (includes retries after HTTP 503) | 3.4s / 34.3s |
| Calls slower than the app's 8s read timeout | 4 (12%) |

## Per true zone

| True primary zone | n | Correct | Most common answers |
|---|---|---|---|
| FRONT_BUMPER | 9 | 89% | FRONT_BUMPER 5, MULTIPLE 3, SIDE_PANEL 1 |
| REAR_BUMPER | 9 | 100% | REAR_BUMPER 7, SIDE_PANEL 2 |
| SIDE_PANEL | 9 | 89% | SIDE_PANEL 6, MULTIPLE 2, FRONT_BUMPER 1 |
| WINDSHIELD | 6 | 83% | WINDSHIELD 4, MULTIPLE 2 |

## Which signal tells right from wrong answers?

| Gemini answered | n | Correct (lenient) | Correct (strict) | Self-reported confidence | Probability margin |
|---|---|---|---|---|---|
| a specific zone | 26 | 96% | 85% | 0.80-0.90 | 0.60-0.85 |
| MULTIPLE / UNKNOWN | 7 | 71% | 0% | 0.90-0.90 | 0.00-0.20 |

Separation (AUC, 0.5 = no information, 1.0 = perfect): self-reported confidence **0.48**, probability margin **0.82**.

## Calibration (signal: probability margin; leave-one-out over 33 images)

| Probability margin | Calibrated confidence | Images |
|---|---|---|
| 0.00-0.00 | 0.81 | 3 |
| 0.10-0.20 | 0.83 | 4 |
| 0.60-0.70 | 0.90 | 10 |
| 0.80-0.80 | 0.95 | 4 |
| 0.85-0.85 | 0.98 | 12 |

| Out of sample | Gemini's raw confidence | Calibrated |
|---|---|---|
| Calibration error per signal group (lower is better) | 0.012 | 0.040 |
| Brier score (lower is better) | 0.084 | 0.089 |
| Claims passing the 0.85 confidence factor | 32/33 | 27/33 |
| ...of which Gemini was right | 91% | 89% |
| Wrong answers let through | 3 | 3 |

Verdict: calibrated map does NOT beat raw confidence out of sample (Brier 0.089 vs 0.084); 33 images (below the 50-image minimum).

