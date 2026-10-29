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
| `v3-subject` | current prompt: as v2, plus `photoSubject` (VEHICLE, BUILDING, OTHER_PROPERTY, NONE) (prompt SHA-256 `501b3308884a64f8`) | 32 of 50 needed | 81% / 56% (`results/report-v3-subject.md`) | Run daily until 50; car photos reported as VEHICLE: 32/32; self-reported confidence AUC 0.52, margin AUC 0.83 |

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

Until the map ships, the app guards against hedged answers directly: the reconciliation gate raises
`AMBIGUOUS_DAMAGE_TYPE` (claim goes to an adjuster) when the probability margin is below
`fnol.stp.min-probability-margin` (default 0.30). In `v2-multiphoto`, all 7 hedged `MULTIPLE` answers had
a margin of 0.20 or less, and 2 of them were wrong; the gate sends all 7 to an adjuster. One wrong answer
was confident (photo 1153: `FRONT_BUMPER` at margin 0.70 for side-panel damage), so the margin gate
does not catch everything; the claimed-amount and severity checks remain the backstop.

On 33 photos the calibrated map does not yet beat Gemini's raw confidence out of sample (Brier 0.089 vs
0.084), so even at 50 photos `calibrate.py` will only write the map if that changes.

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
| `v3-subject` (32) | confidence factor only | 32 | 6 / 14 | 0 |
| | + `AMBIGUOUS_DAMAGE_TYPE` (+ severity, + subject) | 21 | 1 / 3 | 6 |

With the second-opinion check as well (`tools/second-opinion/compare_gemini.py`, both runs): of the 46 answers that
pass the confidence and margin checks, 2 are wrong, and the second opinion (below 0.15 for Gemini's area) flags both,
at the cost of 4 right answers sent to an adjuster. On these 80 answers no wrong answer reaches auto-approval with all
checks on.

The margin check removes most wrong answers that the confidence factor lets through, at the cost of sending
some right answers to an adjuster. The one wrong answer no vision check stops (photo 1153, a confident wrong
zone) is why the claimed-amount check, coverage and the severity gate stay in front of any payment. The
severity and subject checks stop no further wrong answer on these car photos: they target other failures
(inconsistent estimates, the wrong kind of property).

## Trust score (calibrated AI confidence)

Gemini's self-reported confidence is about 0.9 for nearly every answer, so it cannot tell the app which answers to
trust. `trust_score.py` learns the probability that Gemini's damaged area is right from the signals that do separate
right from wrong answers: the probability margin, the second-opinion model's agreement (`tools/second-opinion`,
out-of-fold) and whether Gemini named a specific area. Logistic regression, scored leave-one-image-out:

```bash
python -P trust_score.py --run v2-multiphoto --run v3-subject      # study, results/report-trust-score.md
python -P trust_score.py --run v3-subject --write-app               # app file, once the shipping rules pass
```

48 answers on 33 photos (provisional):

| Confidence used | Separation (AUC) | Brier | Passing 0.85: answers / wrong |
|---|---|---|---|
| Gemini's self-reported confidence | 0.50 | 0.110 | 47 of 48 / 6 |
| Trust score (margin + second opinion) | **0.73** | 0.099 | 36 of 48 / **1** |

At the app's 0.85 confidence factor, Gemini's own number lets 6 wrong answers through; the trust score lets 1 through
and sends 12 answers to an adjuster instead. Gemini's raw number has the lower calibration error only because it says
0.9 for everything and about 88% of answers are right; it does not rank answers at all.

The app's `TrustScorer` replaces Gemini's confidence with the trust score (keeping Gemini's value in `rawConfidence`)
only when `fnol-agents/src/main/resources/trust-score.json` was written from a single run with the current vehicle
prompt, model and thinking budget, on at least 50 images, and beats Gemini's raw confidence on the Brier score. Not
shipped yet (33 images, two prompts). Home claims keep Gemini's confidence: the model is trained on car photos.

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
  and wrong answers alike (AUC 0.52 on 25 photos). The margin separates specific answers (all right)
  from hedged "MULTIPLE" answers (often wrong): AUC 0.86.
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
