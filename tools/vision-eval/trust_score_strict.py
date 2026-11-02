"""Strictly out-of-sample check of the trust score and of "wrong answers that could go straight through: 11 -> 1".

trust_score.py fits the weights and the smoothing inside each training set. Three choices were still made with all 66
answers in view:
  1. which features to use: `namedMargin` was introduced after seeing the fitted margin weight turn negative on these answers;
  2. the bins of the calibration error, ordered by a model fitted on all the labels;
  3. the thresholds of the vision checks (margin 0.30, second-opinion agreement 0.10) behind the 11 -> 1 figure.

Here they are made inside each training fold, from the training answers only. Repeated stratified 5-fold, not leave-one-out:
stratifying keeps the share of right answers the same in every training set, so a held-out answer's score no longer depends
on its own label (the artefact described at `ece` in trust_score.py), and scores can be binned by themselves.

Every figure is one fitted model's result, averaged over the repeats: scores are not averaged across repeats first, since that
would measure an ensemble nobody ships. Ranges: 95% bootstrap over the images, pooled across repeats, so they cover both the
sampling of the images and the fitting noise.

Usage:  python -P trust_score_strict.py --run v3-subject
Writes: results/report-trust-score-strict.md
"""
import argparse
import json
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from calibrate import RESULTS, STP_CONFIDENCE, load   # noqa: E402
from gate_ablation import severity_ok   # noqa: E402
from trust_score import APP_FILE, FEATURES_FULL, SMOOTHING, X, auc, brier, fit, out_of_sample, predict, rows_for  # noqa: E402

REPEATS = 20
FOLDS = 5
RESAMPLES = 200     # per repeat, so 4000 in the pooled range
SHIPPED = "named margin + agreement + named area (shipped)"
# The feature sets that could have been picked: the shipped one, and the ones it was chosen over or next to.
CANDIDATES = {
    SHIPPED: FEATURES_FULL,
    "margin + agreement + named area (earlier)": ["margin", "agreement", "specific"],
    "named margin + named area": ["namedMargin", "specific"],
}
MARGIN_GRID = [0.0, 0.1, 0.2, 0.3, 0.4, 0.5]
AGREEMENT_GRID = [0.0, 0.05, 0.1, 0.2, 0.3]
# How many right answers held back one wrong answer through is worth. An assumption, so several are shown.
WRONG_COSTS = [2, 5, 10, 20]


def ece_self(y, s, bins=5):
    order = np.argsort(s, kind="stable")
    total = 0.0
    for chunk in np.array_split(order, bins):
        if len(chunk):
            total += len(chunk) / len(s) * abs(s[chunk].mean() - y[chunk].mean())
    return float(total)


METRICS = {"auc": auc, "brier": brier, "ece": ece_self}


def stratified(y, seed):
    rng = np.random.default_rng(seed)
    folds = [[] for _ in range(FOLDS)]
    for label in (0, 1):
        for i, j in enumerate(rng.permutation(np.where(y == label)[0])):
            folds[i % FOLDS].append(j)
    return [np.array(f) for f in folds]


def inner_brier(train, y_train, features, C):
    total = 0.0
    for inner in stratified(y_train, 1):
        rest = np.setdiff1d(np.arange(len(train)), inner)
        tr = [train[i] for i in rest]
        s = predict(fit(tr, features, C), [train[i] for i in inner], features, float(y_train[rest].mean()))
        total += float(np.sum((s - y_train[inner]) ** 2))
    return total


def scores_by_repeat(rows, y, candidates):
    """One row of held-out scores per repeat; the feature set (among candidates) and smoothing are chosen inside each fold."""
    scores = np.zeros((REPEATS, len(rows)))
    chosen = {}
    for repeat in range(REPEATS):
        for test in stratified(y, 100 + repeat):
            rest = np.setdiff1d(np.arange(len(rows)), test)
            train = [rows[i] for i in rest]
            name, C = min(((n, c) for n in candidates for c in SMOOTHING),
                          key=lambda t: round(inner_brier(train, y[rest], candidates[t[0]], t[1]), 9))
            chosen[name] = chosen.get(name, 0) + 1
            features = candidates[name]
            scores[repeat, test] = predict(fit(train, features, C), [rows[i] for i in test], features, float(y[rest].mean()))
    return scores, chosen


