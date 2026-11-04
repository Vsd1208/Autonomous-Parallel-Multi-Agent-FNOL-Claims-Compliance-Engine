"""How much the face detector's finds change on harder versions of the same photos. No ground truth exists, so this compares each version
with the original's own finds: it can show loss, not true recall.

Usage: python -P fnol-vision-ml/redaction/evaluate_robustness.py [folder of photos]
"""
import glob
import os
import sys

import cv2

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import redact as r  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT = os.path.join(HERE, "..", "..", "tools", "vision-eval", "data", "home")

VERSIONS = {
    "original": lambda i: i,
    "rotated 90 degrees": lambda i: cv2.rotate(i, cv2.ROTATE_90_CLOCKWISE),
    "rotated 180 degrees": lambda i: cv2.rotate(i, cv2.ROTATE_180),
    "low light (brightness x 0.25)": lambda i: (i * 0.25).astype("uint8"),
    "half size": lambda i: cv2.resize(i, (i.shape[1] // 2, i.shape[0] // 2)),
}


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else DEFAULT
    paths = [p for ext in ("jpg", "jpeg", "png") for p in glob.glob(os.path.join(folder, "**", "*." + ext), recursive=True)]
    counts = {k: 0 for k in VERSIONS}
    photos = 0
    for path in sorted(paths):
        img = cv2.imread(path)
        if img is None:
            continue
        img = r.load(cv2.imencode(".jpg", img)[1].tobytes())
        photos += 1
        for name, change in VERSIONS.items():
            counts[name] += len(r.faces(change(img), "HOME"))
    print(f"{photos} photos from {folder}")
    print("| Version | Faces found | Share of the original's finds |")
    print("|---|---|---|")
    for name, n in counts.items():
        share = f"{n / counts['original']:.0%}" if counts["original"] else "-"
        print(f"| {name} | {n} | {share} |")


if __name__ == "__main__":
    main()
