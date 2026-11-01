# Second-opinion model

Image encoders `laion/CLIP-ViT-B-32-laion2B-s34B-b79K` (MIT) and `facebook/dinov2-base` (Apache-2.0), safetensors weights. Car and extent heads: trained on both embeddings side by side, 5-fold cross-validation (seed 7) over 734 car photos (441 Humans in the Loop, CC0; 293 reviewed CC BY 4.0 from Roboflow Universe; the extent head on the CC0 photos only), regularisation chosen inside each training fold (car head C per fold: [16, 16, 16, 16, 16]); every number below is out-of-fold. Subject and home heads are zero-shot on CLIP (text descriptions, no training).

| Head | Measure | Value |
|---|---|---|
| Car damage area | main area right (strict) | **78.3%** |
| Car damage area | one of the damaged areas (lenient) | 87.2% |
| Car damage area, the 441 CC0 photos only (comparable with earlier runs) | strict / lenient | 72.6% / 85.9% |
| Car damage area | log loss / Brier (lower is better) | 0.484 / 0.293 |
| Car damage area, CLIP-only head (claims without a close-up) | strict / lenient | 72.6% / 82.2% |
| Visible extent | rank correlation with the true share of the photo damaged | 0.64 |
| Visible extent | SMALL / MEDIUM / LARGE right (chance 33%) | 55% |
| Visible extent | off by two bands | 2% |
| Photo subject | car photos reported as VEHICLE | 734/734 |
| Photo subject | home photos reported as BUILDING | 48/53 |
| Home cause of loss | zero-shot accuracy on 53 hand-reviewed FEMA photos (previous head) | 84.9% |
| Property cause of loss | trained head, out-of-fold on 307 reviewed FEMA photos (flood, fire, wind, fallen tree) | **92.8%** |
| Property type | trained head, out-of-fold (residential, commercial, other property) | **89.9%** |

Car head, out-of-fold confusion matrix (rows = true main area):

| True \ predicted | FRONT_BUMPER | REAR_BUMPER | SIDE_PANEL | WINDSHIELD | n |
|---|---|---|---|---|---|
| FRONT_BUMPER | 212 | 3 | 32 | 0 | 247 |
| REAR_BUMPER | 2 | 151 | 20 | 2 | 175 |
| SIDE_PANEL | 54 | 38 | 167 | 2 | 261 |
| WINDSHIELD | 4 | 1 | 1 | 45 | 51 |

Home cause of loss, zero-shot (rows = true cause):

| True \ predicted | FLOOD | FIRE_SMOKE | WIND_STORM | FALLEN_TREE | WATER_DAMAGE |
|---|---|---|---|---|---|
| FLOOD | 13 | 0 | 2 | 0 | 0 |
| FIRE_SMOKE | 0 | 12 | 1 | 0 | 0 |
| WIND_STORM | 0 | 0 | 12 | 0 | 0 |
| FALLEN_TREE | 0 | 0 | 5 | 8 | 0 |
| WATER_DAMAGE | 0 | 0 | 0 | 0 | 0 |

WINDSHIELD has only 6 photos in the dataset and no properly licensed photos could be added, so the car head is weak on windshields; WATER_DAMAGE has no labelled photos (FEMA captions do not cover burst pipes).
