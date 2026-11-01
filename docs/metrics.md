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

CLIP ViT-B/32 + DINOv2-base embeddings side by side + logistic regression (regularisation chosen inside each training
fold); 5-fold stratified cross-validation, seed 7, every number out-of-fold. Measured 1 Nov 2026. Commands:
`cd tools/second-opinion && python -P benchmark_encoders.py --encoders clip-b32,dinov2-b,siglip-b16,clip-l14,clip-b32+dinov2-b,siglip-b16+dinov2-b,clip-l14+dinov2-b`
(encoder choice), `python -P build_model.py` (model), `python -P compare_gemini.py --run v2-multiphoto --run v3-subject`.
Reports: [report-encoders.md](../tools/second-opinion/results/report-encoders.md),
[report-second-opinion.md](../tools/second-opinion/results/report-second-opinion.md),
[disagreement.md](../tools/second-opinion/results/disagreement.md).

**Encoder choice (441 car photos, same folds):**

| Encoder | Licence | Main area right | Log loss | Strict vs CLIP B/32 (95% range) |
|---|---|---|---|---|
| CLIP ViT-B/32 (previous model) | MIT | 66.7% | 0.729 | baseline |
| DINOv2-base | Apache-2.0 | 69.8% | 0.618 | +3.2% (−1.4% to +7.7%) |
| SigLIP-base | Apache-2.0 | 63.5% | 0.759 | −3.2% (−7.7% to +1.1%) |
| CLIP ViT-L/14 | MIT | 66.9% | 0.731 | +0.2% (−4.1% to +4.5%) |
| **CLIP B/32 + DINOv2-base (shipped)** | MIT + Apache-2.0 | **71.9%** | **0.608** | **+5.2% (+2.0% to +8.6%)** |
| SigLIP + DINOv2 | Apache-2.0 | 67.8% | 0.640 | +1.1% (−3.2% to +5.4%) |
| CLIP L/14 + DINOv2 | MIT + Apache-2.0 | 68.5% | 0.623 | +1.8% (−2.5% to +6.1%) |

The combination is the only one whose accuracy gain excludes zero. The shipped model before this change used CLIP
alone with a fixed regularisation (64.6% / 80.5%).

