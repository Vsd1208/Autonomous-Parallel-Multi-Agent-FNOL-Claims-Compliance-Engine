"""Claim-level evaluation of recorded Gemini answers: one report with every measure the brief for a pilot would ask, and a plain statement of
those the data cannot give.

No API calls: it reads a recorded run. Every recorded answer here is one photo from a public dataset, so **each photo stands in for a
claim** until labelled multi-photo claims exist. A manifest with `claimId` on each row is grouped into real claims by `to_claims`.

Usage:  python -P claim_eval.py --run v3-subject
Writes: results/report-claim-level-<run>.md, results/reliability-<run>.svg
"""
import argparse
import json
import math
import os
import sys
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
RESULTS = os.path.join(HERE, "results")
DATA = os.path.join(HERE, "data")
APP_READ_TIMEOUT_S = 8
ZONES = ["FRONT_BUMPER", "REAR_BUMPER", "SIDE_PANEL", "WINDSHIELD", "ROOF", "MULTIPLE", "UNKNOWN"]
# Standard paid tier, per million tokens, read from https://aistudio.google.com/docs/pricing on 4 Nov 2026.
PRICE_PER_MILLION = {"gemini-2.5-flash": (0.30, 2.50)}
PHOTOS_PER_CLAIM = 5


def to_claims(rows):
    """Groups a run's rows by claimId (each row its own claim when absent). The claim's answer is its most confident row's."""
    claims = defaultdict(list)
    for i, r in enumerate(rows):
        claims[r.get("claimId", f"photo-{i}")].append(r)
    out = []
    for claim_id, members in claims.items():
        answered = [m for m in members if m.get("prediction")]
        best = max(answered, key=lambda m: float(m["prediction"].get("confidence") or 0)) if answered else None
        out.append({"claimId": claim_id, "photos": len(members), "answer": best, "label": members[0]["label"]})
    return out


def per_class(truth, predicted, classes):
    """Precision, recall and F1 for each class; a class with nothing to divide by has None, not zero."""
    out = {}
    for c in classes:
        tp = sum(t == c and p == c for t, p in zip(truth, predicted))
        fp = sum(t != c and p == c for t, p in zip(truth, predicted))
        fn = sum(t == c and p != c for t, p in zip(truth, predicted))
        precision = tp / (tp + fp) if tp + fp else None
        recall = tp / (tp + fn) if tp + fn else None
        f1 = 2 * precision * recall / (precision + recall) if precision and recall else (0.0 if precision == 0 or recall == 0 else None)
        out[c] = {"support": tp + fn, "predicted": tp + fp, "tp": tp, "precision": precision, "recall": recall, "f1": f1,
                  "missRate": fn / (tp + fn) if tp + fn else None}
    return out


def confusion(truth, predicted, rows, columns):
    counts = Counter(zip(truth, predicted))
    return [[counts[(r, c)] for c in columns] for r in rows]


def brier(confidence, correct):
    return sum((c - y) ** 2 for c, y in zip(confidence, correct)) / len(correct)


def reliability(confidence, correct, bins=5):
    """Equal-width bins: (low, high, n, mean confidence, share right). Empty bins are skipped: no invented points."""
    out = []
    for b in range(bins):
        lo, hi = b / bins, (b + 1) / bins
        inside = [(c, y) for c, y in zip(confidence, correct) if (lo < c <= hi) or (b == 0 and c == 0)]
        if inside:
            out.append((lo, hi, len(inside), sum(c for c, _ in inside) / len(inside), sum(y for _, y in inside) / len(inside)))
    return out


def ece(confidence, correct, bins=5):
    return sum(n / len(correct) * abs(acc - mean) for _, _, n, mean, acc in reliability(confidence, correct, bins))


def wilson(k, n, z=1.96):
    """95% Wilson interval for a share."""
    if not n:
        return None
    p = k / n
    d = 1 + z * z / n
    mid = (p + z * z / (2 * n)) / d
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return max(0.0, mid - half), min(1.0, mid + half)


def percentile(values, q):
    """Nearest-rank percentile."""
    s = sorted(values)
    return s[max(0, min(len(s) - 1, math.ceil(q / 100 * len(s)) - 1))]


