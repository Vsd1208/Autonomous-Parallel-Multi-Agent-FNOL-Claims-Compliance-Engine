"""The recorded Gemini answers through the severity gate, old and new (ADR-016). No API calls.

Old: LOW / MEDIUM / HIGH from the AI's dollar figure. New: from the severity label, the more severe reading when the second
opinion's visible extent disagrees, and HIGH when the table-based reserve is above the medium threshold. Severity has no ground
truth in these photos, so this shows how routing moves, not which reading is right.

Usage:  python -P severity_replay.py --run v3-subject
Writes: results/severity-replay-<run>.md
"""
import argparse
import json
import os
import sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.join(HERE, "..", "..")
sys.path.insert(0, os.path.join(REPO, "tools", "vision-eval"))
from calibrate import STP_CONFIDENCE, correctness, load, margin  # noqa: E402

LOW_MAX, MEDIUM_MAX = 5000, 20000               # the old gate; MEDIUM_MAX is still the reserve check (fnol.stp.*)
LABEL = {"MINOR": "LOW", "MODERATE": "MEDIUM", "SEVERE": "HIGH"}
EXTENT = {"SMALL": "LOW", "MEDIUM": "MEDIUM", "LARGE": "HIGH"}
ORDER = ["LOW", "MEDIUM", "HIGH"]
BAND_FOR_LABEL = {"MINOR": "SMALL", "MODERATE": "MEDIUM", "SEVERE": "LARGE"}
TOLERANCE = 2.0                                 # RepairCostEstimator.TOLERANCE
SPECIFIC = {"FRONT_BUMPER", "REAR_BUMPER", "SIDE_PANEL", "WINDSHIELD", "ROOF"}


def old_gate(est):
    return "LOW" if est <= LOW_MAX else "MEDIUM" if est <= MEDIUM_MAX else "HIGH"


def new_gate(label, band):
    if label not in LABEL:
        return "HIGH"
    a = LABEL[label]
    b = EXTENT.get(band, a)
    return max(a, b, key=ORDER.index)


def old_mismatch(p):
    est = float(p.get("estimatedDamage") or 0)
    expected = "MINOR" if est < 2000 else "MODERATE" if est <= 10000 else "SEVERE"
    return p.get("severity") not in (None, expected)


def new_mismatch(p, ranges):
    cell = ranges.get(p.get("damageType"), {}).get(BAND_FOR_LABEL.get(p.get("severity")), None)
    if cell is None:
        return False
    est = float(p.get("estimatedDamage") or 0)
    return est < cell["low"] / TOLERANCE or est > cell["high"] * TOLERANCE


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", default="v3-subject")
    args = ap.parse_args()
    with open(os.path.join(REPO, "fnol-agents", "src", "main", "resources", "repair-cost-ranges.json"), encoding="utf-8") as f:
        ranges = json.load(f)["ranges"]
    second = {}
    with open(os.path.join(REPO, "tools", "second-opinion", "results", "oof-auto.jsonl"), encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            second[r["image"]] = r
    rows = []
    for r in load(args.run):
        p = r.get("prediction")
        if not p:
            continue
        s = second.get(r["image"], {})
        zone = p.get("damageType", "UNKNOWN")
        agreement = s.get("probabilities", {}).get(zone, 0.0)
        est = float(p.get("estimatedDamage") or 0)
        band = s.get("extentBand")
        passes = (float(p.get("confidence") or 0) >= STP_CONFIDENCE and margin(p) >= 0.30 and not new_mismatch(p, ranges)
                  and (zone not in SPECIFIC or agreement >= 0.10))
        rows.append({"label": p.get("severity"), "band": band, "old": old_gate(est), "labelOnly": LABEL.get(p.get("severity"), "HIGH"),
                     "new": new_gate(p.get("severity"), band), "passes": passes, "right": correctness(zone, r["label"])[0],
                     "oldFlag": old_mismatch(p), "newFlag": new_mismatch(p, ranges)})

    def table(subset, key):
        c = Counter(r[key] for r in subset)
        return " / ".join(str(c[k]) for k in ORDER)

    passing = [r for r in rows if r["passes"]]
    out = [f"# Severity gate replay - run {args.run}", "",
           f"{len(rows)} recorded answers. Severity has no ground truth here, so this shows how routing moves, not which is right. "
           "HIGH always goes to a person. The table-based reserve check is not replayed: no auto table value can reach the medium threshold.", "",
           "| Gate | All answers: LOW / MEDIUM / HIGH | Answers passing the vision checks: LOW / MEDIUM / HIGH |", "|---|---|---|",
           f"| Old: the AI's dollars | {table(rows, 'old')} | {table(passing, 'old')} |",
           f"| Label only | {table(rows, 'labelOnly')} | {table(passing, 'labelOnly')} |",
           f"| New: label, the more severe reading if the second opinion's extent differs | {table(rows, 'new')} | {table(passing, 'new')} |", "",
           f"Label and extent disagree on {sum(LABEL.get(r['label']) != EXTENT.get(r['band']) for r in rows if r['band'])} of "
           f"{sum(1 for r in rows if r['band'])} answers with an extent. Extent bands: {dict(Counter(r['band'] for r in rows))}. "
           f"Labels: {dict(Counter(r['label'] for r in rows))}.", "",
           f"Answers passing the vision checks that would no longer pass the gate (HIGH): {sum(r['new'] == 'HIGH' for r in passing)} of {len(passing)}; "
           f"wrong among them: {sum(r['new'] == 'HIGH' and not r['right'] for r in passing)}. Wrong answers still not HIGH: "
           f"{sum(r['new'] != 'HIGH' and not r['right'] for r in passing)}.", "",
           f"`SEVERITY_ESTIMATE_MISMATCH` fires on {sum(r['oldFlag'] for r in rows)} answers with fixed dollar bands, "
           f"{sum(r['newFlag'] for r in rows)} against the published range for the label."]
    path = os.path.join(HERE, "results", f"severity-replay-{args.run}.md")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")
    print("\n".join(out))


if __name__ == "__main__":
    main()
