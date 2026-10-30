# Vision evaluation - `v3-subject`

Model `gemini-2.5-flash`, thinking budget 0, 50 images (0 API errors). Dataset: Humans in the Loop car parts & damages (CC0).

## Accuracy

| Metric | Value |
|---|---|
| Damaged zone named correctly (lenient) | **82.0%** (41/50) |
| Zone with the most damage named (strict) | 56.0% |
| Damage seen at all (not UNKNOWN) | 100.0% |
| Photo subject reported as VEHICLE (others are false alarms on auto claims) | 50/50 |
| Latency p50 / p95 (includes retries after HTTP 503) | 4.4s / 34.4s |
| Calls slower than the app's 8s read timeout | 15 (30%) |

## Per true zone

| True primary zone | n | Correct | Most common answers |
|---|---|---|---|
| FRONT_BUMPER | 15 | 93% | FRONT_BUMPER 10, MULTIPLE 4, SIDE_PANEL 1 |
| REAR_BUMPER | 15 | 87% | REAR_BUMPER 9, MULTIPLE 5, SIDE_PANEL 1 |
| SIDE_PANEL | 14 | 64% | MULTIPLE 8, SIDE_PANEL 5, FRONT_BUMPER 1 |
| WINDSHIELD | 6 | 83% | WINDSHIELD 4, MULTIPLE 2 |

## Which signal tells right from wrong answers?

| Gemini answered | n | Correct (lenient) | Correct (strict) | Self-reported confidence | Probability margin |
|---|---|---|---|---|---|
| a specific zone | 31 | 97% | 90% | 0.90-0.95 | 0.50-0.90 |
| MULTIPLE / UNKNOWN | 19 | 58% | 0% | 0.90-0.90 | 0.00-0.55 |

Separation (AUC, 0.5 = no information, 1.0 = perfect): self-reported confidence **0.52**, probability margin **0.79**.

## Calibration (signal: probability margin; leave-one-out over 50 images)

| Probability margin | Calibrated confidence | Images |
|---|---|---|
| 0.00-0.20 | 0.65 | 18 |
| 0.50-0.55 | 0.71 | 2 |
| 0.60-0.77 | 0.88 | 10 |
| 0.80-0.82 | 0.91 | 4 |
| 0.85-0.90 | 0.96 | 16 |

| Out of sample | Gemini's raw confidence | Calibrated |
|---|---|---|
| Calibration error per signal group (lower is better) | 0.086 | 0.055 |
| Brier score (lower is better) | 0.154 | 0.137 |
| Claims passing the 0.85 confidence factor | 50/50 | 24/50 |
| ...of which Gemini was right | 82% | 92% |
| Wrong answers let through | 9 | 2 |

Verdict: calibrated map beats raw confidence out of sample (Brier 0.137 vs 0.154); 50 images (enough).

