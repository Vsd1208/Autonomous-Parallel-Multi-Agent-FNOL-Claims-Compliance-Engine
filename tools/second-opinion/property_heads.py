"""Property heads for the on-premise model: cause of loss and property type, trained on reviewed FEMA photos and
compared with the zero-shot heads in use, on exactly the same photos.

Data: tools/vision-eval/data/property/reviewed.jsonl (public-domain FEMA photos, every one reviewed on a contact sheet:
tools/vision-eval/property_review.json).
  cause          FLOOD, FIRE_SMOKE, WIND_STORM, FALLEN_TREE (WATER_DAMAGE has no photos: FEMA does not photograph pipe leaks)
  property type  RESIDENTIAL, COMMERCIAL, OTHER_PROPERTY

Every trained number is out-of-fold (5-fold stratified, seed 7; regularisation chosen inside each training fold).
The zero-shot heads need no training, so they are scored on all photos directly. Paired bootstrap for the difference.

Usage:  python -P property_heads.py
Writes: results/report-property.md, results/emb-property-*.npy (cache)
"""
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build_model as bm   # noqa: E402

DATA = os.path.join(HERE, "..", "vision-eval", "data", "property")
CAUSES = ["FLOOD", "FIRE_SMOKE", "WIND_STORM", "FALLEN_TREE"]
TYPES = ["RESIDENTIAL", "COMMERCIAL", "OTHER_PROPERTY"]
TYPE_PROMPTS = {
    "RESIDENTIAL": ["a photo of a damaged house", "a photo of a home", "a photo of a mobile home",
                    "a photo of a residential neighborhood"],
    "COMMERCIAL": ["a photo of a damaged store", "a photo of a business building", "a photo of a school or church",
                   "a photo of a commercial building downtown"],
    "OTHER_PROPERTY": ["a photo of a damaged boat", "a photo of a barn or shed", "a photo of a dock or pier",
                       "a photo of a fence"],
}
SEED = 7


def oof_logistic(X, y, classes):
    from sklearn.linear_model import LogisticRegression
    from sklearn.metrics import log_loss
    from sklearn.model_selection import StratifiedKFold
    out = np.zeros((len(y), len(classes)))
    for tr, te in StratifiedKFold(5, shuffle=True, random_state=SEED).split(X, y):
        best = None
        for c in [0.25, 1, 4, 16, 64]:
            p = np.zeros((len(tr), len(classes)))
            for itr, ite in StratifiedKFold(4, shuffle=True, random_state=SEED).split(X[tr], y[tr]):
                m = LogisticRegression(C=c, max_iter=5000, class_weight="balanced").fit(X[tr][itr], y[tr][itr])
                p[ite[:, None], m.classes_] = m.predict_proba(X[tr][ite])
            loss = log_loss(y[tr], np.clip(p, 1e-6, 1), labels=range(len(classes)))
            if best is None or loss < best[0]:
                best = (loss, c)
        m = LogisticRegression(C=best[1], max_iter=5000, class_weight="balanced").fit(X[tr], y[tr])
        out[te[:, None], m.classes_] = m.predict_proba(X[te])
    return out


def summary(proba, y, classes):
    pred = proba.argmax(1)
    per = {c: (int(((pred == i) & (y == i)).sum()), int((y == i).sum())) for i, c in enumerate(classes)}
    balanced = float(np.mean([a / n for a, n in per.values() if n]))
    loss = float(-np.log(np.clip(proba[np.arange(len(y)), y], 1e-6, 1)).mean())
    return float((pred == y).mean()), balanced, loss, per, (pred == y).astype(float)


