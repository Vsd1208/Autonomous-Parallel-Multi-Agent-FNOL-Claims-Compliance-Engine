# Vision evaluation - `v3-subject`

Model `gemini-2.5-flash`, thinking budget 0, 15 images (0 API errors). Dataset: Humans in the Loop car parts & damages (CC0).

## Accuracy

| Metric | Value |
|---|---|
| Damaged zone named correctly (lenient) | **80.0%** (12/15) |
| Zone with the most damage named (strict) | 60.0% |
| Damage seen at all (not UNKNOWN) | 100.0% |
| Photo subject reported as VEHICLE (others are false alarms on auto claims) | 15/15 |
| Latency p50 / p95 (includes retries after HTTP 503) | 4.2s / 35.0s |
| Calls slower than the app's 8s read timeout | 2 (13%) |

## Per true zone

| True primary zone | n | Correct | Most common answers |
|---|---|---|---|
| FRONT_BUMPER | 4 | 75% | FRONT_BUMPER 2, MULTIPLE 1, SIDE_PANEL 1 |
| REAR_BUMPER | 4 | 75% | MULTIPLE 2, REAR_BUMPER 2 |
| SIDE_PANEL | 4 | 75% | SIDE_PANEL 3, MULTIPLE 1 |
| WINDSHIELD | 3 | 100% | WINDSHIELD 2, MULTIPLE 1 |

## Which signal tells right from wrong answers?

| Gemini answered | n | Correct (lenient) | Correct (strict) | Self-reported confidence | Probability margin |
|---|---|---|---|---|---|
| a specific zone | 10 | 100% | 90% | 0.90-0.95 | 0.50-0.85 |
| MULTIPLE / UNKNOWN | 5 | 40% | 0% | 0.90-0.90 | 0.00-0.20 |

Separation (AUC, 0.5 = no information, 1.0 = perfect): self-reported confidence **0.54**, probability margin **0.92**.

## Calibration (signal: probability margin; leave-one-out over 15 images)

| Probability margin | Calibrated confidence | Images |
|---|---|---|
| 0.00-0.00 | 0.60 | 3 |
| 0.10-0.20 | 0.70 | 2 |
| 0.50-0.50 | 0.84 | 1 |
| 0.70-0.70 | 0.84 | 1 |
| 0.75-0.75 | 0.84 | 1 |
| 0.77-0.77 | 0.84 | 1 |
| 0.80-0.80 | 0.87 | 2 |
| 0.85-0.85 | 0.90 | 4 |

| Out of sample | Gemini's raw confidence | Calibrated |
|---|---|---|
| Calibration error per signal group (lower is better) | 0.110 | 0.209 |
| Brier score (lower is better) | 0.170 | 0.171 |
| Claims passing the 0.85 confidence factor | 15/15 | 5/15 |
| ...of which Gemini was right | 80% | 80% |
| Wrong answers let through | 3 | 1 |

Verdict: calibrated map does NOT beat raw confidence out of sample (Brier 0.171 vs 0.170); 15 images (below the 50-image minimum).

