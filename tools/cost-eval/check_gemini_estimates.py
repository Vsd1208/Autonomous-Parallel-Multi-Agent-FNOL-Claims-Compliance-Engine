"""Replays the published-repair-cost check on Gemini's recorded answers.

For each evaluation photo: Gemini's damaged area and dollar estimate (tools/vision-eval/results/<run>.jsonl), the
on-premise model's out-of-fold visible-extent band (tools/second-opinion/results/oof-auto.jsonl), and the published
range for that area and band (fnol-agents/src/main/resources/repair-cost-ranges.json). The app raises
ESTIMATE_OUTSIDE_PUBLISHED_RANGE when the estimate is below half the range's low end or above twice its high end.

There are no true repair costs for these photos, so this measures how often the check would fire and on what, not
whether Gemini's dollars are right.

Usage:  python -P check_gemini_estimates.py --run v3-subject [--tolerance 3 --no-write]
Writes: results/gemini-estimates-<run>.md
"""
import argparse
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
RANGES = os.path.join(ROOT, "fnol-agents", "src", "main", "resources", "repair-cost-ranges.json")
OOF = os.path.join(ROOT, "tools", "second-opinion", "results", "oof-auto.jsonl")
LABELS = os.path.join(ROOT, "tools", "vision-eval", "data", "labels.jsonl")
TOLERANCE = 2.0          # must match PublishedRepairCosts.TOLERANCE in the app


def published_range(table, area, band):
    bands = table["ranges"].get(area)
    if not bands:
        return None
    if band in bands:
        r = bands[band]
        return r["low"], r["high"]
    return min(b["low"] for b in bands.values()), max(b["high"] for b in bands.values())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    ap.add_argument("--tolerance", type=float, default=TOLERANCE, help="what-if only; the app uses TOLERANCE")
    ap.add_argument("--no-write", action="store_true")
    args = ap.parse_args()
    table = json.load(open(RANGES, encoding="utf-8"))
    oof = {r["image"]: r for r in map(json.loads, open(OOF, encoding="utf-8"))}
    labels = {r["image"]: r for r in map(json.loads, open(LABELS, encoding="utf-8"))}
    rows = [json.loads(l) for l in open(os.path.join(ROOT, "tools", "vision-eval", "results", f"{args.run}.jsonl"),
                                        encoding="utf-8") if l.strip()]
    rows = [r for r in rows if "prediction" in r]

    checked, inside, flagged, skipped = [], 0, [], 0
    for r in rows:
        p = r["prediction"]
        area, est = p.get("damageType"), p.get("estimatedDamage")
        band = oof.get(r["image"], {}).get("extentBand")
        rng = published_range(table, area, band)
        if rng is None or est is None:
            skipped += 1      # MULTIPLE / UNKNOWN / ROOF: no published range for the area
            continue
        low, high = rng
        checked.append((r["image"], area, band, est, low, high))
        if low <= est <= high:
            inside += 1
        if est < low / args.tolerance or est > high * args.tolerance:
            right = area in labels.get(r["image"], {}).get("zones", [])
            flagged.append((r["image"], area, band, est, low, high, right))

    n = len(checked)
    ratio = [math.log(est / math.sqrt(low * high)) for _, _, _, est, low, high in checked]
    lines = [f"# Gemini's dollar estimates vs published repair-cost ranges - run {args.run}", "",
             f"{len(rows)} answers; {n} name an area with a published range (the other {skipped} are MULTIPLE, UNKNOWN "
             "or ROOF and are not checked). Band = the on-premise model's out-of-fold visible-extent band. No true "
             "repair costs exist for these photos: this shows how often the check fires, not who is right.", "",
             "| Measure | Value |", "|---|---|",
             f"| Estimate inside the published range | {inside}/{n} |",
             f"| Flagged (below half the low end or above twice the high end) | {len(flagged)}/{n} |",
             f"| Median estimate / range midpoint (geometric) | {math.exp(sorted(ratio)[n // 2]):.2f}x |" if n else "",
             "", "| Photo | Gemini area | Band | Gemini estimate | Published range | Gemini's area right? |",
             "|---|---|---|---|---|---|"]
    for img, area, band, est, low, high, right in flagged:
        lines.append(f"| {img} | {area} | {band} | ${est:,.0f} | ${low:,.0f}-${high:,.0f} | {'yes' if right else 'no'} |")
    if args.no_write:
        print(f"tolerance {args.tolerance}x: flagged {len(flagged)}/{n}")
        return
    os.makedirs(os.path.join(HERE, "results"), exist_ok=True)
    out = os.path.join(HERE, "results", f"gemini-estimates-{args.run}.md")
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(l for l in lines if l is not None) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    sys.exit(main())