def main():
    rows = [json.loads(l) for l in open(os.path.join(DATA, "reviewed.jsonl"), encoding="utf-8")]
    paths = [os.path.join(DATA, r["path"]) for r in rows]
    model, processor = bm.load_encoder()
    scale = float(model.logit_scale.exp().item())
    names = os.path.join(bm.RESULTS, "emb-property.names.json")
    if not os.path.exists(names) or json.load(open(names, encoding="utf-8")) != [r["image"] for r in rows]:
        for f in ("emb-property-clip.npy", "emb-property-dino.npy"):
            if os.path.exists(os.path.join(bm.RESULTS, f)):
                os.remove(os.path.join(bm.RESULTS, f))
        json.dump([r["image"] for r in rows], open(names, "w", encoding="utf-8"))
    X_clip = bm.cached("emb-property-clip.npy", lambda: bm.embed_images(model, processor, paths))
    X_dino = bm.cached("emb-property-dino.npy", lambda: bm.embed_dino(paths))
    X_both = bm.combine(X_clip, X_dino)

    rng = np.random.default_rng(SEED)
    idx = rng.integers(0, len(rows), size=(5000, len(rows)))
    lines = ["# Property heads: trained vs zero-shot", "",
             f"{len(rows)} reviewed public-domain FEMA photos. Trained heads: logistic regression, class-balanced, 5-fold "
             f"stratified out-of-fold (seed {SEED}), regularisation chosen inside each training fold. Zero-shot: CLIP "
             "against text descriptions, as the app does today. Balanced accuracy = mean of per-class accuracy (the "
             "classes are unequal). Differences vs zero-shot: paired bootstrap, 95% range.", ""]
    shipped = {}

    for title, key, classes, prompts in [("Cause of loss", "cause", CAUSES, {c: bm.HOME_PROMPTS[c] for c in CAUSES}),
                                         ("Property type", "subject", TYPES, TYPE_PROMPTS)]:
        y = np.array([classes.index(r[key]) for r in rows])
        text_classes, text = bm.embed_text_classes(model, processor, prompts)
        zs = bm.softmax(scale * X_clip @ text.T)
        zs = zs[:, [text_classes.index(c) for c in classes]]
        trained_clip = oof_logistic(X_clip, y, classes)
        results = {"Zero-shot (CLIP, in use)": summary(zs, y, classes),
                   "Trained on CLIP": summary(trained_clip, y, classes),
                   "Trained on CLIP + DINOv2": summary(oof_logistic(X_both, y, classes), y, classes)}
        if key == "cause":
            # What ships: the trained head for the four causes it has photos for, plus zero-shot's WATER_DAMAGE share
            # (burst pipes: no photos to train on). A photo called WATER_DAMAGE counts as wrong here.
            h_classes, h_text = bm.embed_text_classes(model, processor, bm.HOME_PROMPTS)
            water = bm.softmax(scale * X_clip @ h_text.T)[:, h_classes.index("WATER_DAMAGE")][:, None]
            mixed = np.hstack([trained_clip * (1 - water), water])
            acc = float((mixed.argmax(1) == y).mean())
            results["Shipped: trained on CLIP + zero-shot WATER_DAMAGE"] = summary(mixed[:, :len(classes)], y, classes)
            called_water = int((mixed.argmax(1) == len(classes)).sum())
            lines_extra = [f"Shipped cause head: {acc:.1%} right; {called_water} of {len(rows)} photos (none of them burst "
                           "pipes) called WATER_DAMAGE by the zero-shot share."]
        else:
            lines_extra = []
        base = results["Zero-shot (CLIP, in use)"][4]
        counts = ", ".join(f"{c} {int((y == i).sum())}" for i, c in enumerate(classes))
        lines += [f"## {title} ({counts})", "",
                  "| Head | Accuracy | Balanced accuracy | Log loss | Accuracy vs zero-shot (95% range) | "
                  + " | ".join(classes) + " |", "|---" * (5 + len(classes)) + "|"]
        for name, (acc, bal, loss, per, hits) in results.items():
            d = hits[idx].mean(1) - base[idx].mean(1)
            diff = "baseline" if hits is base else f"{d.mean():+.1%} ({np.percentile(d, 2.5):+.1%} to {np.percentile(d, 97.5):+.1%})"
            lines.append(f"| {name} | {acc:.1%} | {bal:.1%} | {loss:.3f} | {diff} | "
                         + " | ".join(f"{a}/{n}" for a, n in per.values()) + " |")
            shipped.setdefault(key, {})[name] = {"accuracy": round(acc, 4), "balanced": round(bal, 4)}
        lines += [""] + lines_extra + ([""] if lines_extra else [])
    lines += ["Caveats: commercial and other property are small (35 and 41 photos), commercial has no fire or fallen-tree "
              "photos, and other property is mostly storm-damaged boats, so those numbers carry wide ranges. "
              "WATER_DAMAGE (burst pipes) cannot be trained: no public-domain photos."]
    with open(os.path.join(bm.RESULTS, "report-property.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    sys.exit(main())
