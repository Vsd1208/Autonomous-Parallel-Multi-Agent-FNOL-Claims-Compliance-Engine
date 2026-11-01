"""Adds car-damage photos from Roboflow Universe datasets licensed CC BY 4.0 to the 441 CC0 Humans in the Loop photos.

Each dataset labels damaged car parts with boxes. Parts are mapped to the same areas as prepare.py (hood, headlight
and grille count as the front; trunk and tail-light as the rear; doors, fenders and mirrors as the side), and the
main damaged area is the one with the largest share of box area (an area counts as damaged at 10% or more, as in
prepare.py). Boxes that name no side ("bumper") are ignored, and photos whose main area is unknown are skipped.

Safeguards:
  - licence: a dataset is used only if the Roboflow API reports "CC BY 4.0"; every row keeps its source and licence
    for attribution (CREDITS.md);
  - augmented copies: Roboflow exports contain flipped / rotated / recoloured copies of each photo; only one image
    per original file name is kept;
  - duplicates: community datasets often share web photos, with each other and with the CC0 set; a photo whose
    64-bit difference hash is within 6 bits of one already kept (including all 441 CC0 photos) is dropped, so the same
    car cannot land in both training and testing;
  - the downloads are untrusted: only images and COCO JSON are read from them.
Labels are then reviewed on contact sheets (make_contact_sheets.py --set roboflow) before use.

Needs ROBOFLOW_API_KEY in the repository's .env (a free Roboflow account; the key is never printed or logged).

Usage:  python -P prepare_roboflow.py [--target 209]       (new photos wanted; 441 + 209 = 650)
Writes: data/roboflow/labels.jsonl, data/roboflow/img/*.jpg (at most 1024 px), data/roboflow/raw/ (exports)
"""
import argparse
import glob
import http.client
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from collections import Counter

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT = os.path.join(HERE, "data", "roboflow")
API = "https://api.roboflow.com"
# Candidates found on Roboflow Universe (Nov 2026) with damaged-part classes; the licence is checked through the API.
DATASETS = [
    ("startunderstandingroboflow", "car_dam-mxrh6"),
    ("venkadesh-p-fajty", "car-damages-k0bnj"),
    ("musaab-jde4b", "damaged-cars-proj"),
    ("car-damage-severity", "vehicledamagedetection"),
]
# Class name -> area, first match wins; same grouping as prepare.py PART_ZONE.
CLASS_ZONE = [
    (r"wind[ _-]?shield|windscreen|front[ _-]?glass", "WINDSHIELD"),
    (r"rear|back|trunk|dickey|boot|tail", "REAR_BUMPER"),
    (r"front|hood|bonnet|head[ _-]?light|grill", "FRONT_BUMPER"),
    (r"door|fender|quarter|side|mirror|rocker|panel|window", "SIDE_PANEL"),
    (r"roof", "ROOF"),
]
MIN_ZONE_SHARE = 0.10
HASH_DISTANCE = 6


def api_key():
    for line in open(os.path.join(REPO, ".env"), encoding="utf-8"):
        if line.strip().startswith("ROBOFLOW_API_KEY="):
            return line.split("=", 1)[1].strip().strip('"')
    raise SystemExit("ROBOFLOW_API_KEY is not set in .env")


def get_json(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "fnol-vision-eval/1.0"}),
                                timeout=120) as r:
        return json.loads(r.read())


def zone_of(name):
    n = name.lower()
    for pattern, zone in CLASS_ZONE:
        if re.search(pattern, n):
            return zone
    return None


