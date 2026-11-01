"""Trains the second-opinion vision model and exports it for the fnol-vision-ml service.

The second opinion is a different kind of model from Gemini, so the two rarely make the same mistake; when they
disagree, the claim goes to an adjuster (MODEL_DISAGREEMENT). It has three heads on one image encoder:

  auto     damaged vehicle area (FRONT_BUMPER, REAR_BUMPER, SIDE_PANEL, WINDSHIELD): a logistic-regression head
           trained on CLIP + DINOv2 image embeddings (side by side) of the Humans in the Loop car-damage photos (CC0),
           5-fold cross-validated so every photo gets a prediction from a model that never saw it; the regularisation
           is chosen inside each training fold. Encoder choice: benchmark_encoders.py, results/report-encoders.md.
  extent   visible damage extent (share of the photo covered by damage, SMALL / MEDIUM / LARGE): ridge regression on
           the same embeddings. A stand-in for severity that depends on how close the photo was taken; not a cost.
  subject  what the photos show (VEHICLE, BUILDING, OTHER_PROPERTY, NONE): zero-shot from text descriptions.
  home     cause of loss (FLOOD, FIRE_SMOKE, WIND_STORM, FALLEN_TREE, WATER_DAMAGE): zero-shot from text
           descriptions, checked on the hand-reviewed FEMA photos (tools/vision-eval/prepare_home.py).

Image encoders: LAION CLIP ViT-B/32 (laion/CLIP-ViT-B-32-laion2B-s34B-b79K, MIT licence) and Meta DINOv2-base
(facebook/dinov2-base, Apache-2.0; image only, so the zero-shot heads use CLIP), loaded from safetensors only (no
pickle files). DINOv2 features: CLS token and mean patch token side by side, unit length. Data: tools/vision-eval/data (run prepare.py and prepare_home.py first).

Usage:  python -P build_model.py
Writes: ../../fnol-vision-ml/model/second-opinion-model.json   (heads + text embeddings; the service loads it)
        results/oof-auto.jsonl             (out-of-fold car predictions, for compare_gemini.py)
        results/report-second-opinion.md
"""
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
EVAL = os.path.join(HERE, "..", "vision-eval")
CAR_DATA = os.path.join(EVAL, "data")
HOME_DATA = os.path.join(EVAL, "data", "home")
MODEL_DIR = os.path.join(HERE, "..", "..", "fnol-vision-ml", "model")   # shipped with the service
RESULTS = os.path.join(HERE, "results")
ENCODER = "laion/CLIP-ViT-B-32-laion2B-s34B-b79K"
DINO = "facebook/dinov2-base"
SEED = 7
C_GRID = [1, 2, 4, 8, 16, 32, 64]
ALPHAS = [0.1, 1, 10, 100]
EXTENT_BANDS = ["SMALL", "MEDIUM", "LARGE"]

AUTO_CLASSES = ["FRONT_BUMPER", "REAR_BUMPER", "SIDE_PANEL", "WINDSHIELD"]
# Zero-shot heads: several phrasings per class, averaged (prompt ensembling).
SUBJECT_PROMPTS = {
    "VEHICLE": ["a photo of a damaged car", "a photo of a car", "a close-up photo of a car body panel",
                "a photo of a truck or van"],
    "BUILDING": ["a photo of a damaged house", "a photo of a house", "a photo of the inside of a room",
                 "a photo of a building"],
    "OTHER_PROPERTY": ["a photo of a damaged fence", "a photo of a boat", "a photo of furniture",
                       "a photo of a garden"],
    "NONE": ["a photo of a person", "a photo of a document", "a blank or black image", "a photo of a landscape"],
}
HOME_PROMPTS = {
    "FLOOD": ["a house surrounded by flood water", "a flooded street with houses", "flood water up to the windows of a home"],
    "FIRE_SMOKE": ["a house destroyed by fire", "burned ruins of a home", "a fire damaged building with ash and debris"],
    "WIND_STORM": ["a house with its roof torn off by a tornado", "a home destroyed by hurricane winds",
                   "storm damaged house with debris"],
    "FALLEN_TREE": ["a large tree fallen on a house", "an uprooted tree lying on a roof", "a tree crushed a home"],
    "WATER_DAMAGE": ["water damage inside a house from a burst pipe", "a wet stained ceiling from a leak",
                     "a wall opened to repair a leaking pipe"],
}


