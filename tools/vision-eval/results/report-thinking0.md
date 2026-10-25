# Vision evaluation - `thinking0`

Model `gemini-2.5-flash`, thinking budget 0, 25 images (0 API errors). Dataset: Humans in the Loop car parts & damages (CC0).

## Accuracy

| Metric | Value |
|---|---|
| Damaged zone named correctly (lenient) | **84.0%** (21/25) |
| Zone with the most damage named (strict) | 60.0% |
| Damage seen at all (not UNKNOWN) | 100.0% |
| Latency p50 / p95 (includes retries after HTTP 503) | 4.6s / 36.3s |
| Calls slower than the app's 8s read timeout | 7 (28%) |

## Per true zone

| True primary zone | n | Correct | Most common answers |
|---|---|---|---|
| FRONT_BUMPER | 7 | 86% | FRONT_BUMPER 3, MULTIPLE 3, SIDE_PANEL 1 |
| REAR_BUMPER | 6 | 100% | REAR_BUMPER 5, MULTIPLE 1 |
| SIDE_PANEL | 6 | 67% | MULTIPLE 3, SIDE_PANEL 3 |
| WINDSHIELD | 6 | 83% | WINDSHIELD 4, MULTIPLE 2 |

## Which signal tells right from wrong answers?

| Gemini answered | n | Correct (lenient) | Correct (strict) | Self-reported confidence | Probability margin |
|---|---|---|---|---|---|
| a specific zone | 16 | 100% | 94% | 0.90-0.95 | 0.55-0.90 |
| MULTIPLE / UNKNOWN | 9 | 56% | 0% | 0.90-0.95 | 0.00-0.10 |

Separation (AUC, 0.5 = no information, 1.0 = perfect): self-reported confidence **0.52**, probability margin **0.86**.

## Calibration (signal: probability margin; leave-one-out over 25 images)

| Probability margin | Calibrated confidence | Images |
|---|---|---|
| 0.00-0.10 | 0.64 | 9 |
| 0.55-0.55 | 0.87 | 1 |
| 0.65-0.65 | 0.87 | 1 |
| 0.70-0.90 | 0.96 | 14 |

| Out of sample | Gemini's raw confidence | Calibrated |
|---|---|---|
| Calibration error per signal group (lower is better) | 0.074 | 0.085 |
| Brier score (lower is better) | 0.140 | 0.125 |
| Claims passing the 0.85 confidence factor | 25/25 | 15/25 |
| ...of which Gemini was right | 84% | 100% |
| Wrong answers let through | 4 | 0 |

Verdict: calibrated map beats raw confidence out of sample (Brier 0.125 vs 0.140); 25 images (below the 50-image minimum).

