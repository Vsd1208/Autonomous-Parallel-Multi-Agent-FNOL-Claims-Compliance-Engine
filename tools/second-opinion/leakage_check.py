"""How much of the second-opinion model's accuracy survives when photos that look alike cannot sit on both sides of a split.

build_model.py uses stratified folds over photos. Near-duplicates (the same car, burst shots, re-uploads) could then be in training and
test at once and flatter the figures. These public datasets have no vehicle, accident or session IDs, so visual similarity is the only
grouping available: near-duplicates are found from the embeddings (cosine at or above a threshold, connected components). A different
car that looks alike is grouped too, and the same car photographed very differently is not, so this is a control, not proof of no leak.

Four views, each logistic regression on CLIP + DINOv2 with regularisation chosen inside the training set only:
  A. stratified folds (what the headline figures use)
  B. the same folds kept apart by near-duplicate group
  C. one dataset held out at a time, trained on the others (a different source, the hardest check available)
  D. a final holdout: 20% of the groups set aside once, by seed, before anything else, and scored once

Usage:  python -P leakage_check.py [--quick]
Writes: results/report-leakage.md
"""
import argparse
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.path.join(HERE, "results")
CAR_DATA = os.path.join(HERE, "..", "vision-eval", "data")
SEED = 7
CLASSES = ["FRONT_BUMPER", "REAR_BUMPER", "SIDE_PANEL", "WINDSHIELD"]
THRESHOLDS = [0.98, 0.95, 0.90, 0.85]
GROUP_THRESHOLD = 0.90
BOOTSTRAP = 2000


def near_duplicate_groups(X, threshold):
    """Connected components of 'cosine >= threshold'. X has unit-length rows. -> a group number for each row."""
    n = len(X)
    parent = list(range(n))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    similar = X @ X.T
    for i, j in zip(*np.where(np.triu(similar >= threshold, k=1))):
        parent[find(int(i))] = find(int(j))
    roots = {}
    return np.array([roots.setdefault(find(i), len(roots)) for i in range(n)])


def group_holdout(groups, share, seed):
    """Whole groups for a holdout of about the given share. -> (train indices, holdout indices), never sharing a group."""
    rng = np.random.default_rng(seed)
    ids = np.unique(groups)
    rng.shuffle(ids)
    chosen, count = set(), 0
    for g in ids:
        if count >= share * len(groups):
            break
        chosen.add(int(g))
        count += int((groups == g).sum())
    held = np.array([int(g) in chosen for g in groups])
    return np.where(~held)[0], np.where(held)[0]


def expected_calibration_error(confidence, correct, bins=10):
    """Equal-width bins over the top-class probability: |accuracy - mean confidence|, weighted by the bin's share."""
    confidence, correct = np.asarray(confidence, float), np.asarray(correct, float)
    edges = np.linspace(0, 1, bins + 1)
    total = 0.0
    for lo, hi in zip(edges[:-1], edges[1:]):
        inside = (confidence > lo) & (confidence <= hi)
        if inside.any():
            total += inside.mean() * abs(correct[inside].mean() - confidence[inside].mean())
    return float(total)


def scores(p, y, cars):
    """p: probabilities for rows `cars`, y: true class index."""
    pred = p.argmax(1)
    strict = pred == y
    lenient = np.array([CLASSES[k] in r["zones"] for k, r in zip(pred, cars)])
    onehot = np.eye(len(CLASSES))[y]
    return {
        "n": len(y),
        "strict": float(strict.mean()),
        "lenient": float(lenient.mean()),
        "logLoss": float(-np.log(np.clip(p[np.arange(len(y)), y], 1e-6, 1)).mean()),
        "brier": float(((p - onehot) ** 2).sum(1).mean()),
        "ece": expected_calibration_error(p.max(1), strict),
    }


def bootstrap_range(p, y, groups, seed=SEED):
    """95% range for strict accuracy, resampling whole groups, since photos in one group are not independent."""
    rng = np.random.default_rng(seed)
    ids = np.unique(groups)
    members = {g: np.where(groups == g)[0] for g in ids}
    correct = p.argmax(1) == y
    values = []
    for _ in range(BOOTSTRAP):
        idx = np.concatenate([members[g] for g in rng.choice(ids, len(ids))])
        values.append(correct[idx].mean())
    return float(np.percentile(values, 2.5)), float(np.percentile(values, 97.5))


def fit_predict(X, y, train, test, choose_c):
    from sklearn.linear_model import LogisticRegression
    m = LogisticRegression(C=choose_c(X[train], y[train]), max_iter=5000, class_weight="balanced").fit(X[train], y[train])
    p = np.zeros((len(test), len(CLASSES)))
    p[:, m.classes_] = m.predict_proba(X[test])
    return p


