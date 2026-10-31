# Vision evaluation and confidence calibration

Measures how well Gemini identifies the damaged area of a car, and trains a map from Gemini's
self-reported confidence to how often it is actually right. The app uses that map
(`fnol-agents/src/main/resources/vision-calibration.json`) so the WHY panel's "AI Confidence"
factor reflects measured accuracy instead of the model's own guess.

Gemini itself is not fine-tuned: Google does not offer tuning for Gemini 2.5 Flash vision. What is
trained here is the calibration layer on top of it.

## Dataset

[Car Parts and Car Damages](https://humansintheloop.org/resources/datasets/car-parts-and-car-damages-dataset/)
by Humans in the Loop, **CC0 1.0 (public domain)**, downloaded from the Hugging Face copy
`DrBimmer/car-parts-and-damage-dataset`. 441 photos carry both car-part and damage polygons; overlaying
them gives the damaged zone (front bumper, rear bumper, windshield, side panel, roof), which is what
the vision prompt answers. On that copy the two folder names are swapped; `prepare.py` handles it.

Rejected: CarDD (its license requires the authors' prior consent), `tugberkkalay/autodamageiq`
(includes CarDD), `Reverb/CarDamage` (its labels are vehicle-registration fields, not damage),
`DrBimmer/comprehensive-car-damage` (origin and license unverifiable).

## Steps

Requires Python 3.11+ with `pillow` and `huggingface_hub` (`pip install pillow huggingface_hub`).
Run from this folder. `-P` stops Python loading modules from the downloaded data directory.

```bash
# 1. Labels for all 441 paired photos + a stratified sample of 120, images downloaded (~10 min)
python -P prepare.py --sample 120 --seed 7

# 2. Ask Gemini, exactly as the app does (prompt read from GeminiVisionProvider.java).
#    Free tier = 20 calls/day for gemini-2.5-flash, shared with the app. Run once a day; it resumes,
#    retries failures and stops cleanly when the daily quota is used up.
python -P evaluate.py --run v3-subject --thinking-budget 0 --limit 60 --max-calls 15

# 3. Accuracy report (results/report-thinking0.md) and, once >= 50 photos are answered,
#    the calibration map for the app
python -P calibrate.py --run v3-subject --write-app-calibration

# 4. Rebuild the API so it picks up the map
cd ../.. && docker compose up -d --build fnol-api
```

The map is applied only when the app's vehicle prompt (`promptSha256`), `gemini.vision.model` and
`gemini.vision.thinking-budget` match the ones it was measured with (the API logs a warning otherwise). Turn it off with
`GEMINI_VISION_CALIBRATION_ENABLED=false`. Re-run the evaluation after changing the model, the
prompt or the thinking budget.

## Runs

| Run | Prompt | Photos answered | Lenient / strict accuracy | Note |
|---|---|---|---|---|
| `thinking0` | single-photo prompt (before the standard photo set) | 25 | see `results/report-thinking0.md` | Kept for comparison; not used for the app map |
| `v2-multiphoto` | multi-photo prompt, photo labelled `angle: OTHER` (prompt SHA-256 `61a89427e0e132d9`) | 33 | 91% / 67% (`results/report-v2-multiphoto.md`) | Stopped: the prompt now also asks for `photoSubject`. Kept for comparison |
| `v3-subject` | current prompt: as v2, plus `photoSubject` (VEHICLE, BUILDING, OTHER_PROPERTY, NONE) (prompt SHA-256 `501b3308884a64f8`) | **50 (complete, 2026-10-30)** | 82% / 56% (`results/report-v3-subject.md`) | Car photos reported as VEHICLE: 50/50; self-reported confidence AUC 0.52, margin AUC 0.79. Trust score and margin map shipped to the app |

`v3-subject` restarts the count because a changed prompt can change the answers. Compare it with
`v2-multiphoto` on the photos both runs answered to see whether the extra question moved the damage-zone
accuracy. Every photo in the dataset is a car, so any `photoSubject` other than VEHICLE would be a false
`PHOTO_SUBJECT_MISMATCH` on a genuine auto claim.

First 15 photos (the same photos in both runs): asking for `photoSubject` made Gemini hedge more. `MULTIPLE`
answers rose from 2 to 5, lenient accuracy fell from 14/15 to 12/15 and strict from 11/15 to 9/15. All three
wrong answers were hedged (margin 0.20 or less), so `AMBIGUOUS_DAMAGE_TYPE` sends them to an adjuster: none
would be auto-approved, but 5 claims instead of 2 need a person. 15 photos is too few to call this a real
effect; the 50-photo run decides whether the question stays as it is. Every photo was reported as VEHICLE
(no false subject alarms).

On the 18 photos both runs answered, the current prompt was right as often or more often: lenient 17/18
(old 16/18), strict 13/18 (old 12/18). After 33 photos, Gemini's self-reported confidence is still
uninformative (only 0.8 or 0.9; AUC 0.48); the probability margin separates right from wrong answers
(AUC 0.82).

Independently of any confidence score, the app guards against hedged answers directly: the reconciliation gate raises
`AMBIGUOUS_DAMAGE_TYPE` (claim goes to an adjuster) when the probability margin is below
`fnol.stp.min-probability-margin` (default 0.30). In `v2-multiphoto`, all 7 hedged `MULTIPLE` answers had
a margin of 0.20 or less, and 2 of them were wrong; the gate sends all 7 to an adjuster. One wrong answer
was confident (photo 1153: `FRONT_BUMPER` at margin 0.70 for side-panel damage), so the margin gate
does not catch everything; the claimed-amount and severity checks remain the backstop.

At 33 photos the margin map did not yet beat Gemini's raw confidence out of sample (Brier 0.089 vs 0.084). At 50
photos it did (0.137 vs 0.154), so `calibrate.py` wrote it to the app on 30 Oct 2026; for vehicle claims the trust
score below then replaces it. Current figures: [docs/metrics.md](../../docs/metrics.md).

The evaluation scores one photo at a time, while a claim now sends the standard set of five. The map
therefore calibrates single-photo answers; check it against real multi-photo claims before relying on it.

## Gate ablation (no API calls)

`gate_ablation.py` replays the recorded answers through the app's vision-related checks, adding one at a time,
and counts the wrong answers that would still reach auto-approval and the right answers sent to an adjuster:

```bash
python -P gate_ablation.py --run v2-multiphoto --run v3-subject      # writes results/gate-ablation.md
```

| Run | Checks | Auto-approvable | Wrong answers let through (lenient / strict) | Right answers sent to adjuster |
|---|---|---|---|---|
| `v2-multiphoto` (33) | confidence factor only | 32 | 3 / 10 | 1 |
| | + `AMBIGUOUS_DAMAGE_TYPE` | 25 | 1 / 3 | 6 |
| `v3-subject` (50) | confidence factor only | 50 | 9 / 22 | 0 |
| | + `AMBIGUOUS_DAMAGE_TYPE` (+ severity, + subject) | 32 | 2 / 4 | 11 |

With the second-opinion check as well (`tools/second-opinion/compare_gemini.py`, both runs): of the 56 answers that
pass the confidence and margin checks, 2 are wrong (lenient), and the second opinion (below 0.15 for Gemini's area)
flags both, at the cost of 4 right answers sent to an adjuster. Replayed on `v3-subject` alone, one wrong answer still
gets through with every check on (photo 1336, a confident "multiple areas" answer the second opinion cannot check);
see "Final result at 50 photos" below and [docs/metrics.md](../../docs/metrics.md).

The margin check removes most wrong answers that the confidence factor lets through, at the cost of sending
some right answers to an adjuster. Photo 1153 (a confident wrong zone) is stopped only by the second opinion and photo 1336 by no vision check;
they are why the claimed-amount check, coverage and the severity gate stay in front of any payment. The
severity and subject checks stop no further wrong answer on these car photos: they target other failures
(inconsistent estimates, the wrong kind of property).

## Trust score (calibrated AI confidence)

Gemini's self-reported confidence is about 0.9 for nearly every answer, so it cannot tell the app which answers to
trust. `trust_score.py` learns the probability that Gemini's damaged area is right from the signals that do separate
right from wrong answers: the probability margin, the second-opinion model's agreement (`tools/second-opinion`,
out-of-fold) and whether Gemini named a specific area. Logistic regression, scored leave-one-image-out:

```bash
python -P trust_score.py --run v3-subject                    # study, results/report-trust-score.md
python -P trust_score.py --run v3-subject --write-app        # app file, only when the shipping rules pass
python -P refit_from_feedback.py --run v3-subject [--write-app]   # the same, with adjusters' decisions added
```

**Honest measurement.** Every score is leave-one-image-out. The amount of smoothing (regularisation C) is chosen by
cross-validation *inside* each training set (nested), so picking it cannot flatter the result, and every Brier score
and calibration error comes with a 95% range from 2000 bootstrap resamples of the images.

Shipped 30 Oct 2026, 50 photos, current prompt:

| Confidence used | Separation (AUC) | Brier (95% range) | Calibration error (95% range) | Passing 0.85: answers / wrong |
|---|---|---|---|---|
| Gemini's self-reported confidence | 0.52 | 0.154 (0.073-0.234) | 0.122 (0.037-0.201) | 50 / 9 |
| **Trust score in the app** (3 inputs, C=1) | 0.68 | 0.127 (0.074-0.186) | 0.212 (0.091-0.263) | 31 / **1** |
| Smoothing chosen by cross-validation | 0.71 | 0.127 (0.074-0.189) | 0.143 (0.057-0.228) | 30 / 1 |

**What was tried to lower the calibration error (30 Oct 2026), and why the app did not change.** Stronger smoothing
made it worse (C=0.1: 0.24), not better. Fewer inputs did not help either: the probability margin alone lets 2 wrong
answers through. Lighter smoothing, chosen honestly by nested cross-validation, lowers the error to 0.14 at the same
Brier, but the ranges overlap almost completely: with 9 wrong answers in 50 the difference is not reliable. And the
lightly smoothed model gives the probability margin a *negative* weight (a clearer Gemini answer would get a lower
trust score), which fits this sample but could not be explained to an adjuster or a regulator. The shipping rules
refuse it, so the app keeps the trust score above. The remaining error comes mostly from one group: Gemini's
"multiple areas" answers get a low score but are all right.

Shipping rules (`trust_score.py --write-app`): a single run with the app's vehicle prompt, model and thinking budget;
at least 50 evaluation photos; better Brier than Gemini's raw confidence; no worse Brier and no more wrong answers at
0.85 than the app's current trust score on the same data; and no negative weight on the margin or the second-opinion
agreement. The app's `TrustScorer` checks the prompt, model and thinking budget again at startup. Home claims keep
Gemini's confidence: the model is trained on car photos.

**Learning from adjusters.** Each adjuster decision is stored next to the AI's view (`ai_feedback`), including the
model, prompt hash and thinking budget that produced the answer (`vision_source`). `refit_from_feedback.py` exports the
decisions where the adjuster confirmed the damage type and adds them as labelled examples (right when the adjuster
confirmed the AI's damage type), but only those from the same model and prompt; mock, fallback, home and old-prompt
answers are skipped and counted. The refit goes through the same measurement and shipping rules. Note that adjusters
confirm one main area, so these labels are stricter than the evaluation's lenient target. As of 30 Oct 2026 there is no
usable decision yet (one recorded, a home claim).

### Final result at 50 photos (2026-10-30)

All checks replayed on the 50 answers of `v3-subject` (current prompt):

| Checks on | Could go straight through | Wrong answers let through | Right answers sent to an adjuster |
|---|---|---|---|
| Gemini's confidence only (>= 0.85) | 50/50 | 9 | 0 |
| + answer-gap check | 32/50 | 2 | 11 |
| + severity and photo subject | 32/50 | 2 | 11 |
| + second-opinion model | 29/50 | **1** | 13 |

The one left (photo 1336) is a confident "multiple areas" answer; the second-opinion model has no "multiple" class, so it
cannot check that kind of answer. Trust score: see the section above.

## Home (property) evaluation

The property prompt is evaluated on public-domain FEMA photos, labelled by cause of loss (FLOOD, FIRE_SMOKE,
WIND_STORM, FALLEN_TREE) from the photographer's caption and hand-reviewed (photos without visible damage removed,
tree-on-house photos captioned as tornado damage relabelled):

```bash
python -P prepare_home.py --per-cause 15                               # 53 photos after review, no API calls
python -P evaluate.py --line home --run home-v1 --limit 60 --max-calls 15
python -P home_report.py --run home-v1                                 # writes results/report-home-v1.md
```

The report shows cause-of-loss accuracy, how many flood photos raise `POSSIBLE_FLOOD_EXCLUSION`, false flood
warnings, and how many photos are reported as BUILDING. Not run yet (prepared on 2026-10-28; it shares the daily
quota with the car run). Burst-pipe water damage has no FEMA captions to label from, so it is not covered.

## Calibration method

- **Signal: probability margin**, the gap between Gemini's two highest damage-type probabilities.
  Gemini's self-reported confidence was useless as a signal: it only ever said 0.90 or 0.95, for right
  and wrong answers alike (AUC 0.52 on the first 25 photos, run `thinking0`). The margin separates specific answers
  (all right) from hedged "MULTIPLE" answers (often wrong): AUC 0.86 on that run, 0.79 on the 50 `v3-subject` photos.
- **Isotonic regression** from margin to the share of correct answers, each block shrunk toward the
  overall accuracy rather than 50%, so small groups are not dragged to extreme values.
- **Leave-one-out** testing: every photo is scored by a map fitted without it. Calibration error is
  measured per signal group; fixed-width bins misreport leave-one-out predictions on tied scores.
- **Shipping rules**: at least 50 photos (`--min-images`), and the map must beat Gemini's raw
  confidence on the out-of-sample Brier score.

## Metrics

- **Lenient accuracy**: the zone Gemini named is one of the damaged zones (`MULTIPLE` counts when two
  or more zones are damaged). **Strict**: it named the zone with the most damage.
- **Separation (AUC)**: how well a signal ranks right answers above wrong ones (0.5 = none).
- **Calibration**: leave-one-out Brier score and per-group calibration error, plus how many claims
  would pass the 0.85 confidence factor, how often Gemini was right on those, and how many wrong
  answers would get through.

The dataset has no repair costs, so the dollar estimate and severity are not evaluated.
