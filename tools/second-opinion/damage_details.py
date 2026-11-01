"""Stage 2 of our own vision model: damage types and visible damage extent, on a chosen encoder's embeddings.

  types   Broken part, Dent, Scratch, Missing part, Paint chip, Corrosion, Flaking, Cracked: one class-balanced
          logistic regression per type (a photo usually shows several). Reported against the honest baseline of
          always predicting how common the type is (its base rate): AUC, Brier vs base-rate Brier (skill), calibration.
  extent  share of the photo covered by damage polygons (labels from tools/vision-eval/prepare.py), as a stand-in for
          severity: ridge regression on log(share). Reported as rank correlation and small/medium/large tertile accuracy.
          It measures how much of the PHOTO is damaged, which also depends on how close the photo was taken; it is
          not a repair cost and is not presented as one.

Every number is out-of-fold (5-fold, seed 7, as build_model.py); regularisation is chosen inside each training fold.
Embeddings come from benchmark_encoders.py's cache (results/emb-<key>-car.npy).

Usage:  python -P damage_details.py --encoder clip-b32+dinov2-b
Writes: results/report-damage-details.md
"""
import argparse
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
CAR_DATA = os.path.join(HERE, "..", "vision-eval", "data")
RESULTS = os.path.join(HERE, "results")
SEED = 7
TYPES = ["Broken part", "Dent", "Scratch", "Missing part", "Paint chip", "Corrosion", "Flaking", "Cracked"]
C_GRID = [0.25, 1, 4, 16]
ALPHAS = [0.1, 1, 10, 100]


def features(key):
    parts = key.split("+")
    return np.concatenate([np.load(os.path.join(RESULTS, f"emb-{k}-car.npy")) for k in parts], axis=1) / np.sqrt(len(parts))


def auc(y, s):
    from sklearn.metrics import roc_auc_score
    return float(roc_auc_score(y, s))


def ece_binary(y, p, bins=10):
    order = np.argsort(p)
    return float(sum(len(b) / len(y) * abs(p[b].mean() - y[b].mean()) for b in np.array_split(order, bins) if len(b)))


def oof_binary(X, y):
    """Out-of-fold probabilities; C chosen by inner 4-fold log loss."""
    from sklearn.linear_model import LogisticRegression
    from sklearn.metrics import log_loss
    from sklearn.model_selection import StratifiedKFold
    out, chosen = np.zeros(len(y)), []
    for tr, te in StratifiedKFold(5, shuffle=True, random_state=SEED).split(X, y):
        best = None
        for c in C_GRID:
            p = np.zeros(len(tr))
            for itr, ite in StratifiedKFold(4, shuffle=True, random_state=SEED).split(X[tr], y[tr]):
                m = LogisticRegression(C=c, max_iter=3000).fit(X[tr][itr], y[tr][itr])
                p[ite] = m.predict_proba(X[tr][ite])[:, 1]
            loss = log_loss(y[tr], np.clip(p, 1e-6, 1 - 1e-6))
            if best is None or loss < best[0]:
                best = (loss, c)
        chosen.append(best[1])
        out[te] = LogisticRegression(C=best[1], max_iter=3000).fit(X[tr], y[tr]).predict_proba(X[te])[:, 1]
    return out, chosen


def oof_extent(X, t):
    from sklearn.linear_model import Ridge
    from sklearn.model_selection import KFold
    out = np.zeros(len(t))
    for tr, te in KFold(5, shuffle=True, random_state=SEED).split(X):
        best = None
        for a in ALPHAS:
            p = np.zeros(len(tr))
            for itr, ite in KFold(4, shuffle=True, random_state=SEED).split(X[tr]):
                p[ite] = Ridge(alpha=a).fit(X[tr][itr], t[tr][itr]).predict(X[tr][ite])
            err = float(((p - t[tr]) ** 2).mean())
            if best is None or err < best[0]:
                best = (err, a)
        out[te] = Ridge(alpha=best[1]).fit(X[tr], t[tr]).predict(X[te])
    return out


