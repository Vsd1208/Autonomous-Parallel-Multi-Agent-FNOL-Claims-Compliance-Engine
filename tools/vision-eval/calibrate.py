"""Score a Gemini run against the ground truth and train a confidence calibration map.

"Correct" = the damage zone Gemini named is one of the image's damaged zones (MULTIPLE counts as
correct when two or more zones are damaged). "Strict" = it named the zone with the most damage.

Calibration signal: the gap between Gemini's two highest damage-type probabilities ("probability
margin"). Gemini's self-reported confidence is nearly constant (0.90/0.95) and identical for right and
wrong answers, so it cannot be calibrated into anything useful; the margin separates confident,
specific answers from hedged ones (e.g. MULTIPLE).

Method: isotonic regression (pool-adjacent-violators) from margin to the share of correct answers.
Each block is shrunk toward the overall accuracy (not 50%), so small groups are not dragged far from
what the data says. Quality is measured by leave-one-out: every image is scored by a map fitted
without it. The map is written for the app only if it beats Gemini's raw confidence out of sample.

Usage:  python -P calibrate.py --run thinking0 [--write-app-calibration] [--min-images 50]
Writes: results/report-<run>.md, and with --write-app-calibration
        fnol-agents/src/main/resources/vision-calibration.json
"""
import argparse
import datetime
import json
import os
import statistics
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
DATA = os.path.join(HERE, "data")
RESULTS = os.path.join(HERE, "results")
APP_CALIBRATION = os.path.join(REPO, "fnol-agents", "src", "main", "resources", "vision-calibration.json")
APP_READ_TIMEOUT_S = 8
STP_CONFIDENCE = 0.85
MIN_IMAGES_FOR_APP = 50   # below this the map is too noisy to ship
PRIOR_STRENGTH = 4        # pseudo-images pulling each block toward the overall accuracy
SIGNAL = "probabilityMargin"


