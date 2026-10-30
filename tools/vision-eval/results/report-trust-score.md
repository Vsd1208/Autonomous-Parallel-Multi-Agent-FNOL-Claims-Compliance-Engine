# Trust score (calibrated AI confidence) - runs v3-subject

50 answers on 50 distinct photos or claims (50 labelled evaluation photos), 41 right (lenient). Every score is out-of-sample (leave-one-image-out) and the smoothing is chosen inside each training set (nested), so the figures are not flattered by the choice. Lower Brier and calibration error are better; a calibrated score of 0.80 should be right about 80% of the time. Ranges are 95% bootstrap intervals over 2000 resamples of the images.

Adjuster feedback: 0 confirmed decisions used (0 where the adjuster confirmed the AI's damage type); skipped: 1 home claim.

| Confidence used | Separation (AUC) | Brier (95% range) | Calibration error (95% range) |
|---|---|---|---|
| Gemini's self-reported confidence | 0.52 | 0.154 (0.073-0.234) | 0.122 (0.037-0.201) |
| Current app trust score (fixed smoothing C=1) | 0.68 | 0.127 (0.074-0.186) | 0.212 (0.091-0.263) |
| Trust score: margin only (smoothing chosen by cross-validation) | 0.71 | 0.126 (0.073-0.187) | 0.098 (0.049-0.213) |
| Trust score: margin + second opinion (smoothing chosen by cross-validation) | 0.71 | 0.127 (0.074-0.189) | 0.143 (0.057-0.228) |

Smoothing chosen inside the training sets (C, how often): 1 x6, 3 x14, 10 x3, 100 x27. On all data: C=100 with the second opinion, C=100 margin only.

## Risk and coverage

Coverage = answers that would pass the confidence factor at this threshold (could go straight through on the vision checks); wrong = how many of those are wrong.

| Threshold | Gemini raw: coverage / wrong | Trust score (+ second opinion): coverage / wrong |
|---|---|---|
| 0.50 | 50/50 / 9 | 50/50 / 9 |
| 0.60 | 50/50 / 9 | 42/50 / 7 |
| 0.70 | 50/50 / 9 | 34/50 / 4 |
| 0.80 | 50/50 / 9 | 30/50 / 1 |
| 0.85 | 50/50 / 9 | 30/50 / 1 |
| 0.90 | 50/50 / 9 | 30/50 / 1 |
| 0.95 | 2/50 / 0 | 17/50 / 1 |

Fitted on all answers (what the app would use):

- with second opinion: margin -4.40, agreement +7.77, specific +3.60, intercept +0.69 (C=100)
- margin only: margin -3.65, specific +5.61, intercept +0.63 (C=100)

Verdict: not written to the app: negative weight on margin, margin only (a clearer or better-supported answer would get a lower trust score); worse than the current app trust score on Brier (0.1275 vs 0.1274).
