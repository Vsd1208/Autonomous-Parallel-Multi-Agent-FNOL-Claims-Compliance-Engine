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
| **Measured on** | 50 labelled car-damage photos (Humans in the Loop, CC0), one photo per call, current prompt (run `v3-subject`); 33 more under the previous prompt. |
| **Results** | Damaged area right (any damaged area / the main one): **82% / 56%**. Car photos recognised as a vehicle: 50/50. With all checks on, wrong answers that could reach straight-through processing fall from 9 to 1 of 50. |
| **Confidence** | Its self-reported confidence is about 0.9 for nearly every answer and does not separate right from wrong (AUC 0.52). It is not used as the AI confidence once the trust score ships (card 3). |
| **Known failure modes** | Hedges ("multiple areas") on unclear photos; occasionally a confident wrong area (photo 1153: front bumper for side-panel damage at a 0.70 probability margin). Dollar estimates and severity are **not validated** (no dataset with repair costs). The property prompt (HOME) has not been evaluated yet (53 labelled FEMA photos are prepared). |
| **Controls** | Probability-margin check (`AMBIGUOUS_DAMAGE_TYPE`), second-opinion check (`MODEL_DISAGREEMENT`), photo-subject check, severity/estimate consistency, reserve capped at the lower of claim and estimate, flood exclusion flag, fallback to a human on any error or timeout. |
| **Human decision** | Every claim: straight-through-eligible claims still wait for an adjuster to confirm by default; everything else goes to the adjuster queue with the reasons. |

## 2. Second-opinion vision model (trained in this project)

| | |
|---|---|
| **Purpose** | An independent view of the same photos. When it gives Gemini's answer less than 15%, the claim goes to an adjuster (`MODEL_DISAGREEMENT`). It never decides on its own. |
| **Type** | CLIP ViT-B/32 image encoder (LAION, MIT licence, safetensors weights) with a logistic-regression head for the vehicle area; zero-shot heads for the photo subject and the home cause of loss. Served by `fnol-vision-ml` on the insurer's own infrastructure. |
| **Training data** | 441 car-damage photos with damage outlines (Humans in the Loop, CC0); labels derived from the outlines. |
| **Results (out-of-fold)** | Vehicle area right (main / any): 65% / 81%. Home cause of loss (zero-shot, 53 FEMA photos): 85%. Photo subject: 441/441 car photos as vehicle, 48/53 home photos as building. |
| **As a check on Gemini** | Of 56 Gemini answers that pass the confidence and margin checks, it flagged **both wrong ones** (2/2) and 4 of 6 with the wrong main area, while sending 4 right answers to an adjuster. |
| **Known failure modes** | Weak on windshields (6 training photos; no properly licensed photos found to add). On laboratory crash-test photos (NHTSA) it named the right area first for 1 of 6. The 0.15 threshold has not been checked on home claims. |
| **Human decision** | It only adds a flag and a note; the adjuster sees both models' answers side by side. |

## 3. Trust score (calibrated AI confidence)

| | |
|---|---|
| **Purpose** | Replace Gemini's uninformative self-reported confidence with the measured probability that the damaged area is right. Used by the straight-through confidence factor (0.85) and shown on the claim. |
| **Type** | Logistic regression on the probability margin, the second opinion's agreement and whether a specific area was named (`tools/vision-eval/trust_score.py`, `TrustScorer`). |
| **Results (leave-one-image-out, 50 photos, current prompt)** | Separation of right from wrong answers: **AUC 0.68** (Gemini's own confidence 0.52). At the 0.85 threshold: **1 wrong answer passes instead of 9**; 31 of 50 answers pass instead of 50. |
| **Status** | **Active since 30 Oct 2026** (50 photos, one prompt, Brier 0.127 against 0.154 for Gemini's confidence). It is re-checked automatically: a prompt, model or thinking-budget change switches it off until re-measured. |
| **Uncertainty** | 95% bootstrap ranges: Brier 0.127 (0.074-0.186), calibration error 0.212 (0.091-0.263). Gemini's own confidence: Brier 0.154 (0.073-0.234). |
| **Known limits** | Vehicle claims only (trained on car photos). Calibration error is still high at this sample size: it ranks answers well, but a score of 0.80 is not yet exactly 80% right. Lighter smoothing lowered the error to 0.14 under nested cross-validation, but within the uncertainty and with a negative weight on the probability margin, so it was not shipped. |
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
| **Training data** | **Synthetic** (seed 20261031, 20,000 claims, 4% fraud); the reference profile is fitted without labels. |
| **Results (held-out synthetic claims)** | See [metrics.md §7](metrics.md#7-fraud-anomaly-score-synthetic-data-only): AUC 0.92; at a 5% referral budget, precision 40% and recall 56%, against 14% and 60% for the existing fraud rules flagging 16% of claims. **On real claims: unknown.** |
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