def reliability_svg(series, width=420, height=420, pad=46):
    """A reliability diagram: mean confidence against the share right, with the diagonal, and each point sized by its count."""
    size = width - 2 * pad
    x = lambda v: pad + v * size
    y = lambda v: height - pad - v * size
    colours = ["#2563eb", "#d97706", "#059669", "#7c3aed"]
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height + 24 * len(series)}" font-family="sans-serif" font-size="12">',
             f'<rect width="{width}" height="{height + 24 * len(series)}" fill="#ffffff"/>',
             f'<line x1="{x(0)}" y1="{y(0)}" x2="{x(1)}" y2="{y(1)}" stroke="#9ca3af" stroke-dasharray="4 4"/>',
             f'<rect x="{x(0)}" y="{y(1)}" width="{size}" height="{size}" fill="none" stroke="#6b7280"/>',
             f'<text x="{width / 2}" y="{height - 10}" text-anchor="middle">Stated confidence</text>',
             f'<text x="14" y="{height / 2}" text-anchor="middle" transform="rotate(-90 14 {height / 2})">Share actually right</text>']
    for t in (0, 0.5, 1):
        parts.append(f'<text x="{x(t)}" y="{y(0) + 16}" text-anchor="middle">{t}</text>')
        parts.append(f'<text x="{x(0) - 6}" y="{y(t) + 4}" text-anchor="end">{t}</text>')
    for k, (name, points) in enumerate(series):
        colour = colours[k % len(colours)]
        coords = " ".join(f"{x(m):.1f},{y(a):.1f}" for _, _, _, m, a in points)
        parts.append(f'<polyline points="{coords}" fill="none" stroke="{colour}"/>')
        for _, _, n, m, a in points:
            parts.append(f'<circle cx="{x(m):.1f}" cy="{y(a):.1f}" r="{2 + math.sqrt(n):.1f}" fill="{colour}" fill-opacity="0.6"><title>{name}: n={n}</title></circle>')
        parts.append(f'<circle cx="{pad}" cy="{height + 8 + 24 * k}" r="5" fill="{colour}"/><text x="{pad + 12}" y="{height + 12 + 24 * k}">{name} (point size shows the count)</text>')
    parts.append("</svg>")
    return "\n".join(parts)


def sharpness(path):
    """Variance of the Laplacian on a grey copy: a measure from the pixels, unlike the model's own photoQuality."""
    import cv2
    img = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    return None if img is None else float(cv2.Laplacian(img, cv2.CV_64F).var())


def fmt(v):
    return "–" if v is None else f"{v:.1%}"


def load(run):
    labels = {}
    with open(os.path.join(DATA, "labels.jsonl"), encoding="utf-8") as f:
        for line in f:
            lab = json.loads(line)
            labels[lab["image"]] = lab
    with open(os.path.join(RESULTS, f"{run}.jsonl"), encoding="utf-8") as f:
        rows = [json.loads(line) for line in f]
    for r in rows:
        r["label"] = labels[r["image"]]
    return rows


