"""Calibrated AI confidence ("trust score") for vehicle damage assessments.

Gemini's self-reported confidence is nearly constant (about 0.9) and does not separate right from wrong answers, so
the app needs a confidence that does: the probability that the damaged area Gemini named is right, learned from
labelled photos and, once there are some, from adjusters' confirmed damage types. Features (all available in the app
when it scores a claim):

  margin        gap between Gemini's two highest damage-type probabilities
  agreement     the second-opinion model's probability for Gemini's area (tools/second-opinion, out-of-fold, so
                the second opinion never saw the photo); 0 when Gemini did not name a specific area
  specific      1 when Gemini named a specific area (not MULTIPLE or UNKNOWN)

Two logistic-regression models are fitted: with the second opinion, and margin-only (used when the second opinion is
unavailable). The amount of smoothing (regularisation strength C) is chosen from SMOOTHING by cross-validation.

Honest measurement: every reported score is out-of-sample (leave-one-image-out), and the smoothing is chosen again
inside each training set, without the held-out image (nested cross-validation), so choosing C cannot flatter the
result. Uncertainty: 95% ranges from 2000 bootstrap resamples of the images.

Target: Gemini's area is one of the damaged areas (lenient correctness). Adjuster feedback rows (--feedback) count as
right when the adjuster confirmed the same damage type; they are used only when they were produced by the same model,
prompt and thinking budget as the evaluation run (the app records this as visionSource).

Usage:  python -P trust_score.py --run v3-subject [--feedback data/feedback.csv] [--write-app]
Writes: results/report-trust-score.md; with --write-app, when the shipping rules pass,
        fnol-agents/src/main/resources/trust-score.json (read by the app's TrustScorer).

Shipping rules for --write-app: a single run (one prompt), at least --min-images distinct images, the trust score beats
Gemini's raw confidence out of sample on the Brier score, and, when the app already has a trust score for this prompt,
the new one is no worse on Brier and lets no more wrong answers through at the 0.85 threshold on the same data, and
the weights point the sensible way (a larger probability margin or second-opinion agreement never lowers the score).
"""
import argparse
import csv
import datetime
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
SMOOTHING = [0.3, 1.0, 3.0, 10.0, 100.0]     # C: smaller = stronger pull towards the average accuracy
THRESHOLDS = [0.50, 0.60, 0.70, 0.80, 0.85, 0.90, 0.95]
BOOTSTRAP = 2000


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
            rows.append({"source": "evaluation", "image": r["image"], "y": int(lenient),
                         "raw": float(p.get("confidence") or 0), "margin": margin(p), "specific": int(specific),
                         "agreement": float(second.get(r["image"], {}).get(zone, 0.0)) if specific else 0.0,
                         "model": r.get("model"), "thinkingBudget": r.get("thinkingBudget"),
                         "promptSha256": r.get("promptSha256")})
    return rows


def vision_source(row):
    """The app's visionSource for answers like this row (GeminiVisionProvider.visionSource)."""
    return f"{row['model']} prompt {row['promptSha256']} thinking {row['thinkingBudget']}"


