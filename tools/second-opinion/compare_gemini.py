"""Does the second opinion catch Gemini's mistakes? Joins Gemini's recorded answers (tools/vision-eval results) with
the second-opinion model's out-of-fold probabilities for the same photos, and replays a MODEL_DISAGREEMENT check:
Gemini names a specific area Z, and the second opinion gives Z a probability below a threshold.

Only answers that already pass the existing vision checks (confidence >= 0.85 and probability margin >= 0.30) are
counted, because those are the ones that would otherwise reach auto-approval.

Usage:  python -P compare_gemini.py --run v2-multiphoto --run v3-subject
Writes: results/disagreement.md
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
EVAL = os.path.join(HERE, "..", "vision-eval")
sys.path.insert(0, EVAL)
from calibrate import STP_CONFIDENCE, correctness, load, margin   # noqa: E402

THRESHOLDS = [0.05, 0.10, 0.15, 0.20, 0.30]
SPECIFIC = {"FRONT_BUMPER", "REAR_BUMPER", "SIDE_PANEL", "WINDSHIELD"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", action="append", required=True)
    args = ap.parse_args()
    oof = {}
    with open(os.path.join(HERE, "results", "oof-auto.jsonl"), encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            oof[r["image"]] = r["probabilities"]

    rows = []
    for run in args.run:
        for r in load(run):
            p = r.get("prediction")
            if not p or r["image"] not in oof:
                continue
            zone = p.get("damageType", "UNKNOWN")
            if float(p.get("confidence") or 0) < STP_CONFIDENCE or margin(p) < 0.30 or zone not in SPECIFIC:
                continue   # already sent to an adjuster by the existing checks
            lenient, strict = correctness(zone, r["label"])
            rows.append({"run": run, "image": r["image"], "zone": zone, "truth": r["label"]["primaryZone"],
                         "lenient": lenient, "strict": strict, "second": oof[r["image"]].get(zone, 0.0)})

    wrong_l = [r for r in rows if not r["lenient"]]
    wrong_s = [r for r in rows if not r["strict"]]
    out = ["# Second opinion vs Gemini", "",
           f"Gemini answers that pass the existing vision checks (confidence >= {STP_CONFIDENCE}, margin >= 0.30) in "
           f"runs {', '.join(args.run)}: {len(rows)} answers, {len(wrong_l)} wrong (lenient), {len(wrong_s)} with the "
           "wrong main area (strict). The second opinion's probabilities are out-of-fold (it never saw the photo).", "",
           "| Disagreement threshold | Flagged | Wrong caught (lenient) | Wrong main area caught (strict) | Right answers flagged (extra adjuster work) |",
           "|---|---|---|---|---|"]
    for t in THRESHOLDS:
        flagged = [r for r in rows if r["second"] < t]
        out.append(f"| second opinion gives Gemini's area < {t:.2f} | {len(flagged)}/{len(rows)} | "
                   f"{sum(not r['lenient'] for r in flagged)}/{len(wrong_l)} | {sum(not r['strict'] for r in flagged)}/{len(wrong_s)} | "
                   f"{sum(r['lenient'] for r in flagged)} |")
    out += ["", "Answers with the wrong main area:", ""]
    for r in sorted(wrong_s, key=lambda r: r["second"]):
        out.append(f"- {r['image']} ({r['run']}): Gemini {r['zone']}, true {r['truth']}, second opinion gives "
                   f"{r['zone']} {r['second']:.2f}" + ("" if r["lenient"] else "  **(wrong under both measures)**"))
    path = os.path.join(HERE, "results", "disagreement.md")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")
    print("\n".join(out))


if __name__ == "__main__":
    main()
