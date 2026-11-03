# Demo scenarios through both reserve methods

Mock vision, whose figures this project wrote: this shows what changes, not which is more accurate.

| Scenario | AI estimate | Claimed | Reserve: `AI_ESTIMATE` | Reserve: `AVERAGE_TABLE` | Status: `AI_ESTIMATE` | Status: `AVERAGE_TABLE` | Flags that differ |
|---|---|---|---|---|---|---|---|
| 1_clean_stp | 4200 | 3000 | 2000 | 2000 | PENDING_ADVISORY_REVIEW | PENDING_ADVISORY_REVIEW | - |
| 2_low_confidence | 4200 | 3500 | 2500 | 2500 | HUMAN_REVIEW | HUMAN_REVIEW | - |
| 3_compliance_block | - | - | - | - | 422 | 422 | - |
| 4_api_timeout | - | - | - | - | 504 | 504 | - |
| 5_duplicate | 4200 | 3000 | 2000 | 2000 | PENDING_ADVISORY_REVIEW | PENDING_ADVISORY_REVIEW | - |
| 6_high_claim_frequency | 4200 | 2500 | 1500 | 1500 | HUMAN_REVIEW | HUMAN_REVIEW | - |
| 7_home_policy | 4200 | 3000 | 500 | 500 | PENDING_ADVISORY_REVIEW | PENDING_ADVISORY_REVIEW | - |
| 8_claim_above_estimate | 4200 | 9000 | 3200 | 3200 | HUMAN_REVIEW | HUMAN_REVIEW | - |
| 9_home_flood | 38000 | 38000 | 35500 | 32500 | HUMAN_REVIEW | HUMAN_REVIEW | - |
| 10_fraud_anomaly | 4200 | 4600 | 3200 | 3200 | HUMAN_REVIEW | HUMAN_REVIEW | - |

Of 10 scenarios: reserve differs on 1, routing differs on 0.
