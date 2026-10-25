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
python -P evaluate.py --run thinking0 --thinking-budget 0 --limit 60 --max-calls 15

# 3. Accuracy report (results/report-thinking0.md) and, once >= 50 photos are answered,
#    the calibration map for the app
python -P calibrate.py --run thinking0 --write-app-calibration

# 4. Rebuild the API so it picks up the map
cd ../.. && docker compose up -d --build fnol-api
```

The map is applied only when the app's `gemini.vision.model` and `gemini.vision.thinking-budget` match
the ones it was measured with (the API logs a warning otherwise). Turn it off with
`GEMINI_VISION_CALIBRATION_ENABLED=false`. Re-run the evaluation after changing the model, the
prompt or the thinking budget.

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