def dhash(img):
    g = img.convert("L").resize((9, 8))
    px = list(g.getdata())
    return sum(1 << i for i in range(64) if px[(i // 8) * 9 + i % 8] > px[(i // 8) * 9 + i % 8 + 1])


def near(h, seen):
    return any(bin(h ^ s).count("1") <= HASH_DISTANCE for s in seen)


def export(ws, proj, key):
    """Metadata and the extracted COCO export of the latest version; None if the licence is not CC BY 4.0."""
    meta = get_json(f"{API}/{ws}/{proj}?api_key={urllib.parse.quote(key)}")
    project = meta.get("project", {})
    licence = project.get("license") or meta.get("license") or ""
    if licence.strip().upper() not in ("CC BY 4.0", "CC-BY-4.0", "CC BY-4.0"):
        print(f"skip {ws}/{proj}: licence '{licence}'")
        return None
    versions = meta.get("versions") or project.get("versions") or []
    if not versions:
        print(f"skip {ws}/{proj}: no versions")
        return None
    version = max(int(str(v.get("id", "")).rsplit("/", 1)[-1]) for v in versions)
    folder = os.path.join(OUT, "raw", f"{proj}-v{version}")
    if not glob.glob(os.path.join(folder, "**", "_annotations.coco.json"), recursive=True):
        # The first request starts generating the export; poll until its link is ready.
        for _ in range(60):
            r = get_json(f"{API}/{ws}/{proj}/{version}/coco?api_key={urllib.parse.quote(key)}")
            if "export" in r and r["export"].get("link"):
                break
            time.sleep(10)
        else:
            print(f"skip {ws}/{proj}: export not ready after 10 minutes")
            return None
        link = r["export"]["link"]
        # Streamed to disk with progress and resume (a 250 MB export over a slow link must survive drops).
        part = folder + ".zip.part"
        total = None                       # bytes the server says the export has; keep resuming until all arrived
        for attempt in range(40):
            have = os.path.getsize(part) if os.path.exists(part) else 0
            if total is not None and have >= total:
                break
            req = urllib.request.Request(link, headers={"Range": f"bytes={have}-"} if have else {})
            try:
                with urllib.request.urlopen(req, timeout=120) as resp, open(part, "ab" if have else "wb") as out:
                    if have and resp.status != 206:      # server ignored the range: start again
                        out.seek(0)
                        out.truncate()
                        have = 0
                    rng = resp.headers.get("Content-Range")          # "bytes start-end/total"
                    total = int(rng.rsplit("/", 1)[1]) if rng else have + int(resp.headers.get("Content-Length", 0))
                    while chunk := resp.read(1 << 20):
                        out.write(chunk)
                        if out.tell() % (20 << 20) < (1 << 20):
                            print(f"  {proj}: {out.tell() >> 20} of {total >> 20} MB", flush=True)
            except (OSError, urllib.error.URLError, http.client.HTTPException) as e:
                print(f"  {proj}: download interrupted ({type(e).__name__}); resuming", flush=True)
                time.sleep(5)
        if total is None or os.path.getsize(part) < total:
            print(f"skip {ws}/{proj}: download incomplete")
            return None
        with zipfile.ZipFile(part) as z:
            z.extractall(folder)
        os.remove(part)
    return {"workspace": ws, "project": proj, "version": version, "licence": licence, "folder": folder,
            "url": f"https://universe.roboflow.com/{ws}/{proj}", "name": project.get("name", proj)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", type=int, default=209)
    args = ap.parse_args()
    key = api_key()
    os.makedirs(os.path.join(OUT, "img"), exist_ok=True)

    seen = []
    cc0 = [json.loads(l) for l in open(os.path.join(HERE, "data", "labels.jsonl"), encoding="utf-8")]
    for r in cc0:
        seen.append(dhash(Image.open(os.path.join(HERE, "data", "hitl", r["path"]))))
    print(f"{len(seen)} CC0 photos hashed")

    rows, skipped = [], Counter()
    for ws, proj in DATASETS:
        info = export(ws, proj, key)
        if info is None:
            continue
        originals = set()   # Roboflow exports add augmented copies ("10_jpg.rf.<hash>.jpg"): one image per original
        for ann_file in glob.glob(os.path.join(info["folder"], "**", "_annotations.coco.json"), recursive=True):
            coco = json.load(open(ann_file, encoding="utf-8"))
            # A dataset that also labels undamaged parts ("door" next to "damaged_door") is read only through its
            # damage classes; a dataset of damage only ("Car Damages") through its part names.
            damage_only = any("damag" in c["name"].lower() for c in coco["categories"])
            cats = {c["id"]: zone_of(c["name"]) for c in coco["categories"]
                    if not damage_only or ("damag" in c["name"].lower() and "no damage" not in c["name"].lower())}
            area = {}
            for a in coco["annotations"]:
                z = cats.get(a["category_id"])
                if z:
                    area.setdefault(a["image_id"], Counter())[z] += a["bbox"][2] * a["bbox"][3]
            for im in coco["images"]:
                original = re.sub(r"\.rf\.[0-9a-f]+\.\w+$", "", im["file_name"])
                if original in originals:
                    skipped["augmented copy"] += 1
                    continue
                originals.add(original)
                zones = area.get(im["id"])
                if not zones:
                    skipped["no damaged area with a known side"] += 1
                    continue
                total = sum(zones.values())
                primary = zones.most_common(1)[0][0]
                if primary == "ROOF":                 # not one of the on-premise model's areas
                    skipped["main damage on the roof"] += 1
                    continue
                kept = sorted(z for z, v in zones.items() if v / total >= MIN_ZONE_SHARE)
                path = os.path.join(os.path.dirname(ann_file), im["file_name"])
                img = Image.open(path).convert("RGB")
                h = dhash(img)
                if near(h, seen):
                    skipped["duplicate"] += 1
                    continue
                seen.append(h)
                img.thumbnail((1024, 1024))
                # Image ids restart in each split (train / valid / test): the split keeps names unique.
                split = os.path.basename(os.path.dirname(ann_file))
                name = f"rf_{proj}_{split}_{im['id']}.jpg"
                img.save(os.path.join(OUT, "img", name), quality=90)
                rows.append({"image": name, "path": "img/" + name, "zones": kept, "primaryZone": primary,
                             "original": original,
                             "source": f"{info['url']} (v{info['version']})", "dataset": info["name"],
                             "license": "CC BY 4.0", "labelSource": "dataset boxes"})
        print(f"{ws}/{proj}: {len(rows)} kept so far", flush=True)
        if len(rows) >= args.target * 2:   # enough to choose from after review
            break
    if len({r["image"] for r in rows}) != len(rows):
        raise SystemExit("two photos got the same file name: labels would point at the wrong photo")
    with open(os.path.join(OUT, "labels.jsonl"), "w", encoding="utf-8", newline="\n") as f:
        f.writelines(json.dumps(r) + "\n" for r in rows)
    print(f"{len(rows)} new photos; skipped {dict(skipped)}; by main area {dict(Counter(r['primaryZone'] for r in rows))}")


if __name__ == "__main__":
    sys.exit(main())
