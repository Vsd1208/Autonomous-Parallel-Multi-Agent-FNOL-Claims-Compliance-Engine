"""
How Gemini's dollar figures are distributed, from its recorded answers: does it measure, or name a band?

    python -P stock_figures.py [--run v3-subject]

No model calls. Writes results/stock-figures-<run>.md.
"""
import argparse
import collections
import json
import statistics
from pathlib import Path

HERE = Path(__file__).parent


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", default="v3-subject")
    args = ap.parse_args()
    rows = [json.loads(l) for l in (HERE.parent / "vision-eval" / "results" / f"{args.run}.jsonl").read_text("utf-8").splitlines() if l.strip()]
    answers = [r["prediction"] for r in rows if isinstance(r.get("prediction"), dict) and r["prediction"].get("estimatedDamage") is not None]
    values = [a["estimatedDamage"] for a in answers]
    counts = collections.Counter(values)
    top5 = sum(n for _, n in counts.most_common(5))
    lines = [f"# Gemini's dollar figures - run {args.run}", "",
             f"{len(values)} answers with a figure; {len(counts)} distinct values. **{top5 / len(values):.0%} fall on five figures**; "
             f"{sum(1 for v in values if v % 500 == 0) / len(values):.0%} are multiples of $500.", "",
             "| Figure | Answers |", "|---|---|"]
    lines += [f"| ${v:,} | {n} |" for v, n in counts.most_common()]
    lines += ["", "| Severity | Answers | Lowest | Median | Highest | Most common |", "|---|---|---|---|---|---|"]
    by = collections.defaultdict(list)
    for a in answers:
        by[a.get("severity")].append(a["estimatedDamage"])
    for severity, v in sorted(by.items()):
        figure, n = collections.Counter(v).most_common(1)[0]
        lines.append(f"| {severity} | {len(v)} | ${min(v):,} | ${statistics.median(v):,.0f} | ${max(v):,} | ${figure:,} ({n}) |")
    lines += ["", "The figure mostly restates the severity band the prompt defines, so it is shown as a rough figure, not a "
              "measurement. There are no true costs here: this says what the figures look like, not how far off they are."]
    out = HERE / "results" / f"stock-figures-{args.run}.md"
    out.write_text("\n".join(lines) + "\n", "utf-8")
    print("\n".join(lines[:3]))
    print("Wrote", out)


if __name__ == "__main__":
    main()
