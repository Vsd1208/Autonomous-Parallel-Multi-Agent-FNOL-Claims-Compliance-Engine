"""Calibrated AI confidence ("trust score") for vehicle damage assessments.

Gemini's self-reported confidence is nearly constant (about 0.9) and does not separate right from wrong answers, so
the app needs a confidence that does: the probability that the damaged area Gemini named is right, learned from
labelled photos. Features (all available in the app when it scores a claim):

  margin        gap between Gemini's two highest damage-type probabilities
  agreement     the second-opinion model's probability for Gemini's area (tools/second-opinion, out-of-fold, so
                the second opinion never saw the photo); 0 when Gemini did not name a specific area
  specific      1 when Gemini named a specific area (not MULTIPLE or UNKNOWN)

Two logistic-regression models are fitted: with the second opinion, and margin-only (used when the second opinion is
unavailable). Every score in the report is out-of-sample: leave-one-image-out, so an image answered in two runs is
never in its own training set. Target: Gemini's area is one of the damaged areas (lenient correctness).

Usage:  python -P trust_score.py --run v3-subject [--run v2-multiphoto] [--write-app]
Writes: results/report-trust-score.md; with --write-app, when the shipping rules pass,
        fnol-agents/src/main/resources/trust-score.json (read by the app's TrustScorer).

Shipping rules for --write-app: a single run (one prompt), at least --min-images distinct images, and the trust score
beats Gemini's raw confidence out of sample on the Brier score.
"""
import argparse
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from calibrate import REPO, RESULTS, STP_CONFIDENCE, correctness, load, margin   # noqa: E402

OOF = os.path.join(HERE, "..", "second-opinion", "results", "oof-auto.jsonl")
APP_FILE = os.path.join(REPO, "fnol-agents", "src", "main", "resources", "trust-score.json")
SPECIFIC = {"FRONT_BUMPER", "REAR_BUMPER", "SIDE_PANEL", "WINDSHIELD", "ROOF"}
FEATURES_FULL = ["margin", "agreement", "specific"]
FEATURES_MARGIN = ["margin", "specific"]
THRESHOLDS = [0.50, 0.60, 0.70, 0.80, 0.85, 0.90, 0.95]


