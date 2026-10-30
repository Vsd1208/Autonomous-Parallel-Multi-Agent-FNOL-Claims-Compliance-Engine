# Second-opinion vision model

A small vision model trained here, separately from Gemini. It looks at the same claim photos, and when it disagrees
with Gemini the claim goes to an adjuster (gate flag `MODEL_DISAGREEMENT`). Two different kinds of model rarely make the
same mistake, so it catches confident Gemini errors that no other check stops. It never decides a claim on its own.

The trained model is served by `fnol-vision-ml` (Python, CPU) and called by the API in parallel with Gemini
(`SecondOpinionAgent`). If the service is off or slow, claims are handled exactly as without it.

## Data (all public domain or CC0)

| Data | Source | Licence | Used for |
|---|---|---|---|
| 441 car-damage photos with part and damage polygons, labelled by damaged area (front bumper, rear bumper, side panel, windshield) | [Humans in the Loop, Car Parts and Car Damages](https://humansintheloop.org/resources/datasets/car-parts-and-car-damages-dataset/) (Hugging Face copy `DrBimmer/car-parts-and-damage-dataset`), labels derived by `tools/vision-eval/prepare.py` | CC0 1.0 | Training and evaluating the car head |
| 53 home-damage photos labelled by cause of loss (flood, fire, wind, fallen tree) from the photographer's caption, hand-reviewed | FEMA photo library via Wikimedia Commons (`tools/vision-eval/prepare_home.py`) | Public domain (U.S. government work) | Evaluating the home head |

The images are downloaded locally (`tools/vision-eval/data`, not committed). The windshield class has only 6 photos
and no properly licensed windshield-damage photos could be found to add, so the model is weak on windshields.
Burst-pipe water damage has no labelled photos.

## Model

- **Image encoder:** LAION CLIP ViT-B/32 (`laion/CLIP-ViT-B-32-laion2B-s34B-b79K`, MIT licence), loaded from
  safetensors only, never pickle files.
- **Car damaged area:** logistic regression on the CLIP image embeddings, class-balanced. For a claim, the close-up
  photo is used when there is one; otherwise the photos are averaged.
- **Photo subject (vehicle, building, other property, nothing damaged) and home cause of loss:** zero-shot, by
  comparing the image with text descriptions of each class. No training.

## Results (out-of-fold: every photo scored by a model that never saw it)

| Head | Measure | Value |
|---|---|---|
| Car damaged area | main area right / one of the damaged areas | 64.6% / 80.5% (Gemini: 67% / 91% on its sample) |
| Photo subject | car photos reported as vehicle | 441/441 |
| Photo subject | home photos reported as building | 48/53 |
| Home cause of loss (zero-shot) | right | 84.9% |

On its own the car head is weaker than Gemini. Its value is as a disagreement check. Of the 56 Gemini answers that
pass every existing vision check (confidence at least 0.85, probability margin at least 0.30; runs v2-multiphoto and
v3-subject, 2026-10-30), a threshold on the second opinion's probability for Gemini's answer did the following:

| Second opinion gives Gemini's area less than | Flagged | Wrong answers caught | Wrong main area caught | Right answers flagged |
|---|---|---|---|---|
| 0.10 | 4/56 | 0/2 | 2/6 | 4 |
| **0.15 (default)** | 6/56 | **2/2** | 4/6 | 4 |
| 0.30 | 19/56 | 2/2 | 4/6 | 17 |

Both wrong answers that get past every other vision check (e.g. photo 1153: Gemini said front bumper at a 0.70 margin,
the damage is on the side panel) are caught. The samples are still small: re-run `compare_gemini.py` as the evaluation
grows. The threshold is `fnol.stp.second-opinion-min-probability`. For home claims it uses the same threshold, which
has not been checked against Gemini's home answers yet (`tools/vision-eval`, home run).

**Different photo style (2026-10-28):** on the close-ups of the six NHTSA crash-test sets in
`demo-scenarios/sample-images` (laboratory photos, mostly three-quarter views, unlike the everyday photos it was trained
on), the car head named the right area first for only 1 of 6. It gave the right area at least 0.15 for 5 of 6, though,
so it would raise `MODEL_DISAGREEMENT` on one of these real claims (the Civic rear impact). These crash claims are severe
and go to an adjuster anyway. More varied training photos would help most; none with a clear licence were found.

## Rebuild

```bash
python -P ../vision-eval/prepare.py --download-all      # all 441 labelled car photos (CC0), labels unchanged
python -P ../vision-eval/prepare_home.py --per-cause 15 # FEMA home photos (public domain)
python -P build_model.py                                # embeddings, training, results/report-second-opinion.md,
                                                        # ../../fnol-vision-ml/model/second-opinion-model.json
python -P compare_gemini.py --run v2-multiphoto --run v3-subject   # results/disagreement.md
docker compose up -d --build vision-ml                  # serve the new model
```

Needs Python 3.11+ with `torch`, `transformers`, `scikit-learn`, `numpy` and `pillow`.
