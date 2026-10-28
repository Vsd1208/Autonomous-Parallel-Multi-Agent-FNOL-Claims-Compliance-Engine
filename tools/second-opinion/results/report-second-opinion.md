# Second-opinion model

Image encoder `laion/CLIP-ViT-B-32-laion2B-s34B-b79K` (MIT), safetensors weights. Car head: logistic regression on CLIP embeddings, class-balanced, 5-fold stratified cross-validation (seed 7) over 441 Humans in the Loop photos (CC0); every number below is out-of-fold. Subject and home heads are zero-shot (text descriptions, no training).

| Head | Measure | Value |
|---|---|---|
| Car damage area | main area right (strict) | **64.6%** |
| Car damage area | one of the damaged areas (lenient) | 80.5% |
| Photo subject | car photos reported as VEHICLE | 441/441 |
| Photo subject | home photos reported as BUILDING | 48/53 |
| Home cause of loss | zero-shot accuracy on 53 hand-reviewed FEMA photos | 84.9% |

Car head, out-of-fold confusion matrix (rows = true main area):

| True \ predicted | FRONT_BUMPER | REAR_BUMPER | SIDE_PANEL | WINDSHIELD | n |
|---|---|---|---|---|---|
| FRONT_BUMPER | 159 | 2 | 26 | 1 | 188 |
| REAR_BUMPER | 1 | 54 | 11 | 0 | 66 |
| SIDE_PANEL | 65 | 40 | 72 | 4 | 181 |
| WINDSHIELD | 3 | 0 | 3 | 0 | 6 |

Home cause of loss, zero-shot (rows = true cause):

| True \ predicted | FLOOD | FIRE_SMOKE | WIND_STORM | FALLEN_TREE | WATER_DAMAGE |
|---|---|---|---|---|---|
| FLOOD | 13 | 0 | 2 | 0 | 0 |
| FIRE_SMOKE | 0 | 12 | 1 | 0 | 0 |
| WIND_STORM | 0 | 0 | 12 | 0 | 0 |
| FALLEN_TREE | 0 | 0 | 5 | 8 | 0 |
| WATER_DAMAGE | 0 | 0 | 0 | 0 | 0 |

WINDSHIELD has only 6 photos in the dataset and no properly licensed photos could be added, so the car head is weak on windshields; WATER_DAMAGE has no labelled photos (FEMA captions do not cover burst pipes).
