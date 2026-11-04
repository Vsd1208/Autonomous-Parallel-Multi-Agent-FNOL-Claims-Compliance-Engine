# Trust score and vision checks, strictly out of sample - run v3-subject

66 answers, 54 right, 12 wrong (lenient). 20 repeats of stratified 5-fold. Each figure is one fitted model's result averaged over the repeats. Calibration bins use each score itself. Ranges are 95% bootstrap intervals over the images, pooled across repeats.

| Confidence | Evaluated | AUC | Brier | Calibration error |
|---|---|---|---|---|
| Gemini's own | nothing fitted | 0.57 (0.51-0.66) | 0.150 (0.082-0.223) | 0.090 (0.044-0.177) |
| Trust score, shipped features | **out of sample**: weights and smoothing inside each fold; the feature list was chosen by people who had seen these answers | 0.79 (0.64-0.90) | 0.113 (0.074-0.158) | 0.063 (0.030-0.153) |
| Trust score, features also chosen in each fold | **strictly out of sample**: features, weights and smoothing inside each fold | 0.84 (0.71-0.94) | 0.108 (0.068-0.155) | 0.041 (0.020-0.137) |
| The weights in the app | in sample: fitted on these answers | 0.86 (0.78-0.92) | 0.107 (0.068-0.148) | 0.063 (0.019-0.117) |
| Leave-one-out, as in trust_score.py (self-binned here) | out of sample, with the artefact at `ece` | 0.68 (0.49-0.82) | 0.117 (0.074-0.165) | 0.218 (0.068-0.238) |

Feature set picked inside the training folds: margin + agreement + named area (earlier) x84, named margin + agreement + named area (shipped) x15, named margin + named area x1.

## At the 0.85 confidence factor, mean over the repeats

| Score | Pass | Wrong among them |
|---|---|---|
| Shipped features, out of sample | 41.0/66 | 1.0 |
| Features chosen in each fold, strictly out of sample | 38.5/66 | 1.0 |
| The weights in the app, in sample | 41/66 | 1 |

## Wrong answers that could go straight through

| Vision checks | Pass | Wrong among them | Right answers held back | What saw these answers |
|---|---|---|---|---|
| Gemini's confidence only | 65/66 | 11 | 0 | Nothing fitted |
| All checks at the shipped thresholds (0.30, 0.10) | 32/66 | 1 (rate up to 16%, exact 95%) | 23 | Both thresholds were set after looking at recorded answers, including these |
| Thresholds chosen inside each fold, one wrong through = 2 right held back | 39.5/66 | **4.6** (repeats 3-7) | 19.1 | Nothing from the held-out answers |
| Thresholds chosen inside each fold, one wrong through = 5 right held back | 35.6/66 | **3.0** (repeats 3-3) | 21.4 | Nothing from the held-out answers |
| Thresholds chosen inside each fold, one wrong through = 10 right held back | 35.6/66 | **3.0** (repeats 3-3) | 21.4 | Nothing from the held-out answers |
| Thresholds chosen inside each fold, one wrong through = 20 right held back | 35.6/66 | **3.0** (repeats 3-3) | 21.4 | Nothing from the held-out answers |

Thresholds picked most often (margin, agreement) at each cost: 2: (0.3, 0.0) x66, (0.1, 0.0) x14; 5: (0.3, 0.05) x64, (0.3, 0.0) x16; 10: (0.3, 0.05) x64, (0.3, 0.0) x16; 20: (0.3, 0.05) x64, (0.3, 0.0) x16.

## Sample size

With 12 wrong answers the AUC range is about ±0.13. Ranges shrink with the square root of the sample, so ±0.05 needs about 474 answers at the same 18% wrong rate (rough scaling, not a power calculation). Showing that the wrong-through rate is below 5% takes 72 answers that pass with none wrong (exact two-sided 95%), or 110 with one wrong: about 149 and 227 answers at today's pass rate of 48%.
