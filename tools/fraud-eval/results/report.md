# Fraud anomaly score: synthetic evaluation

**Synthetic data only.** Generator and seed: `tools/fraud-eval/fraud_eval.py`, seed 20261031, 20000 claims, 4% fraudulent, split in half (fit on the first half without labels, evaluated on the second). The generator encodes this project's assumptions about fraud; these numbers show the score finds the patterns it was designed for. **Performance on real claims is unknown.**

Held-out claims: 10000, of which 405 fraudulent. Threshold 6.7439 points (95th percentile of training scores, a 5% referral budget).

**AUC 0.917** (ranking fraud above genuine claims; 0.5 = chance).

| Method | Claims flagged (rate) | Precision | Recall |
|---|---|---|---|
| Anomaly score ≥ threshold | 502 (5.0%) | 44.4% | 55.1% |
| Existing fraud rules (photo reused, high frequency, loss < 30 days after policy start) | 1667 (16.7%) | 14.3% | 59.0% |
| Fraud rules + claimed > 10% above estimate + model disagreement | 3918 (39.2%) | 8.9% | 85.9% |
| Fraud rules or anomaly score | 1879 (18.8%) | 16.1% | 74.8% |

## Recall by fraud pattern (held-out)

| Pattern | Claims with it | Caught by the score | Caught by the fraud rules |
|---|---|---|---|
| inflation | 132 | 80/132 (61%) | 64/132 (48%) |
| earlyPolicy | 121 | 87/121 (72%) | 73/121 (60%) |
| lateReport | 117 | 86/117 (74%) | 52/117 (44%) |
| repeatClaimant | 123 | 68/123 (55%) | 97/123 (79%) |
| photoMismatch | 118 | 85/118 (72%) | 65/118 (55%) |
| photoReused | 121 | 89/121 (74%) | 121/121 (100%) |

Fraud claims with one pattern: score 41/175 (23%), rules 79/175 (45%). With two or more: score 182/230 (79%), rules 160/230 (70%).

## Percentile calibration (held-out)

The app reports each claim's percentile against the training scores. If that is calibrated, the share of held-out claims above the p-th percentile is 1 − p:

| Percentile | Expected share above | Observed share above |
|---|---|---|
| 50th | 50.0% | 50.4% |
| 80th | 20.0% | 21.0% |
| 90th | 10.0% | 10.4% |
| 95th | 5.0% | 5.0% |
| 99th | 1.0% | 1.2% |

## Precision and recall as the referral budget changes (held-out, synthetic)

Average precision 0.543; a score with no skill would get the fraud rate, 0.041. A real fraud rate and real claims would move every row. The chosen budget is a business decision: this table shows the trade, it does not pick one.

| Claims referred | Score it takes | Claims | Precision | Recall |
|---|---|---|---|---|
| 1% | 9.61 | 100 | 93.0% | 23.0% |
| 2% | 8.11 | 200 | 71.5% | 35.3% |
| 5% | 6.75 | 500 | 44.6% | 55.1% |
| 10% | 5.59 | 1000 | 28.7% | 70.9% |
| 20% | 4.37 | 2000 | 17.0% | 83.7% |
| 30% | 3.60 | 3000 | 12.3% | 91.1% |

## Why genuine claims get flagged (held-out, synthetic)

279 genuine claims reach the threshold. These are the features behind them, so a reviewer knows which explanations most often accompany a false alarm. They follow from the generator's assumptions; real false positives need real claims.

| Feature | Flagged genuine claims it added points to | Mean points added |
|---|---|---|
| Days from policy start to loss | 259 (93%) | 3.05 |
| Second opinion's disagreement with the AI damage type | 254 (91%) | 2.60 |
| Days from loss to report | 211 (76%) | 1.54 |
| Amount claimed / AI estimate | 175 (63%) | 1.15 |
| Claims on the policy in the last 12 months | 100 (36%) | 1.17 |
| Photo already used on another claim | 11 (4%) | 3.00 |

## Fitted reference profile

| Feature | Direction | Transform | Median | Scale |
|---|---|---|---|---|
| Amount claimed / AI estimate | high is unusual | log | 0.003524 | 0.121365 |
| Days from policy start to loss | low is unusual | log1p | 7.025571 | 0.856167 |
| Days from loss to report | high is unusual | log1p | 1.252902 | 0.885496 |
| Claims on the policy in the last 12 months | high is unusual | none | 0.0 | 1.0 |
| Second opinion's disagreement with the AI damage type | high is unusual | none | 0.1784 | 0.21557 |
| Photo already used on another claim | yes = unusual | binary | — | weight 3.0 (expert prior) |

Each continuous feature adds at most 4.0 points. Not used, by design: channel, time of day, state or ZIP code, and any personal or demographic attribute (fairness; see docs/model-cards.md).