def summary(y, scores):
    """metric -> (mean of one model's value over the repeats, pooled 95% bootstrap range)."""
    rng = np.random.default_rng(42)
    out = {}
    for name, metric in METRICS.items():
        pooled = []
        for s in scores:
            for _ in range(RESAMPLES):
                idx = rng.integers(0, len(y), len(y))
                v = metric(y[idx], s[idx])
                if not np.isnan(v):
                    pooled.append(v)
        out[name] = (float(np.mean([metric(y, s) for s in scores])), float(np.percentile(pooled, 2.5)), float(np.percentile(pooled, 97.5)))
    return out


def passes(rows, margin_min, agreement_min):
    """The vision checks of metrics section 3 at these thresholds."""
    return np.array([r["raw"] >= STP_CONFIDENCE and r["margin"] >= margin_min and r["severityOk"]
                     and (not r["specific"] or r["agreement"] >= agreement_min) for r in rows])


def thresholds_strict(rows, y, wrong_cost):
    """Thresholds chosen on the training answers only, applied to the held-out ones: counts per repeat."""
    through, wrong, right_held = np.zeros(REPEATS), np.zeros(REPEATS), np.zeros(REPEATS)
    picked = {}
    for repeat in range(REPEATS):
        for test in stratified(y, 500 + repeat):
            rest = np.setdiff1d(np.arange(len(rows)), test)
            train, y_train = [rows[i] for i in rest], y[rest]

            def cost(t):
                p = passes(train, *t)
                return wrong_cost * int((p & (y_train == 0)).sum()) + int((~p & (y_train == 1)).sum())

            best = min(((m, a) for m in MARGIN_GRID for a in AGREEMENT_GRID), key=cost)
            picked[best] = picked.get(best, 0) + 1
            p = passes([rows[i] for i in test], *best)
            through[repeat] += int(p.sum())
            wrong[repeat] += int((p & (y[test] == 0)).sum())
            right_held[repeat] += int((~p & (y[test] == 1)).sum())
    return through, wrong, right_held, picked


def rate_upper(k, n, conf=0.95):
    """Upper end of an exact two-sided interval for a rate of k in n."""
    if n == 0 or k == n:
        return 1.0
    lo, hi = k / n, 1.0
    for _ in range(100):
        mid = (lo + hi) / 2
        tail = sum(math.comb(n, i) * mid ** i * (1 - mid) ** (n - i) for i in range(k + 1))
        lo, hi = (mid, hi) if tail > (1 - conf) / 2 else (lo, mid)
    return hi


