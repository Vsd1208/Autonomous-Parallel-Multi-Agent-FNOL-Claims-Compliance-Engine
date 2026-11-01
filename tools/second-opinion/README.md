# Second-opinion vision model

A small vision model trained here, separately from Gemini. It looks at the same claim photos, and when it disagrees
with Gemini the claim goes to an adjuster (gate flag `MODEL_DISAGREEMENT`). Two different kinds of model rarely make the
same mistake, so it catches confident Gemini errors that no other check stops. It never decides a claim on its own.

The trained model is served by `fnol-vision-ml` (Python, CPU) and called by the API in parallel with Gemini
(`SecondOpinionAgent`). If the service is off or slow, claims are handled exactly as without it.

## Data

| Data | Source | Licence | Used for |
|---|---|---|---|
| 441 car-damage photos with part and damage polygons, labelled by damaged area (front bumper, rear bumper, side panel, windshield) | [Humans in the Loop, Car Parts and Car Damages](https://humansintheloop.org/resources/datasets/car-parts-and-car-damages-dataset/) (Hugging Face copy `DrBimmer/car-parts-and-damage-dataset`), labels derived by `tools/vision-eval/prepare.py` | CC0 1.0 | Training and evaluating the car head |
| 293 more car-damage photos, damaged area from the datasets' part boxes, every one reviewed by eye (`tools/vision-eval/roboflow_review.json`: watermarked stock photos and unclear ones excluded, 36 labels corrected) | [car_dam](https://universe.roboflow.com/startunderstandingroboflow/car_dam-mxrh6), [damaged-cars-proj](https://universe.roboflow.com/musaab-jde4b/damaged-cars-proj), [VehicleDamageDetection](https://universe.roboflow.com/car-damage-severity/vehicledamagedetection) (Roboflow Universe; [Car Damages](https://universe.roboflow.com/venkadesh-p-fajty/car-damages-k0bnj) added no new photo after de-duplication), via `tools/vision-eval/prepare_roboflow.py` | CC BY 4.0 (as declared by the uploaders; see the caveat below) | Training and evaluating the car head |
| 307 property-damage photos labelled by cause of loss and property type (residential, commercial, other property) from the photographer's caption, every one reviewed by eye (`tools/vision-eval/property_review.json`) | FEMA photo library via Wikimedia Commons (`tools/vision-eval/prepare_property.py`, `prepare_home.py`) | Public domain (U.S. government work) | Training and evaluating the property heads |

**Provenance caveat (CC BY 4.0 photos).** Community datasets often contain photos collected from the web. The licence is
the uploader's declaration; photos showing a stock agency's or another owner's watermark (63 of the 420 reviewed) were
excluded because the uploader could not license them. The rest cannot be traced further.

The images are downloaded locally (`tools/vision-eval/data`, not committed). The windshield class has only 6 photos
and no properly licensed windshield-damage photos could be found to add, so the model is weak on windshields.
Burst-pipe water damage has no labelled photos.

## Model

- **Image encoders:** LAION CLIP ViT-B/32 (`laion/CLIP-ViT-B-32-laion2B-s34B-b79K`, MIT licence) and Meta
  DINOv2-base (`facebook/dinov2-base`, Apache-2.0), loaded from safetensors only, never pickle files. Chosen by
  `benchmark_encoders.py` out of CLIP B/32, CLIP L/14, SigLIP-base and DINOv2-base and their combinations
  ([results/report-encoders.md](results/report-encoders.md)): the two side by side are the only choice whose gain over
  CLIP alone excludes zero (+5.2 points, 95% range +2.0 to +8.6). The bigger CLIP L/14 was no better than B/32.
- **Car damaged area:** logistic regression on both embeddings side by side, class-balanced, regularisation chosen
  inside each training fold. For a claim, the close-up photo is used when there is one (DINOv2 runs only on it, to keep
  the claim fast); otherwise the photos are averaged.
- **Visible damage extent:** ridge regression on the same embeddings, predicting the share of the photo covered by
  damage outlines, banded into thirds of the labelled photos (SMALL / MEDIUM / LARGE). It depends on how close the photo
  was taken, so it is a stand-in for severity, not a cost.
- **Photo subject (vehicle, building, other property, nothing damaged) and home cause of loss:** zero-shot, by
  comparing the image with text descriptions of each class. No training.

## Results (out-of-fold: every photo scored by a model that never saw it)

| Head | Measure | Value |
|---|---|---|
| Car damaged area | main area right / one of the damaged areas | **78.3% / 87.2%** on 734 photos (72.6% / 85.9% on the 441 CC0 ones; CC0-only model 71.9% / 86.2%; CLIP alone: 64.6% / 80.5%; Gemini, current prompt: 58% / 82% on its 66-photo sample, see [docs/metrics.md](../../docs/metrics.md)) |
| Visible damage extent | SMALL / MEDIUM / LARGE right; off by two bands | 55% (chance 33%); 2% |
| Damage types (dent, scratch, ...) | AUC per type (`damage_details.py`) | 0.57-0.68: not good enough to ship |
| Photo subject | car photos reported as vehicle | 441/441 |
| Photo subject | home photos reported as building | 48/53 |
| Home cause of loss (zero-shot) | right | 84.9% |

On its own the car head is weaker than Gemini. Its value is as a disagreement check. Of the 66 Gemini answers that
pass every existing vision check (confidence at least 0.85, probability margin at least 0.30; runs v2-multiphoto and
v3-subject, re-run 2026-10-31 with 66 passing answers), a threshold on the second opinion's probability for Gemini's answer did the following:

| Second opinion gives Gemini's area less than | Flagged | Wrong answers caught | Wrong main area caught | Right answers flagged |
|---|---|---|---|---|
| 0.05 | 7/66 | 2/2 | 4/6 | 5 |
| **0.10 (default)** | 11/66 | **2/2** | 4/6 | 9 |
| 0.15 | 11/66 | 2/2 | 4/6 | 9 |
| 0.30 | 13/66 | 2/2 | 4/6 | 11 |

(Model trained on 734 car photos. The wrong answers get 0.00 and 0.03, so 0.10 keeps a margin.)

With the earlier CLIP-only model the same catches needed 0.15 (6 flagged, 4 right answers); the new model's
probabilities are sharper, so the default moved to 0.10. 0.05 would flag fewer right answers but is tuned too tightly to
this small sample.

Both wrong answers that get past every other vision check (e.g. photo 1153: Gemini said front bumper at a 0.70 margin,
the damage is on the side panel) are caught. The samples are still small: re-run `compare_gemini.py` as the evaluation
grows. The threshold is `fnol.stp.second-opinion-min-probability`. For home claims it uses the same threshold, which
has not been checked against Gemini's home answers yet (`tools/vision-eval`, home run).

**Different photo style (2026-10-28, CLIP-only model; see below for the current model):** on the close-ups of the six NHTSA crash-test sets in
`demo-scenarios/sample-images` (laboratory photos, mostly three-quarter views, unlike the everyday photos it was trained
on), the car head named the right area first for only 1 of 6. It gave the right area at least 0.15 for 5 of 6, though,
so it would raise `MODEL_DISAGREEMENT` on one of these real claims (the Civic rear impact). These crash claims are severe
and go to an adjuster anyway. More varied training photos would help most; none with a clear licence were found.

**With the model trained on all 734 car photos:** 4 of 6 (the Corolla side impact is now called rear). **With the
CC0-only CLIP + DINOv2 model (2026-11-01):** the right area first for **5 of 6** (Altima, Camry, F-150
front; Corolla, Equinox side). The miss is again the Civic rear impact, now called side panel at 99% (rear 0.6%), so a
correct Gemini answer on that claim raises `MODEL_DISAGREEMENT` and goes to an adjuster.

**Speed.** In the `vision-ml` container (2 CPU threads, 5 photos) a claim with a close-up takes 0.89 s median, 1.37 s
worst (DINOv2 reads only the close-up). DINOv2 on all five photos took 2.4 s median, 6.6 s worst, past Branch A's 2.5 s
wait, so a claim without a close-up uses the CLIP-only head (`autoClipOnly` in the model file; 66.7% / 81.2%
out-of-fold): 0.48 s.

## Rebuild

```bash
python -P ../vision-eval/prepare.py --download-all      # all 441 labelled car photos (CC0), labels unchanged
python -P ../vision-eval/prepare_home.py --per-cause 15 # FEMA home photos (public domain)
python -P benchmark_encoders.py --encoders clip-b32,dinov2-b,clip-b32+dinov2-b   # optional: encoder comparison
python -P build_model.py                                # embeddings, training, results/report-second-opinion.md,
                                                        # ../../fnol-vision-ml/model/second-opinion-model.json,
                                                        # ../fraud-eval/second-opinion-disagreement.json
python -P damage_details.py --encoder clip-b32+dinov2-b # optional: damage types and extent report
python -P compare_gemini.py --run v2-multiphoto --run v3-subject   # results/disagreement.md
cd ../vision-eval && python -P trust_score.py --run v3-subject --write-app   # refit the trust score (shipping rules)
cd ../fraud-eval && python -P fraud_eval.py --write-app                       # refit the fraud score
docker compose up -d --build vision-ml                  # serve the new model
```

Needs Python 3.11+ with `torch`, `transformers`, `scikit-learn`, `numpy` and `pillow`.
