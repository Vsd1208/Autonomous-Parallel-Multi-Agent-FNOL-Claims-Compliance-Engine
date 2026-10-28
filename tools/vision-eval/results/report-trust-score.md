# Trust score (calibrated AI confidence) - runs v2-multiphoto, v3-subject

48 Gemini answers on 33 distinct labelled car photos, 42 right (lenient). Every score is out-of-sample (leave-one-image-out). Lower Brier and calibration error are better; a calibrated score of 0.80 should be right about 80% of the time.

| Confidence used | Separation (AUC) | Brier | Calibration error |
|---|---|---|---|
| Gemini's self-reported confidence | 0.50 | 0.110 | 0.028 |
| Trust score: margin only | 0.69 | 0.100 | 0.062 |
| Trust score: margin + second opinion | 0.73 | 0.099 | 0.061 |

## Risk and coverage

Coverage = answers that would pass the confidence factor at this threshold (could go straight through on the vision checks); wrong = how many of those are wrong.

| Threshold | Gemini raw: coverage / wrong | Trust score (+ second opinion): coverage / wrong |
|---|---|---|
| 0.50 | 48/48 / 6 | 48/48 / 6 |
| 0.60 | 48/48 / 6 | 48/48 / 6 |
| 0.70 | 48/48 / 6 | 41/48 / 6 |
| 0.80 | 48/48 / 6 | 38/48 / 3 |
| 0.85 | 47/48 / 6 | 36/48 / 1 |
| 0.90 | 47/48 / 6 | 36/48 / 1 |
| 0.95 | 1/48 / 0 | 5/48 / 0 |

Fitted on all answers (what the app would use):

- with second opinion: margin +0.81, agreement +0.75, specific +1.13, intercept +0.68
- margin only: margin +0.91, specific +1.27, intercept +0.73

Verdict: not written to the app: more than one run (mixed prompts); 33 images (below 50).
