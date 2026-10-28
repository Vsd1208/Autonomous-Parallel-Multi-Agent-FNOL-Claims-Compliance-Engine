"""Trains the second-opinion vision model and exports it for the fnol-vision-ml service.

The second opinion is a different kind of model from Gemini, so the two rarely make the same mistake; when they
disagree, the claim goes to an adjuster (MODEL_DISAGREEMENT). It has three heads on one image encoder:

  auto     damaged vehicle area (FRONT_BUMPER, REAR_BUMPER, SIDE_PANEL, WINDSHIELD): a logistic-regression head
           trained on CLIP image embeddings of the Humans in the Loop car-damage photos (CC0), 5-fold
           cross-validated so every photo gets a prediction from a model that never saw it.
  subject  what the photos show (VEHICLE, BUILDING, OTHER_PROPERTY, NONE): zero-shot from text descriptions.
  home     cause of loss (FLOOD, FIRE_SMOKE, WIND_STORM, FALLEN_TREE, WATER_DAMAGE): zero-shot from text
           descriptions, checked on the hand-reviewed FEMA photos (tools/vision-eval/prepare_home.py).

Image encoder: LAION CLIP ViT-B/32 (laion/CLIP-ViT-B-32-laion2B-s34B-b79K, MIT licence), loaded from safetensors
only (no pickle files). Data: tools/vision-eval/data (run prepare.py and prepare_home.py first).

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
SEED = 7

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
    from sklearn.model_selection import StratifiedKFold

    os.makedirs(MODEL_DIR, exist_ok=True)
    os.makedirs(RESULTS, exist_ok=True)
    cars = [json.loads(l) for l in open(os.path.join(CAR_DATA, "labels.jsonl"), encoding="utf-8")]
    homes = [json.loads(l) for l in open(os.path.join(HOME_DATA, "labels.jsonl"), encoding="utf-8")]
    model, processor = load_encoder()
    logit_scale = float(model.logit_scale.exp().item())

    print("Car photos:")
    X = cached("emb-car.npy", lambda: embed_images(model, processor,
                                                   [os.path.join(CAR_DATA, "hitl", r["path"]) for r in cars]))
    print("Home photos:")
    H = cached("emb-home.npy", lambda: embed_images(model, processor,
                                                    [os.path.join(HOME_DATA, r["path"]) for r in homes]))

    # ── auto head: logistic regression, 5-fold out-of-fold predictions ──
    y = np.array([AUTO_CLASSES.index(r["primaryZone"]) for r in cars])
    oof = np.zeros((len(cars), len(AUTO_CLASSES)))
    # WINDSHIELD has only 6 photos: StratifiedKFold keeps at least one in each of the 5 folds.
    for train, test in StratifiedKFold(n_splits=5, shuffle=True, random_state=SEED).split(X, y):
        clf = LogisticRegression(C=2.0, max_iter=5000, class_weight="balanced")
        clf.fit(X[train], y[train])
        proba = np.zeros((len(test), len(AUTO_CLASSES)))
        proba[:, clf.classes_] = clf.predict_proba(X[test])
        oof[test] = proba
    pred = oof.argmax(1)
    strict = float((pred == y).mean())
    lenient = float(np.mean([AUTO_CLASSES[p] in r["zones"] for p, r in zip(pred, cars)]))
    final = LogisticRegression(C=2.0, max_iter=5000, class_weight="balanced").fit(X, y)
    with open(os.path.join(RESULTS, "oof-auto.jsonl"), "w", encoding="utf-8", newline="\n") as f:
        for r, p in zip(cars, oof):
            f.write(json.dumps({"image": r["image"], "probabilities": dict(zip(AUTO_CLASSES, np.round(p, 4).tolist()))}) + "\n")

    # ── zero-shot heads ──
    subj_classes, subj_text = embed_text_classes(model, processor, SUBJECT_PROMPTS)
    home_classes, home_text = embed_text_classes(model, processor, HOME_PROMPTS)
    subj_car = softmax(logit_scale * X @ subj_text.T).argmax(1)
    subj_home = softmax(logit_scale * H @ subj_text.T).argmax(1)
    home_pred = softmax(logit_scale * H @ home_text.T).argmax(1)
    home_true = [r["cause"] for r in homes]
    home_acc = float(np.mean([home_classes[p] == t for p, t in zip(home_pred, home_true)]))

    export = {
        "version": 1,
        "encoder": ENCODER,
        "embeddingDim": int(X.shape[1]),
        "logitScale": logit_scale,
        "auto": {"classes": AUTO_CLASSES, "coef": final.coef_.round(6).tolist(),
                 "intercept": final.intercept_.round(6).tolist(), "trainedOn": f"{len(cars)} Humans in the Loop car photos (CC0)"},
        "subject": {"classes": subj_classes, "textEmbeddings": subj_text.round(6).tolist()},
        "home": {"classes": home_classes, "textEmbeddings": home_text.round(6).tolist()},
        "metrics": {"autoStrictAccuracyOutOfFold": round(strict, 4), "autoLenientAccuracyOutOfFold": round(lenient, 4),
                    "homeZeroShotAccuracy": round(home_acc, 4)},
    }
    with open(os.path.join(MODEL_DIR, "second-opinion-model.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(export, f)

    # ── report ──
    cm = confusion_matrix(y, pred, labels=range(len(AUTO_CLASSES)))
    lines = ["# Second-opinion model", "",
             f"Image encoder `{ENCODER}` (MIT), safetensors weights. Car head: logistic regression on CLIP embeddings, "
             f"class-balanced, 5-fold stratified cross-validation (seed {SEED}) over {len(cars)} Humans in the Loop photos "
             "(CC0); every number below is out-of-fold. Subject and home heads are zero-shot (text descriptions, no training).",
             "", "| Head | Measure | Value |", "|---|---|---|",
             f"| Car damage area | main area right (strict) | **{strict:.1%}** |",
             f"| Car damage area | one of the damaged areas (lenient) | {lenient:.1%} |",
             f"| Photo subject | car photos reported as VEHICLE | {int((np.array(subj_classes)[subj_car] == 'VEHICLE').sum())}/{len(cars)} |",
             f"| Photo subject | home photos reported as BUILDING | {int((np.array(subj_classes)[subj_home] == 'BUILDING').sum())}/{len(homes)} |",
             f"| Home cause of loss | zero-shot accuracy on {len(homes)} hand-reviewed FEMA photos | {home_acc:.1%} |",
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