def load_encoder():
    from transformers import CLIPModel, CLIPProcessor
    model = CLIPModel.from_pretrained(ENCODER, use_safetensors=True).eval()
    return model, CLIPProcessor.from_pretrained(ENCODER)


def embed_images(model, processor, paths, batch=32):
    import torch
    from PIL import Image
    out = []
    for i in range(0, len(paths), batch):
        images = [Image.open(p).convert("RGB") for p in paths[i:i + batch]]
        with torch.no_grad():
            feats = model.get_image_features(**processor(images=images, return_tensors="pt"))
        out.append(torch.nn.functional.normalize(feats, dim=-1).numpy())
        print(f"  embedded {min(i + batch, len(paths))}/{len(paths)}", flush=True)
    return np.concatenate(out)


def embed_dino(paths, batch=16):
    """DINOv2 features exactly as fnol-vision-ml computes them: [CLS, mean of patch tokens], unit length."""
    import torch
    from PIL import Image
    from transformers import AutoImageProcessor, AutoModel
    local = os.path.join(os.path.expanduser("~"), ".cache", "fnol-encoders", DINO.replace("/", "--"))
    src = local if os.path.exists(os.path.join(local, "model.safetensors")) else DINO
    model = AutoModel.from_pretrained(src, use_safetensors=True).eval()
    processor = AutoImageProcessor.from_pretrained(src)
    out = []
    for i in range(0, len(paths), batch):
        images = [Image.open(p).convert("RGB") for p in paths[i:i + batch]]
        with torch.no_grad():
            h = model(**processor(images=images, return_tensors="pt")).last_hidden_state
        feats = torch.cat([h[:, 0], h[:, 1:].mean(1)], dim=-1)
        out.append(torch.nn.functional.normalize(feats, dim=-1).numpy())
        print(f"  DINOv2 embedded {min(i + batch, len(paths))}/{len(paths)}", flush=True)
    return np.concatenate(out)


def combine(clip_emb, dino_emb):
    """Both embeddings side by side, each unit length, scaled so the joint vector is unit length."""
    return np.concatenate([clip_emb, dino_emb], axis=1) / np.sqrt(2)


def choose_c(X, y):
    """Regularisation by 4-fold log loss on the given (training) photos only."""
    from sklearn.linear_model import LogisticRegression
    from sklearn.metrics import log_loss
    from sklearn.model_selection import StratifiedKFold
    best = None
    for c in C_GRID:
        p = np.zeros((len(y), len(AUTO_CLASSES)))
        for tr, te in StratifiedKFold(n_splits=4, shuffle=True, random_state=SEED).split(X, y):
            m = LogisticRegression(C=c, max_iter=5000, class_weight="balanced").fit(X[tr], y[tr])
            p[te[:, None], m.classes_] = m.predict_proba(X[te])
        loss = log_loss(y, np.clip(p, 1e-6, 1), labels=range(len(AUTO_CLASSES)))
        if best is None or loss < best[0]:
            best = (loss, c)
    return best[1]


def choose_alpha(X, t):
    from sklearn.linear_model import Ridge
    from sklearn.model_selection import KFold
    best = None
    for a in ALPHAS:
        p = np.zeros(len(t))
        for tr, te in KFold(n_splits=4, shuffle=True, random_state=SEED).split(X):
            p[te] = Ridge(alpha=a).fit(X[tr], t[tr]).predict(X[te])
        err = float(((p - t) ** 2).mean())
        if best is None or err < best[0]:
            best = (err, a)
    return best[1]


