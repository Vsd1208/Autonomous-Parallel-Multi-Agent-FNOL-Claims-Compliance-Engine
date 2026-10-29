# Vision evaluation - `v3-subject`

Model `gemini-2.5-flash`, thinking budget 0, 32 images (0 API errors). Dataset: Humans in the Loop car parts & damages (CC0).

## Accuracy

| Metric | Value |
|---|---|
| Damaged zone named correctly (lenient) | **81.2%** (26/32) |
| Zone with the most damage named (strict) | 56.2% |
| Damage seen at all (not UNKNOWN) | 100.0% |
| Photo subject reported as VEHICLE (others are false alarms on auto claims) | 32/32 |
| Latency p50 / p95 (includes retries after HTTP 503) | 3.8s / 34.6s |
| Calls slower than the app's 8s read timeout | 5 (16%) |

## Per true zone

| True primary zone | n | Correct | Most common answers |
|---|---|---|---|
| FRONT_BUMPER | 9 | 89% | FRONT_BUMPER 5, MULTIPLE 3, SIDE_PANEL 1 |
| REAR_BUMPER | 9 | 89% | REAR_BUMPER 6, MULTIPLE 2, SIDE_PANEL 1 |
| SIDE_PANEL | 8 | 62% | MULTIPLE 4, SIDE_PANEL 3, FRONT_BUMPER 1 |
| WINDSHIELD | 6 | 83% | WINDSHIELD 4, MULTIPLE 2 |

## Which signal tells right from wrong answers?

| Gemini answered | n | Correct (lenient) | Correct (strict) | Self-reported confidence | Probability margin |
|---|---|---|---|---|---|
| a specific zone | 21 | 95% | 86% | 0.90-0.95 | 0.50-0.85 |
| MULTIPLE / UNKNOWN | 11 | 55% | 0% | 0.90-0.90 | 0.00-0.20 |

Separation (AUC, 0.5 = no information, 1.0 = perfect): self-reported confidence **0.52**, probability margin **0.83**.

## Calibration (signal: probability margin; leave-one-out over 32 images)

| Probability margin | Calibrated confidence | Images |
|---|---|---|
| 0.00-0.05 | 0.62 | 6 |
| 0.10-0.20 | 0.69 | 5 |
| 0.50-0.70 | 0.82 | 6 |
| 0.75-0.75 | 0.85 | 1 |
| 0.77-0.77 | 0.85 | 1 |
| 0.80-0.80 | 0.89 | 3 |
| 0.85-0.85 | 0.95 | 10 |

| Out of sample | Gemini's raw confidence | Calibrated |
|---|---|---|
| Calibration error per signal group (lower is better) | 0.092 | 0.090 |
| Brier score (lower is better) | 0.160 | 0.154 |
| Claims passing the 0.85 confidence factor | 32/32 | 14/32 |
| ...of which Gemini was right | 81% | 93% |
| Wrong answers let through | 6 | 1 |

Verdict: calibrated map beats raw confidence out of sample (Brier 0.154 vs 0.160); 32 images (below the 50-image minimum).

