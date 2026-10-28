# Second opinion vs Gemini

Gemini answers that pass the existing vision checks (confidence >= 0.85, margin >= 0.30) in runs v2-multiphoto, v3-subject: 35 answers, 1 wrong (lenient), 4 with the wrong main area (strict). The second opinion's probabilities are out-of-fold (it never saw the photo).

| Disagreement threshold | Flagged | Wrong caught (lenient) | Wrong main area caught (strict) | Right answers flagged (extra adjuster work) |
|---|---|---|---|---|
| second opinion gives Gemini's area < 0.05 | 0/35 | 0/1 | 0/4 | 0 |
| second opinion gives Gemini's area < 0.10 | 2/35 | 0/1 | 1/4 | 2 |
| second opinion gives Gemini's area < 0.15 | 3/35 | 1/1 | 2/4 | 2 |
| second opinion gives Gemini's area < 0.20 | 4/35 | 1/1 | 2/4 | 3 |
| second opinion gives Gemini's area < 0.30 | 12/35 | 1/1 | 2/4 | 11 |

Answers with the wrong main area:

- Car damages 280.png (v2-multiphoto): Gemini SIDE_PANEL, true REAR_BUMPER, second opinion gives SIDE_PANEL 0.05
- Car damages 1153.png (v2-multiphoto): Gemini FRONT_BUMPER, true SIDE_PANEL, second opinion gives FRONT_BUMPER 0.13  **(wrong under both measures)**
- Car damages 870.png (v2-multiphoto): Gemini SIDE_PANEL, true FRONT_BUMPER, second opinion gives SIDE_PANEL 0.47
- Car damages 870.png (v3-subject): Gemini SIDE_PANEL, true FRONT_BUMPER, second opinion gives SIDE_PANEL 0.47
