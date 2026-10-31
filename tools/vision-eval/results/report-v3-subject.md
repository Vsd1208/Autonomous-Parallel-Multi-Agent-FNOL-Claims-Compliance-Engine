# Vision evaluation - `v3-subject`

Model `gemini-2.5-flash`, thinking budget 0, 66 images (0 API errors). Dataset: Humans in the Loop car parts & damages (CC0).

## Accuracy

| Metric | Value |
|---|---|
| Damaged zone named correctly (lenient) | **81.8%** (54/66) |
| Zone with the most damage named (strict) | 57.6% |
| Damage seen at all (not UNKNOWN) | 98.5% |
| Photo subject reported as VEHICLE (others are false alarms on auto claims) | 66/66 |
| Latency p50 / p95 (includes retries after HTTP 503) | 4.5s / 34.4s |
| Calls slower than the app's 8s read timeout | 18 (27%) |

## Per true zone

| True primary zone | n | Correct | Most common answers |
|---|---|---|---|
| FRONT_BUMPER | 20 | 85% | FRONT_BUMPER 13, MULTIPLE 6, SIDE_PANEL 1 |
| REAR_BUMPER | 20 | 85% | REAR_BUMPER 13, MULTIPLE 5, SIDE_PANEL 1 |
| SIDE_PANEL | 20 | 75% | MULTIPLE 11, SIDE_PANEL 8, FRONT_BUMPER 1 |
| WINDSHIELD | 6 | 83% | WINDSHIELD 4, MULTIPLE 2 |

## Which signal tells right from wrong answers?

| Gemini answered | n | Correct (lenient) | Correct (strict) | Self-reported confidence | Probability margin |
|---|---|---|---|---|---|
| a specific zone | 41 | 98% | 93% | 0.90-0.95 | 0.50-0.90 |
| MULTIPLE / UNKNOWN | 25 | 56% | 0% | 0.70-0.90 | 0.00-1.00 |

Separation (AUC, 0.5 = no information, 1.0 = perfect): self-reported confidence **0.57**, probability margin **0.74**.

## Calibration (signal: probability margin; leave-one-out over 66 images)

| Probability margin | Calibrated confidence | Images |
|---|---|---|
| 0.00-0.20 | 0.64 | 23 |
| 0.50-0.55 | 0.71 | 2 |
| 0.60-0.70 | 0.88 | 10 |
| 0.75-1.00 | 0.95 | 31 |

| Out of sample | Gemini's raw confidence | Calibrated |
|---|---|---|
| Calibration error per signal group (lower is better) | 0.086 | 0.039 |
| Brier score (lower is better) | 0.150 | 0.137 |
| Claims passing the 0.85 confidence factor | 65/66 | 42/66 |
| ...of which Gemini was right | 83% | 93% |
| Wrong answers let through | 11 | 3 |

Verdict: calibrated map beats raw confidence out of sample (Brier 0.137 vs 0.150); 66 images (enough).