def load(run):
    labels = {}
    with open(os.path.join(DATA, "labels.jsonl"), encoding="utf-8") as f:
        for line in f:
            lab = json.loads(line)
            labels[lab["image"]] = lab
    rows = []
    with open(os.path.join(RESULTS, f"{run}.jsonl"), encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            r["label"] = labels[r["image"]]
            rows.append(r)
    return rows


def correctness(pred_zone, label):
    lenient = pred_zone in label["zones"] or (pred_zone == "MULTIPLE" and len(label["zones"]) >= 2)
    strict = pred_zone == label["primaryZone"]
    return lenient, strict


def margin(prediction):
    """Top damage-type probability minus the runner-up (0 when Gemini gave no probabilities).
    Must match VisionConfidenceCalibrator.margin in the app."""
    probs = sorted((float(v) for v in (prediction.get("damageTypeProbabilities") or {}).values()), reverse=True)
    if not probs:
        return 0.0
    return round(probs[0] - (probs[1] if len(probs) > 1 else 0.0), 2)


def fit_isotonic(points, prior_mean, strength=PRIOR_STRENGTH):
    """points: [(score, correct 0/1)] -> step table [{from, to, calibrated, n}], non-decreasing in score."""
    groups = defaultdict(lambda: [0, 0])
    for x, y in points:
        groups[round(x, 2)][0] += y
        groups[round(x, 2)][1] += 1

    def value(s, n):
        return (s + strength * prior_mean) / (n + strength)

    merged = []
    for x, (s, n) in sorted(groups.items()):
        merged.append([x, x, s, n])
        while len(merged) > 1 and value(merged[-2][2], merged[-2][3]) > value(merged[-1][2], merged[-1][3]):
            hi = merged.pop()
            merged[-1][1], merged[-1][2], merged[-1][3] = hi[1], merged[-1][2] + hi[2], merged[-1][3] + hi[3]
    return [{"from": lo, "to": hi, "calibrated": round(value(s, n), 3), "n": n} for lo, hi, s, n in merged]


def apply(table, x):
    """Step function: the last block starting at or below x (the first block below the range)."""
    chosen = table[0]
    for block in table:
        if x >= block["from"]:
            chosen = block
    return chosen["calibrated"]


def apply_block(table, x):
    """Index of the block a score falls into."""
    index = 0
    for i, block in enumerate(table):
        if x >= block["from"]:
            index = i
    return index


def leave_one_out(scored):
    """Calibrated prediction for each image from a map fitted on all the other images."""
    out = []
    for i, s in enumerate(scored):
        others = scored[:i] + scored[i + 1:]
        prior = statistics.mean(o["ok"] for o in others)
        table = fit_isotonic([(o["margin"], o["ok"]) for o in others], prior)
        out.append(apply(table, s["margin"]))
    return out


def ece(pairs, groups):
    """Calibration error measured per signal group (not per narrow prediction bin).

    Leave-one-out predictions inside a group of tied scores are anti-correlated with the held-out
    outcome (holding out a correct answer lowers the group's rate). Fixed-width bins then split one
    group into an "all right" and an "all wrong" bin and report a large, meaningless error, so the
    error is measured on the groups the map actually distinguishes."""
    by_group = defaultdict(list)
    for (p, y), g in zip(pairs, groups):
        by_group[g].append((p, y))
    total = len(pairs)
    return sum(len(items) / total * abs(statistics.mean(p for p, _ in items) - statistics.mean(y for _, y in items))
               for items in by_group.values())


def brier(pairs):
    return statistics.mean((p - y) ** 2 for p, y in pairs)


def auc(pairs):
    """Chance that a correct answer scores higher than a wrong one (0.5 = no information)."""
    pos = [p for p, y in pairs if y]
    neg = [p for p, y in pairs if not y]
    if not pos or not neg:
        return float("nan")
    wins = sum((p > q) + 0.5 * (p == q) for p in pos for q in neg)
    return wins / (len(pos) * len(neg))


def gate(pairs, threshold=STP_CONFIDENCE):
    """(claims passing the confidence factor, of which correct, wrong answers let through)."""
    passed = [y for p, y in pairs if p >= threshold]
    return len(passed), sum(passed), len(passed) - sum(passed)


def fmt_pct(x):
    return "n/a" if x != x else f"{x:.0%}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    ap.add_argument("--write-app-calibration", action="store_true")
    ap.add_argument("--min-images", type=int, default=MIN_IMAGES_FOR_APP,
                    help="refuse to write the app map below this many answered images")
    args = ap.parse_args()

    rows = load(args.run)
    ok_rows = [r for r in rows if "prediction" in r]
    errors = len(rows) - len(ok_rows)
    lines = [f"# Vision evaluation - `{args.run}`", "",
             f"Model `{rows[0]['model']}`, thinking budget {rows[0]['thinkingBudget']}, "
             f"{len(rows)} images ({errors} API errors). Dataset: Humans in the Loop car parts & damages (CC0).", ""]

    scored = []
    for r in ok_rows:
        p = r["prediction"]
        zone = p.get("damageType", "UNKNOWN")
        lenient, strict = correctness(zone, r["label"])
        scored.append({"image": r["image"], "zone": zone, "conf": float(p.get("confidence") or 0),
                       "margin": margin(p), "ok": int(lenient), "strict": int(strict),
                       "truth": r["label"]["primaryZone"], "latency": r.get("latency", 0),
                       "subject": p.get("photoSubject")})
    n = len(scored)
    accuracy = statistics.mean(s["ok"] for s in scored)
    # Every photo in the dataset is a damaged car, so the right photoSubject is always VEHICLE; anything else
    # would raise PHOTO_SUBJECT_MISMATCH on a genuine auto claim (a false alarm).
    with_subject = [s for s in scored if s["subject"]]
    subject_row = (f"| Photo subject reported as VEHICLE (others are false alarms on auto claims) | "
                   f"{sum(s['subject'] == 'VEHICLE' for s in with_subject)}/{len(with_subject)} |"
                   if with_subject else "| Photo subject | not asked by this prompt |")

    lat = sorted(s["latency"] for s in scored)
    over = sum(l > APP_READ_TIMEOUT_S for l in lat)
    lines += ["## Accuracy", "", "| Metric | Value |", "|---|---|",
              f"| Damaged zone named correctly (lenient) | **{accuracy:.1%}** ({sum(s['ok'] for s in scored)}/{n}) |",
              f"| Zone with the most damage named (strict) | {statistics.mean(s['strict'] for s in scored):.1%} |",
              f"| Damage seen at all (not UNKNOWN) | {sum(s['zone'] != 'UNKNOWN' for s in scored) / n:.1%} |",
              subject_row,
              f"| Latency p50 / p95 (includes retries after HTTP 503) | {lat[n // 2]:.1f}s / {lat[max(0, int(n * 0.95) - 1)]:.1f}s |",
              f"| Calls slower than the app's {APP_READ_TIMEOUT_S}s read timeout | {over} ({over / n:.0%}) |", ""]

    lines += ["## Per true zone", "", "| True primary zone | n | Correct | Most common answers |", "|---|---|---|---|"]
    by_truth = defaultdict(list)
    for s in scored:
        by_truth[s["truth"]].append(s)
    for zone, items in sorted(by_truth.items()):
        answers = ", ".join(f"{z} {c}" for z, c in Counter(i["zone"] for i in items).most_common(3))
        lines.append(f"| {zone} | {len(items)} | {statistics.mean(i['ok'] for i in items):.0%} | {answers} |")
    lines.append("")

    lines += ["## Which signal tells right from wrong answers?", "",
              "| Gemini answered | n | Correct (lenient) | Correct (strict) | Self-reported confidence | Probability margin |",
              "|---|---|---|---|---|---|"]
    for label, items in (("a specific zone", [s for s in scored if s["zone"] not in ("MULTIPLE", "UNKNOWN")]),
                         ("MULTIPLE / UNKNOWN", [s for s in scored if s["zone"] in ("MULTIPLE", "UNKNOWN")])):
        if items:
            lines.append(f"| {label} | {len(items)} | {statistics.mean(i['ok'] for i in items):.0%} | "
                         f"{statistics.mean(i['strict'] for i in items):.0%} | "
                         f"{min(i['conf'] for i in items):.2f}-{max(i['conf'] for i in items):.2f} | "
                         f"{min(i['margin'] for i in items):.2f}-{max(i['margin'] for i in items):.2f} |")
    raw_pairs = [(s["conf"], s["ok"]) for s in scored]
    margin_pairs = [(s["margin"], s["ok"]) for s in scored]
    lines += ["", f"Separation (AUC, 0.5 = no information, 1.0 = perfect): self-reported confidence "
                  f"**{auc(raw_pairs):.2f}**, probability margin **{auc(margin_pairs):.2f}**.", ""]

    loo = leave_one_out(scored)
    cal_pairs = [(p, s["ok"]) for p, s in zip(loo, scored)]
    raw_gate, cal_gate = gate(raw_pairs), gate(cal_pairs)
    better = brier(cal_pairs) < brier(raw_pairs)
    table = fit_isotonic(margin_pairs, accuracy)
    raw_groups = [round(s["conf"], 2) for s in scored]                      # what raw confidence distinguishes
    cal_groups = [apply_block(table, s["margin"]) for s in scored]          # what the map distinguishes
    ece_raw, ece_cal = ece(raw_pairs, raw_groups), ece(cal_pairs, cal_groups)
    lines += [f"## Calibration (signal: probability margin; leave-one-out over {n} images)", "",
              "| Probability margin | Calibrated confidence | Images |", "|---|---|---|"]
    lines += [f"| {b['from']:.2f}-{b['to']:.2f} | {b['calibrated']:.2f} | {b['n']} |" for b in table]
    lines += ["", "| Out of sample | Gemini's raw confidence | Calibrated |", "|---|---|---|",
              f"| Calibration error per signal group (lower is better) | {ece_raw:.3f} | {ece_cal:.3f} |",
              f"| Brier score (lower is better) | {brier(raw_pairs):.3f} | {brier(cal_pairs):.3f} |",
              f"| Claims passing the {STP_CONFIDENCE} confidence factor | {raw_gate[0]}/{n} | {cal_gate[0]}/{n} |",
              f"| ...of which Gemini was right | {fmt_pct(raw_gate[1] / raw_gate[0] if raw_gate[0] else float('nan'))} "
              f"| {fmt_pct(cal_gate[1] / cal_gate[0] if cal_gate[0] else float('nan'))} |",
              f"| Wrong answers let through | {raw_gate[2]} | {cal_gate[2]} |", "",
              f"Verdict: calibrated map {'beats' if better else 'does NOT beat'} raw confidence out of sample "
              f"(Brier {brier(cal_pairs):.3f} vs {brier(raw_pairs):.3f}); {n} images "
              f"({'enough' if n >= args.min_images else f'below the {args.min_images}-image minimum'}).", ""]

    os.makedirs(RESULTS, exist_ok=True)
    report = os.path.join(RESULTS, f"report-{args.run}.md")
    with open(report, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    print(f"report: {report}")

    if args.write_app_calibration:
        if n < args.min_images:
            raise SystemExit(f"Only {n} answered images; need at least {args.min_images} before writing the app "
                             "calibration (override with --min-images). Run evaluate.py again; it resumes.")
        if not better:
            raise SystemExit("The calibrated map does not beat Gemini's raw confidence out of sample; not writing it.")
        payload = {
            "description": "Maps the gap between Gemini's two highest damage-type probabilities to the observed share "
                           "of correct damage-zone answers. Fitted by tools/vision-eval/calibrate.py.",
            "signal": SIGNAL, "model": rows[0]["model"], "thinkingBudget": rows[0]["thinkingBudget"],
            "promptSha256": rows[0].get("promptSha256", "unrecorded"),
            "dataset": "Humans in the Loop - Car parts and car damages (CC0 1.0)",
            "images": n, "overallAccuracy": round(accuracy, 3), "createdAt": datetime.date.today().isoformat(),
            "leaveOneOut": {"brier_raw": round(brier(raw_pairs), 3), "brier_calibrated": round(brier(cal_pairs), 3),
                            "ece_raw": round(ece_raw, 3), "ece_calibrated": round(ece_cal, 3),
                            "auc_confidence": round(auc(raw_pairs), 3), "auc_margin": round(auc(margin_pairs), 3)},
            "table": table,
        }
        with open(APP_CALIBRATION, "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2)
            f.write("\n")
        print(f"app calibration written: {APP_CALIBRATION}")


if __name__ == "__main__":
    main()
