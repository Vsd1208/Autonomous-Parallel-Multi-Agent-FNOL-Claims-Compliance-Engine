# Second-opinion model: leakage check

734 car photos. Seed 7. Command: `python -P leakage_check.py`.

These datasets have no vehicle, accident or session IDs, so near-duplicate groups come from image similarity only: a control, not proof that no leak remains.

## Near-duplicates

| Cosine at or above | Groups | Photos sharing a group | Largest group |
|---|---|---|---|
| 0.98 | 734 | 0 | 1 |
| 0.95 | 728 | 11 | 3 |
| 0.9 | 715 | 30 | 10 |
| 0.85 | 682 | 72 | 33 |

## Results (groups at cosine 0.9)

| Split | Photos scored | Main area right (95% range) | One of the damaged areas | Log loss | Brier | Calibration error |
|---|---|---|---|---|---|---|
| A. Stratified folds over photos | 734 | 78.3% (75.2%–81.4%) | 87.2% | 0.484 | 0.293 | 0.032 |
| B. Folds kept apart by near-duplicate group | 734 | 78.6% (75.5%–81.5%) | 88.4% | 0.492 | 0.295 | 0.032 |
| C. Held out: Humans in the Loop | 441 | 64.2% (59.7%–68.8%) | 80.5% | 1.010 | 0.545 | 0.218 |
| C. Held out: VehicleDamageDetection | 260 | 67.7% (61.5%–73.6%) | 68.1% | 0.853 | 0.487 | 0.149 |
| C. Held out: car_dam | 22 | 72.7% (54.5%–90.9%) | 86.4% | 0.576 | 0.335 | 0.143 |
| C. Held out: damaged-cars-proj | 11 | 63.6% (36.4%–90.9%) | 72.7% | 0.901 | 0.586 | 0.309 |
| D. Final holdout, whole groups, scored once | 147 | 81.0% (73.6%–87.7%) | 89.8% | 0.480 | 0.268 | 0.060 |

The encoder and the model family were chosen earlier on the CC0 photos (`report-encoders.md`), so even D is not free of every earlier choice. The model that ships is trained on all the photos.
