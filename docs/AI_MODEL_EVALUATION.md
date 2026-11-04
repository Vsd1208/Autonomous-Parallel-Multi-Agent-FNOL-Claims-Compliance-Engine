# AI model evaluation

What was measured, how far to trust it, and what cannot be measured yet. [metrics.md](metrics.md) keeps the full history and corrections. **No figure comes from real insurer claims.**

## The numbers that matter most

| Finding | Evidence | What it means |
|---|---|---|
| **On a photo source it has not trained on, the second-opinion model falls to about 64–68%** (from 78%), and its calibration error rises from 0.03 to 0.15–0.22 | [report-leakage.md](../tools/second-opinion/results/report-leakage.md) | An insurer's photos would be a new source. **Expect the lower figure, and recalibrate before relying on its confidence** |
| **`gemini-2.5-flash` is retiring.** It is closed to new users, and the provider's [pricing page](https://aistudio.google.com/docs/pricing) says requests will route to another model from 1 December 2026 | 20 refused calls in `results/v3-subject.jsonl`; pricing page read 4 Nov 2026 | Every Gemini figure below is for a model about to go. See [the retirement](#the-model-retirement) |
| Gemini names the damaged area 57.6% (strict) or 81.8% (lenient) of the time | [report-claim-level-v3-subject.md](../tools/vision-eval/results/report-claim-level-v3-subject.md) | 66 single photos. 95% ranges: 45.6–68.8% and 70.9–89.3% |
| Severity and repair-cost accuracy: **not measured** | No labelled data exists | The reserve stays table-based and experimental. [A visual check of severity](#severity-a-visual-check) found errors both ways |

## Claim-level evaluation

`tools/vision-eval/claim_eval.py` reads a recorded run and makes no API calls. **Each photo stands in for a claim,** because no labelled multi-photo claims exist. A manifest with `claimId` groups real claims when there are some.

Gemini, 66 answers, strict, against the main damaged area:

| Area | Photos | Precision | Recall | F1 | Missed |
|---|---|---|---|---|---|
| Front bumper | 20 | 92.9% | 65.0% | 76.5% | 35.0% |
| Rear bumper | 20 | 100.0% | 65.0% | 78.8% | 35.0% |
| Side panel | 20 | 80.0% | 40.0% | 53.3% | 60.0% |
| Windshield | 6 | 100.0% | 66.7% | 80.0% | 33.3% |

Most "misses" are "multiple areas" answers: 24 of 66, none labelled that way. So strict accuracy understates it and lenient flatters it; read both.

Calibration of Gemini's own confidence, against lenient correctness: Brier 0.150, calibration error 0.081 (5 equal-width bins). 65 of 66 answers sit in one bin, 0.8–1.0, right 83.1% of the time: **Gemini's confidence barely varies, which is why it is not used as a probability.** [metrics.md](metrics.md) gives 0.090 from 5 bins filled equally, and both are small-sample estimates. Reliability diagram: [reliability-v3-subject.svg](../tools/vision-eval/results/reliability-v3-subject.svg).

By image quality: Gemini called all 66 photos clear, so it cannot split the sample. By sharpness measured from the pixels, the thirds were right 81.8%, 86.4% and 77.3% of the time, about 22 answers each: no evidence of a difference either way.

| Latency and cost | Value |
|---|---|
| p50 / p90 / p95 / p99 | 4.4 / 20.9 / 34.6 / 40.6 s, free tier, including the tool's retries after HTTP 503 (not a service level) |
| Slower than the app's 8 s timeout | 18 of 66 (27.3%): those claims go to a person |
| Failed after retries | 0 of 66 |
| Cost at the paid rate | $0.00068 for one photo; about $0.00099 for a five-photo claim, an estimate that assumes each extra photo adds the same image tokens |

## Leakage and generalisation

The headline second-opinion figures use folds over photos, so near-duplicates could sit on both sides. `tools/second-opinion/leakage_check.py` tests that using cached embeddings. These datasets have no vehicle, accident or session IDs, so similarity is the only grouping available: **a control, not proof that no leak remains.**

| Split | Photos | Main area right (95% range) | Log loss | Calibration error |
|---|---|---|---|---|
| Folds over photos (headline) | 734 | 78.3% (75.2–81.4) | 0.484 | 0.032 |
| Folds kept apart by near-duplicate group | 734 | 78.6% (75.5–81.5) | 0.492 | 0.032 |
| Final holdout of whole groups, scored once | 147 | 81.0% (73.6–87.7) | 0.480 | 0.060 |
| Held out: Humans in the Loop (CC0) | 441 | 64.2% (59.7–68.8) | 1.010 | 0.218 |
| Held out: VehicleDamageDetection | 260 | 67.7% (61.5–73.6) | 0.853 | 0.149 |
| Held out: car_dam | 22 | 72.7% (54.5–90.9) | 0.576 | 0.143 |
| Held out: damaged-cars-proj | 11 | 63.6% (36.4–90.9) | 0.901 | 0.309 |

Near-duplicates are not inflating the score: few photos are near-duplicates (30 of 734 at cosine 0.90). **The drop comes from new sources.** Grouped folds and the holdout still mix all sources, so they do not show this; holding out a source does. The encoder and model family were chosen earlier on the CC0 photos, so even the holdout is not free of every earlier choice.

## Trust score

Out-of-fold, with bootstrap ranges and baselines: [metrics.md §2](metrics.md). Its AUC is 0.79 (0.64–0.90) against 0.57 for Gemini's own confidence. It rests on 66 answers with 12 wrong. There is no separate holdout, since one would leave too few to fit on. It is tied to the model, prompt and thinking budget it was fitted on, and turns itself off for a different one.

## Abstention

The AI can now say it has no answer worth relying on: `FNOLResponse.abstention`, set by `AbstentionPolicy`. **An abstained claim goes to a person. It is never a rejection and says nothing against the claim.** The workspace shows the reasons first.

| Reason | When |
|---|---|
| `INSUFFICIENT_QUALITY` | The model called the photos blurry or unusable |
| `MODEL_DISAGREEMENT` | The second-opinion model disagrees |
| `LOW_CONFIDENCE` | Below the threshold, or the top two damage types are too close |
| `UNSUPPORTED_DAMAGE` | The model saw none, or the damage does not fit this line of business |
| `OUT_OF_DISTRIBUTION` | The photos do not show what this policy insures. **There is no statistical detector:** an unusual photo of the expected subject is not caught |
| `INFERENCE_FAILURE` | The model did not answer, or the on-premise model stood in |
| `MISSING_MODEL_VERSION` | With Gemini on, the answer names no model and prompt |
| `MODEL_VERSION_CHANGED` | A different model answered from the one asked for |

The fallback's confidence of 0.60 is a placeholder, chosen to fail the threshold. The workspace no longer shows it as a number.

## The model retirement

The recorded run stopped with 20 refusals: "no longer available to new users". The default remains `gemini-2.5-flash`, deliberately, because the calibration and trust score were fitted on it.

- **What the code now does.** Gemini's answer names the model that served it. If that differs from the one asked for, the calibration and trust score are not applied, the answer is marked, and the claim abstains (`MODEL_VERSION_CHANGED`). So a silent re-route should send claims to people rather than apply stale calibration. **This rests on the response's `modelVersion` field naming the real model, which could not be tested against an actual re-route.**
- **What a new deployment gets.** With a new key the calls may be refused now: every claim falls back to a person and the on-premise model. Safe, but the AI adds little.
- **What it needs.** A run on a current model, then a refit. Two trials of `gemini-3.8-flash` on 4 Nov 2026 answered 0 of 5 calls: HTTP 503s, a disconnect and timeouts. So **no successor has been measured**, and no figure here carries over to one. It also costs more per token: $0.75 in and $3.75 out through 2026, doubling from 1 January 2027, against $0.30 and $2.50.

## Fraud anomaly score

**Synthetic data only; performance on real claims is unknown.** The generator encodes this project's assumptions. Report: [tools/fraud-eval/results/report.md](../tools/fraud-eval/results/report.md). It routes to a person and suggests a referral; it never denies.

| Claims referred | Precision | Recall |
|---|---|---|
| 1% | 93.0% | 23.0% |
| 2% | 71.5% | 35.3% |
| 5% | 44.6% | 55.1% |
| 10% | 28.7% | 70.9% |
| 20% | 17.0% | 83.7% |
| 30% | 12.3% | 91.1% |

Average precision 0.543 against a chance level of 0.041. The false-positive analysis shows which features accompany a false alarm, chiefly time since the policy began and second-opinion disagreement. Reasons are shown on each flag.

## Severity: a visual check

No severity labels exist, so routing cannot be scored. One person looked at thumbnails of all 66 recorded answers, on 4 Nov 2026. **This is one non-adjuster's opinion of photos, not a measurement,** and hidden structural damage cannot be seen.

- **24 were routed HIGH.** 17 looked clearly serious. **About 7 looked over-called** (`Car damages` 764, 250, 316, 307, 1065, 1147, 252). Five of those 7 were raised only by the second opinion's visible extent, which measures how much of the photo the damage fills, so close-ups and windshields score high whatever the damage.
- **42 were not HIGH. About 5 looked under-called,** with crumpled fronts or crushed sides.

So severity is a rough reading in both directions. The effect is limited: HIGH only forces a person to review, an extent-only HIGH does not change the reserve, which is looked up from Gemini's label, and by default a person confirms even a claim that passes every check.

**The rule was left unchanged on purpose.** Retuning it on 66 photos would fit one reader's opinion. Having an adjuster grade these 66 photos is the way to real data, and would take well under an hour.

## Reserve

Deterministic: a sourced table by line, coverage and severity, with the AI's figure moving it only within a bound. **Experimental. No repair invoices exist,** so accuracy is unmeasured and none is claimed. Make and model, parts and labour, geography and complexity are not modelled, because no evidence supports doing so. The published repair-cost ranges are shown beside it, with sources: [metrics.md §8](metrics.md).

## Photo redaction

`evaluate_robustness.py` compares finds on harder versions of the same 66 home photos. **There is no ground truth, so this can show loss, not true recall.**

| Version | Before (upright only) | After (four orientations) |
|---|---|---|
| Original | 50 | 63 |
| Rotated 90° | 2 (4%) | 63 |
| Rotated 180° | 8 (16%) | 63 |
| Low light (brightness × 0.25) | 41 | 49 (78% of 63) |
| Half size | 34 | 45 (71% of 63) |

The sideways result was a real privacy gap, now closed. What it cost:

- **More boxes.** On 300 property photos, 318 boxes became 443, and 77 photos gained one. In one look at a random 48 of the rotated-view boxes, about 40 showed a person's face, ear or head, and about 8 did not (a tyre, aerial shots, a hand). That is one reading of a contact sheet, not a labelled measurement, and some boxes overlap faces already found. Vehicle photos: 1 box became 3 of 300.
- **Speed.** About four times slower: 166 to 670 ms a photo on home photos, 76 to 309 ms on vehicle photos, on one laptop.

Low-light and small faces are still lost. Plate recall is unknown.

## Blocked on data or people

| Measure | Needs |
|---|---|
| True claim-level accuracy | Labelled multi-photo claims |
| Severity accuracy | Adjuster-graded photos |
| Repair-cost accuracy | Photos linked to invoices |
| Adjuster override rate, comparison with people | Real adjusters (`feedback.csv` holds one demo decision) |
| Gemini on property claims | A recorded run on the FEMA photos, kept separate |
| Fraud on real claims | Labelled real claims |

## Reproduce

```bash
cd tools/vision-eval && python -P claim_eval.py --run v3-subject
cd tools/second-opinion && python -P leakage_check.py
cd tools/fraud-eval && python -P fraud_eval.py
python -P fnol-vision-ml/redaction/evaluate_robustness.py
python -m pytest tools/test_evaluation.py fnol-vision-ml/redaction/test_redact.py
```

The photos and embeddings must be prepared first ([tools/vision-eval/README.md](../tools/vision-eval/README.md)). `pytest` needs neither.
