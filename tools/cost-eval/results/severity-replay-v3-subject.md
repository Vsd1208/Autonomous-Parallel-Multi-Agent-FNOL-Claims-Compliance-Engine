# Severity gate replay - run v3-subject

66 recorded answers. Severity has no ground truth here, so this shows how routing moves, not which is right. HIGH always goes to a person. The table-based reserve check is not replayed: no auto table value can reach the medium threshold.

| Gate | All answers: LOW / MEDIUM / HIGH | Answers passing the vision checks: LOW / MEDIUM / HIGH |
|---|---|---|
| Old: the AI's dollars | 45 / 21 / 0 | 25 / 7 / 0 |
| Label only | 14 / 34 / 18 | 10 / 16 / 6 |
| New: label, the more severe reading if the second opinion's extent differs | 6 / 36 / 24 | 3 / 19 / 10 |

Label and extent disagree on 34 of 66 answers with an extent. Extent bands: {'MEDIUM': 33, 'SMALL': 19, 'LARGE': 14}. Labels: {'MINOR': 14, 'MODERATE': 34, 'SEVERE': 18}.

Answers passing the vision checks that would no longer pass the gate (HIGH): 10 of 32; wrong among them: 1. Wrong answers still not HIGH: 0.

`SEVERITY_ESTIMATE_MISMATCH` fires on 0 answers with fixed dollar bands, 5 against the published range for the label.
