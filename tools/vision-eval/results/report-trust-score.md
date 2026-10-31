# Trust score (calibrated AI confidence) - runs v3-subject

66 answers on 66 distinct photos or claims (66 labelled evaluation photos), 54 right (lenient). Every score is out-of-sample (leave-one-image-out) and the smoothing is chosen inside each training set (nested), so the figures are not flattered by the choice. Lower Brier and calibration error are better; a calibrated score of 0.80 should be right about 80% of the time. Ranges are 95% bootstrap intervals over 2000 resamples of the images.


| Confidence used | Separation (AUC) | Brier (95% range) | Calibration error (95% range) |
|---|---|---|---|
| Gemini's self-reported confidence | 0.57 | 0.150 (0.082-0.228) | 0.090 (0.044-0.188) |
| Current app trust score (margin, agreement, specific; C=1) | 0.72 | 0.121 (0.078-0.170) | 0.055 (0.045-0.171) |
| Trust score: named-area margin only (smoothing chosen by cross-validation) | 0.68 | 0.118 (0.077-0.165) | 0.053 (0.023-0.131) |
| Trust score: named-area margin + second opinion (smoothing chosen by cross-validation) | 0.68 | 0.118 (0.077-0.164) | 0.051 (0.025-0.132) |

Calibration error: equal-count bins ordered by each model's all-data score, measured on the out-of-sample scores (see `ece`).

Smoothing chosen inside the training sets (C, how often): 3 x56, 10 x9, 100 x1. On all data: C=3 with the second opinion, C=10 margin only.

## Risk and coverage

Coverage = answers that would pass the confidence factor at this threshold (could go straight through on the vision checks); wrong = how many of those are wrong.

| Threshold | Gemini raw: coverage / wrong | Trust score (+ second opinion): coverage / wrong |
|---|---|---|
| 0.50 | 66/66 / 12 | 66/66 / 12 |
| 0.60 | 66/66 / 12 | 50/66 / 10 |
| 0.70 | 66/66 / 12 | 41/66 / 1 |
| 0.80 | 65/66 / 11 | 41/66 / 1 |
| 0.85 | 65/66 / 11 | 41/66 / 1 |
| 0.90 | 65/66 / 11 | 41/66 / 1 |
| 0.95 | 3/66 / 0 | 35/66 / 1 |

Fitted on all answers (what the app would use):

- with second opinion: namedMargin +1.24, agreement +1.46, specific +1.36, intercept +0.31 (C=3)
- margin only: namedMargin +2.00, specific +1.70, intercept +0.27 (C=10)

Verdict: ready for the app.
