# Measured results

The single source of truth for every measured number in this repository. Other documents link here instead of
repeating figures. Each row gives the value, when it was measured, the run and command that produced it, the sample
size and the caveat. Where two runs legitimately differ (an older prompt, a different sample), the row says which run.

**Read these as prototype measurements on small public datasets, not production performance.** No figure here comes
from real insurer claims.

## 1. Gemini damage assessment (vehicle)

Run `v3-subject`: `gemini-2.5-flash`, thinking budget 0, the app's current vehicle prompt (SHA-256 `501b3308884a64f8`),
one photo per call, on labelled car-damage photos (Humans in the Loop, CC0). Measured 30 Oct 2026.
Command: `cd tools/vision-eval && python -P evaluate.py --run v3-subject …` then `python -P calibrate.py --run v3-subject`.
Report: [tools/vision-eval/results/report-v3-subject.md](../tools/vision-eval/results/report-v3-subject.md).

| Metric | Value | Sample | Caveat |
|---|---|---|---|
| Damaged area named correctly, lenient (one of the damaged areas) | **82.0%** (41/50) | 50 photos | One photo per call; a real claim sends the standard set of five |
| Damaged area named correctly, strict (the most damaged area) | 56.0% | 50 photos | "Multiple areas" answers count as wrong here |
| Car photos reported as a vehicle (`photoSubject`) | 50/50 | 50 photos | Every photo is a car, so this measures false alarms only |
| Gemini's self-reported confidence: separation of right from wrong (AUC) | 0.52 | 50 photos | 0.5 = no separation |
| Probability margin: separation of right from wrong (AUC) | 0.79 | 50 photos | From `vision-calibration.json` (`leaveOneOut.auc_margin`) |
| Latency p50 / p95 (free tier, includes retries after HTTP 503) | 4.4 s / 34.4 s | 50 calls | 30% of calls were slower than the app's 8 s read timeout; those claims fall back to a human |
| Dollar estimate and severity accuracy | **not measured** | — | No public dataset with repair costs was found |
| Property (HOME) prompt accuracy | **not measured** | — | 53 labelled FEMA photos are prepared (`prepare_home.py`); the run has not been done |

Previous prompt, kept for comparison only: run `v2-multiphoto` (prompt `61a89427e0e132d9`), 33 photos, lenient / strict
91% / 67% ([report-v2-multiphoto.md](../tools/vision-eval/results/report-v2-multiphoto.md)). It is a different and
smaller sample, so it is not evidence that the newer prompt is worse.

## 2. Trust score (calibrated AI confidence, vehicle claims)

Logistic regression on the probability margin, the second-opinion model's agreement and whether a specific area was
named (`tools/vision-eval/trust_score.py`, app class `TrustScorer`). Leave-one-image-out on the 50 `v3-subject`
answers; 95% ranges from 2000 bootstrap resamples. Measured 30 Oct 2026.
Command: `python -P trust_score.py --run v3-subject`. Report:
[tools/vision-eval/results/report-trust-score.md](../tools/vision-eval/results/report-trust-score.md).

| Metric | Gemini's own confidence | Trust score in the app | Caveat |
|---|---|---|---|
| Separation of right from wrong (AUC) | 0.52 | **0.68** | 50 answers, 9 wrong |
| Brier score, lower is better (95% range) | 0.154 (0.073–0.234) | **0.127** (0.074–0.186) | Ranges overlap |
| Calibration error, lower is better (95% range) | 0.122 (0.037–0.201) | 0.212 (0.091–0.263) | The trust score ranks well but is not yet well calibrated |
| Answers passing the 0.85 confidence factor / wrong among them | 50 / 9 | **31 / 1** | Recorded when the score shipped (30 Oct 2026), [tools/vision-eval/README.md](../tools/vision-eval/README.md) |

Status in the app: **active** (`fnol-agents/src/main/resources/trust-score.json`: 50 images, matching prompt, model and
thinking budget). Home claims keep Gemini's confidence.

The margin-based calibration map (`vision-calibration.json`, `tools/vision-eval/calibrate.py`) reports Brier 0.137 vs
0.154 and calibration error 0.055 vs 0.086 on the same 50 answers. Its calibration error uses the older binning, so it
is not comparable with the trust score's (which reports 0.122 for Gemini's raw confidence on the same data).

## 3. Vision checks replayed (gate ablation, vehicle)

The 50 `v3-subject` answers replayed through the app's checks, adding one at a time (no API calls). Commands:
`python -P gate_ablation.py --run v2-multiphoto --run v3-subject`
([results/gate-ablation.md](../tools/vision-eval/results/gate-ablation.md)) and, for the second-opinion row,
`python -P compare_gemini.py` in `tools/second-opinion`. Measured 30 Oct 2026; `gate_ablation.py` re-run 31 Oct 2026 with the same result.

