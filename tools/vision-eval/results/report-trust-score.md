# Trust score (calibrated AI confidence) - runs v3-subject

66 answers on 66 distinct photos or claims (66 labelled evaluation photos), 54 right (lenient). Every score is out-of-sample (leave-one-image-out) and the smoothing is chosen inside each training set (nested), so the figures are not flattered by the choice. Lower Brier and calibration error are better; a calibrated score of 0.80 should be right about 80% of the time. Ranges are 95% bootstrap intervals over 2000 resamples of the images.


| Confidence used | Separation (AUC) | Brier (95% range) | Calibration error (95% range) |
|---|---|---|---|
| Gemini's self-reported confidence | 0.57 | 0.150 (0.082-0.228) | 0.090 (0.044-0.188) |
| Current app trust score (namedMargin, agreement, specific; C=3) | 0.73 | 0.116 (0.077-0.161) | 0.067 (0.021-0.129) |
| Trust score: named-area margin only (smoothing chosen by cross-validation) | 0.68 | 0.118 (0.077-0.165) | 0.053 (0.023-0.131) |
| Trust score: named-area margin + second opinion (smoothing chosen by cross-validation) | 0.68 | 0.117 (0.077-0.163) | 0.069 (0.022-0.131) |

Calibration error: equal-count bins ordered by each model's all-data score, measured on the out-of-sample scores (see `ece`).

Smoothing chosen inside the training sets (C, how often): 1 x5, 3 x60, 100 x1. On all data: C=3 with the second opinion, C=10 margin only.

## Risk and coverage

Coverage = answers that would pass the confidence factor at this threshold (could go straight through on the vision checks); wrong = how many of those are wrong.

| Threshold | Gemini raw: coverage / wrong | Trust score (+ second opinion): coverage / wrong |
|---|---|---|
| 0.50 | 66/66 / 12 | 66/66 / 12 |
| 0.60 | 66/66 / 12 | 41/66 / 1 |
| 0.70 | 66/66 / 12 | 41/66 / 1 |
| 0.80 | 65/66 / 11 | 41/66 / 1 |
| 0.85 | 65/66 / 11 | 41/66 / 1 |
| 0.90 | 65/66 / 11 | 39/66 / 1 |
| 0.95 | 3/66 / 0 | 34/66 / 1 |

Fitted on all answers (what the app would use):

- with second opinion: namedMargin +1.06, agreement +1.87, specific +1.12, intercept +0.30 (C=3)
- margin only: namedMargin +2.00, specific +1.70, intercept +0.27 (C=10)

Verdict: not written to the app: worse than the current app trust score on Brier (0.1174 vs 0.1163).