def feedback_rows(path, source):
    """Adjuster-confirmed AUTO decisions on answers from the same model, prompt and thinking budget."""
    rows, skipped = [], {"other model or prompt": 0, "no confirmed type": 0, "home claim": 0, "no second opinion": 0}
    with open(path, encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            if (r.get("line_of_business") or "").upper() != "AUTO":
                skipped["home claim"] += 1
                continue
            if r.get("vision_source") != source:
                skipped["other model or prompt"] += 1
                continue
            ai, adjuster = r.get("ai_damage_type") or "", r.get("adjuster_damage_type") or ""
            if not ai or not adjuster:
                skipped["no confirmed type"] += 1
                continue
            specific = ai in SPECIFIC
            agreement = r.get("second_opinion_agreement") or ""
            if specific and agreement == "":
                skipped["no second opinion"] += 1
                continue
            raw = r.get("raw_confidence") or r.get("ai_confidence") or "0"
            rows.append({"source": "adjuster", "image": "claim:" + r["claim_id"], "y": int(ai == adjuster),
                         "raw": float(raw), "margin": float(r.get("probability_margin") or 0),
                         "specific": int(specific), "agreement": float(agreement) if specific else 0.0})
    return rows, skipped


def X(rows, features):
    return np.array([[r[f] for f in features] for r in rows], dtype=float)


def fit(rows, features, C):
    from sklearn.linear_model import LogisticRegression
    y = np.array([r["y"] for r in rows])
    if len(set(y)) < 2:                     # every answer right (or wrong): nothing to separate
        return None
    return LogisticRegression(C=C, max_iter=5000).fit(X(rows, features), y)


def predict(model, rows, features, prior):
    return model.predict_proba(X(rows, features))[:, 1] if model else np.full(len(rows), prior)


def groups(rows):
    """Image (or claim) of each row: every row of one image is held out together."""
    return sorted({r["image"] for r in rows})


def cv_brier(rows, features, C, folds=5):
    """Grouped k-fold Brier score of one smoothing level, used to choose C inside a training set."""
    images = groups(rows)
    rng = np.random.default_rng(0)
    order = rng.permutation(len(images))
    total, n = 0.0, 0
    for k in range(folds):
        held = {images[i] for i in order[k::folds]}
        train = [r for r in rows if r["image"] not in held]
        test = [r for r in rows if r["image"] in held]
        if not test:
            continue
        prior = np.mean([r["y"] for r in train])
        s = predict(fit(train, features, C), test, features, prior)
        total += float(np.sum((s - np.array([r["y"] for r in test])) ** 2))
        n += len(test)
    return total / n


def choose_smoothing(rows, features):
    return min(SMOOTHING, key=lambda C: (round(cv_brier(rows, features, C), 6), -C))


def out_of_sample(rows, features, C=None):
    """Leave-one-image-out scores. With C=None the smoothing is chosen again inside every training set (nested)."""
    scores = np.zeros(len(rows))
    chosen = []
    for image in groups(rows):
        train = [r for r in rows if r["image"] != image]
        test = [i for i, r in enumerate(rows) if r["image"] == image]
        c = C if C is not None else choose_smoothing(train, features)
        chosen.append(c)
        prior = np.mean([r["y"] for r in train])
        scores[test] = predict(fit(train, features, c), [rows[i] for i in test], features, prior)
    return scores, chosen


def auc(y, s):
    pos, neg = s[y == 1], s[y == 0]
    if not len(pos) or not len(neg):
        return float("nan")
    return float(np.mean([(p > n) + 0.5 * (p == n) for p in pos for n in neg]))


def brier(y, s):
    return float(np.mean((s - y) ** 2))


def ece(y, s, bins=5):
    """Calibration error over equal-count bins (robust with few images)."""
    order = np.argsort(s, kind="stable")
    total = 0.0
    for chunk in np.array_split(order, bins):
        if len(chunk):
            total += len(chunk) / len(s) * abs(s[chunk].mean() - y[chunk].mean())
    return float(total)


def bootstrap(rows, y, s, metric):
    """95% range of a metric over resamples of whole images."""
    by_image = {}
    for i, r in enumerate(rows):
        by_image.setdefault(r["image"], []).append(i)
    images = list(by_image)
    rng = np.random.default_rng(42)
    values = []
    for _ in range(BOOTSTRAP):
        idx = np.concatenate([by_image[images[j]] for j in rng.integers(0, len(images), len(images))])
        v = metric(y[idx], s[idx])
        if not np.isnan(v):
            values.append(v)
    return float(np.percentile(values, 2.5)), float(np.percentile(values, 97.5))


def current_app_config(prompt_sha):
    """Features and smoothing of the trust score the app has now, when it was measured on this prompt."""
    if not os.path.exists(APP_FILE):
        return None
    with open(APP_FILE, encoding="utf-8") as f:
        app = json.load(f)
    if app.get("promptSha256") != prompt_sha:
        return None
    return {"C": float(app["withSecondOpinion"].get("C", 1.0)), "features": app["withSecondOpinion"]["features"]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", action="append", required=True)
    ap.add_argument("--feedback", help="CSV of adjuster feedback (refit_from_feedback.py exports it)")
    ap.add_argument("--write-app", action="store_true")
    ap.add_argument("--min-images", type=int, default=50)
    args = ap.parse_args()

    rows = rows_for(args.run)
    eval_images = len(groups(rows))
    feedback_note = []
    if args.feedback:
        extra, skipped = feedback_rows(args.feedback, vision_source(rows[0]))
        rows += extra
        feedback_note = [f"Adjuster feedback: {len(extra)} confirmed decisions used ("
                         f"{sum(r['y'] for r in extra)} where the adjuster confirmed the AI's damage type); skipped: "
                         + ", ".join(f"{v} {k}" for k, v in skipped.items() if v) + "." if any(skipped.values())
                         else f"Adjuster feedback: {len(extra)} confirmed decisions used."]
    y = np.array([r["y"] for r in rows])
    raw = np.array([r["raw"] for r in rows])
    n_img = len(groups(rows))

    s_full, chosen_full = out_of_sample(rows, FEATURES_FULL)
    s_margin, _ = out_of_sample(rows, FEATURES_MARGIN)
    current = current_app_config(rows[0]["promptSha256"])
    s_current = out_of_sample(rows, current["features"], current["C"])[0] if current else None
    C_full = choose_smoothing(rows, FEATURES_FULL)
    C_margin = choose_smoothing(rows, FEATURES_MARGIN)

    def line(name, s):
        lo_b, hi_b = bootstrap(rows, y, s, brier)
        lo_e, hi_e = bootstrap(rows, y, s, ece)
        return (f"| {name} | {auc(y, s):.2f} | {brier(y, s):.3f} ({lo_b:.3f}-{hi_b:.3f}) | "
                f"{ece(y, s):.3f} ({lo_e:.3f}-{hi_e:.3f}) |")

    counts = {c: chosen_full.count(c) for c in SMOOTHING if chosen_full.count(c)}
    out = [f"# Trust score (calibrated AI confidence) - runs {', '.join(args.run)}", "",
           f"{len(rows)} answers on {n_img} distinct photos or claims ({eval_images} labelled evaluation photos), "
           f"{int(y.sum())} right (lenient). Every score is out-of-sample (leave-one-image-out) and the smoothing is "
           "chosen inside each training set (nested), so the figures are not flattered by the choice. Lower Brier and "
           "calibration error are better; a calibrated score of 0.80 should be right about 80% of the time. "
           f"Ranges are 95% bootstrap intervals over {BOOTSTRAP} resamples of the images.", ""] + feedback_note + [
           "", "| Confidence used | Separation (AUC) | Brier (95% range) | Calibration error (95% range) |",
           "|---|---|---|---|",
           line("Gemini's self-reported confidence", raw)]
    if current:
        out.append(line(f"Current app trust score (fixed smoothing C={current['C']:g})", s_current))
    out += [line("Trust score: margin only (smoothing chosen by cross-validation)", s_margin),
            line("Trust score: margin + second opinion (smoothing chosen by cross-validation)", s_full), "",
            "Smoothing chosen inside the training sets (C, how often): "
            + ", ".join(f"{c:g} x{n}" for c, n in counts.items())
            + f". On all data: C={C_full:g} with the second opinion, C={C_margin:g} margin only.", "",
            "## Risk and coverage", "",
            "Coverage = answers that would pass the confidence factor at this threshold (could go straight through on "
            "the vision checks); wrong = how many of those are wrong.", "",
            "| Threshold | Gemini raw: coverage / wrong | Trust score (+ second opinion): coverage / wrong |",
            "|---|---|---|"]
    for t in THRESHOLDS:
        c_raw, c_full = raw >= t, s_full >= t
        out.append(f"| {t:.2f} | {int(c_raw.sum())}/{len(rows)} / {int((c_raw & (y == 0)).sum())} | "
                   f"{int(c_full.sum())}/{len(rows)} / {int((c_full & (y == 0)).sum())} |")
    full = fit(rows, FEATURES_FULL, C_full)
    marg = fit(rows, FEATURES_MARGIN, C_margin)
    if full is not None:
        out += ["", "Fitted on all answers (what the app would use):", "",
                "- with second opinion: " + ", ".join(f"{f} {c:+.2f}" for f, c in zip(FEATURES_FULL, full.coef_[0]))
                + f", intercept {full.intercept_[0]:+.2f} (C={C_full:g})",
                "- margin only: " + ", ".join(f"{f} {c:+.2f}" for f, c in zip(FEATURES_MARGIN, marg.coef_[0]))
                + f", intercept {marg.intercept_[0]:+.2f} (C={C_margin:g})"]

    wrong_at = lambda s: int(((s >= STP_CONFIDENCE) & (y == 0)).sum())   # noqa: E731
    reasons = []
    if len(args.run) != 1:
        reasons.append("more than one run (mixed prompts)")
    if eval_images < args.min_images:
        reasons.append(f"{eval_images} evaluation images (below {args.min_images})")
    if full is None:
        reasons.append("nothing to separate")
    elif brier(y, s_full) >= brier(y, raw):
        reasons.append("does not beat Gemini's raw confidence on Brier")
    if full is not None:
        # A clearer answer, or a second opinion that agrees, must never lower the trust score: a model that fits the
        # sample the other way round cannot be explained to an adjuster or a regulator.
        weights = dict(zip(FEATURES_FULL, full.coef_[0])) | {"margin only": marg.coef_[0][0]}
        backwards = [k for k in ("margin", "agreement", "margin only") if weights[k] < 0]
        if backwards:
            reasons.append("negative weight on " + ", ".join(backwards)
                           + " (a clearer or better-supported answer would get a lower trust score)")
    if current and full is not None:
        if brier(y, s_full) > brier(y, s_current):
            reasons.append(f"worse than the current app trust score on Brier ({brier(y, s_full):.4f} vs "
                           f"{brier(y, s_current):.4f})")
        if wrong_at(s_full) > wrong_at(s_current):
            reasons.append(f"lets more wrong answers through at {STP_CONFIDENCE} ({wrong_at(s_full)} vs "
                           f"{wrong_at(s_current)})")
    shippable = not reasons
    out += ["", "Verdict: " + ("ready for the app." if shippable else "not written to the app: " + "; ".join(reasons) + ".")]
    path = os.path.join(RESULTS, "report-trust-score.md")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")
    print("\n".join(out))

    if args.write_app:
        if not shippable:
            raise SystemExit("Not written: " + "; ".join(reasons))
        first = rows[0]
        payload = {"version": 2, "line": "AUTO", "model": first["model"], "thinkingBudget": first["thinkingBudget"],
                   "promptSha256": first["promptSha256"], "images": eval_images,
                   "adjusterFeedbackRows": sum(1 for r in rows if r["source"] == "adjuster"),
                   "measuredOn": datetime.date.today().isoformat(), "target": "lenient",
                   "withSecondOpinion": {"features": FEATURES_FULL, "C": C_full,
                                         "coef": full.coef_[0].round(6).tolist(),
                                         "intercept": round(float(full.intercept_[0]), 6)},
                   "marginOnly": {"features": FEATURES_MARGIN, "C": C_margin,
                                  "coef": marg.coef_[0].round(6).tolist(),
                                  "intercept": round(float(marg.intercept_[0]), 6)},
                   "metrics": {"aucRaw": round(auc(y, raw), 4), "aucTrust": round(auc(y, s_full), 4),
                               "brierRaw": round(brier(y, raw), 4), "brierTrust": round(brier(y, s_full), 4),
                               "eceRaw": round(ece(y, raw), 4), "eceTrust": round(ece(y, s_full), 4)}}
        with open(APP_FILE, "w", encoding="utf-8", newline="\n") as f:
            json.dump(payload, f, indent=2)
        print("app trust score written:", APP_FILE)


if __name__ == "__main__":
    main()
