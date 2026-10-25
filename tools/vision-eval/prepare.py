"""Build ground-truth labels for the vision evaluation and download a sample of images.

Dataset: "Car Parts and Car Damages" by Humans in the Loop, CC0 1.0 (public domain),
https://humansintheloop.org/resources/datasets/car-parts-and-car-damages-dataset/ ,
via the Hugging Face copy DrBimmer/car-parts-and-damage-dataset.

On that copy the two folder names are swapped: "Car damages dataset" holds the car-PART polygons and
"Car parts dataset" holds the DAMAGE polygons. Each folder is therefore classified by its classes,
not its name. 441 photos are annotated in both, so overlaying each damage polygon on the part
polygons tells us which part of the car is damaged. Those parts are grouped into the zones the vision
prompt can answer with (FRONT_BUMPER, REAR_BUMPER, WINDSHIELD, SIDE_PANEL, ROOF).

Usage:  python -P prepare.py --sample 120 --seed 7
Writes: data/labels.jsonl (all paired images), data/sample.txt (the chosen images), data/hitl/... (images)
"""
import argparse
import glob
import json
import os
import random
from collections import Counter, defaultdict

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")
HITL = os.path.join(DATA, "hitl")
REPO_ID = "DrBimmer/car-parts-and-damage-dataset"

DAMAGE_CLASSES = {"Missing part", "Broken part", "Scratch", "Cracked", "Dent", "Flaking", "Paint chip", "Corrosion"}

# Car part -> zone the vision prompt can name. License-plate is ignored (front or rear is unknown).
PART_ZONE = {
    "Front-bumper": "FRONT_BUMPER", "Grille": "FRONT_BUMPER", "Headlight": "FRONT_BUMPER", "Hood": "FRONT_BUMPER",
    "Back-bumper": "REAR_BUMPER", "Tail-light": "REAR_BUMPER", "Trunk": "REAR_BUMPER", "Back-windshield": "REAR_BUMPER",
    "Windshield": "WINDSHIELD",
    "Front-door": "SIDE_PANEL", "Back-door": "SIDE_PANEL", "Fender": "SIDE_PANEL", "Quarter-panel": "SIDE_PANEL",
    "Rocker-panel": "SIDE_PANEL", "Mirror": "SIDE_PANEL", "Front-window": "SIDE_PANEL", "Back-window": "SIDE_PANEL",
    "Front-wheel": "SIDE_PANEL", "Back-wheel": "SIDE_PANEL",
    "Roof": "ROOF",
}
MIN_ZONE_SHARE = 0.10   # a zone counts as damaged if it holds at least 10% of the damaged area


def load_annotations():
    by_kind = {"parts": {}, "damage": {}}
    for ann_dir in glob.glob(os.path.join(HITL, "*", "File1", "ann")):
        anns = {}
        for f in glob.glob(os.path.join(ann_dir, "*.json")):
            with open(f, encoding="utf-8") as fh:
                anns[os.path.basename(f)[:-5]] = (fh.name, json.load(fh))
        classes = Counter(o["classTitle"] for _, a in anns.values() for o in a["objects"])
        kind = "damage" if set(classes) <= DAMAGE_CLASSES else "parts"
        by_kind[kind].update({name: (ann_dir, a) for name, (_, a) in anns.items()})
    return by_kind["parts"], by_kind["damage"]


def mask(size, objects):
    """One raster mask per object (list of (title, set_of_pixel_indices))."""
    w, h = size["width"], size["height"]
    out = []
    for o in objects:
        if o.get("geometryType") != "polygon" or len(o["points"]["exterior"]) < 3:
            continue
        img = Image.new("1", (w, h), 0)
        d = ImageDraw.Draw(img)
        d.polygon([tuple(p) for p in o["points"]["exterior"]], fill=1)
        for hole in o["points"].get("interior", []):
            if len(hole) >= 3:
                d.polygon([tuple(p) for p in hole], fill=0)
        pixels = img.getdata()
        out.append((o["classTitle"], {i for i, v in enumerate(pixels) if v}))
    return out


def label(parts_ann, damage_ann):
    size = parts_ann["size"]
    parts = [(PART_ZONE.get(t), px) for t, px in mask(size, parts_ann["objects"])]
    damages = mask(size, damage_ann["objects"])
    zone_area = defaultdict(int)
    for _, dpx in damages:
        best_zone, best_overlap = None, 0
        for zone, ppx in parts:
            if zone is None:
                continue
            overlap = len(dpx & ppx)
            if overlap > best_overlap:
                best_zone, best_overlap = zone, overlap
        if best_zone:
            zone_area[best_zone] += len(dpx)
    total = sum(zone_area.values())
    if total == 0:
        return None
    zones = sorted(z for z, a in zone_area.items() if a / total >= MIN_ZONE_SHARE)
    primary = max(zone_area, key=zone_area.get)
    image_area = size["width"] * size["height"]
    damage_px = set().union(*(px for _, px in damages)) if damages else set()
    return {
        "zones": zones,
        "primaryZone": primary,
        "damageTypes": sorted({t for t, _ in damages}),
        "damageAreaShare": round(len(damage_px) / image_area, 4),
        "size": size,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sample", type=int, default=120, help="images to download for the Gemini run")
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    parts, damage = load_annotations()
    paired = sorted(set(parts) & set(damage))
    print(f"parts annotations: {len(parts)}, damage annotations: {len(damage)}, paired: {len(paired)}")

    labels = []
    for name in paired:
        lab = label(parts[name][1], damage[name][1])
        if lab:
            img_rel = os.path.relpath(os.path.join(os.path.dirname(parts[name][0]), "img", name), HITL)
            labels.append({"image": name, "path": img_rel.replace(os.sep, "/"), **lab})
    with open(os.path.join(DATA, "labels.jsonl"), "w", encoding="utf-8") as f:
        for lab in labels:
            f.write(json.dumps(lab) + "\n")
    print(f"labelled images: {len(labels)}; primary zones: {Counter(l['primaryZone'] for l in labels)}")

    # Stratified sample: equal share per primary zone, so rare zones are not drowned out.
    rng = random.Random(args.seed)
    by_zone = defaultdict(list)
    for lab in labels:
        by_zone[lab["primaryZone"]].append(lab)
    for zone in by_zone.values():
        rng.shuffle(zone)
    sample, zones = [], sorted(by_zone)
    while len(sample) < min(args.sample, len(labels)):
        for z in zones:
            if by_zone[z] and len(sample) < args.sample:
                sample.append(by_zone[z].pop())
    with open(os.path.join(DATA, "sample.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(s["image"] for s in sample) + "\n")
    print(f"sample: {len(sample)} images; primary zones: {Counter(s['primaryZone'] for s in sample)}")

    from huggingface_hub import snapshot_download
    snapshot_download(REPO_ID, repo_type="dataset", local_dir=HITL,
                      allow_patterns=[s["path"] for s in sample])
    missing = [s["path"] for s in sample if not os.path.exists(os.path.join(HITL, s["path"]))]
    print(f"downloaded; missing: {len(missing)}")


if __name__ == "__main__":
    main()
