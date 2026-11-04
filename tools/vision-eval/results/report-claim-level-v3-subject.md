# Claim-level evaluation: run `v3-subject`

Model `gemini-2.5-flash`, prompt `501b3308884a64f8`. Command: `python -P claim_eval.py --run v3-subject`. No API calls.

**Each photo stands in for a claim.** A real claim sends five photos together; no labelled multi-photo claims exist. Public car-damage photos, vehicle only, all answers on one prompt. Read as a prototype measurement, not production performance.

## Outcome of every call

| Outcome | Count |
|---|---|
| Answered | 66 |
| Failed after retries (timeout, disconnect, server error) | 0 |
| Refused: the provider had closed this model to this key | 20 |

Refusals are excluded from the failure rate below because they measure the model's retirement, not its reliability. They are the most serious operational finding here: see `docs/AI_MODEL_EVALUATION.md`.

## Accuracy

| Measure | Value | 95% range (Wilson) |
|---|---|---|
| Main damaged area right (strict) | 38/66 = 57.6% | 45.6%–68.8% |
| One of the damaged areas right (lenient) | 54/66 = 81.8% | 70.9%–89.3% |

## Per class (strict, against the main damaged area)

| Area | Photos | Predicted | Precision | Recall | F1 | Missed (false-negative rate) |
|---|---|---|---|---|---|---|
| FRONT_BUMPER | 20 | 14 | 92.9% | 65.0% | 76.5% | 35.0% |
| REAR_BUMPER | 20 | 13 | 100.0% | 65.0% | 78.8% | 35.0% |
| SIDE_PANEL | 20 | 10 | 80.0% | 40.0% | 53.3% | 60.0% |
| WINDSHIELD | 6 | 4 | 100.0% | 66.7% | 80.0% | 33.3% |
| MULTIPLE | 0 | 24 | 0.0% | – | 0.0% | – |
| UNKNOWN | 0 | 1 | 0.0% | – | 0.0% | – |

No label says MULTIPLE or UNKNOWN, so those columns count as wrong under strict by construction.

## Confusion matrix (rows: true main area, columns: answer)

| True \ answered | FRONT_BUMPER | REAR_BUMPER | SIDE_PANEL | WINDSHIELD | MULTIPLE | UNKNOWN |
|---|---|---|---|---|---|---|
| FRONT_BUMPER | 13 | 0 | 1 | 0 | 6 | 0 |
| REAR_BUMPER | 0 | 13 | 1 | 0 | 5 | 1 |
| SIDE_PANEL | 1 | 0 | 8 | 0 | 11 | 0 |
| WINDSHIELD | 0 | 0 | 0 | 4 | 2 | 0 |

## Calibration of Gemini's own confidence (against lenient correctness)

Brier 0.150, calibration error 0.081, over 66 answers. The trust score's calibration, out of sample with ranges, is in `report-trust-score-strict.md`.

| Stated confidence | Answers | Mean stated | Share right |
|---|---|---|---|
| 0.6–0.8 | 1 | 0.70 | 0.0% |
| 0.8–1.0 | 65 | 0.90 | 83.1% |

### By answered class

| Answered | Answers | Brier | Calibration error | Share right |
|---|---|---|---|---|
| FRONT_BUMPER | 14 | 0.067 | 0.029 | 92.9% |
| MULTIPLE | 24 | 0.343 | 0.317 | 58.3% |
| REAR_BUMPER | 13 | 0.010 | 0.100 | 100.0% |
| SIDE_PANEL | 10 | 0.008 | 0.085 | 100.0% |
| UNKNOWN | 1 | 0.490 | 0.700 | 0.0% |
| WINDSHIELD | 4 | 0.010 | 0.100 | 100.0% |

### By image quality

Gemini's own photoQuality was {'CLEAR': 66}, so it cannot split the sample. Sharpness measured from the pixels (variance of the Laplacian), in thirds of this sample:

| Sharpness third | Answers | Laplacian variance | Brier | Calibration error | Share right |
|---|---|---|---|---|---|
| Softest | 22 | 110–443 | 0.155 | 0.084 | 81.8% |
| Middle | 22 | 446–775 | 0.119 | 0.039 | 86.4% |
| Sharpest | 22 | 775–3047 | 0.177 | 0.120 | 77.3% |

About 22 answers a band: differences between bands are not evidence of anything on their own.

Reliability diagram: [reliability-v3-subject.svg](reliability-v3-subject.svg).

## Latency and reliability

| Measure | Value |
|---|---|
| Latency p50 / p90 / p95 / p99 / max | 4.4 / 20.9 / 34.6 / 40.6 / 40.6 s |
| Slower than the app's 8 s read timeout (those claims go to a person) | 18/66 = 27.3% |
| Failed after retries | 0/66 = 0.0% |

Free tier, and the latency includes the tool's retries after HTTP 503, so it is not a paid-tier service level.

## Cost per claim

Measured: mean 882 input tokens (258 of them the photo) and 165 output tokens for one photo.

At $0.30 in and $2.50 out per million tokens (standard paid tier, read from the provider's pricing page on 4 Nov 2026): **$0.00068** for this one-photo call; about **$0.00099** for a five-photo claim, an estimate assuming each extra photo adds the same image tokens and the answer stays the same length. The second-opinion model and photo redaction run on your own hardware and are not priced.

## What this data cannot measure

| Measure | Why not | What it needs |
|---|---|---|
| Severity accuracy | These public photos have no severity label | Adjuster-graded photos |
| Repair-cost accuracy | No public dataset links photos to repair costs | Repair invoices |
| Adjuster override rate | `data/feedback.csv` holds one decision, from a demo | Decisions from real adjusters |
| Comparison with human adjusters | No adjuster has labelled these photos | Double-labelling by adjusters |
| True claim-level accuracy | Each photo stands in for a claim | Labelled multi-photo claims |
| Property claims through Gemini | The property prompt has no recorded run | A run on the labelled FEMA photos, kept separate from these |
