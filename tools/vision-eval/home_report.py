"""Accuracy report for the property prompt on the labelled home photos (prepare_home.py + evaluate.py --line home).

Usage:  python -P home_report.py --run home-v1
Writes: results/report-<run>.md

Measures what matters for a homeowners claim: is the cause of loss right (it decides coverage), is flood caught
(standard homeowners policies exclude it, so the gate must raise POSSIBLE_FLOOD_EXCLUSION), and are the photos
reported as a BUILDING (anything else would raise PHOTO_SUBJECT_MISMATCH on a genuine home claim).
"""
import argparse
import json
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from calibrate import RESULTS, STP_CONFIDENCE, margin

HOME = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "home")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    args = ap.parse_args()
    labels = {}
    with open(os.path.join(HOME, "labels.jsonl"), encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            labels[r["image"]] = r
    rows = []
    if not os.path.exists(os.path.join(RESULTS, f"{args.run}.jsonl")):
        raise SystemExit(f"No results/{args.run}.jsonl yet: run  python -P evaluate.py --line home --run {args.run}")
    with open(os.path.join(RESULTS, f"{args.run}.jsonl"), encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            if "prediction" in r and r["image"] in labels:
                p = r["prediction"]
                rows.append({"truth": labels[r["image"]]["cause"], "answer": p.get("damageType", "UNKNOWN"),
                             "subject": p.get("photoSubject"), "conf": float(p.get("confidence") or 0),
                             "margin": margin(p)})
    if not rows:
        raise SystemExit(f"No answers in results/{args.run}.jsonl yet.")
    n = len(rows)
    right = [r for r in rows if r["answer"] == r["truth"]]
    floods = [r for r in rows if r["truth"] == "FLOOD"]
    false_floods = [r for r in rows if r["truth"] != "FLOOD" and r["answer"] == "FLOOD"]
    building = sum(r["subject"] == "BUILDING" for r in rows)
    confident_wrong = [r for r in rows if r["answer"] != r["truth"] and r["conf"] >= STP_CONFIDENCE and r["margin"] >= 0.30]

    out = [f"# Property prompt evaluation - `{args.run}`", "",
           f"{n} FEMA photos (public domain), cause of loss labelled from the photographer's caption and hand-reviewed "
           "(`prepare_home.py`). One photo per call, labelled `angle: OVERVIEW`.", "",
           "| Metric | Value |", "|---|---|",
           f"| Cause of loss named correctly | **{len(right) / n:.0%}** ({len(right)}/{n}) |",
           f"| Flood caught (would raise POSSIBLE_FLOOD_EXCLUSION) | {sum(r['answer'] == 'FLOOD' for r in floods)}/{len(floods)} |",
           f"| Not flood but answered FLOOD (false exclusion warning) | {len(false_floods)} |",
           f"| Photos reported as BUILDING (others are false PHOTO_SUBJECT_MISMATCH) | {building}/{n} |",
           f"| Wrong answers the vision checks would not stop (confidence >= 0.85, margin >= 0.30) | {len(confident_wrong)} |",
           "", "| True cause | n | Correct | Answers |", "|---|---|---|---|"]
    by_truth = defaultdict(list)
    for r in rows:
        by_truth[r["truth"]].append(r)
    for cause, items in sorted(by_truth.items()):
        answers = ", ".join(f"{k} {v}" for k, v in Counter(i["answer"] for i in items).most_common())
        out.append(f"| {cause} | {len(items)} | {sum(i['answer'] == cause for i in items) / len(items):.0%} | {answers} |")
    out += ["", "Captions describe what the photographer saw, not an adjuster's cause-of-loss finding, so a few labels "
            "may still be arguable (e.g. wind damage next to a fallen tree)."]
    path = os.path.join(RESULTS, f"report-{args.run}.md")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")
    print("\n".join(out))
    print("report:", path)


if __name__ == "__main__":
    main()