**More photos (1 Nov 2026).** Cars: 1,085 candidates from three CC BY 4.0 Roboflow Universe datasets (augmented copies and
near-duplicates of the CC0 set removed); a class-balanced 420 were reviewed one by one on contact sheets: 127 excluded (63
carry a stock-photo or other owner's watermark, so the uploader could not license them; 64 unclear), 36 relabelled,
293 kept. Property: 707 FEMA photos found by caption search; all reviewed, 407 excluded (mostly people, paperwork,
temporary housing or scenery rather than damage), 15 relabelled, 300 kept, plus 7 from the earlier home set: 307. The
review decisions are kept in `tools/vision-eval/roboflow_review.json` and `property_review.json`.

**Speed (inside the `vision-ml` container, 2 CPU threads, 5 real photos, 5 calls each, 1 Nov 2026).** DINOv2 on every
photo of a claim was too slow (2.4 s median, 6.6 s worst, against Branch A's 2.5 s wait), so DINOv2 reads only the
close-up, and a claim without a close-up uses a CLIP-only head (tuned the same way: 66.7% / 81.2% out-of-fold):

| Claim | Head | Median | Worst |
|---|---|---|---|
| With a close-up | CLIP + DINOv2 | 0.89 s | 1.37 s |
| Without a close-up | CLIP only | 0.48 s | 0.49 s |

**Different photo style.** On the close-ups of the six NHTSA crash-test sets in `demo-scenarios/sample-images`
(laboratory photos, unlike the training set) the CC0-trained model named the right area first for 5 of 6 (CLIP alone:
1 of 6); the model trained on all 734 photos gets **4 of 6** (the Corolla side impact is now called rear at 41%; the
Civic rear impact is still called side panel, now with rear at 11%). Six photos are too few to tell these apart.

| Metric | Value | Sample | Caveat |
|---|---|---|---|
| Car damaged area, strict / lenient | **78.3% / 87.2%** | 734 car photos: 441 CC0 + 293 reviewed CC BY 4.0 | Windshields 45/51 (were 1 of 6). On the 441 CC0 photos alone 72.6% / 85.9% (71.9% / 86.2% when trained on them only). 1 Nov 2026 |
| Car damaged area, log loss / Brier | 0.484 / 0.293 | 734 | Lower is better; CC0-only model 0.608 / 0.388 |
| Car damaged area, CLIP-only head (claims without a close-up) | 72.6% / 82.2% | 734 | Used for speed |
| Visible damage extent: rank correlation with the true share of the photo damaged / SMALL-MEDIUM-LARGE right / off by two bands | 0.64 / 55% / 2% | 441 CC0 (only they have damage outlines) | Share of the photo, so it also depends on how close the photo was taken; not a cost. Chance 33% |
| Car photos reported as vehicle | 734/734 | 734 | Zero-shot |
| Home photos reported as building | 48/53 | 53 FEMA photos | Zero-shot |
| Property cause of loss (flood, fire, wind, fallen tree), **trained** head | **92.8%** (balanced 90.0%) | 307 reviewed FEMA photos, out-of-fold | Zero-shot head on the same photos: 79.8% (+13.0 points, 95% range +8.5 to +17.6). Fallen tree 14/18. No burst-pipe photos exist, so WATER_DAMAGE is not a class |
| Property type (residential, commercial, other property), trained head | **89.9%** | 307 (231 / 35 / 41) | Zero-shot: 85.7% (+4.2, range +0.3 to +8.1). Commercial 22/35; commercial has no fire or fallen-tree photos |
| As a check on Gemini (threshold 0.10): answers flagged / lenient-wrong caught / strict-wrong caught / right answers sent to an adjuster | 11/66, **2/2**, 4/6, 9 | 66 Gemini answers that pass the confidence and margin checks (runs `v2-multiphoto` + `v3-subject`) | Two wrong answers is too few to estimate a catch rate. The wrong answers get 0.00 and 0.03; 0.05 would flag 7 with the same catches, but 0.10 keeps a margin |
| Damage types (dent, scratch, broken part, ...) | AUC 0.57-0.68 per type; Brier only 0-11% better than guessing each type's frequency | 441 (`damage_details.py`) | **Not shipped**: whole-photo embeddings do not capture damage type well |

**Trust score after the change.** The trust score's second-opinion input now comes from the new model. Re-fitting it
(`trust_score.py --run v3-subject --write-app`) did not pass the shipping rules (Brier 0.1173 vs 0.1162 for the current
coefficients; with the 734-photo model 0.1174 vs 0.1163), so the app keeps its current trust score; with the new model's outputs it measures AUC 0.73, Brier
0.116, calibration error 0.067 on the 66 answers (the current coefficients were fitted on these same answers, so this
is partly in-sample).

**On-premise vision.** When Gemini cannot answer on usable vehicle photos (quota, outage, no key), the car head's
reading replaces the "UNKNOWN" placeholder (`OnPremiseVision`), with the flag `ON_PREMISE_VISION` that always sends the
claim to an adjuster; the claim is priced from the published repair-cost range (§8), not a placeholder.

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
| Backend tests | **294 passed**, 0 failed (fnol-agents 93, fnol-orchestrator 87, fnol-api 70, fnol-mock-guidewire 19, fnol-guardrails 13, fnol-enrichment 12; fnol-common has none) | 1 Nov 2026 | `mvn -B test` |
| Instruction coverage (JaCoCo) | fnol-guardrails 96%, fnol-orchestrator 93%, fnol-mock-guidewire 93%, fnol-agents 85%, fnol-api 79%, fnol-enrichment 66% | 31 Oct 2026 | `mvn -B clean verify`, then `*/target/site/jacoco/index.html` |
| Build warnings | 0 | 31 Oct 2026 | `mvn -B verify` |

Update these rows whenever the test suite changes.

## 7. Fraud anomaly score (synthetic data only)

**These numbers are on synthetic data.** The generator encodes this project's assumptions about fraud, so these
figures show the score recovers the patterns it was designed for; **performance on real claims is unknown.** One input
is real: genuine claims' second-opinion disagreement is drawn from the model's out-of-fold outputs on the 441 labelled
car photos (now 734; median 0.18 with the current model, 0.31 with the CC0-only CLIP + DINOv2 model, 0.51 with the
CLIP-only one; the first, fully synthetic version assumed 0.27 and nearly flagged an ordinary live claim).
Command: `cd tools/fraud-eval && python -P fraud_eval.py`; report:
[tools/fraud-eval/results/report.md](../tools/fraud-eval/results/report.md). Seed 20261031, 20,000 claims (4% fraud),
fitted without labels on one half and evaluated on the other (10,000 claims, 409 fraudulent). Refitted 1 Nov 2026 on
the new second-opinion model's outputs.

| Method | Claims flagged | Precision | Recall |
|---|---|---|---|
| Anomaly score ≥ 6.74 (5% referral budget) | 5.0% | **44.4%** | 55.1% |
| Existing fraud rules (photo reused, high claim frequency, loss < 30 days after policy start) | 16.7% | 14.3% | 59.0% |
| Fraud rules + claimed > 10% above estimate + model disagreement | 39.2% | 8.9% | 85.9% |
| Fraud rules or anomaly score | 18.8% | 16.1% | 74.8% |

Separation of fraudulent from genuine claims (AUC): **0.92**. Fraud with a single pattern: score 23%, rules 45%; with
two or more patterns: score 79%, rules 70%. The score complements the rules; it does not replace them.

**Percentile calibration.** Each claim's score is also reported as a percentile of the reference claims ("more unusual
than 97% of reference claims"). On held-out claims the share above each percentile matches: 50th → 50.4% above
(expected 50%), 80th → 21.0% (20%), 90th → 10.4% (10%), 95th → 5.0% (5%), 99th → 1.2% (1%). This is calibration
against the reference population, not a probability of fraud; that needs labelled real claims.

**Live check (31 Oct 2026, real Gemini).** Claim `CLM-2026-000034` (F-150 photos, policy 176 days old, reported after
8 days, second opinion at 70% disagreement) scored 5.90 against 5.91 with the first version; with the refit its score is
below 4.5 (regression test `FraudAnomalyScorerTest.typicalRealClaimIsNotFlagged`).

## 8. Repair-cost estimates

No dataset in this project pairs photos with real repair costs, so **no dollar estimate is validated yet**, Gemini's
included. Details and sources: [tools/cost-eval/README.md](../tools/cost-eval/README.md).

**Published ranges (in use).** `repair-cost-ranges.json`: per vehicle area and visible-extent band, a range built only
from quoted AAA (2018, 2023, windshield), Consumer Reports (2017, 2021) and CCC (2025 average $4,818) figures, retrieved
1 Nov 2026. Which figure applies to which band is this project's mapping, an assumption.

**Gemini's estimates against the ranges** (`tools/cost-eval/check_gemini_estimates.py --run v3-subject`, 1 Nov 2026):

| Measure | Value | Sample |
|---|---|---|
| Answers naming an area with a range | 41 of 66 | MULTIPLE / UNKNOWN / ROOF have none |
| Gemini's estimate inside the range | 17/41 | |
| Gemini's median estimate / range midpoint | 2.7x | |
| Flagged at 2x / 3x / **4x (used)** / 5x outside | 12 / 6 / **3** / 3 of 41 | `ESTIMATE_OUTSIDE_PUBLISHED_RANGE` |

Caveat: without true costs this shows disagreement, not who is right.

**Learning from real costs.** Each decision now stores the repair cost next to the AI's estimate (`V18`), with what it
rests on: invoice, body-shop estimate or inspection (evidence, learned from) or the adjuster's judgement (recorded, never
learned from, so the model cannot learn its own guesses). On a claim sent to investigation every figure is held until
the investigator reports; their offline cost (earlier damage excluded) is then the claim's label. One label per claim. A changed reserve proposes a repair cost (reserve +
deductible) for the adjuster to confirm; `tools/cost-eval/fit_costs.py` fits area x band costs
shrunk toward the published table and replaces it only if it is more accurate out of sample on at least 50 claims.
Recorded so far: none with a real cost (the app has no real claims). Synthetic self-test (300 claims, hidden per-area
bias): leave-one-out median error 34% learned vs 50% published, which only shows the method recovers a known pattern.

## Not measured

- Gemini's repair-cost estimates and severity.
- The property (HOME) prompt.
- Anything on real insurer claims: straight-through rate, cycle time, leakage, adjuster agreement. The AI Governance
  page (`GET /api/v1/ai/metrics`) computes these from whatever claims the running system holds; on the demo data they
  describe the demo, not an insurer.