def main():
    from scipy.stats import spearmanr

    ap = argparse.ArgumentParser()
    ap.add_argument("--encoder", required=True, help="cached embedding key, e.g. dinov2-b or clip-b32+dinov2-b")
    args = ap.parse_args()
    cars = [json.loads(l) for l in open(os.path.join(CAR_DATA, "labels.jsonl"), encoding="utf-8")]
    X = features(args.encoder)
    n = len(cars)
    rng = np.random.default_rng(SEED)
    boot = rng.integers(0, n, size=(2000, n))

    lines = ["# Our own vision model: damage types and visible extent", "",
             f"{n} Humans in the Loop car photos (CC0), encoder `{args.encoder}` (see report-encoders.md). Every number "
             "is out-of-fold (5-fold, seed 7); regularisation chosen inside each training fold. Brier skill = how much "
             "lower the Brier score is than always predicting the type's base rate (0% = no better than guessing the "
             "base rate; higher is better). AUC 95% ranges by bootstrap over photos.", "",
             "| Damage type | Photos | Base rate | AUC (95% range) | Brier | Base-rate Brier | Brier skill | Calibration error |",
             "|---|---|---|---|---|---|---|---|"]
    summary = {}
    for t in TYPES:
        y = np.array([t in r["damageTypes"] for r in cars], dtype=float)
        p, _ = oof_binary(X, y.astype(int))
        base = y.mean()
        brier, brier0 = float(((p - y) ** 2).mean()), float(((base - y) ** 2).mean())
        a = auc(y, p)
        ab = [auc(y[b], p[b]) for b in boot if 0 < y[b].sum() < n]
        lo, hi = np.percentile(ab, [2.5, 97.5])
        summary[t] = {"auc": a, "skill": 1 - brier / brier0}
        lines.append(f"| {t} | {int(y.sum())} | {base:.0%} | {a:.2f} ({lo:.2f}-{hi:.2f}) | {brier:.3f} | {brier0:.3f} | "
                     f"{1 - brier / brier0:+.0%} | {ece_binary(y, p):.3f} |")
        print(f"{t}: AUC {a:.3f}, skill {1 - brier / brier0:+.1%}", flush=True)

    share = np.array([r["damageAreaShare"] for r in cars])
    target = np.log(np.clip(share, 1e-4, None))
    pred = oof_extent(X, target)
    rho = float(spearmanr(pred, target).correlation)
    rb = [spearmanr(pred[b], target[b]).correlation for b in boot[:1000]]
    cut = np.percentile(target, [100 / 3, 200 / 3])
    true_t, pred_t = np.digitize(target, cut), np.digitize(pred, np.percentile(pred, [100 / 3, 200 / 3]))
    tert = float((true_t == pred_t).mean())
    far = float((abs(true_t - pred_t) == 2).mean())
    lines += ["", "## Visible damage extent (share of the photo covered by damage)", "",
              f"Median share {np.median(share):.1%} (10th-90th percentile {np.percentile(share, 10):.1%}-"
              f"{np.percentile(share, 90):.1%}). Ridge regression on log(share), out-of-fold.", "",
              "| Measure | Value |", "|---|---|",
              f"| Rank correlation with the true share (Spearman) | {rho:.2f} (95% range {np.percentile(rb, 2.5):.2f}-"
              f"{np.percentile(rb, 97.5):.2f}) |",
              f"| Small / medium / large (tertiles) right | {tert:.0%} (chance 33%) |",
              f"| Off by two classes (small called large or the reverse) | {far:.0%} |",
              "", "The extent depends on how close the photo was taken as well as on the damage, so it is a stand-in "
              "for severity, not a repair cost."]
    with open(os.path.join(RESULTS, "report-damage-details.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    sys.exit(main())
