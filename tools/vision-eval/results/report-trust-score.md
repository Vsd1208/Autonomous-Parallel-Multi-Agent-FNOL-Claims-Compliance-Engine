# Trust score (calibrated AI confidence) - runs v3-subject

32 Gemini answers on 32 distinct labelled car photos, 26 right (lenient). Every score is out-of-sample (leave-one-image-out). Lower Brier and calibration error are better; a calibrated score of 0.80 should be right about 80% of the time.

| Confidence used | Separation (AUC) | Brier | Calibration error |
|---|---|---|---|
| Gemini's self-reported confidence | 0.52 | 0.160 | 0.127 |
| Trust score: margin only | 0.64 | 0.138 | 0.180 |
| Trust score: margin + second opinion | 0.65 | 0.136 | 0.181 |

## Risk and coverage

Coverage = answers that would pass the confidence factor at this threshold (could go straight through on the vision checks); wrong = how many of those are wrong.

| Threshold | Gemini raw: coverage / wrong | Trust score (+ second opinion): coverage / wrong |
|---|---|---|
| 0.50 | 32/32 / 6 | 32/32 / 6 |
| 0.60 | 32/32 / 6 | 29/32 / 6 |
| 0.70 | 32/32 / 6 | 22/32 / 2 |
| 0.80 | 32/32 / 6 | 21/32 / 1 |
| 0.85 | 32/32 / 6 | 21/32 / 1 |
| 0.90 | 32/32 / 6 | 14/32 / 1 |
| 0.95 | 1/32 / 0 | 0/32 / 0 |

Fitted on all answers (what the app would use):

- with second opinion: margin +0.68, agreement +0.68, specific +0.95, intercept +0.50
- margin only: margin +0.77, specific +1.07, intercept +0.54

Verdict: not written to the app: 32 images (below 50).
