# Property heads: trained vs zero-shot

307 reviewed public-domain FEMA photos. Trained heads: logistic regression, class-balanced, 5-fold stratified out-of-fold (seed 7), regularisation chosen inside each training fold. Zero-shot: CLIP against text descriptions, as the app does today. Balanced accuracy = mean of per-class accuracy (the classes are unequal). Differences vs zero-shot: paired bootstrap, 95% range.

## Cause of loss (FLOOD 103, FIRE_SMOKE 44, WIND_STORM 142, FALLEN_TREE 18)

| Head | Accuracy | Balanced accuracy | Log loss | Accuracy vs zero-shot (95% range) | FLOOD | FIRE_SMOKE | WIND_STORM | FALLEN_TREE |
|---|---|---|---|---|---|---|---|---|
| Zero-shot (CLIP, in use) | 79.8% | 78.1% | 0.822 | baseline | 64/103 | 38/44 | 130/142 | 13/18 |
| Trained on CLIP | 92.8% | 90.0% | 0.217 | +13.0% (+8.5% to +17.6%) | 96/103 | 42/44 | 133/142 | 14/18 |
| Trained on CLIP + DINOv2 | 93.2% | 88.7% | 0.209 | +13.3% (+8.8% to +18.2%) | 97/103 | 41/44 | 135/142 | 13/18 |
| Shipped: trained on CLIP + zero-shot WATER_DAMAGE | 92.8% | 90.0% | 0.371 | +13.0% (+8.5% to +17.6%) | 96/103 | 42/44 | 133/142 | 14/18 |

Shipped cause head: 88.9% right; 12 of 307 photos (none of them burst pipes) called WATER_DAMAGE by the zero-shot share.

## Property type (RESIDENTIAL 231, COMMERCIAL 35, OTHER_PROPERTY 41)

| Head | Accuracy | Balanced accuracy | Log loss | Accuracy vs zero-shot (95% range) | RESIDENTIAL | COMMERCIAL | OTHER_PROPERTY |
|---|---|---|---|---|---|---|---|
| Zero-shot (CLIP, in use) | 85.7% | 77.4% | 0.414 | baseline | 208/231 | 19/35 | 36/41 |
| Trained on CLIP | 89.9% | 81.0% | 0.268 | +4.2% (+0.3% to +8.1%) | 219/231 | 22/35 | 35/41 |
| Trained on CLIP + DINOv2 | 92.2% | 84.3% | 0.222 | +6.5% (+2.6% to +10.7%) | 223/231 | 24/35 | 36/41 |

Caveats: commercial and other property are small (35 and 41 photos), commercial has no fire or fallen-tree photos, and other property is mostly storm-damaged boats, so those numbers carry wide ranges. WATER_DAMAGE (burst pipes) cannot be trained: no public-domain photos.