def choose_c_for(X, y, n_classes):
    """choose_c for any number of classes (choose_c is for the car areas)."""
    from sklearn.linear_model import LogisticRegression
    from sklearn.metrics import log_loss
    from sklearn.model_selection import StratifiedKFold
    best = None
    for c in [0.25, 1, 4, 16, 64]:
        p = np.zeros((len(y), n_classes))
        for tr, te in StratifiedKFold(n_splits=4, shuffle=True, random_state=SEED).split(X, y):
            m = LogisticRegression(C=c, max_iter=5000, class_weight="balanced").fit(X[tr], y[tr])
            p[te[:, None], m.classes_] = m.predict_proba(X[te])
        loss = log_loss(y, np.clip(p, 1e-6, 1), labels=range(n_classes))
        if best is None or loss < best[0]:
            best = (loss, c)
    return best[1]


def embed_text_classes(model, processor, prompts):
    import torch
    classes, vectors = [], []
    for cls, texts in prompts.items():
        with torch.no_grad():
            t = model.get_text_features(**processor(text=texts, return_tensors="pt", padding=True))
        t = torch.nn.functional.normalize(t, dim=-1).mean(0)
        vectors.append(torch.nn.functional.normalize(t, dim=0).numpy())
        classes.append(cls)
    return classes, np.stack(vectors)


def softmax(x):
    e = np.exp(x - x.max(axis=-1, keepdims=True))
    return e / e.sum(axis=-1, keepdims=True)


def cached(name, fn):
    path = os.path.join(RESULTS, name)
    if os.path.exists(path):
        return np.load(path)
    arr = fn()
    np.save(path, arr)
    return arr


