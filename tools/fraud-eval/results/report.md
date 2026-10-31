# Fraud anomaly score: synthetic evaluation

**Synthetic data only.** Generator and seed: `tools/fraud-eval/fraud_eval.py`, seed 20261031, 20000 claims, 4% fraudulent, split in half (fit on the first half without labels, evaluated on the second). The generator encodes this project's assumptions about fraud; these numbers show the score finds the patterns it was designed for. **Performance on real claims is unknown.**

Held-out claims: 10000, of which 382 fraudulent. Threshold 5.9118 points (95th percentile of training scores, a 5% referral budget).

**AUC 0.915** (ranking fraud above genuine claims; 0.5 = chance).

| Method | Claims flagged (rate) | Precision | Recall |
|---|---|---|---|
| Anomaly score ≥ threshold | 537 (5.4%) | 40.0% | 56.3% |
| Existing fraud rules (photo reused, high frequency, loss < 30 days after policy start) | 1641 (16.4%) | 13.9% | 59.7% |
| Fraud rules + claimed > 10% above estimate + model disagreement | 3488 (34.9%) | 8.7% | 79.6% |
| Fraud rules or anomaly score | 1864 (18.6%) | 15.4% | 75.1% |

## Recall by fraud pattern (held-out)

| Pattern | Claims with it | Caught by the score | Caught by the fraud rules |
|---|---|---|---|
| inflation | 99 | 66/99 (67%) | 45/99 (45%) |
| earlyPolicy | 120 | 92/120 (77%) | 73/120 (61%) |
| lateReport | 112 | 76/112 (68%) | 56/112 (50%) |
| repeatClaimant | 104 | 56/104 (54%) | 83/104 (80%) |
| photoMismatch | 102 | 71/102 (70%) | 59/102 (58%) |
| photoReused | 116 | 89/116 (77%) | 116/116 (100%) |

Fraud claims with one pattern: score 47/180 (26%), rules 82/180 (46%). With two or more: score 168/202 (83%), rules 146/202 (72%).

## Fitted reference profile

| Feature | Direction | Transform | Median | Scale |
|---|---|---|---|---|
| Amount claimed / AI estimate | high is unusual | log | 0.002507 | 0.121172 |
| Days from policy start to loss | low is unusual | log1p | 7.033951 | 0.860969 |
| Days from loss to report | high is unusual | log1p | 1.237623 | 0.866081 |
| Claims on the policy in the last 12 months | high is unusual | none | 0.0 | 1.0 |
| Second opinion's disagreement with the AI damage type | high is unusual | none | 0.26569 | 0.165717 |
| Photo already used on another claim | yes = unusual | binary | — | weight 3.0 (expert prior) |

Each continuous feature adds at most 4.0 points. Not used, by design: channel, time of day, state or ZIP code, and any personal or demographic attribute (fairness; see docs/model-cards.md).
