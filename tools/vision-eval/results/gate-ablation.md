# Gate ablation

Recorded Gemini answers on labelled car-damage photos (Humans in the Loop, CC0), replayed through the app's vision-related checks one at a time. *Wrong answers let through* would reach straight-through approval on the vision checks alone; *right answers sent to adjuster* is the extra workload the checks cost. Lenient = the zone named is one of the damaged zones; strict = it is the most damaged zone. Coverage, claimed amount, intake flags and the severity gate also apply to real claims and are not replayed here.

## Run `v2-multiphoto` (33 photos, 3 wrong answers, lenient)

| Checks applied | Auto-approvable | Wrong answers let through (lenient / strict) | Right answers sent to adjuster |
|---|---|---|---|
| Confidence factor only (>= 0.85) | 32/33 | 3 / 10 | 1 |
| + AMBIGUOUS_DAMAGE_TYPE (margin >= 0.30) | 25/33 | 1 / 3 | 6 |
| + SEVERITY_ESTIMATE_MISMATCH | 25/33 | 1 / 3 | 6 |

Wrong answers (lenient) and the checks that stop them:

- Car damages 1177.png: answered MULTIPLE, true FRONT_BUMPER, confidence 0.9, margin 0.20 -> AMBIGUOUS_DAMAGE_TYPE
- Car damages 305.png: answered MULTIPLE, true WINDSHIELD, confidence 0.9, margin 0.00 -> AMBIGUOUS_DAMAGE_TYPE
- Car damages 1153.png: answered FRONT_BUMPER, true SIDE_PANEL, confidence 0.9, margin 0.70 -> **not stopped by any vision check**

## Run `v3-subject` (66 photos, 12 wrong answers, lenient)

| Checks applied | Auto-approvable | Wrong answers let through (lenient / strict) | Right answers sent to adjuster |
|---|---|---|---|
| Confidence factor only (>= 0.85) | 65/66 | 11 / 27 | 0 |
| + AMBIGUOUS_DAMAGE_TYPE (margin >= 0.30) | 42/66 | 2 / 4 | 14 |
| + SEVERITY_ESTIMATE_MISMATCH | 42/66 | 2 / 4 | 14 |
| + PHOTO_SUBJECT_MISMATCH (subject = VEHICLE) | 42/66 | 2 / 4 | 14 |

Photo subject reported: VEHICLE 66 (every photo is a car).

Wrong answers (lenient) and the checks that stop them:

- Car damages 764.png: answered MULTIPLE, true SIDE_PANEL, confidence 0.9, margin 0.00 -> AMBIGUOUS_DAMAGE_TYPE
- Car damages 1177.png: answered MULTIPLE, true FRONT_BUMPER, confidence 0.9, margin 0.20 -> AMBIGUOUS_DAMAGE_TYPE
- Car damages 762.png: answered MULTIPLE, true REAR_BUMPER, confidence 0.9, margin 0.00 -> AMBIGUOUS_DAMAGE_TYPE
- Car damages 707.png: answered MULTIPLE, true SIDE_PANEL, confidence 0.9, margin 0.05 -> AMBIGUOUS_DAMAGE_TYPE
- Car damages 305.png: answered MULTIPLE, true WINDSHIELD, confidence 0.9, margin 0.10 -> AMBIGUOUS_DAMAGE_TYPE
- Car damages 1153.png: answered FRONT_BUMPER, true SIDE_PANEL, confidence 0.9, margin 0.70 -> **not stopped by any vision check**
- Car damages 1143.jpg: answered MULTIPLE, true SIDE_PANEL, confidence 0.9, margin 0.10 -> AMBIGUOUS_DAMAGE_TYPE
- Car damages 1336.png: answered MULTIPLE, true SIDE_PANEL, confidence 0.9, margin 0.55 -> **not stopped by any vision check**
- Car damages 900.png: answered MULTIPLE, true REAR_BUMPER, confidence 0.9, margin 0.10 -> AMBIGUOUS_DAMAGE_TYPE
- Car damages 269.png: answered MULTIPLE, true FRONT_BUMPER, confidence 0.9, margin 0.10 -> AMBIGUOUS_DAMAGE_TYPE
- Car damages 1091.jpg: answered MULTIPLE, true FRONT_BUMPER, confidence 0.9, margin 0.05 -> AMBIGUOUS_DAMAGE_TYPE
- Car damages 109.png: answered UNKNOWN, true REAR_BUMPER, confidence 0.7, margin 1.00 -> **not stopped by any vision check**