def main():
    from sklearn.linear_model import LogisticRegression
    from sklearn.metrics import confusion_matrix
    sys.path.insert(0, HERE)
    from sklearn.model_selection import StratifiedKFold

    os.makedirs(MODEL_DIR, exist_ok=True)
    os.makedirs(RESULTS, exist_ok=True)
    cars = [json.loads(l) for l in open(os.path.join(CAR_DATA, "labels.jsonl"), encoding="utf-8")]
    homes = [json.loads(l) for l in open(os.path.join(HOME_DATA, "labels.jsonl"), encoding="utf-8")]
    model, processor = load_encoder()
    logit_scale = float(model.logit_scale.exp().item())

    car_paths = [os.path.join(CAR_DATA, "hitl", r["path"]) for r in cars]
    print("Car photos:")
    X_clip = cached("emb-car.npy", lambda: embed_images(model, processor, car_paths))
    X_dino = cached("emb-dinov2-b-car.npy", lambda: embed_dino(car_paths))
    n_cc0 = len(cars)
    # Extra car photos: CC BY 4.0 Roboflow Universe datasets, every one reviewed on a contact sheet (watermarked stock
    # photos and unclear ones excluded, labels corrected): tools/vision-eval/prepare_roboflow.py, roboflow_review.json.
    extra_file = os.path.join(CAR_DATA, "roboflow", "reviewed.jsonl")
    extra = [json.loads(l) for l in open(extra_file, encoding="utf-8")] if os.path.exists(extra_file) else []
    if extra:
        names = os.path.join(RESULTS, "emb-car-extra.names.json")
        listed = [r["image"] for r in extra]
        if not os.path.exists(names) or json.load(open(names, encoding="utf-8")) != listed:
            for stale in ("emb-car-extra-clip.npy", "emb-car-extra-dino.npy"):
                if os.path.exists(os.path.join(RESULTS, stale)):
                    os.remove(os.path.join(RESULTS, stale))
            json.dump(listed, open(names, "w", encoding="utf-8"))
        extra_paths = [os.path.join(CAR_DATA, "roboflow", r["path"]) for r in extra]
        X_clip = np.vstack([X_clip, cached("emb-car-extra-clip.npy", lambda: embed_images(model, processor, extra_paths))])
        X_dino = np.vstack([X_dino, cached("emb-car-extra-dino.npy", lambda: embed_dino(extra_paths))])
        cars = cars + extra
    print(f"{len(cars)} car photos: {n_cc0} CC0 + {len(cars) - n_cc0} reviewed CC BY 4.0")
    X = combine(X_clip, X_dino)
    print("Home photos:")
    H = cached("emb-home.npy", lambda: embed_images(model, processor,
                                                    [os.path.join(HOME_DATA, r["path"]) for r in homes]))

    # ── auto head: logistic regression, 5-fold out-of-fold predictions ──
    y = np.array([AUTO_CLASSES.index(r["primaryZone"]) for r in cars])
    oof = np.zeros((len(cars), len(AUTO_CLASSES)))
    chosen = []
    # WINDSHIELD has only 6 photos: StratifiedKFold keeps at least one in each of the 5 folds.
    for train, test in StratifiedKFold(n_splits=5, shuffle=True, random_state=SEED).split(X, y):
        c = choose_c(X[train], y[train])      # chosen without the test fold
        chosen.append(c)
        clf = LogisticRegression(C=c, max_iter=5000, class_weight="balanced")
        clf.fit(X[train], y[train])
        proba = np.zeros((len(test), len(AUTO_CLASSES)))
        proba[:, clf.classes_] = clf.predict_proba(X[test])
        oof[test] = proba
    pred = oof.argmax(1)
    strict = float((pred == y).mean())
    lenient = float(np.mean([AUTO_CLASSES[p] in r["zones"] for p, r in zip(pred, cars)]))
    # The same out-of-fold predictions, on the CC0 photos alone: comparable with earlier runs trained on them only.
    strict_cc0 = float((pred[:n_cc0] == y[:n_cc0]).mean())
    lenient_cc0 = float(np.mean([AUTO_CLASSES[p] in r["zones"] for p, r in zip(pred[:n_cc0], cars[:n_cc0])]))
    final_c = choose_c(X, y)
    final = LogisticRegression(C=final_c, max_iter=5000, class_weight="balanced").fit(X, y)
    onehot = np.eye(len(AUTO_CLASSES))[y]
    log_loss_oof = float(-np.log(np.clip(oof[np.arange(len(y)), y], 1e-6, 1)).mean())
    brier_oof = float(((oof - onehot) ** 2).sum(1).mean())

    # ── CLIP-only car head, for claims without a close-up: DINOv2 on every photo of a claim is too slow on the
    #    service's 2 CPU threads (measured 2.4 s median, 6.6 s worst for 5 photos), so those use CLIP alone ──
    oof_clip = np.zeros((len(cars), len(AUTO_CLASSES)))
    for train, test in StratifiedKFold(n_splits=5, shuffle=True, random_state=SEED).split(X_clip, y):
        m = LogisticRegression(C=choose_c(X_clip[train], y[train]), max_iter=5000, class_weight="balanced")
        m.fit(X_clip[train], y[train])
        oof_clip[test[:, None], m.classes_] = m.predict_proba(X_clip[test])
    strict_clip = float((oof_clip.argmax(1) == y).mean())
    lenient_clip = float(np.mean([AUTO_CLASSES[p] in r["zones"] for p, r in zip(oof_clip.argmax(1), cars)]))
    clip_c = choose_c(X_clip, y)
    clip_final = LogisticRegression(C=clip_c, max_iter=5000, class_weight="balanced").fit(X_clip, y)

    # ── extent head: ridge regression on log(share of the photo covered by damage) ──
    from scipy.stats import spearmanr
    from sklearn.linear_model import Ridge
    from sklearn.model_selection import KFold
    # Only the CC0 photos have damage outlines, so the extent head learns from them alone.
    X_ext = X[:n_cc0]
    target = np.log(np.clip(np.array([r["damageAreaShare"] for r in cars[:n_cc0]]), 1e-4, None))
    ext_oof = np.zeros(n_cc0)
    for train, test in KFold(n_splits=5, shuffle=True, random_state=SEED).split(X_ext):
        ext_oof[test] = Ridge(alpha=choose_alpha(X_ext[train], target[train])).fit(X_ext[train], target[train]).predict(X_ext[test])
    ext_final = Ridge(alpha=choose_alpha(X_ext, target)).fit(X_ext, target)
    # Bands: tertiles of the TRUE extent, so SMALL / MEDIUM / LARGE each cover a third of the labelled photos.
    cuts = np.percentile(target, [100 / 3, 200 / 3])
    ext_rho = float(spearmanr(ext_oof, target).correlation)
    true_band, pred_band = np.digitize(target, cuts), np.digitize(ext_oof, cuts)
    ext_band_acc = float((true_band == pred_band).mean())
    ext_far = float((abs(true_band - pred_band) == 2).mean())
    with open(os.path.join(RESULTS, "oof-auto.jsonl"), "w", encoding="utf-8", newline="\n") as f:
        for i, (r, p) in enumerate(zip(cars, oof)):
            # extentBand: the out-of-fold visible-extent band (tools/cost-eval replays the published cost ranges with it);
            # only the CC0 photos have one
            row = {"image": r["image"], "probabilities": dict(zip(AUTO_CLASSES, np.round(p, 4).tolist()))}
            if i < n_cc0:
                row["extentBand"] = EXTENT_BANDS[int(np.digitize(ext_oof[i], cuts))]
            f.write(json.dumps(row) + "\n")
    # The fraud score's reference distribution for genuine claims (tools/fraud-eval/fraud_eval.py).
    disagreement = [round(1 - float(p[t]), 4) for p, t in zip(oof, y)]
    with open(os.path.join(HERE, "..", "fraud-eval", "second-opinion-disagreement.json"), "w", encoding="utf-8",
              newline="\n") as f:
        json.dump({"description": "Second-opinion model's disagreement with the TRUE main damaged area (1 - its "
                                  "out-of-fold probability for that area) on each labelled car photo. The reference "
                                  "distribution for genuine claims in fraud_eval.py.",
                   "source": "tools/second-opinion/build_model.py: results/oof-auto.jsonl (5-fold out-of-fold), "
                             "labels from tools/vision-eval/prepare.py (Humans in the Loop, CC0) and the reviewed "
                             "CC BY 4.0 photos (tools/vision-eval/roboflow_review.json)",
                   "model": f"{ENCODER} + {DINO}", "n": len(disagreement), "values": disagreement}, f, indent=1)

    # ── zero-shot heads ──
    subj_classes, subj_text = embed_text_classes(model, processor, SUBJECT_PROMPTS)
    home_classes, home_text = embed_text_classes(model, processor, HOME_PROMPTS)
    subj_car = softmax(logit_scale * X_clip @ subj_text.T).argmax(1)   # zero-shot heads use CLIP alone
    subj_home = softmax(logit_scale * H @ subj_text.T).argmax(1)
    home_pred = softmax(logit_scale * H @ home_text.T).argmax(1)
    home_true = [r["cause"] for r in homes]
    home_acc = float(np.mean([home_classes[p] == t for p, t in zip(home_pred, home_true)]))

    # ── trained property heads (cause of loss, property type) on the reviewed FEMA photos; CLIP embeddings only, so a
    #    property claim needs no DINOv2 pass. Comparison with zero-shot: property_heads.py, results/report-property.md ──
    from property_heads import CAUSES, TYPES, oof_logistic
    prop_file = os.path.join(HERE, "..", "vision-eval", "data", "property", "reviewed.jsonl")
    prop = [json.loads(l) for l in open(prop_file, encoding="utf-8")]
    prop_paths = [os.path.join(os.path.dirname(prop_file), r["path"]) for r in prop]
    names = os.path.join(RESULTS, "emb-property.names.json")
    if not os.path.exists(names) or json.load(open(names, encoding="utf-8")) != [r["image"] for r in prop]:
        for stale in ("emb-property-clip.npy", "emb-property-dino.npy"):
            if os.path.exists(os.path.join(RESULTS, stale)):
                os.remove(os.path.join(RESULTS, stale))
        json.dump([r["image"] for r in prop], open(names, "w", encoding="utf-8"))
    P = cached("emb-property-clip.npy", lambda: embed_images(model, processor, prop_paths))
    trained = {}
    for key, classes in (("cause", CAUSES), ("subject", TYPES)):
        yp = np.array([classes.index(r[key]) for r in prop])
        oof_p = oof_logistic(P, yp, classes)
        c_p = choose_c_for(P, yp, len(classes))
        head = LogisticRegression(C=c_p, max_iter=5000, class_weight="balanced").fit(P, yp)
        trained[key] = {"classes": classes, "coef": head.coef_.round(6).tolist(),
                        "intercept": head.intercept_.round(6).tolist(), "C": c_p,
                        "accuracy": float((oof_p.argmax(1) == yp).mean()), "n": len(prop)}

    export = {
        "version": 2,
        "encoder": ENCODER,
        "dinoEncoder": DINO,
        "embeddingDim": int(X.shape[1]),            # CLIP + DINOv2 side by side (auto and extent heads)
        "logitScale": logit_scale,
        "auto": {"classes": AUTO_CLASSES, "coef": final.coef_.round(6).tolist(),
                 "intercept": final.intercept_.round(6).tolist(), "C": final_c,
                 "trainedOn": f"{n_cc0} Humans in the Loop car photos (CC0) + {len(cars) - n_cc0} reviewed CC BY 4.0 "
                              "Roboflow Universe photos"},
        # Used when a claim has no close-up photo (speed); CLIP embeddings only.
        "autoClipOnly": {"classes": AUTO_CLASSES, "coef": clip_final.coef_.round(6).tolist(),
                         "intercept": clip_final.intercept_.round(6).tolist(), "C": clip_c},
        "extent": {"bands": EXTENT_BANDS, "coef": ext_final.coef_.round(6).tolist(),
                   "intercept": round(float(ext_final.intercept_), 6), "logShareCuts": cuts.round(6).tolist(),
                   "note": "share of the photo covered by damage; depends on how close the photo was taken; not a cost"},
        "subject": {"classes": subj_classes, "textEmbeddings": subj_text.round(6).tolist()},
        "home": {"classes": home_classes, "textEmbeddings": home_text.round(6).tolist()},
        # Trained on reviewed FEMA photos; replaces the zero-shot home head (no WATER_DAMAGE class: no photos).
        "homeTrained": {k: v for k, v in trained["cause"].items() if k != "accuracy"},
        "propertyType": {k: v for k, v in trained["subject"].items() if k != "accuracy"},
        "metrics": {"autoStrictAccuracyOutOfFold": round(strict, 4), "autoLenientAccuracyOutOfFold": round(lenient, 4),
                    "autoLogLossOutOfFold": round(log_loss_oof, 4), "autoBrierOutOfFold": round(brier_oof, 4),
                    "autoStrictAccuracyCc0OutOfFold": round(strict_cc0, 4), "carPhotos": len(cars), "carPhotosCc0": n_cc0,
                    "autoClipOnlyStrictAccuracyOutOfFold": round(strict_clip, 4),
                    "extentSpearmanOutOfFold": round(ext_rho, 4), "extentBandAccuracyOutOfFold": round(ext_band_acc, 4),
                    "homeZeroShotAccuracy": round(home_acc, 4),
                    "homeTrainedAccuracyOutOfFold": round(trained["cause"]["accuracy"], 4),
                    "propertyTypeAccuracyOutOfFold": round(trained["subject"]["accuracy"], 4)},
    }
    with open(os.path.join(MODEL_DIR, "second-opinion-model.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(export, f)

    # ── report ──
    cm = confusion_matrix(y, pred, labels=range(len(AUTO_CLASSES)))
    lines = ["# Second-opinion model", "",
             f"Image encoders `{ENCODER}` (MIT) and `{DINO}` (Apache-2.0), safetensors weights. Car and extent heads: "
             f"trained on both embeddings side by side, 5-fold cross-validation (seed {SEED}) over {len(cars)} car photos "
             f"({n_cc0} Humans in the Loop, CC0; {len(cars) - n_cc0} reviewed CC BY 4.0 from Roboflow Universe; the extent "
             f"head on the CC0 photos only), regularisation chosen inside each training fold (car head C per fold: {chosen}); "
             "every number below is out-of-fold. Subject and home heads are zero-shot on CLIP (text descriptions, no "
             "training).",
             "", "| Head | Measure | Value |", "|---|---|---|",
             f"| Car damage area | main area right (strict) | **{strict:.1%}** |",
             f"| Car damage area | one of the damaged areas (lenient) | {lenient:.1%} |",
             f"| Car damage area, the {n_cc0} CC0 photos only (comparable with earlier runs) | strict / lenient | "
             f"{strict_cc0:.1%} / {lenient_cc0:.1%} |",
             f"| Car damage area | log loss / Brier (lower is better) | {log_loss_oof:.3f} / {brier_oof:.3f} |",
             f"| Car damage area, CLIP-only head (claims without a close-up) | strict / lenient | {strict_clip:.1%} / {lenient_clip:.1%} |",
             f"| Visible extent | rank correlation with the true share of the photo damaged | {ext_rho:.2f} |",
             f"| Visible extent | SMALL / MEDIUM / LARGE right (chance 33%) | {ext_band_acc:.0%} |",
             f"| Visible extent | off by two bands | {ext_far:.0%} |",
             f"| Photo subject | car photos reported as VEHICLE | {int((np.array(subj_classes)[subj_car] == 'VEHICLE').sum())}/{len(cars)} |",
             f"| Photo subject | home photos reported as BUILDING | {int((np.array(subj_classes)[subj_home] == 'BUILDING').sum())}/{len(homes)} |",
             f"| Home cause of loss | zero-shot accuracy on {len(homes)} hand-reviewed FEMA photos (previous head) | {home_acc:.1%} |",
             f"| Property cause of loss | trained head, out-of-fold on {len(prop)} reviewed FEMA photos (flood, fire, wind, "
             f"fallen tree) | **{trained['cause']['accuracy']:.1%}** |",
             f"| Property type | trained head, out-of-fold (residential, commercial, other property) | "
             f"**{trained['subject']['accuracy']:.1%}** |",
             "", "Car head, out-of-fold confusion matrix (rows = true main area):", "",
             "| True \\ predicted | " + " | ".join(AUTO_CLASSES) + " | n |", "|---" * (len(AUTO_CLASSES) + 2) + "|"]
    for i, cls in enumerate(AUTO_CLASSES):
        lines.append(f"| {cls} | " + " | ".join(str(v) for v in cm[i]) + f" | {cm[i].sum()} |")
    lines += ["", "Home cause of loss, zero-shot (rows = true cause):", "",
              "| True \\ predicted | " + " | ".join(home_classes) + " |", "|---" * (len(home_classes) + 1) + "|"]
    hcm = confusion_matrix(home_true, [home_classes[p] for p in home_pred], labels=home_classes)
    for i, cls in enumerate(home_classes):
        lines.append(f"| {cls} | " + " | ".join(str(v) for v in hcm[i]) + " |")
    lines += ["", "WINDSHIELD has only 6 photos in the dataset and no properly licensed photos could be added, so the car "
              "head is weak on windshields; WATER_DAMAGE has no labelled photos (FEMA captions do not cover burst pipes)."]
    with open(os.path.join(RESULTS, "report-second-opinion.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    sys.exit(main())