def rows_for(runs):
    second = {}
    with open(OOF, encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            second[r["image"]] = r["probabilities"]
    rows = []
    for run in runs:
        for r in load(run):
            p = r.get("prediction")
            if not p:
                continue
            zone = p.get("damageType", "UNKNOWN")
            lenient, _ = correctness(zone, r["label"])
            specific = zone in SPECIFIC
            rows.append({"run": run, "image": r["image"], "y": int(lenient), "raw": float(p.get("confidence") or 0),
                         "margin": margin(p), "specific": int(specific),
                         "agreement": float(second.get(r["image"], {}).get(zone, 0.0)) if specific else 0.0,
                         "model": r.get("model"), "thinkingBudget": r.get("thinkingBudget"),
                         "promptSha256": r.get("promptSha256")})
    return rows


def X(rows, features):
    return np.array([[r[f] for f in features] for r in rows], dtype=float)


def fit(rows, features):
    from sklearn.linear_model import LogisticRegression
    y = np.array([r["y"] for r in rows])
    if len(set(y)) < 2:                     # every answer right (or wrong): nothing to separate
        return None
    return LogisticRegression(C=1.0, max_iter=2000).fit(X(rows, features), y)


def out_of_sample(rows, features):
    """Leave-one-image-out scores (all rows of the held-out image are scored by a model fitted without it)."""
    scores = np.zeros(len(rows))
    images = sorted({r["image"] for r in rows})
    for image in images:
        train = [r for r in rows if r["image"] != image]
        test = [i for i, r in enumerate(rows) if r["image"] == image]
        model = fit(train, features)
        prior = np.mean([r["y"] for r in train])
        scores[test] = model.predict_proba(X([rows[i] for i in test], features))[:, 1] if model else prior
    return scores


def auc(y, s):
    pos, neg = s[y == 1], s[y == 0]
    if not len(pos) or not len(neg):
        return float("nan")
    return float(np.mean([(p > n) + 0.5 * (p == n) for p in pos for n in neg]))


def brier(y, s):
    return float(np.mean((s - y) ** 2))


def ece(y, s, bins=5):
    """Calibration error over equal-count bins (robust with few images)."""
    order = np.argsort(s)
    total = 0.0
    for chunk in np.array_split(order, bins):
        if len(chunk):
            total += len(chunk) / len(s) * abs(s[chunk].mean() - y[chunk].mean())
    return float(total)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", action="append", required=True)
    ap.add_argument("--write-app", action="store_true")
    ap.add_argument("--min-images", type=int, default=50)
    args = ap.parse_args()

    rows = rows_for(args.run)
    y = np.array([r["y"] for r in rows])
    raw = np.array([r["raw"] for r in rows])
    s_margin = out_of_sample(rows, FEATURES_MARGIN)
    s_full = out_of_sample(rows, FEATURES_FULL)
    n_img = len({r["image"] for r in rows})

    def line(name, s):
        return f"| {name} | {auc(y, s):.2f} | {brier(y, s):.3f} | {ece(y, s):.3f} |"

    out = [f"# Trust score (calibrated AI confidence) - runs {', '.join(args.run)}", "",
           f"{len(rows)} Gemini answers on {n_img} distinct labelled car photos, {int(y.sum())} right (lenient). "
           "Every score is out-of-sample (leave-one-image-out). Lower Brier and calibration error are better; a "
           "calibrated score of 0.80 should be right about 80% of the time.", "",
           "| Confidence used | Separation (AUC) | Brier | Calibration error |", "|---|---|---|---|",
           line("Gemini's self-reported confidence", raw),
           line("Trust score: margin only", s_margin),
           line("Trust score: margin + second opinion", s_full), "",
           "## Risk and coverage", "",
           "Coverage = answers that would pass the confidence factor at this threshold (could go straight through on "
           "the vision checks); wrong = how many of those are wrong.", "",
           "| Threshold | Gemini raw: coverage / wrong | Trust score (+ second opinion): coverage / wrong |", "|---|---|---|"]
    for t in THRESHOLDS:
        c_raw, c_full = raw >= t, s_full >= t
        out.append(f"| {t:.2f} | {int(c_raw.sum())}/{len(rows)} / {int((c_raw & (y == 0)).sum())} | "
                   f"{int(c_full.sum())}/{len(rows)} / {int((c_full & (y == 0)).sum())} |")
    full = fit(rows, FEATURES_FULL)
    marg = fit(rows, FEATURES_MARGIN)
    if full is not None:
        out += ["", "Fitted on all answers (what the app would use):", "",
                "- with second opinion: " + ", ".join(f"{f} {c:+.2f}" for f, c in zip(FEATURES_FULL, full.coef_[0]))
                + f", intercept {full.intercept_[0]:+.2f}",
                "- margin only: " + ", ".join(f"{f} {c:+.2f}" for f, c in zip(FEATURES_MARGIN, marg.coef_[0]))
                + f", intercept {marg.intercept_[0]:+.2f}"]

    shippable = (len(args.run) == 1 and n_img >= args.min_images and full is not None
                 and brier(y, s_full) < brier(y, raw))
    reasons = []
    if len(args.run) != 1:
        reasons.append("more than one run (mixed prompts)")
    if n_img < args.min_images:
        reasons.append(f"{n_img} images (below {args.min_images})")
    if full is not None and brier(y, s_full) >= brier(y, raw):
        reasons.append("does not beat Gemini's raw confidence on Brier")
    out += ["", "Verdict: " + ("ready for the app." if shippable else "not written to the app: " + "; ".join(reasons) + ".")]
    path = os.path.join(RESULTS, "report-trust-score.md")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")
    print("\n".join(out))

    if args.write_app:
        if not shippable:
            raise SystemExit("Not written: " + "; ".join(reasons))
        first = rows[0]
        payload = {"version": 1, "line": "AUTO", "model": first["model"], "thinkingBudget": first["thinkingBudget"],
                   "promptSha256": first["promptSha256"], "images": n_img, "target": "lenient",
                   "withSecondOpinion": {"features": FEATURES_FULL, "coef": full.coef_[0].round(6).tolist(),
                                         "intercept": round(float(full.intercept_[0]), 6)},
                   "marginOnly": {"features": FEATURES_MARGIN, "coef": marg.coef_[0].round(6).tolist(),
                                  "intercept": round(float(marg.intercept_[0]), 6)},
                   "metrics": {"aucRaw": round(auc(y, raw), 4), "aucTrust": round(auc(y, s_full), 4),
                               "brierRaw": round(brier(y, raw), 4), "brierTrust": round(brier(y, s_full), 4)}}
        with open(APP_FILE, "w", encoding="utf-8", newline="\n") as f:
            json.dump(payload, f, indent=2)
        print("app trust score written:", APP_FILE)


if __name__ == "__main__":
    main()