| Checks on | Could go straight through | Wrong answers let through (lenient / strict) | Right answers sent to an adjuster |
|---|---|---|---|
| Gemini's confidence only (≥ 0.85) | 50/50 | 9 / 22 | 0 |
| + probability-margin check (`AMBIGUOUS_DAMAGE_TYPE`) | 32/50 | 2 / 4 | 11 |
| + severity and photo-subject checks | 32/50 | 2 / 4 | 11 |
| + second-opinion model (`MODEL_DISAGREEMENT`) | 29/50 | **1** / — | 13 |

Caveat: vision checks only. Coverage, the claimed-amount check, intake flags and the severity gate also apply to real
claims. The one wrong answer left (photo 1336) is a confident "multiple areas" answer, which the second-opinion model
has no class for.

## 4. Second-opinion model (trained in this project)

CLIP ViT-B/32 embeddings + logistic regression; 5-fold stratified cross-validation, seed 7, every number out-of-fold.
Measured 30 Oct 2026. Command: `cd tools/second-opinion && python -P build_model.py`. Reports:
[report-second-opinion.md](../tools/second-opinion/results/report-second-opinion.md),
[disagreement.md](../tools/second-opinion/results/disagreement.md).

| Metric | Value | Sample | Caveat |
|---|---|---|---|
| Car damaged area, strict / lenient | 64.6% / 80.5% | 441 car photos (CC0) | Weaker than Gemini alone; 6 windshield photos only |
| Car photos reported as vehicle | 441/441 | 441 | Zero-shot |
| Home photos reported as building | 48/53 | 53 FEMA photos | Zero-shot |
| Home cause of loss | 84.9% | 53 FEMA photos | Zero-shot, hand-reviewed labels |
| As a check on Gemini (threshold 0.15): answers flagged / lenient-wrong caught / strict-wrong caught / right answers sent to an adjuster | 6/56, **2/2**, 4/6, 4 | 56 Gemini answers that pass the confidence and margin checks (runs `v2-multiphoto` + `v3-subject`) | Two wrong answers is too few to estimate a catch rate |

## 5. Privacy redaction

From `fnol-vision-ml/redaction/evaluate_redaction.py`, as recorded in [model-cards.md](model-cards.md) (30 Oct 2026). The
raw output is not committed; re-run the script to reproduce.

| Metric | Value | Sample |
|---|---|---|
| Licence plates blurred / false faces | 6 / 0 | 441 car photos |
| Photos where a blur touched damage / share of damage pixels hidden | 2 / 0.05% | 441 car photos |
| Faces blurred | 50 faces in 20 photos | 66 FEMA home photos |

Caveat: the plate detector misses many plate styles; no ground-truth count of plates or faces exists, so recall is
unknown.

## 6. Engineering

| Metric | Value | Date | Command |
|---|---|---|---|
| Backend tests | 253 passed, 0 failed before this round's changes (fnol-agents 84, fnol-orchestrator 72, fnol-api 61, fnol-guardrails 13, fnol-mock-guidewire 13, fnol-enrichment 10); current count in [AUDIT.md](AUDIT.md#5-after) | 31 Oct 2026 | `mvn -B verify` |
| Instruction coverage (JaCoCo) | fnol-guardrails 96%, fnol-orchestrator 93%, fnol-mock-guidewire 93%, fnol-agents 85%, fnol-api 78%, fnol-enrichment 65% | 31 Oct 2026 | `mvn -B verify`, then `*/target/site/jacoco/index.html` |
| Build warnings | 0 | 31 Oct 2026 | `mvn -B verify` |

These rows are updated at the end of each change set; the final values are in [AUDIT.md](AUDIT.md#5-after).

## 7. Fraud anomaly score (synthetic data only)

**Every number here is on synthetic data.** The generator encodes this project's assumptions about fraud, so these
figures show the score recovers the patterns it was designed for; **performance on real claims is unknown.**
Command: `cd tools/fraud-eval && python -P fraud_eval.py`; report:
[tools/fraud-eval/results/report.md](../tools/fraud-eval/results/report.md). Seed 20261031, 20,000 claims (4% fraud),
fitted without labels on one half and evaluated on the other (10,000 claims, 382 fraudulent). Measured 31 Oct 2026.

| Method | Claims flagged | Precision | Recall |
|---|---|---|---|
| Anomaly score ≥ 5.91 (5% referral budget) | 5.4% | **40.0%** | 56.3% |
| Existing fraud rules (photo reused, high claim frequency, loss < 30 days after policy start) | 16.4% | 13.9% | 59.7% |
| Fraud rules + claimed > 10% above estimate + model disagreement | 34.9% | 8.7% | 79.6% |
| Fraud rules or anomaly score | 18.6% | 15.4% | 75.1% |

Separation of fraudulent from genuine claims (AUC): **0.92**. Fraud with a single pattern: score 26%, rules 46%; with
two or more patterns: score 83%, rules 72%. The score complements the rules; it does not replace them.

## Not measured

- Gemini's repair-cost estimates and severity.
- The property (HOME) prompt.
- Anything on real insurer claims: straight-through rate, cycle time, leakage, adjuster agreement. The AI Governance
  page (`GET /api/v1/ai/metrics`) computes these from whatever claims the running system holds; on the demo data they
  describe the demo, not an insurer.