def report(run, rows):
    claims = to_claims(rows)
    answered = [c for c in claims if c["answer"]]
    errors = [r for r in rows if r.get("error")]
    retired = [r for r in errors if "no longer available" in r["error"]]
    other_errors = [r for r in errors if r not in retired]
    predicted = [c["answer"]["prediction"].get("damageType", "UNKNOWN") for c in answered]
    truth = [c["label"]["primaryZone"] for c in answered]
    strict = [p == t for p, t in zip(predicted, truth)]
    lenient = [p in c["label"]["zones"] or (p == "MULTIPLE" and len(c["label"]["zones"]) >= 2) for p, c in zip(predicted, answered)]
    n = len(answered)
    confidence = [float(c["answer"]["prediction"].get("confidence") or 0) for c in answered]
    latencies = [c["answer"]["latency"] for c in answered if c["answer"].get("latency") is not None]
    tokens_in = [c["answer"]["usage"].get("promptTokenCount", 0) for c in answered]
    tokens_out = [c["answer"]["usage"].get("candidatesTokenCount", 0) + c["answer"]["usage"].get("thoughtsTokenCount", 0) for c in answered]
    image_tokens = [next((d["tokenCount"] for d in c["answer"]["usage"].get("promptTokensDetails", []) if d.get("modality") == "IMAGE"), 0) for c in answered]
    model = answered[0]["answer"]["model"]

    lines = [f"# Claim-level evaluation: run `{run}`", "",
             f"Model `{model}`, prompt `{answered[0]['answer']['promptSha256']}`. Command: `python -P claim_eval.py --run {run}`. No API calls.", "",
             f"**Each photo stands in for a claim.** A real claim sends five photos together; no labelled multi-photo claims exist. "
             f"Public car-damage photos, vehicle only, all answers on one prompt. Read as a prototype measurement, not production performance.", "",
             "## Outcome of every call", "", "| Outcome | Count |", "|---|---|",
             f"| Answered | {n} |", f"| Failed after retries (timeout, disconnect, server error) | {len(other_errors)} |",
             f"| Refused: the provider had closed this model to this key | {len(retired)} |", "",
             "Refusals are excluded from the failure rate below because they measure the model's retirement, not its reliability. They are the "
             "most serious operational finding here: see `docs/AI_MODEL_EVALUATION.md`.", "",
             "## Accuracy", "", "| Measure | Value | 95% range (Wilson) |", "|---|---|---|"]
    for name, hits in (("Main damaged area right (strict)", strict), ("One of the damaged areas right (lenient)", lenient)):
        lo, hi = wilson(sum(hits), n)
        lines.append(f"| {name} | {sum(hits)}/{n} = {sum(hits) / n:.1%} | {lo:.1%}–{hi:.1%} |")
    lines += ["", "## Per class (strict, against the main damaged area)", "",
              "| Area | Photos | Predicted | Precision | Recall | F1 | Missed (false-negative rate) |", "|---|---|---|---|---|---|---|"]
    classes = per_class(truth, predicted, ZONES)
    for c in ZONES:
        m = classes[c]
        if m["support"] or m["predicted"]:
            lines.append(f"| {c} | {m['support']} | {m['predicted']} | {fmt(m['precision'])} | {fmt(m['recall'])} | {fmt(m['f1'])} | {fmt(m['missRate'])} |")
    labelled = sorted({*truth})
    used = [z for z in ZONES if z in set(predicted) | set(truth)]
    lines += ["", "No label says MULTIPLE or UNKNOWN, so those columns count as wrong under strict by construction.", "",
              "## Confusion matrix (rows: true main area, columns: answer)", "", "| True \\ answered | " + " | ".join(used) + " |", "|---|" + "---|" * len(used)]
    for r, row in zip(labelled, confusion(truth, predicted, labelled, used)):
        lines.append(f"| {r} | " + " | ".join(str(v) for v in row) + " |")

    lines += ["", "## Calibration of Gemini's own confidence (against lenient correctness)", "",
              f"Brier {brier(confidence, lenient):.3f}, calibration error {ece(confidence, lenient):.3f}, over {n} answers. "
              "The trust score's calibration, out of sample with ranges, is in `report-trust-score-strict.md`.", "",
              "| Stated confidence | Answers | Mean stated | Share right |", "|---|---|---|---|"]
    for lo, hi, k, m, a in reliability(confidence, lenient):
        lines.append(f"| {lo:.1f}–{hi:.1f} | {k} | {m:.2f} | {a:.1%} |")
    by_class = defaultdict(list)
    for p, conf, y in zip(predicted, confidence, lenient):
        by_class[p].append((conf, y))
    lines += ["", "### By answered class", "", "| Answered | Answers | Brier | Calibration error | Share right |", "|---|---|---|---|---|"]
    for p, pairs in sorted(by_class.items()):
        cs, ys = zip(*pairs)
        lines.append(f"| {p} | {len(pairs)} | {brier(cs, ys):.3f} | {ece(cs, ys):.3f} | {sum(ys) / len(ys):.1%} |")

    quality = Counter(c["answer"]["prediction"].get("photoQuality") for c in answered)
    series = [("All answers", reliability(confidence, lenient))]
    lines += ["", "### By image quality", "", f"Gemini's own photoQuality was {dict(quality)}, so it cannot split the sample. Sharpness measured from the pixels "
              "(variance of the Laplacian), in thirds of this sample:", ""]
    measured = []
    for c, conf, y in zip(answered, confidence, lenient):
        path = os.path.join(DATA, "hitl", c["label"]["path"])
        s = sharpness(path) if os.path.exists(path) else None
        if s is not None:
            measured.append((s, conf, y))
    if measured:
        measured.sort(key=lambda t: t[0])
        third = len(measured) // 3
        lines += ["| Sharpness third | Answers | Laplacian variance | Brier | Calibration error | Share right |", "|---|---|---|---|---|---|"]
        for name, part in (("Softest", measured[:third]), ("Middle", measured[third:2 * third]), ("Sharpest", measured[2 * third:])):
            cs, ys = [t[1] for t in part], [t[2] for t in part]
            lines.append(f"| {name} | {len(part)} | {part[0][0]:.0f}–{part[-1][0]:.0f} | {brier(cs, ys):.3f} | {ece(cs, ys):.3f} | {sum(ys) / len(ys):.1%} |")
            series.append((name, reliability(cs, ys, bins=5)))
        lines += ["", "About 22 answers a band: differences between bands are not evidence of anything on their own."]
    else:
        lines.append("Photos not present on this machine: not measured.")
    lines += ["", f"Reliability diagram: [reliability-{run}.svg](reliability-{run}.svg).", ""]

    attempts = n + len(other_errors)
    slow = sum(l > APP_READ_TIMEOUT_S for l in latencies)
    lines += ["## Latency and reliability", "", "| Measure | Value |", "|---|---|",
              f"| Latency p50 / p90 / p95 / p99 / max | {percentile(latencies, 50):.1f} / {percentile(latencies, 90):.1f} / {percentile(latencies, 95):.1f} / {percentile(latencies, 99):.1f} / {max(latencies):.1f} s |",
              f"| Slower than the app's {APP_READ_TIMEOUT_S} s read timeout (those claims go to a person) | {slow}/{len(latencies)} = {slow / len(latencies):.1%} |",
              f"| Failed after retries | {len(other_errors)}/{attempts} = {len(other_errors) / attempts:.1%} |", "",
              "Free tier, and the latency includes the tool's retries after HTTP 503, so it is not a paid-tier service level.", ""]

    price = PRICE_PER_MILLION.get(model)
    mean_in, mean_out, mean_image = sum(tokens_in) / n, sum(tokens_out) / n, sum(image_tokens) / n
    claim_in = mean_in + (PHOTOS_PER_CLAIM - 1) * mean_image
    lines += ["## Cost per claim", "", f"Measured: mean {mean_in:.0f} input tokens ({mean_image:.0f} of them the photo) and {mean_out:.0f} output tokens for one photo.", ""]
    if price:
        one = (mean_in * price[0] + mean_out * price[1]) / 1e6
        five = (claim_in * price[0] + mean_out * price[1]) / 1e6
        lines += [f"At ${price[0]:.2f} in and ${price[1]:.2f} out per million tokens (standard paid tier, read from the provider's pricing page on 4 Nov 2026): "
                  f"**${one:.5f}** for this one-photo call; about **${five:.5f}** for a five-photo claim, an estimate assuming each extra photo adds the same image tokens "
                  "and the answer stays the same length. The second-opinion model and photo redaction run on your own hardware and are not priced.", ""]
    else:
        lines += [f"No sourced price for `{model}` is recorded in this script, so no cost is stated.", ""]

    lines += ["## What this data cannot measure", "",
              "| Measure | Why not | What it needs |", "|---|---|---|",
              "| Severity accuracy | These public photos have no severity label | Adjuster-graded photos |",
              "| Repair-cost accuracy | No public dataset links photos to repair costs | Repair invoices |",
              "| Adjuster override rate | `data/feedback.csv` holds one decision, from a demo | Decisions from real adjusters |",
              "| Comparison with human adjusters | No adjuster has labelled these photos | Double-labelling by adjusters |",
              "| True claim-level accuracy | Each photo stands in for a claim | Labelled multi-photo claims |",
              "| Property claims through Gemini | The property prompt has no recorded run | A run on the labelled FEMA photos, kept separate from these |", ""]
    return "\n".join(lines), reliability_svg(series)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    args = ap.parse_args()
    text, svg = report(args.run, load(args.run))
    with open(os.path.join(RESULTS, f"report-claim-level-{args.run}.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    with open(os.path.join(RESULTS, f"reliability-{args.run}.svg"), "w", encoding="utf-8", newline="\n") as f:
        f.write(svg)
    print(text)


if __name__ == "__main__":
    main()
