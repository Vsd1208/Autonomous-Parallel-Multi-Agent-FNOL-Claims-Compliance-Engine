# Image encoders for our own car-damage model

441 Humans in the Loop car photos (CC0). Logistic regression on each encoder's embeddings, class-balanced, 5-fold stratified (seed 7); regularisation chosen inside each training fold. Every number is out-of-fold. Lower is better for log loss, Brier and calibration error (ECE, top probability, 10 equal-count bins). Differences are against `clip-b32` (in use today): paired bootstrap over the same photos, mean and 95% range; for log loss a negative difference is an improvement. `a + b` means both encoders' embeddings side by side.

| Encoder | Licence | Dim | Main area right | One of the areas | Log loss | Brier | ECE | Strict vs today | Log loss vs today |
|---|---|---|---|---|---|---|---|---|---|
| `laion/CLIP-ViT-B-32-laion2B-s34B-b79K` | MIT | 512 | 66.7% | 81.2% | 0.729 | 0.450 | 0.080 | baseline | baseline |
| `facebook/dinov2-base` | Apache-2.0 | 1536 | 69.8% | 84.8% | 0.618 | 0.390 | 0.064 | +3.2% (-1.4% to +7.7%) | -0.111 (-0.171 to -0.050) |
| `google/siglip-base-patch16-224` | Apache-2.0 | 768 | 63.5% | 81.9% | 0.759 | 0.473 | 0.100 | -3.2% (-7.7% to +1.1%) | +0.030 (-0.027 to +0.085) |
| `laion/CLIP-ViT-L-14-laion2B-s32B-b82K` | MIT | 768 | 66.9% | 81.9% | 0.731 | 0.451 | 0.083 | +0.2% (-4.1% to +4.5%) | +0.001 (-0.054 to +0.057) |
| `laion/CLIP-ViT-B-32-laion2B-s34B-b79K` + `facebook/dinov2-base` | Apache-2.0 + MIT | 2048 | 71.9% | 86.2% | 0.608 | 0.388 | 0.072 | +5.2% (+2.0% to +8.6%) | -0.121 (-0.156 to -0.086) |
| `google/siglip-base-patch16-224` + `facebook/dinov2-base` | Apache-2.0 | 2304 | 67.8% | 84.8% | 0.640 | 0.407 | 0.065 | +1.1% (-3.2% to +5.4%) | -0.089 (-0.142 to -0.034) |
| `laion/CLIP-ViT-L-14-laion2B-s32B-b82K` + `facebook/dinov2-base` | Apache-2.0 + MIT | 2304 | 68.5% | 84.6% | 0.623 | 0.398 | 0.078 | +1.8% (-2.5% to +6.1%) | -0.106 (-0.159 to -0.052) |

Main area right, per true class (n = FRONT_BUMPER 188, REAR_BUMPER 66, SIDE_PANEL 181, WINDSHIELD 6):

| Encoder | FRONT_BUMPER | REAR_BUMPER | SIDE_PANEL | WINDSHIELD |
|---|---|---|---|---|
| clip-b32 | 82% | 77% | 49% | 0% |
| dinov2-b | 85% | 77% | 53% | 33% |
| siglip-b16 | 76% | 71% | 49% | 50% |
| clip-l14 | 78% | 76% | 53% | 33% |
| clip-b32+dinov2-b | 86% | 77% | 57% | 17% |
| siglip-b16+dinov2-b | 81% | 80% | 50% | 33% |
| clip-l14+dinov2-b | 81% | 79% | 53% | 17% |

Regularisation chosen per outer fold: clip-b32 [16, 8, 16, 16, 16]; dinov2-b [16, 16, 16, 32, 16]; siglip-b16 [16, 16, 16, 16, 16]; clip-l14 [16, 32, 16, 32, 16]; clip-b32+dinov2-b [16, 16, 16, 16, 16]; siglip-b16+dinov2-b [8, 16, 16, 32, 16]; clip-l14+dinov2-b [16, 16, 16, 32, 16].
