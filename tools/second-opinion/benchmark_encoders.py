"""Compares image encoders for our own car-damage model, on exactly the protocol build_model.py uses.

Each encoder turns every photo into an embedding; a class-balanced logistic regression predicts the main damaged area.
Every photo is scored by a model that never saw it (5-fold stratified, seed 7, as build_model.py). The regularisation
strength is chosen inside each training fold (inner 4-fold, by log loss), so the test folds never influence it.

Reported per encoder: main area right (strict), one of the damaged areas (lenient), log loss, Brier, calibration error
of the top probability, and the paired bootstrap difference in strict accuracy against the encoder in use today.

All encoders are permissively licensed and loaded from safetensors only (no pickle files).

Usage:  python -P benchmark_encoders.py [--encoders key1,key2,key1+key2]   ("a+b" = both embeddings side by side)
        Encoders load from ~/.cache/fnol-encoders/<org>--<name>/ when that folder holds model.safetensors,
        otherwise from Hugging Face.
Writes: results/emb-<key>-car.npy (cache, not committed), results/report-encoders.md
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
AUTO_CLASSES = ["FRONT_BUMPER", "REAR_BUMPER", "SIDE_PANEL", "WINDSHIELD"]
C_GRID = [1, 2, 4, 8, 16, 32, 64]   # every encoder so far chose 8-32

# key: (Hugging Face id, kind, licence)
ENCODERS = {
    "clip-b32": ("laion/CLIP-ViT-B-32-laion2B-s34B-b79K", "clip", "MIT"),        # in use today
    "clip-l14": ("laion/CLIP-ViT-L-14-laion2B-s32B-b82K", "clip", "MIT"),
    "siglip-b16": ("google/siglip-base-patch16-224", "siglip", "Apache-2.0"),
    "dinov2-b": ("facebook/dinov2-base", "dinov2", "Apache-2.0"),
}
BASELINE = "clip-b32"


def embed(key, paths, batch=16):
    import torch
    from PIL import Image
    from transformers import AutoImageProcessor, AutoModel, CLIPModel, SiglipModel

    repo, kind, _ = ENCODERS[key]
    # A copy fetched by hand (curl resumes; useful on slow links) is used when present.
    local = os.path.join(os.path.expanduser("~"), ".cache", "fnol-encoders", repo.replace("/", "--"))
    if os.path.exists(os.path.join(local, "model.safetensors")):
        repo = local
    cls = {"clip": CLIPModel, "siglip": SiglipModel, "dinov2": AutoModel}[kind]
    model = cls.from_pretrained(repo, use_safetensors=True).eval()
    processor = AutoImageProcessor.from_pretrained(repo)
    torch.set_num_threads(max(1, (os.cpu_count() or 4) - 2))
    out = []
    for i in range(0, len(paths), batch):
        images = [Image.open(p).convert("RGB") for p in paths[i:i + batch]]
        inputs = processor(images=images, return_tensors="pt")
        with torch.no_grad():
            if kind == "dinov2":
                h = model(**inputs).last_hidden_state
                feats = torch.cat([h[:, 0], h[:, 1:].mean(1)], dim=-1)   # CLS token + mean of patch tokens
            else:
                feats = model.get_image_features(**inputs)
        out.append(torch.nn.functional.normalize(feats, dim=-1).numpy())
        print(f"  {key}: embedded {min(i + batch, len(paths))}/{len(paths)}", flush=True)
    return np.concatenate(out)


def ece_top(proba, y, bins=10):
    """Calibration error of the top probability: equal-count bins, |mean confidence - accuracy|, weighted."""
    conf, right = proba.max(1), (proba.argmax(1) == y).astype(float)
    order = np.argsort(conf)
    return float(sum(len(b) / len(y) * abs(conf[b].mean() - right[b].mean())
                     for b in np.array_split(order, bins) if len(b)))


def out_of_fold(X, y):
    from sklearn.linear_model import LogisticRegression
    from sklearn.metrics import log_loss
    from sklearn.model_selection import StratifiedKFold

    oof, chosen = np.zeros((len(y), len(AUTO_CLASSES))), []
    for train, test in StratifiedKFold(n_splits=5, shuffle=True, random_state=SEED).split(X, y):
        inner = list(StratifiedKFold(n_splits=4, shuffle=True, random_state=SEED).split(X[train], y[train]))
        best = None
        for c in C_GRID:
            p = np.zeros((len(train), len(AUTO_CLASSES)))
            for itr, ite in inner:
                m = LogisticRegression(C=c, max_iter=5000, class_weight="balanced").fit(X[train][itr], y[train][itr])
                p[ite[:, None], m.classes_] = m.predict_proba(X[train][ite])
            loss = log_loss(y[train], np.clip(p, 1e-6, 1), labels=range(len(AUTO_CLASSES)))
            if best is None or loss < best[0]:
                best = (loss, c)
        chosen.append(best[1])
        m = LogisticRegression(C=best[1], max_iter=5000, class_weight="balanced").fit(X[train], y[train])
        oof[test[:, None], m.classes_] = m.predict_proba(X[test])
    return oof, chosen


def main():
    from sklearn.metrics import log_loss

    ap = argparse.ArgumentParser()
    ap.add_argument("--encoders", default=",".join(ENCODERS))
    args = ap.parse_args()
    keys = [k for k in args.encoders.split(",") if k]
    if BASELINE not in keys:
        keys.insert(0, BASELINE)

    os.makedirs(RESULTS, exist_ok=True)
    cars = [json.loads(l) for l in open(os.path.join(CAR_DATA, "labels.jsonl"), encoding="utf-8")]
    paths = [os.path.join(CAR_DATA, "hitl", r["path"]) for r in cars]
    y = np.array([AUTO_CLASSES.index(r["primaryZone"]) for r in cars])
    zones = [set(r["zones"]) for r in cars]

    def features(key):
        cache = os.path.join(RESULTS, f"emb-{key}-car.npy")
        if not os.path.exists(cache):
            np.save(cache, embed(key, paths))
        return np.load(cache)

    rows, strict_hits, losses = {}, {}, {}
    for key in keys:
        # "a+b": both encoders' embeddings side by side, each unit length, so neither dominates.
        X = np.concatenate([features(k) for k in key.split("+")], axis=1) / np.sqrt(len(key.split("+")))
        oof, chosen = out_of_fold(X, y)
        pred = oof.argmax(1)
        strict_hits[key] = (pred == y).astype(float)
        losses[key] = -np.log(np.clip(oof[np.arange(len(y)), y], 1e-6, 1))
        onehot = np.eye(len(AUTO_CLASSES))[y]
        rows[key] = {
            "dim": X.shape[1], "C": chosen,
            "strict": float(strict_hits[key].mean()),
            "lenient": float(np.mean([AUTO_CLASSES[p] in z for p, z in zip(pred, zones)])),
            "logLoss": float(log_loss(y, np.clip(oof, 1e-6, 1), labels=range(len(AUTO_CLASSES)))),
            "brier": float(((oof - onehot) ** 2).sum(1).mean()),
            "ece": ece_top(oof, y),
            "perClass": {c: float((pred[y == i] == i).mean()) for i, c in enumerate(AUTO_CLASSES)},
        }
        print(f"{key}: strict {rows[key]['strict']:.3f}, lenient {rows[key]['lenient']:.3f}, "
              f"log loss {rows[key]['logLoss']:.3f}, ECE {rows[key]['ece']:.3f}", flush=True)

    # Paired bootstrap: the same resampled photos for both encoders.
    rng = np.random.default_rng(SEED)
    idx = rng.integers(0, len(y), size=(5000, len(y)))
    for key in keys:
        d = strict_hits[key][idx].mean(1) - strict_hits[BASELINE][idx].mean(1)
        rows[key]["diff"] = (float(d.mean()), float(np.percentile(d, 2.5)), float(np.percentile(d, 97.5)))
        l = losses[key][idx].mean(1) - losses[BASELINE][idx].mean(1)
        rows[key]["lossDiff"] = (float(l.mean()), float(np.percentile(l, 2.5)), float(np.percentile(l, 97.5)))

    n = len(y)
    lines = ["# Image encoders for our own car-damage model", "",
             f"{n} Humans in the Loop car photos (CC0). Logistic regression on each encoder's embeddings, class-balanced, "
             f"5-fold stratified (seed {SEED}); regularisation chosen inside each training fold. Every number is "
             "out-of-fold. Lower is better for log loss, Brier and calibration error (ECE, top probability, 10 "
             f"equal-count bins). Differences are against `{BASELINE}` (in use today): paired bootstrap over the same photos, "
             "mean and 95% range; for log loss a negative difference is an improvement. `a + b` means both encoders' "
             "embeddings side by side.", "",
             "| Encoder | Licence | Dim | Main area right | One of the areas | Log loss | Brier | ECE | Strict vs today | Log loss vs today |",
             "|---|---|---|---|---|---|---|---|---|---|"]
    for key in keys:
        r, parts = rows[key], key.split("+")
        repo = " + ".join(f"`{ENCODERS[k][0]}`" for k in parts)
        lic = " + ".join(sorted({ENCODERS[k][2] for k in parts}))
        d, l = r["diff"], r["lossDiff"]
        diff = "baseline" if key == BASELINE else f"{d[0]:+.1%} ({d[1]:+.1%} to {d[2]:+.1%})"
        ldiff = "baseline" if key == BASELINE else f"{l[0]:+.3f} ({l[1]:+.3f} to {l[2]:+.3f})"
        lines.append(f"| {repo} | {lic} | {r['dim']} | {r['strict']:.1%} | {r['lenient']:.1%} | {r['logLoss']:.3f} | "
                     f"{r['brier']:.3f} | {r['ece']:.3f} | {diff} | {ldiff} |")
    counts = {c: int((y == i).sum()) for i, c in enumerate(AUTO_CLASSES)}
    lines += ["", "Main area right, per true class (n = " + ", ".join(f"{c} {k}" for c, k in counts.items()) + "):", "",
              "| Encoder | " + " | ".join(AUTO_CLASSES) + " |", "|---" * (len(AUTO_CLASSES) + 1) + "|"]
    for key in keys:
        lines.append(f"| {key} | " + " | ".join(f"{rows[key]['perClass'][c]:.0%}" for c in AUTO_CLASSES) + " |")
    lines += ["", "Regularisation chosen per outer fold: " +
              "; ".join(f"{k} {rows[k]['C']}" for k in keys) + "."]
    with open(os.path.join(RESULTS, "report-encoders.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    sys.exit(main())
