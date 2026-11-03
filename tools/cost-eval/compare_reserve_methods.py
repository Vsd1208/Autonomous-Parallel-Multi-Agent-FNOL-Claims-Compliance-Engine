"""
Replays Gemini's recorded answers through both reserve methods, and compares each with the published repair-cost range.

    python -P compare_reserve_methods.py [--run v3-subject] [--no-write]

No model calls. The app's rules are repeated here (FNOLOrchestrator.reserveAdjustment, ReserveCalculatorAgent) and the tables
are the app's own files. Compared before any claimed amount, deductible or limit: the recorded photos have none.
The published ranges are themselves estimates, and which range applies is this project's mapping, so this shows how far
each method is from those ranges. It does not show which method is closer to true repair costs: no true costs exist.
"""
import argparse
import json
import math
import os
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
RES = os.path.join(ROOT, "fnol-agents", "src", "main", "resources")
OOF = os.path.join(ROOT, "tools", "second-opinion", "results", "oof-auto.jsonl")
SPECIFIC = {"FRONT_BUMPER", "REAR_BUMPER", "SIDE_PANEL", "WINDSHIELD", "ROOF"}
TOLERANCE = 2.0          # RepairCostEstimator.TOLERANCE
CONFIDENCE = 0.85        # StpProperties.confidence
AGREEMENT_MIN = 0.10     # StpProperties.secondOpinionMinProbability
ADJUSTMENT_MAX = 0.40    # fnol.reserve.ai-adjustment-max


def load(name):
    return json.load(open(os.path.join(RES, name), encoding="utf-8"))


def margin(probabilities):
    top = sorted((float(v) for v in (probabilities or {}).values()), reverse=True)
    return top[0] - top[1] if len(top) > 1 else (top[0] if top else 0.0)


def rng(table, area, band):
    bands = table["ranges"].get(area)
    if not bands:
        return None
    if band in bands:
        return bands[band]["low"], bands[band]["high"]
    return min(b["low"] for b in bands.values()), max(b["high"] for b in bands.values())


def trust(model, m, agreement, specific):
    """The app's TrustScorer: a logistic regression over namedMargin, agreement and specific."""
    coef = dict(zip(model["features"], model["coef"]))
    z = model["intercept"] + coef.get("namedMargin", 0) * (m if specific else 0) \
        + coef.get("agreement", 0) * (agreement if specific else 0) + coef.get("specific", 0) * (1 if specific else 0)
    return 1 / (1 + math.exp(-z))


def severity_band(estimate):
    return "MINOR" if estimate < 2000 else "MODERATE" if estimate <= 10000 else "SEVERE"


def summarise(values, mids, ranges):
    ratios = [v / m for v, m in zip(values, mids)]
    errors = [abs(math.log(r)) for r in ratios]
    return {"median_ratio": statistics.median(ratios), "median_factor_off": math.exp(statistics.median(errors)),
            "p90_factor_off": math.exp(sorted(errors)[int(len(errors) * 0.9)]),
            "inside": sum(1 for v, (lo, hi) in zip(values, ranges) if lo <= v <= hi)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", default="v3-subject")
    ap.add_argument("--no-write", action="store_true")
    args = ap.parse_args()
    ranges, table = load("repair-cost-ranges.json"), load("initial-reserves.json")["reserves"]["AUTO"]["COLLISION"]
    score = load("trust-score.json")
    model = score["withSecondOpinion"]
    min_margin = 0.30  # StpProperties.minProbabilityMargin
    oof = {r["image"]: r for r in map(json.loads, open(OOF, encoding="utf-8"))}
    rows = [json.loads(l) for l in open(os.path.join(ROOT, "tools", "vision-eval", "results", f"{args.run}.jsonl"), encoding="utf-8") if l.strip()]
    answers = [r for r in rows if isinstance(r.get("prediction"), dict) and r["prediction"].get("estimatedDamage") is not None]

    old, new, mids, spans, allowed, blocked, moved = [], [], [], [], 0, {}, []
    no_range = 0
    for r in answers:
        p = r["prediction"]
        area, severity, estimate = p.get("damageType"), p.get("severity"), float(p["estimatedDamage"])
        band = oof.get(r["image"], {}).get("extentBand")
        span = rng(ranges, area, band)
        if span is None or severity not in table:
            no_range += 1
            continue
        probs = oof.get(r["image"], {}).get("damageProbabilities") or oof.get(r["image"], {}).get("probabilities") or {}
        agreement = float(probs.get(area, 0)) if probs else None
        m = margin(p.get("damageTypeProbabilities"))
        confidence = round(trust(model, m, agreement or 0, area in SPECIFIC), 2)

        why = None
        if confidence < CONFIDENCE:
            why = "trust score"
        elif severity != severity_band(estimate):
            why = "severity label and figure disagree"
        elif estimate < span[0] / TOLERANCE or estimate > span[1] * TOLERANCE:
            why = "figure outside the published range"
        elif agreement is not None and agreement < AGREEMENT_MIN:
            why = "the models disagree"
        elif m < min_margin:
            why = "ambiguous damage type"

        base = float(table[severity]["amount"])
        value = base
        if why is None:
            allowed += 1
            signal = min(max(estimate, span[0]), span[1])
            value = min(max(signal, base * (1 - ADJUSTMENT_MAX)), base * (1 + ADJUSTMENT_MAX))
            moved.append(abs(value - base) > 0.5)
        else:
            blocked[why] = blocked.get(why, 0) + 1
        old.append(estimate)
        new.append(value)
        spans.append(span)
        mids.append(math.sqrt(span[0] * span[1]))

    a, b = summarise(old, mids, spans), summarise(new, mids, spans)
    n = len(old)
    lines = [f"# Reserve methods compared with the published repair-cost ranges - run {args.run}", "",
             f"{len(answers)} recorded Gemini answers; {n} name an area and severity with a range and a table row ({no_range} do not). "
             "Before any claimed amount, deductible or limit, on the COLLISION rows.", "",
             "| Measure | AI_ESTIMATE (old) | AVERAGE_TABLE (new) |", "|---|---|---|",
             f"| Median figure / range midpoint | {a['median_ratio']:.2f}x | {b['median_ratio']:.2f}x |",
             f"| Median distance from the midpoint (a factor, either way) | {a['median_factor_off']:.2f}x | {b['median_factor_off']:.2f}x |",
             f"| 90th percentile distance | {a['p90_factor_off']:.2f}x | {b['p90_factor_off']:.2f}x |",
             f"| Inside the published range | {a['inside']}/{n} | {b['inside']}/{n} |", "",
             f"AI adjustment allowed on {allowed} of {n}, and it moved the base on {sum(moved)}. Blocked on {n - allowed}: "
             + (", ".join(f"{k} {v}" for k, v in sorted(blocked.items(), key=lambda kv: -kv[1])) or "none") + ".", "",
             "**Caveat.** The ranges are themselves estimates, and the band mapping is this project's assumption. A closer figure "
             "here means closer to those ranges, not closer to the true cost: no true costs exist for these photos. The table's "
             "rows also come from the same sources as the ranges, so some closeness is built in."]
    print("\n".join(lines))
    if not args.no_write:
        out = os.path.join(HERE, "results", f"reserve-methods-{args.run}.md")
        with open(out, "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    sys.exit(main())
