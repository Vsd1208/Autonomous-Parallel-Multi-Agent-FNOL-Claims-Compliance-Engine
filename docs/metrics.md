# Measured results

The single source of truth for every measured number in this repository. Other documents link here instead of
repeating figures. Each row gives the value, when it was measured, the run and command that produced it, the sample
size and the caveat. Where two runs legitimately differ (an older prompt, a different sample), the row says which run.

**Read these as prototype measurements on small public datasets, not production performance.** No figure here comes
from real insurer claims.

## 1. Gemini damage assessment (vehicle)

Run `v3-subject`: `gemini-2.5-flash`, thinking budget 0, the app's current vehicle prompt (SHA-256 `501b3308884a64f8`),
one photo per call, on labelled car-damage photos (Humans in the Loop, CC0). 50 photos on 30 Oct 2026, 16 more on
31 Oct 2026 (the free API tier's daily limit stopped the run at 66 of the 120 prepared).
Command: `cd tools/vision-eval && python -P evaluate.py --run v3-subject --limit 120` then
`python -P calibrate.py --run v3-subject`. Report:
[tools/vision-eval/results/report-v3-subject.md](../tools/vision-eval/results/report-v3-subject.md).

| Metric | Value | Sample | Caveat |
|---|---|---|---|
| Damaged area named correctly, lenient (one of the damaged areas) | **81.8%** (54/66) | 66 photos | One photo per call; a real claim sends the standard set of five |
| Damaged area named correctly, strict (the most damaged area) | 57.6% | 66 photos | "Multiple areas" answers count as wrong here |
| Car photos reported as a vehicle (`photoSubject`) | 66/66 | 66 photos | Every photo is a car, so this measures false alarms only |
| Gemini's self-reported confidence: separation of right from wrong (AUC) | 0.57 | 66 photos | 0.5 = no separation |
| Probability margin: separation of right from wrong (AUC) | 0.74 | 66 photos | |
| Latency p50 / p95 (free tier, includes retries after HTTP 503) | 4.5 s / 34.4 s | 66 calls | 27% of calls were slower than the app's 8 s read timeout; those claims fall back to a human |
| Dollar estimate and severity accuracy | **not measured** | — | No public dataset with repair costs was found |
| Property (HOME) prompt accuracy | **not measured** | — | 53 labelled FEMA photos are prepared (`prepare_home.py`); the run has not been done |

Previous prompt, kept for comparison only: run `v2-multiphoto` (prompt `61a89427e0e132d9`), 33 photos, lenient / strict
91% / 67% ([report-v2-multiphoto.md](../tools/vision-eval/results/report-v2-multiphoto.md)). It is a different and
smaller sample, so it is not evidence that the newer prompt is worse.

## 2. Trust score (calibrated AI confidence, vehicle claims)

Logistic regression on the probability margin **when Gemini names an area** (`namedMargin`), the second-opinion model's
agreement and whether a specific area was named (`tools/vision-eval/trust_score.py`, app class `TrustScorer`).
Leave-one-image-out on the 66 `v3-subject` answers, smoothing chosen inside each training set; 95% ranges from 2000
bootstrap resamples. Measured and shipped 31 Oct 2026. Command: `python -P trust_score.py --run v3-subject`. Report:
[tools/vision-eval/results/report-trust-score.md](../tools/vision-eval/results/report-trust-score.md).

| Metric | Gemini's own confidence | Previous trust score (re-measured on 66) | **Trust score in the app** |
|---|---|---|---|
| Separation of right from wrong (AUC) | 0.57 | 0.72 | 0.68 |
| Brier score, lower is better (95% range) | 0.150 (0.082–0.228) | 0.121 (0.078–0.170) | **0.118** (0.077–0.164) |
| Calibration error, lower is better (95% range) | 0.090 (0.044–0.188) | 0.055 (0.045–0.171) | **0.051** (0.025–0.132) |
| Answers passing the 0.85 confidence factor / wrong among them | 65 / 11 | — | **41 / 1** |

What the calibration error means: on these answers a trust score is within about 5 percentage points of how often
Gemini is actually right; the trust score predicts 0.97 on average for answers that name an area (97.6% are right) and 0.58 for
"multiple areas" answers (56% are right). 66 answers with 12 wrong is still a small sample: the ranges are wide.

**Correction (31 Oct 2026).** Earlier versions of this page reported a calibration error of 0.212 for the trust score.
That figure was mostly a measurement artefact: under leave-one-out, holding out a right answer slightly lowers its own
prediction, and the metric sorted answers by those scores before binning, so answers with identical features were
split into bins by their labels. The metric now orders the bins by the model's all-data score (a function of the
features only) and measures the out-of-sample scores (`ece` in `trust_score.py`). All calibration errors on this page
use the corrected metric.

**Why the margin now counts only for a named area.** For a "multiple areas" or UNKNOWN answer the margin says nothing
about whether an area is right: an UNKNOWN answer puts 1.0 on "unknown" (margin 1.0) and is always wrong. Using it
there made the fitted margin weight negative (which the shipping rules refuse) and gave UNKNOWN answers more trust than
genuine "multiple areas" answers. All three weights are now positive (named-area margin +1.24, second-opinion agreement
+1.46, named area +1.36).

