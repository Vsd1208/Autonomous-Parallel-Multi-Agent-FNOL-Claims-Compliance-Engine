# Second opinion vs Gemini

Gemini answers that pass the existing vision checks (confidence >= 0.85, margin >= 0.30) in runs v2-multiphoto, v3-subject: 66 answers, 2 wrong (lenient), 6 with the wrong main area (strict). The second opinion's probabilities are out-of-fold (it never saw the photo).

| Disagreement threshold | Flagged | Wrong caught (lenient) | Wrong main area caught (strict) | Right answers flagged (extra adjuster work) |
|---|---|---|---|---|
| second opinion gives Gemini's area < 0.05 | 0/66 | 0/2 | 0/6 | 0 |
| second opinion gives Gemini's area < 0.10 | 4/66 | 0/2 | 2/6 | 4 |
| second opinion gives Gemini's area < 0.15 | 6/66 | 2/2 | 4/6 | 4 |
| second opinion gives Gemini's area < 0.20 | 7/66 | 2/2 | 4/6 | 5 |
| second opinion gives Gemini's area < 0.30 | 19/66 | 2/2 | 4/6 | 17 |

Answers with the wrong main area:

- Car damages 280.png (v2-multiphoto): Gemini SIDE_PANEL, true REAR_BUMPER, second opinion gives SIDE_PANEL 0.05
- Car damages 280.png (v3-subject): Gemini SIDE_PANEL, true REAR_BUMPER, second opinion gives SIDE_PANEL 0.05
- Car damages 1153.png (v2-multiphoto): Gemini FRONT_BUMPER, true SIDE_PANEL, second opinion gives FRONT_BUMPER 0.13  **(wrong under both measures)**
- Car damages 1153.png (v3-subject): Gemini FRONT_BUMPER, true SIDE_PANEL, second opinion gives FRONT_BUMPER 0.13  **(wrong under both measures)**
- Car damages 870.png (v2-multiphoto): Gemini SIDE_PANEL, true FRONT_BUMPER, second opinion gives SIDE_PANEL 0.47
- Car damages 870.png (v3-subject): Gemini SIDE_PANEL, true FRONT_BUMPER, second opinion gives SIDE_PANEL 0.47
