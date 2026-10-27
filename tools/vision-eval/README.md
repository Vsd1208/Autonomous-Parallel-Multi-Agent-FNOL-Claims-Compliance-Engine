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
| `v3-subject` | current prompt: as v2, plus `photoSubject` (VEHICLE, BUILDING, OTHER_PROPERTY, NONE) (prompt SHA-256 `501b3308884a64f8`) | 0 of 50 needed | — | Run daily until 50; the report also counts how often a car photo is reported as VEHICLE |

`v3-subject` restarts the count because a changed prompt can change the answers. Compare it with
`v2-multiphoto` on the photos both runs answered to see whether the extra question moved the damage-zone
accuracy. Every photo in the dataset is a car, so any `photoSubject` other than VEHICLE would be a false
`PHOTO_SUBJECT_MISMATCH` on a genuine auto claim.

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
