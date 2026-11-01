# Our own vision model: damage types and visible extent

441 Humans in the Loop car photos (CC0), encoder `dinov2-b` (see report-encoders.md). Every number is out-of-fold (5-fold, seed 7); regularisation chosen inside each training fold. Brier skill = how much lower the Brier score is than always predicting the type's base rate (0% = no better than guessing the base rate; higher is better). AUC 95% ranges by bootstrap over photos.

| Damage type | Photos | Base rate | AUC (95% range) | Brier | Base-rate Brier | Brier skill | Calibration error |
|---|---|---|---|---|---|---|---|
| Broken part | 357 | 81% | 0.68 (0.61-0.74) | 0.145 | 0.154 | +6% | 0.042 |
| Dent | 331 | 75% | 0.64 (0.58-0.70) | 0.178 | 0.187 | +5% | 0.033 |
| Scratch | 287 | 65% | 0.60 (0.55-0.66) | 0.220 | 0.227 | +3% | 0.051 |
| Missing part | 238 | 54% | 0.68 (0.63-0.73) | 0.221 | 0.248 | +11% | 0.046 |
| Paint chip | 160 | 36% | 0.60 (0.54-0.66) | 0.225 | 0.231 | +3% | 0.042 |
| Corrosion | 70 | 16% | 0.67 (0.61-0.74) | 0.128 | 0.134 | +4% | 0.035 |
| Flaking | 67 | 15% | 0.64 (0.56-0.72) | 0.124 | 0.129 | +4% | 0.029 |
| Cracked | 37 | 8% | 0.57 (0.47-0.66) | 0.077 | 0.077 | +0% | 0.038 |

## Visible damage extent (share of the photo covered by damage)

Median share 6.8% (10th-90th percentile 1.4%-18.4%). Ridge regression on log(share), out-of-fold.

| Measure | Value |
|---|---|
| Rank correlation with the true share (Spearman) | 0.64 (95% range 0.58-0.70) |
| Small / medium / large (tertiles) right | 59% (chance 33%) |
| Off by two classes (small called large or the reverse) | 6% |

The extent depends on how close the photo was taken as well as on the damage, so it is a stand-in for severity, not a repair cost.
