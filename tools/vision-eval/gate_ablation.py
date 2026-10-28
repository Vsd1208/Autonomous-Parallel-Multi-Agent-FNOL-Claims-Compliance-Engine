"""Gate ablation: replays recorded Gemini answers through the app's vision-related safety checks, adding one at a
time, and counts how many WRONG answers would still reach auto-approval and how many RIGHT answers are sent to an
adjuster (extra workload). No API calls: it reads results/<run>.jsonl from evaluate.py.

Usage:  python -P gate_ablation.py --run v2-multiphoto --run v3-subject
Writes: results/gate-ablation.md

The checks mirror the app (fnol-orchestrator GateConsistencyChecker and the STP confidence factor):
  1. confidence factor     - Gemini's confidence >= 0.85 (the only vision check before the gate existed)
  2. AMBIGUOUS_DAMAGE_TYPE - gap between the two highest damage-type probabilities >= 0.30
  3. SEVERITY_ESTIMATE_MISMATCH - the severity label agrees with the estimate band
  4. PHOTO_SUBJECT_MISMATCH - the photos are reported as a VEHICLE (only runs whose prompt asks for photoSubject)
Only the vision-related part of the STP decision is replayed: coverage, claimed amount, intake flags and the
severity gate are not part of a single-photo evaluation, so real claims face more checks than this.
"""
import argparse
import os
import sys

# -P keeps the current directory off the import path; add this tool's own folder (not the downloaded data).
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from calibrate import RESULTS, STP_CONFIDENCE, correctness, load, margin

MIN_MARGIN = 0.30          # fnol.stp.min-probability-margin
MINOR_MAX, MODERATE_MAX = 2000, 10000


def severity_ok(p):
    est = float(p.get("estimatedDamage") or 0)
    expected = "MINOR" if est < MINOR_MAX else "MODERATE" if est <= MODERATE_MAX else "SEVERE"
    return p.get("severity") in (None, expected)


CHECKS = [
    ("Confidence factor only (>= 0.85)", lambda p: float(p.get("confidence") or 0) >= STP_CONFIDENCE),
    ("+ AMBIGUOUS_DAMAGE_TYPE (margin >= 0.30)", lambda p: margin(p) >= MIN_MARGIN),
    ("+ SEVERITY_ESTIMATE_MISMATCH", severity_ok),
    ("+ PHOTO_SUBJECT_MISMATCH (subject = VEHICLE)", lambda p: p.get("photoSubject") in (None, "VEHICLE")),
]


def table(rows, asks_subject):
    checks = CHECKS if asks_subject else CHECKS[:3]
    lines = ["| Checks applied | Auto-approvable | Wrong answers let through (lenient / strict) "
             "| Right answers sent to adjuster |", "|---|---|---|---|"]
    for k in range(1, len(checks) + 1):
        active = checks[:k]
        passed = [r for r in rows if all(check(r["p"]) for _, check in active)]
        held = [r for r in rows if r not in passed]
        wrong_l = sum(not r["lenient"] for r in passed)
        wrong_s = sum(not r["strict"] for r in passed)
        right_held = sum(r["lenient"] for r in held)
        lines.append(f"| {active[-1][0]} | {len(passed)}/{len(rows)} | {wrong_l} / {wrong_s} | {right_held} |")
    return lines


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", action="append", required=True, help="evaluation run(s), e.g. v2-multiphoto")
    args = ap.parse_args()

    out = ["# Gate ablation", "",
           "Recorded Gemini answers on labelled car-damage photos (Humans in the Loop, CC0), replayed through the "
           "app's vision-related checks one at a time. *Wrong answers let through* would reach straight-through "
           "approval on the vision checks alone; *right answers sent to adjuster* is the extra workload the checks "
           "cost. Lenient = the zone named is one of the damaged zones; strict = it is the most damaged zone. "
           "Coverage, claimed amount, intake flags and the severity gate also apply to real claims and are not "
           "replayed here.", ""]
    for run in args.run:
        rows = []
        for r in load(run):
            if "prediction" not in r:
                continue
            p = r["prediction"]
            lenient, strict = correctness(p.get("damageType", "UNKNOWN"), r["label"])
            rows.append({"image": r["image"], "p": p, "lenient": lenient, "strict": strict, "label": r["label"]})
        asks_subject = any("photoSubject" in r["p"] for r in rows)
        wrong = sum(not r["lenient"] for r in rows)
        out += [f"## Run `{run}` ({len(rows)} photos, {wrong} wrong answers, lenient)", ""]
        out += table(rows, asks_subject)
        if asks_subject:
            subjects = {}
            for r in rows:
                subjects[r["p"].get("photoSubject")] = subjects.get(r["p"].get("photoSubject"), 0) + 1
            out += ["", "Photo subject reported: " + ", ".join(f"{k} {v}" for k, v in sorted(subjects.items(), key=str))
                    + " (every photo is a car)."]
        caught = [r for r in rows if not r["lenient"]]
        if caught:
            out += ["", "Wrong answers (lenient) and the checks that stop them:", ""]
            for r in caught:
                p = r["p"]
                stops = [name.lstrip("+ ").split(" (")[0] for name, check in CHECKS[1:] if not check(p)]
                out.append(f"- {r['image']}: answered {p.get('damageType')}, true {r['label']['primaryZone']}, "
                           f"confidence {p.get('confidence')}, margin {margin(p):.2f} -> "
                           + (", ".join(stops) if stops else "**not stopped by any vision check**"))
        out.append("")
    path = os.path.join(RESULTS, "gate-ablation.md")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out))
    print("\n".join(out))
    print("report:", path)


if __name__ == "__main__":
    main()
