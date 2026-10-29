"""Measures what the privacy redaction blurs, and whether it hides damage.

Car photos (Humans in the Loop, CC0): the dataset outlines every damaged area, so the share of damage pixels that a
blurred box covers is measured exactly. Home photos (FEMA, public domain): faces blurred per photo.

Usage (from the repo root):  python -P fnol-vision-ml/redaction/evaluate_redaction.py
Needs: tools/vision-eval/prepare.py --download-all and prepare_home.py first.
"""
import glob
import os
import sys

import cv2
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(REPO, "tools", "vision-eval"))
import redact as R                                   # noqa: E402
from prepare import HITL, load_annotations           # noqa: E402


def damage_mask(ann, shape):
    img = Image.new("1", (ann["size"]["width"], ann["size"]["height"]), 0)
    d = ImageDraw.Draw(img)
    for o in ann["objects"]:
        if o.get("geometryType") == "polygon" and len(o["points"]["exterior"]) >= 3:
            d.polygon([tuple(p) for p in o["points"]["exterior"]], fill=1)
    m = np.array(img, dtype=np.uint8)
    return cv2.resize(m, (shape[1], shape[0]), interpolation=cv2.INTER_NEAREST).astype(bool)


def boxes_for(img, line):
    faces = R.faces(img, line)
    plates = R.plates(img, line)
    return faces, plates


def main():
    _, damage = load_annotations()
    import json
    with open(os.path.join(REPO, "tools", "vision-eval", "data", "labels.jsonl"), encoding="utf-8") as fh:
        image_path = {r["image"]: os.path.join(HITL, r["path"]) for r in map(json.loads, fh)}
    photos = hidden_any = 0
    shares, faces_n, plates_n = [], 0, 0
    for name, (ann_dir, ann) in sorted(damage.items()):
        path = image_path.get(name)   # the labelled images (folder names differ between the dataset copies)
        if not path or not os.path.exists(path):
            continue
        img = R.load(open(path, "rb").read())
        faces, plates = boxes_for(img, "AUTO")
        faces_n += len(faces)
        plates_n += len(plates)
        photos += 1
        m = damage_mask(ann, img.shape)
        if not m.any() or not (faces or plates):
            continue
        covered = np.zeros_like(m)
        for x0, y0, x1, y1 in R.grown(img, faces + plates):
            covered[y0:y1, x0:x1] = True
        share = float((covered & m).sum() / m.sum())
        if share > 0:
            hidden_any += 1
            shares.append(share)
    home = sorted(glob.glob(os.path.join(REPO, "tools", "vision-eval", "data", "home", "img", "*.jpg")))
    home_faces = [len(R.faces(R.load(open(p, "rb").read()), "HOME")) for p in home]
    print(f"Car photos: {photos}; faces blurred {faces_n}, plates blurred {plates_n}; "
          f"photos where a blur touches damage: {hidden_any}; "
          f"damage hidden in those: max {max(shares, default=0):.1%}, mean {np.mean(shares) if shares else 0:.1%}; "
          f"over all photos: {sum(shares) / max(photos, 1):.2%} of damage pixels")
    print(f"Home photos: {len(home)}; photos with faces blurred: {sum(1 for n in home_faces if n)}; faces: {sum(home_faces)}")


if __name__ == "__main__":
    main()
