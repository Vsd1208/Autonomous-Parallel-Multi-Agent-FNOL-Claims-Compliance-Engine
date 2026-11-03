# Model cards

One card per AI component in the FNOL engine: what it is for, what it was measured on, how well it does, where it
fails, and where a person must decide. Figures are from `tools/vision-eval` and `tools/second-opinion` as of
30 Oct 2026; samples are still small. [metrics.md](metrics.md) is the authoritative list of every figure, with its
run, sample size and caveat; if a number here and there differ, metrics.md is right.

The engine is advisory. No AI component pays, denies or closes a claim: a licensed adjuster decides, and every AI
output is written to the claim's tamper-evident audit chain.

---

## 1. Damage assessment (Google Gemini 2.5 Flash)

| | |
|---|---|
| **Purpose** | From the claim photos: the damaged vehicle area (AUTO) or the cause of loss (HOME), severity, a repair-cost estimate, photo quality, and what the photos show. |
| **Type** | General-purpose multimodal model from a third party, used through its API with a fixed prompt (`GeminiVisionProvider`), temperature 0, thinking off. Not trained or fine-tuned for insurance. |
| **Inputs sent** | Up to 6 claim photos per claim, faces and licence plates blurred first, the odometer / VIN photo withheld. No claimant text, name or policy data. |
| **Measured on** | 66 labelled car-damage photos (Humans in the Loop, CC0), one photo per call, current prompt (run `v3-subject`); 33 more under the previous prompt. |
| **Results** | Damaged area right (any damaged area / the main one): **82% / 58%**. Car photos recognised as a vehicle: 66/66. With all checks on, wrong answers that could reach straight-through processing fall from 11 to 1 of 66 in sample, and to about 3 with the thresholds chosen out of sample ([metrics.md §3](metrics.md#3-vision-checks-replayed-gate-ablation-vehicle)). |
| **Confidence** | Its self-reported confidence is about 0.9 for nearly every answer and does not separate right from wrong (AUC 0.57). It is not used as the AI confidence once the trust score ships (card 3). |
| **Known failure modes** | Hedges ("multiple areas") on unclear photos; occasionally a confident wrong area (photo 1153: front bumper for side-panel damage at a 0.70 probability margin). Dollar estimates and severity are **not validated** (no dataset with repair costs); against published repair-cost ranges its median estimate is 2.7x the midpoint, and 3 of 41 are more than 4x outside (flag `ESTIMATE_OUTSIDE_PUBLISHED_RANGE`, see the repair-cost card). The property prompt (HOME) has not been evaluated yet (53 labelled FEMA photos are prepared). |
| **Controls** | Probability-margin check (`AMBIGUOUS_DAMAGE_TYPE`), second-opinion check (`MODEL_DISAGREEMENT`), photo-subject check, severity/estimate consistency, reserve capped at the lower of claim and estimate, flood exclusion flag, fallback to a human on any error or timeout. |
| **Human decision** | Every claim: straight-through-eligible claims still wait for an adjuster to confirm by default; everything else goes to the adjuster queue with the reasons. |

## 2. Second-opinion vision model (trained in this project)

| | |
|---|---|
| **Purpose** | An independent view of the same photos. When it gives Gemini's answer less than 10%, the claim goes to an adjuster (`MODEL_DISAGREEMENT`). When Gemini cannot answer, it reads vehicle photos on its own (`ON_PREMISE_VISION`, always to an adjuster). It never decides on its own. |
| **Type** | Two image encoders side by side, LAION CLIP ViT-B/32 (MIT) and Meta DINOv2-base (Apache-2.0), safetensors weights, chosen by benchmark over four encoders and three combinations; logistic-regression head for the vehicle area and ridge regression for the visible damage extent; trained CLIP heads for the property cause of loss and property type; a zero-shot CLIP head for the photo subject. Served by `fnol-vision-ml` on the insurer's own infrastructure: photos never leave. |
| **Training data** | Cars: 734 photos, 441 with damage outlines (Humans in the Loop, CC0) + 293 from CC BY 4.0 Roboflow Universe datasets, each reviewed by eye (watermarked stock photos excluded). Property: 307 public-domain FEMA photos, each reviewed by eye. |
| **Results (out-of-fold)** | Vehicle area right (main / any): 78% / 87% (CC0-only model: 72% / 86%); windshields 45/51. Visible extent SMALL / MEDIUM / LARGE right 55% (chance 33%). Property cause of loss 93% (zero-shot 80%); property type 90% (zero-shot 86%). Photo subject: 734/734 car photos as vehicle. |
| **As a check on Gemini** | Of 66 Gemini answers that pass the confidence and margin checks, it flagged **both wrong ones** (2/2) and 4 of 6 with the wrong main area, while sending 9 right answers to an adjuster (threshold 0.10). |
| **Known failure modes** | Side panels are the weakest car area. On NHTSA crash-test close-ups it names the right area first for 4 of 6. The CC BY photos' origin is unverified beyond the uploader's licence (some were scraped stock photos; those with a visible watermark were excluded). Commercial property has no fire or fallen-tree photos; burst pipes cannot be learned (no photos). Claims without a close-up use a CLIP-only head (67% / 81%) for speed. Damage types (dent, scratch, ...) are not shipped: AUC 0.57-0.68. The extent measures how much of the photo is damaged, so a close-up looks LARGE; it is not a cost by itself (the repair-cost card turns area + extent into a sourced range). The 0.10 threshold has not been checked on home claims. |
| **Human decision** | It only adds a flag and a note; the adjuster sees both models' answers side by side. |

## 2a. Repair-cost range

| | |
|---|---|
| **Purpose** | A dollar range for a damaged vehicle area at the visible extent the on-premise model saw. Prices claims the on-premise model read (Gemini unavailable) and flags a Gemini estimate more than 4x outside it. It never pays or denies. |
| **Type** | A lookup table (`repair-cost-ranges.json`, `RepairCostEstimator`) of published US figures with sources (AAA, Consumer Reports, CCC); estimate = the range's geometric mean. Replaced cell by cell by costs learned from this insurer's claims once that is more accurate out of sample (`tools/cost-eval/fit_costs.py`). |
| **Evidence** | Every figure is quoted from its source with a link. Which figure applies to which extent band is an assumption. No real repair costs exist in the project yet, so its accuracy is **unknown**; the decision form and inspections now collect them. |
| **Known failure modes** | Ranges are wide (a front bumper with LARGE damage: $4,818-$11,708). Vehicle make, ADAS sensors and local labour rates are not used. The extent depends on photo framing. Gemini's estimates average 2.7x the midpoints: one of the two is off, and only real costs will tell which. |
| **Reserve (ADR-015)** | The reserve no longer rests on the AI's dollar figure: it starts from `initial-reserves.json` by line, coverage and severity, and this range only limits how far the AI's figure can move it. Severity accuracy is not measured. On 41 recorded answers the new method is **not** closer to these ranges (median 3.57x from the midpoint, against 2.69x); see [metrics §8](metrics.md#8-repair-cost-estimates) |
| **Human decision** | The range and its sources are shown to the adjuster, who sets the final reserve. A repair cost they record with evidence (invoice, body-shop estimate, inspection) teaches the next version; their judgement alone is kept but not learned from. On a claim sent to investigation, figures are held and the investigator's offline cost is what it learns. |

## 3. Trust score (calibrated AI confidence)

| | |
|---|---|
| **Purpose** | Replace Gemini's uninformative self-reported confidence with the measured probability that the damaged area is right. Used by the straight-through confidence factor (0.85) and shown on the claim. |
| **Type** | Logistic regression on the probability margin when Gemini names an area, the second opinion's agreement and whether a specific area was named (`tools/vision-eval/trust_score.py`, `TrustScorer`). All weights positive: a clearer or better-supported answer always gets more trust. |
| **Results (out of sample, repeated 5-fold, 66 photos, current prompt)** | Calibration error **0.063** (Gemini's own confidence 0.090). Brier 0.113 against 0.150. AUC 0.79 against 0.57. At the 0.85 threshold, **1 wrong answer passes instead of 11**; 41 of 66 pass. The weights in the app were fitted on these same answers; their in-sample AUC is 0.86. Figures: [metrics.md §2](metrics.md#2-trust-score-calibrated-ai-confidence-vehicle-claims). |
| **Status** | **Active**, version 3 shipped 31 Oct 2026 (66 photos). Re-checked automatically: a prompt, model or thinking-budget change switches it off until re-measured. |
| **Uncertainty** | 95% bootstrap ranges: AUC 0.64–0.90, Brier 0.074–0.158, calibration error 0.030–0.153, which overlaps Gemini's own. 12 wrong answers in 66 is a small sample. |
| **Known limits** | Vehicle claims only (trained on car photos). It mainly distinguishes named-area answers from hedged ones. Earlier figures (calibration error 0.21, then 0.05, AUC 0.68) came from leave-one-out, which has a measurement artefact; corrected 31 Oct and 2 Nov 2026 (see metrics.md). |
| **Updates** | `tools/vision-eval/refit_from_feedback.py` adds adjuster-confirmed damage types from claims answered with the same model and prompt, and replaces the app's trust score only if it passes the same shipping rules (better than Gemini's confidence, no worse than the current trust score, sensible weight directions). |

## 4. Privacy redaction

| | |
|---|---|
| **Purpose** | Blur faces and licence plates before any photo leaves the insurer for the external model. |
| **Type** | YuNet face detector (OpenCV model zoo, MIT licence) and OpenCV's licence-plate cascade (Apache-2.0); thresholds per line of business. Runs in `fnol-vision-ml`. |
| **Results** | 441 car photos: 6 plates blurred, no false faces; blurs touched damage in 2 photos and hid 0.05% of all damage pixels. 66 FEMA home photos: 50 faces blurred in 20 photos. (`fnol-vision-ml/redaction/evaluate_redaction.py`) |
| **Known limits** | The plate detector was trained on one plate style and misses many plates; faces turned away or very small can be missed. It reduces exposure; it is not a guarantee. |
| **Failure behaviour** | Fails closed: if redaction is unavailable, no photo is sent to the external model and the claim goes to an adjuster. |

## 5. Photo reuse detection

| | |
|---|---|
| **Purpose** | Flag a photo already submitted on another claim (`PHOTO_REUSED`), a common fraud pattern. |
| **Type** | Exact SHA-256 match or a 64-bit perceptual hash (dHash) within 5 bits, so resized or recompressed copies match. Not a learned model. |
| **Known limits** | Cropped, mirrored or heavily edited copies can escape; photos from the internet are only caught once they appear on a claim in this system. |

## 6. Fraud anomaly score

| | |
|---|---|
| **Purpose** | Spot claims whose intake details are unusual *together*, which individual fraud rules miss, and suggest a referral to the special investigations unit (SIU). |
| **Type** | Sum of one-sided robust z-scores against a reference profile (median and median absolute deviation per feature), capped at 4 points per feature; a reused photo adds a fixed 3 points. Not a learned classifier. `FraudAnomalyScorer`, parameters `fraud-anomaly.json`, fitted by `tools/fraud-eval/fraud_eval.py`. |
| **Inputs** | Amount claimed / AI estimate; days from policy start to loss; days from loss to report; claims in the last 12 months; the second opinion's disagreement with the AI damage type; reused photo. |
| **Deliberately not used** | Reporting channel, time of day, state or ZIP code, claimant name, age or any demographic, vehicle age or value: they are protected attributes or plausible proxies (income, location, working hours) and would make the score unfair. |
| **Training data** | **Synthetic** (seed 20261031, 20,000 claims, 4% fraud), except genuine claims' second-opinion disagreement, which is drawn from the real model's out-of-fold outputs on 441 labelled car photos; the reference profile is fitted without labels. |
| **Results (held-out synthetic claims)** | See [metrics.md §7](metrics.md#7-fraud-anomaly-score-synthetic-data-only): AUC 0.92; at a 5% referral budget, precision 44.4% and recall 55.1%, against 14.3% and 59.0% for the existing fraud rules flagging 16.7% of claims. The reported percentile is calibrated on held-out claims (e.g. 5.0% above the 95th). **On real claims: unknown.** |
| **Known limits** | The synthetic generator encodes our own assumptions about fraud. Weaker than the rules on fraud with a single pattern; stronger on combinations. A deployment population that differs from the reference profile (e.g. many new policies) raises the flag rate; the threshold must be re-set on real data. |
| **Fairness** | The AI Governance page reports each group's not-flagged rate by state, channel and line of business and marks a group below four-fifths of the best for review (`AiGovernanceService.fairness`). |
| **Human decision** | `FRAUD_ANOMALY` only routes the claim to an adjuster with its top contributing details and an SIU-referral suggestion. It never denies, never changes the reserve, and the adjuster decides whether to refer. Turn it off with `fnol.stp.fraud-anomaly-enabled=false`; override the threshold with `fnol.stp.fraud-anomaly-threshold`. |

---

## Mapping to AI governance expectations

The NAIC Model Bulletin on the Use of Artificial Intelligence Systems by Insurers (adopted by many US states) expects
insurers to govern AI that affects consumers: documented purpose and limits, risk controls, testing and monitoring,
oversight of third-party models, and human accountability. How the engine supports that:

| Expectation | In this project |
|---|---|
| Documented purpose, data and limits | These model cards; evaluation reports in `tools/vision-eval/results` and `tools/second-opinion/results` |
| Testing before use, with measured error | Out-of-sample evaluation on public labelled data; gate ablation (wrong answers reaching approval vs adjuster workload) |
| Controls proportionate to risk | Advisory-only AI, layered checks, reserve cap, fail-closed redaction, fallback to a human on any error |
| Monitoring in use | AI Governance page: routing, flags, adjuster agreement, privacy, weekly drift (`GET /api/v1/ai/metrics`) |
| Third-party model oversight | Gemini's answers are checked by an independently trained model; its confidence is not trusted until calibrated; photos are minimised and redacted before they are sent |
| Human accountability | Licensed adjusters decide; each decision is recorded next to the AI's view (`GET /api/v1/ai/feedback`) and in the hash-chained audit trail |
| Consumer data protection | Personal data removed from text before analysis; faces, plates and the VIN photo kept from the external model |

This mapping is the project's own reading of the bulletin, not legal advice.
