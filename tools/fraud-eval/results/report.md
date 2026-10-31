# Fraud anomaly score: synthetic evaluation

**Synthetic data only.** Generator and seed: `tools/fraud-eval/fraud_eval.py`, seed 20261031, 20000 claims, 4% fraudulent, split in half (fit on the first half without labels, evaluated on the second). The generator encodes this project's assumptions about fraud; these numbers show the score finds the patterns it was designed for. **Performance on real claims is unknown.**

Held-out claims: 10000, of which 409 fraudulent. Threshold 5.859 points (95th percentile of training scores, a 5% referral budget).

**AUC 0.927** (ranking fraud above genuine claims; 0.5 = chance).

| Method | Claims flagged (rate) | Precision | Recall |
|---|---|---|---|
| Anomaly score ≥ threshold | 523 (5.2%) | 44.7% | 57.2% |
| Existing fraud rules (photo reused, high frequency, loss < 30 days after policy start) | 1644 (16.4%) | 14.5% | 58.4% |
| Fraud rules + claimed > 10% above estimate + model disagreement | 3655 (36.5%) | 9.6% | 85.8% |
| Fraud rules or anomaly score | 1848 (18.5%) | 16.8% | 75.8% |

## Recall by fraud pattern (held-out)

| Pattern | Claims with it | Caught by the score | Caught by the fraud rules |
|---|---|---|---|
| inflation | 133 | 93/133 (70%) | 57/133 (43%) |
| earlyPolicy | 125 | 92/125 (74%) | 70/125 (56%) |
| lateReport | 114 | 82/114 (72%) | 58/114 (51%) |
| repeatClaimant | 121 | 75/121 (62%) | 104/121 (86%) |
| photoMismatch | 122 | 69/122 (57%) | 55/122 (45%) |
| photoReused | 120 | 95/120 (79%) | 120/120 (100%) |

Fraud claims with one pattern: score 46/171 (27%), rules 82/171 (48%). With two or more: score 188/238 (79%), rules 157/238 (66%).

## Percentile calibration (held-out)

The app reports each claim's percentile against the training scores. If that is calibrated, the share of held-out claims above the p-th percentile is 1 − p:

| Percentile | Expected share above | Observed share above |
|---|---|---|
| 50th | 50.0% | 50.6% |
| 80th | 20.0% | 20.6% |
| 90th | 10.0% | 10.7% |
| 95th | 5.0% | 5.2% |
| 99th | 1.0% | 0.9% |

## Fitted reference profile

| Feature | Direction | Transform | Median | Scale |
|---|---|---|---|---|
| Amount claimed / AI estimate | high is unusual | log | 0.000492 | 0.120594 |
| Days from policy start to loss | low is unusual | log1p | 7.026681 | 0.856366 |
| Days from loss to report | high is unusual | log1p | 1.247455 | 0.870508 |
| Claims on the policy in the last 12 months | high is unusual | none | 0.0 | 1.0 |
| Second opinion's disagreement with the AI damage type | high is unusual | none | 0.5148 | 0.189625 |
| Photo already used on another claim | yes = unusual | binary | — | weight 3.0 (expert prior) |

Each continuous feature adds at most 4.0 points. Not used, by design: channel, time of day, state or ZIP code, and any personal or demographic attribute (fairness; see docs/model-cards.md).