def load():
    cars = [json.loads(l) for l in open(os.path.join(CAR_DATA, "labels.jsonl"), encoding="utf-8")]
    for r in cars:
        r["dataset"] = "Humans in the Loop"
    extra = [json.loads(l) for l in open(os.path.join(CAR_DATA, "roboflow", "reviewed.jsonl"), encoding="utf-8")]
    res = lambda name: np.load(os.path.join(RESULTS, name))
    clip = np.vstack([res("emb-car.npy"), res("emb-car-extra-clip.npy")])
    dino = np.vstack([res("emb-dinov2-b-car.npy"), res("emb-car-extra-dino.npy")])
    return cars + extra, np.concatenate([clip, dino], axis=1) / np.sqrt(2)


def main():
    from sklearn.model_selection import StratifiedGroupKFold, StratifiedKFold
    sys.path.insert(0, HERE)
    from build_model import choose_c

    ap = argparse.ArgumentParser()
    ap.add_argument("--quick", action="store_true", help="a fixed regularisation instead of choosing it (for a fast look only)")
    args = ap.parse_args()
    pick = (lambda X, y: 16) if args.quick else choose_c

    cars, X = load()
    y = np.array([CLASSES.index(r["primaryZone"]) for r in cars])
    lines = ["# Second-opinion model: leakage check", "",
             f"{len(cars)} car photos. Seed {SEED}. Command: `python -P leakage_check.py`.", "",
             "These datasets have no vehicle, accident or session IDs, so near-duplicate groups come from image similarity only: a control, "
             "not proof that no leak remains.", "", "## Near-duplicates", "",
             "| Cosine at or above | Groups | Photos sharing a group | Largest group |", "|---|---|---|---|"]
    all_groups = {}
    for t in THRESHOLDS:
        g = near_duplicate_groups(X, t)
        all_groups[t] = g
        sizes = np.bincount(g)
        lines.append(f"| {t} | {len(sizes)} | {int(sizes[sizes > 1].sum())} | {int(sizes.max())} |")
        print(f"threshold {t}: {len(sizes)} groups, {int(sizes[sizes > 1].sum())} photos share one, largest {int(sizes.max())}", flush=True)
    groups = all_groups[GROUP_THRESHOLD]

    def cv(splitter, label, split_groups=None):
        p = np.zeros((len(y), len(CLASSES)))
        for train, test in splitter.split(X, y, split_groups):
            p[test] = fit_predict(X, y, train, test, pick)
        s = scores(p, y, cars)
        lo, hi = bootstrap_range(p, y, groups)
        print(label, {k: round(v, 3) for k, v in s.items()}, flush=True)
        return f"| {label} | {s['n']} | {s['strict']:.1%} ({lo:.1%}–{hi:.1%}) | {s['lenient']:.1%} | {s['logLoss']:.3f} | {s['brier']:.3f} | {s['ece']:.3f} |"

    header = ["| Split | Photos scored | Main area right (95% range) | One of the damaged areas | Log loss | Brier | Calibration error |",
              "|---|---|---|---|---|---|---|"]
    lines += ["", f"## Results (groups at cosine {GROUP_THRESHOLD})", "", *header]
    lines.append(cv(StratifiedKFold(n_splits=5, shuffle=True, random_state=SEED), "A. Stratified folds over photos"))
    lines.append(cv(StratifiedGroupKFold(n_splits=5, shuffle=True, random_state=SEED), "B. Folds kept apart by near-duplicate group", groups))

    sources = np.array([r.get("dataset") for r in cars])
    for source in sorted(set(sources)):
        test = np.where(sources == source)[0]
        train = np.where(sources != source)[0]
        if len(set(y[train])) < len(CLASSES):
            lines.append(f"| C. Held out: {source} | {len(test)} | not scored: training would lack a class | | | | |")
            continue
        p = fit_predict(X, y, train, test, pick)
        s = scores(p, y[test], [cars[i] for i in test])
        lo, hi = bootstrap_range(p, y[test], groups[test])
        print("C", source, {k: round(v, 3) for k, v in s.items()}, flush=True)
        lines.append(f"| C. Held out: {source} | {s['n']} | {s['strict']:.1%} ({lo:.1%}–{hi:.1%}) | {s['lenient']:.1%} | {s['logLoss']:.3f} | {s['brier']:.3f} | {s['ece']:.3f} |")

    train, held = group_holdout(groups, 0.2, SEED)
    assert not set(groups[train]) & set(groups[held])
    p = fit_predict(X, y, train, held, pick)
    s = scores(p, y[held], [cars[i] for i in held])
    lo, hi = bootstrap_range(p, y[held], groups[held])
    print("D", {k: round(v, 3) for k, v in s.items()}, flush=True)
    lines.append(f"| D. Final holdout, whole groups, scored once | {s['n']} | {s['strict']:.1%} ({lo:.1%}–{hi:.1%}) | {s['lenient']:.1%} | {s['logLoss']:.3f} | {s['brier']:.3f} | {s['ece']:.3f} |")
    lines += ["", "The encoder and the model family were chosen earlier on the CC0 photos (`report-encoders.md`), so even D is not free of every "
              "earlier choice. The model that ships is trained on all the photos.", ""]
    with open(os.path.join(RESULTS, "report-leakage.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))


if __name__ == "__main__":
    main()
