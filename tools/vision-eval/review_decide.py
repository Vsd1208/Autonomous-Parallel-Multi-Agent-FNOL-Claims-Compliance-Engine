"""Records contact-sheet review decisions by sheet and tile number.

Usage:  python -P review_decide.py --set roboflow --sheet 1 --exclude 8,11,17,20 --relabel 4=REAR_BUMPER,5=REAR_BUMPER
        python -P review_decide.py --set property --sheet 3 --exclude 2 --relabel 7=COMMERCIAL/WIND_STORM
Writes: <set>_review.json next to this script ({"reviewedSheets": [...], "exclude": {...}, "relabel": {...}}), keyed by
        the photo's title (property) or image name (cars). A relabel is an area for cars, SUBJECT/CAUSE for property.
"""
import argparse
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", required=True)
    ap.add_argument("--sheet", type=int, required=True)
    ap.add_argument("--exclude", default="")
    ap.add_argument("--relabel", default="")
    ap.add_argument("--why", default="no visible damage, unclear area, or not the labelled subject")
    args = ap.parse_args()
    index = json.load(open(os.path.join(HERE, "data", args.set, "sheets", "index.json"), encoding="utf-8"))
    tiles = index[f"sheet-{args.sheet:03d}"]
    path = os.path.join(HERE, f"{args.set}_review.json")
    review = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {}
    review.setdefault("reviewedSheets", [])
    review.setdefault("exclude", {})
    review.setdefault("relabel", {})
    for t in filter(None, args.exclude.split(",")):
        review["exclude"][tiles[t.strip()]] = args.why
    for pair in filter(None, args.relabel.split(",")):
        t, label = pair.split("=")
        review["relabel"][tiles[t.strip()]] = label.strip().split("/") if "/" in label else label.strip()
    if args.sheet not in review["reviewedSheets"]:
        review["reviewedSheets"].append(args.sheet)
    # Every photo on the sheet has been looked at (kept, excluded or relabelled); later sheets skip them.
    review["reviewed"] = sorted(set(review.get("reviewed", [])) | set(tiles.values()))
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(review, f, indent=1, sort_keys=True)
    print(f"sheet {args.sheet}: {len(tiles)} photos, {len(list(filter(None, args.exclude.split(','))))} excluded, "
          f"{len(list(filter(None, args.relabel.split(','))))} relabelled; {len(review['reviewedSheets'])} sheets reviewed")


if __name__ == "__main__":
    main()