Status in the app: **active** (`fnol-agents/src/main/resources/trust-score.json` version 3: 66 images, matching prompt,
model and thinking budget). Home claims keep Gemini's confidence. The older margin-based calibration map
(`vision-calibration.json`, 50 photos) still applies first; for vehicle claims the trust score then replaces it.

## 3. Vision checks replayed (gate ablation, vehicle)

The 66 `v3-subject` answers replayed through the app's checks, adding one at a time (no API calls). Commands:
`python -P gate_ablation.py --run v2-multiphoto --run v3-subject`
([results/gate-ablation.md](../tools/vision-eval/results/gate-ablation.md)) and, for the second-opinion row,
`python -P compare_gemini.py --run v2-multiphoto --run v3-subject` in `tools/second-opinion`. Measured 31 Oct 2026.

| Checks on | Could go straight through | Wrong answers let through (lenient / strict) | Right answers sent to an adjuster |
|---|---|---|---|
| Gemini's confidence only (≥ 0.85) | 65/66 | 11 / 27 | 0 |
| + probability-margin check (`AMBIGUOUS_DAMAGE_TYPE`) | 42/66 | 2 / 4 | 14 |
| + severity and photo-subject checks | 42/66 | 2 / 4 | 14 |
| + second-opinion model (`MODEL_DISAGREEMENT`) | 39/66 | **1** / — | 16 |

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
| As a check on Gemini (threshold 0.15): answers flagged / lenient-wrong caught / strict-wrong caught / right answers sent to an adjuster | 6/66, **2/2**, 4/6, 4 | 66 Gemini answers that pass the confidence and margin checks (runs `v2-multiphoto` + `v3-subject`, 31 Oct 2026) | Two wrong answers is too few to estimate a catch rate |

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
| Backend tests | **275 passed**, 0 failed (fnol-agents 85, fnol-orchestrator 82, fnol-api 64, fnol-mock-guidewire 19, fnol-guardrails 13, fnol-enrichment 12; fnol-common has none) | 31 Oct 2026 | `mvn -B clean verify` |
| Instruction coverage (JaCoCo) | fnol-guardrails 96%, fnol-orchestrator 93%, fnol-mock-guidewire 93%, fnol-agents 85%, fnol-api 79%, fnol-enrichment 66% | 31 Oct 2026 | `mvn -B clean verify`, then `*/target/site/jacoco/index.html` |
| Build warnings | 0 | 31 Oct 2026 | `mvn -B verify` |

Update these rows whenever the test suite changes.

## 7. Fraud anomaly score (synthetic data only)

**These numbers are on synthetic data.** The generator encodes this project's assumptions about fraud, so these
figures show the score recovers the patterns it was designed for; **performance on real claims is unknown.** One input
is real: genuine claims' second-opinion disagreement is drawn from the model's out-of-fold outputs on the 441 labelled
car photos (median 0.51; the first, fully synthetic version assumed 0.27 and nearly flagged an ordinary live claim).
Command: `cd tools/fraud-eval && python -P fraud_eval.py`; report:
[tools/fraud-eval/results/report.md](../tools/fraud-eval/results/report.md). Seed 20261031, 20,000 claims (4% fraud),
fitted without labels on one half and evaluated on the other (10,000 claims, 409 fraudulent). Refitted 31 Oct 2026.

| Method | Claims flagged | Precision | Recall |
|---|---|---|---|
| Anomaly score ≥ 5.86 (5% referral budget) | 5.2% | **44.7%** | 57.2% |
| Existing fraud rules (photo reused, high claim frequency, loss < 30 days after policy start) | 16.4% | 14.5% | 58.4% |
| Fraud rules + claimed > 10% above estimate + model disagreement | 36.5% | 9.6% | 85.8% |
| Fraud rules or anomaly score | 18.5% | 16.8% | 75.8% |

Separation of fraudulent from genuine claims (AUC): **0.93**. Fraud with a single pattern: score 27%, rules 48%; with
two or more patterns: score 79%, rules 66%. The score complements the rules; it does not replace them.

**Percentile calibration.** Each claim's score is also reported as a percentile of the reference claims ("more unusual
than 97% of reference claims"). On held-out claims the share above each percentile matches: 50th → 50.6% above
(expected 50%), 80th → 20.6% (20%), 90th → 10.7% (10%), 95th → 5.2% (5%), 99th → 0.9% (1%). This is calibration
against the reference population, not a probability of fraud; that needs labelled real claims.

**Live check (31 Oct 2026, real Gemini).** Claim `CLM-2026-000034` (F-150 photos, policy 176 days old, reported after
8 days, second opinion at 70% disagreement) scored 5.90 against 5.91 with the first version; with the refit its score is
below 4.5 (regression test `FraudAnomalyScorerTest.typicalRealClaimIsNotFlagged`).

## Not measured

- Gemini's repair-cost estimates and severity.
- The property (HOME) prompt.
- Anything on real insurer claims: straight-through rate, cycle time, leakage, adjuster agreement. The AI Governance
  page (`GET /api/v1/ai/metrics`) computes these from whatever claims the running system holds; on the demo data they
  describe the demo, not an insurer.