def shipped_scores(rows):
    """The exact weights in the app, which were fitted on these same answers."""
    with open(APP_FILE, encoding="utf-8") as f:
        m = json.load(f)["withSecondOpinion"]
    z = X(rows, m["features"]) @ np.array(m["coef"]) + m["intercept"]
    return 1 / (1 + np.exp(-z))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", default="v3-subject")
    args = ap.parse_args()
    rows = rows_for([args.run])
    answers = {r["image"]: r["prediction"] for r in load(args.run) if r.get("prediction")}
    for r in rows:
        r["severityOk"] = severity_ok(answers[r["image"]])
    y = np.array([r["y"] for r in rows])
    raw = np.array([r["raw"] for r in rows])
    wrong = int((y == 0).sum())

    fixed, _ = scores_by_repeat(rows, y, {SHIPPED: FEATURES_FULL})
    free, chosen = scores_by_repeat(rows, y, CANDIDATES)
    in_sample = shipped_scores(rows)
    loo, _ = out_of_sample(rows, FEATURES_FULL)

    def line(name, kind, scores):
        m = summary(y, np.atleast_2d(scores))
        return (f"| {name} | {kind} | {m['auc'][0]:.2f} ({m['auc'][1]:.2f}-{m['auc'][2]:.2f}) | "
                f"{m['brier'][0]:.3f} ({m['brier'][1]:.3f}-{m['brier'][2]:.3f}) | {m['ece'][0]:.3f} ({m['ece'][1]:.3f}-{m['ece'][2]:.3f}) |")

    def at_factor(scores):
        scores = np.atleast_2d(scores)
        p = scores >= STP_CONFIDENCE
        return float(p.sum(axis=1).mean()), float((p & (y == 0)).sum(axis=1).mean())

    shipped_auc = summary(y, fixed)["auc"]
    half = (shipped_auc[2] - shipped_auc[1]) / 2
    needed = math.ceil(len(rows) * (half / 0.05) ** 2)
    pass_rate = float(passes(rows, 0.30, 0.10).mean())
    none = next(n for n in range(1, 1000) if rate_upper(0, n) < 0.05)
    one = next(n for n in range(2, 1000) if rate_upper(1, n) < 0.05)
    out = [f"# Trust score and vision checks, strictly out of sample - run {args.run}", "",
           f"{len(rows)} answers, {int(y.sum())} right, {wrong} wrong (lenient). {REPEATS} repeats of stratified {FOLDS}-fold. Each figure is "
           "one fitted model's result averaged over the repeats. Calibration bins use each score itself. Ranges are 95% bootstrap "
           "intervals over the images, pooled across repeats.", "",
           "| Confidence | Evaluated | AUC | Brier | Calibration error |", "|---|---|---|---|---|",
           line("Gemini's own", "nothing fitted", raw),
           line("Trust score, shipped features", "**out of sample**: weights and smoothing inside each fold; the feature list was chosen by people who had seen these answers", fixed),
           line("Trust score, features also chosen in each fold", "**strictly out of sample**: features, weights and smoothing inside each fold", free),
           line("The weights in the app", "in sample: fitted on these answers", in_sample),
           line("Leave-one-out, as in trust_score.py (self-binned here)", "out of sample, with the artefact at `ece`", loo), "",
           "Feature set picked inside the training folds: " + ", ".join(f"{k} x{v}" for k, v in sorted(chosen.items(), key=lambda kv: -kv[1])) + ".", "",
           "## At the 0.85 confidence factor, mean over the repeats", "",
           "| Score | Pass | Wrong among them |", "|---|---|---|",
           f"| Shipped features, out of sample | {at_factor(fixed)[0]:.1f}/{len(rows)} | {at_factor(fixed)[1]:.1f} |",
           f"| Features chosen in each fold, strictly out of sample | {at_factor(free)[0]:.1f}/{len(rows)} | {at_factor(free)[1]:.1f} |",
           f"| The weights in the app, in sample | {at_factor(in_sample)[0]:.0f}/{len(rows)} | {at_factor(in_sample)[1]:.0f} |", "",
           "## Wrong answers that could go straight through", "",
           "| Vision checks | Pass | Wrong among them | Right answers held back | What saw these answers |", "|---|---|---|---|---|"]
    conf = raw >= STP_CONFIDENCE
    out.append(f"| Gemini's confidence only | {int(conf.sum())}/{len(rows)} | {int((conf & (y == 0)).sum())} | {int((~conf & (y == 1)).sum())} | Nothing fitted |")
    p = passes(rows, 0.30, 0.10)
    out.append(f"| All checks at the shipped thresholds (0.30, 0.10) | {int(p.sum())}/{len(rows)} | {int((p & (y == 0)).sum())} "
               f"(rate up to {rate_upper(int((p & (y == 0)).sum()), int(p.sum())):.0%}, exact 95%) | {int((~p & (y == 1)).sum())} | Both thresholds were set after looking at recorded answers, including these |")
    notes = []
    for cost in WRONG_COSTS:
        through, wrong_through, held, picked = thresholds_strict(rows, y, cost)
        out.append(f"| Thresholds chosen inside each fold, one wrong through = {cost} right held back | {through.mean():.1f}/{len(rows)} | "
                   f"**{wrong_through.mean():.1f}** (repeats {wrong_through.min():.0f}-{wrong_through.max():.0f}) | {held.mean():.1f} | Nothing from the held-out answers |")
        top = sorted(picked.items(), key=lambda kv: -kv[1])[:2]
        notes.append(f"{cost}: " + ", ".join(f"{k} x{v}" for k, v in top))
    out += ["", "Thresholds picked most often (margin, agreement) at each cost: " + "; ".join(notes) + ".", "",
            "## Sample size", "",
            f"With {wrong} wrong answers the AUC range is about ±{half:.2f}. Ranges shrink with the square root of the sample, so ±0.05 needs about "
            f"{needed} answers at the same {wrong / len(rows):.0%} wrong rate (rough scaling, not a power calculation). Showing that the wrong-through "
            f"rate is below 5% takes {none} answers that pass with none wrong (exact two-sided 95%), or {one} with one wrong: "
            f"about {math.ceil(none / pass_rate)} and {math.ceil(one / pass_rate)} answers at today's pass rate of {pass_rate:.0%}."]
    path = os.path.join(RESULTS, "report-trust-score-strict.md")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")
    print("\n".join(out))


if __name__ == "__main__":
    main()
