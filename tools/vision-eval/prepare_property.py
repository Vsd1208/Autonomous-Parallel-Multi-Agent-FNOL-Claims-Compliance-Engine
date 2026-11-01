"""Builds a labelled property-damage photo set (homes, commercial buildings, other property) from public-domain FEMA
photos on Wikimedia Commons, for training and evaluating the on-premise model's property heads.

Every FEMA photo has a photographer's caption ("A business destroyed by the tornado ...", "Flood waters surround this
home ..."). Each photo gets two labels from its caption, by keyword rules:
  subject  RESIDENTIAL (home, house, mobile home, apartment), COMMERCIAL (business, store, school, church, office,
           restaurant, hospital, ...), OTHER_PROPERTY (barn, shed, fence, boat, dock, ...)
  cause    FLOOD, FIRE_SMOKE, WIND_STORM, FALLEN_TREE
A caption that names no subject or several, no cause or several, or is about people, is skipped. Captions are noisy,
so every photo is then reviewed on a contact sheet (make_contact_sheets.py) and the decisions are kept in
property_review.json (EXCLUDE: no visible damage or not property; RELABEL: a corrected label). Only reviewed photos
should be used for numbers.

WATER_DAMAGE (a burst pipe inside) is not covered: FEMA photographs disasters, not plumbing leaks.

Usage:  python -P prepare_property.py [--residential 160 --commercial 80 --other 80]   (targets per cause)
Writes: data/property/labels.jsonl, data/property/img/<file>.jpg (1024 px). Downloads only from
        commons.wikimedia.org / upload.wikimedia.org; resumable (existing images are kept).
"""
import argparse
import json
import os
import re
import time
import sys
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from prepare_home import CUES, PEOPLE, get, query   # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "data", "property")
REVIEW = os.path.join(HERE, "property_review.json")

SUBJECTS = {
    "RESIDENTIAL": r"\b(homes?|houses?|residences?|residential|mobile homes?|trailers?|apartments?|duplex|condos?|"
                   r"condominiums?|townhomes?|dwellings?)\b",
    "COMMERCIAL": r"\b(business(es)?|stores?|shops?|shopping|schools?|church(es)?|offices?|restaurants?|warehouses?|"
                  r"hospitals?|motels?|hotels?|banks?|factory|factories|mall|gas stations?|courthouse|city hall|"
                  r"fire stations?|librar(y|ies)|commercial|post office|nursing home)\b",
    "OTHER_PROPERTY": r"\b(barns?|sheds?|fences?|boats?|docks?|piers?|outbuildings?|greenhouses?|silos?|marinas?|"
                      r"carports?|grain bins?|stables?)\b",
}
SUBJECT_WORDS = {"RESIDENTIAL": ["home", "house", "mobile home", "apartment"],
                 "COMMERCIAL": ["business", "store", "school", "church", "office building", "restaurant"],
                 "OTHER_PROPERTY": ["barn", "shed", "fence", "boat", "dock"]}
CAUSE_WORDS = {"FLOOD": ["flood", "flooded", "flood damaged"], "FIRE_SMOKE": ["wildfire", "fire damaged", "burned"],
               "WIND_STORM": ["tornado", "hurricane", "wind damaged"], "FALLEN_TREE": ["tree fell on", "fallen tree"]}
# --more: other wordings, run only for the (subject, cause) cells still below their target after the first search
MORE_SUBJECT_WORDS = {"RESIDENTIAL": ["home", "house", "residence"],
                      "COMMERCIAL": ["business", "building", "store", "school", "church", "warehouse", "shopping center"],
                      "OTHER_PROPERTY": ["barn", "shed", "garage", "outbuilding", "fence", "boat"]}
MORE_CAUSE_WORDS = {"FLOOD": ["flood waters", "inundated"],
                    "FIRE_SMOKE": ["fire destroyed", "burned down", "destroyed by fire", "fire gutted", "wildfire destroyed"],
                    "WIND_STORM": ["tornado destroyed", "storm damaged"],
                    "FALLEN_TREE": ["tree crushed", "downed tree", "uprooted tree", "tree landed on", "trees fell on"]}


def labels(caption):
    """(subject, cause) when the caption names exactly one of each and is not about people; otherwise None."""
    text = caption.lower()
    if re.search(PEOPLE, text, re.I):
        return None
    # "nursing home" is a business, not a home; drop it before the residential test
    subjects = [s for s, cue in SUBJECTS.items() if re.search(cue, text.replace("nursing home", "nursinghome")
                                                                if s == "RESIDENTIAL" else text)]
    causes = [c for c, cue in CUES.items() if re.search(cue, text)]
    if sorted(causes) == ["FALLEN_TREE", "WIND_STORM"]:
        causes = ["FALLEN_TREE"]            # the wind brought a tree down: the tree did the damage
    if len(subjects) != 1 or len(causes) != 1:
        return None
    return subjects[0], causes[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--residential", type=int, default=160)
    ap.add_argument("--commercial", type=int, default=80)
    ap.add_argument("--other", type=int, default=80)
    ap.add_argument("--more", action="store_true", help="search other wordings for cells still below target")
    args = ap.parse_args()
    target = {"RESIDENTIAL": args.residential, "COMMERCIAL": args.commercial, "OTHER_PROPERTY": args.other}
    review = json.load(open(REVIEW, encoding="utf-8")) if os.path.exists(REVIEW) else {"exclude": {}, "relabel": {}}
    os.makedirs(os.path.join(OUT, "img"), exist_ok=True)

    found = {}                                   # title -> row
    count = {(s, c): 0 for s in SUBJECTS for c in CUES}
    cache = os.path.join(OUT, "candidates.json")  # the search takes about an hour: a failed download never redoes it
    if os.path.exists(cache):
        found = json.load(open(cache, encoding="utf-8"))
        print(f"{len(found)} candidates from {cache}")
        for info in found.values():
            count[(info["subject"], info["cause"])] += 1
    plan = (SUBJECT_WORDS, CAUSE_WORDS) if not found else (MORE_SUBJECT_WORDS, MORE_CAUSE_WORDS) if args.more else ({}, {})
    for subject, s_words in plan[0].items():
        for cause, c_words in plan[1].items():
            if count[(subject, cause)] >= target[subject]:
                continue
            for sw in s_words:
                for cw in c_words:
                    if count[(subject, cause)] >= target[subject]:
                        break
                    for offset in (0, 50, 100):
                        r = query(dict(action="query", generator="search", gsrsearch=f"FEMA {cw} {sw} filetype:bitmap",
                                       gsrnamespace=6, gsrlimit=50, gsroffset=offset, prop="imageinfo",
                                       iiprop="extmetadata|size|url", iiurlwidth=1024,
                                       iiextmetadatafilter="LicenseShortName|ImageDescription"))
                        pages = r.get("query", {}).get("pages", {}).values()
                        for p in pages:
                            title, ii = p["title"][5:], p["imageinfo"][0]
                            meta = ii.get("extmetadata", {})
                            caption = re.sub(r"\s+", " ", re.sub("<[^>]+>", "",
                                                                 meta.get("ImageDescription", {}).get("value", "")))
                            if (title in found or not title.startswith("FEMA") or ii.get("width", 0) < 800
                                    or meta.get("LicenseShortName", {}).get("value") != "Public domain"
                                    or title in review["exclude"]):
                                continue
                            got = tuple(review["relabel"][title]) if title in review["relabel"] else labels(caption)
                            if got is None or count[got] >= target[got[0]]:
                                continue
                            count[got] += 1
                            found[title] = {"subject": got[0], "cause": got[1], "caption": caption[:400],
                                            "thumb": ii.get("thumburl"), "relabelled": title in review["relabel"],
                                            "page": ii.get("descriptionurl") or "https://commons.wikimedia.org/wiki/File:"
                                            + urllib.parse.quote(title.replace(" ", "_"))}
                        time.sleep(2)
                        if len(pages) < 50:
                            break
            print(f"{subject} / {cause}: {count[(subject, cause)]}", flush=True)
    with open(cache, "w", encoding="utf-8", newline=chr(10)) as f:
        json.dump(found, f)

    rows = []
    for i, (title, info) in enumerate(found.items(), 1):
        name = re.sub(r"[^A-Za-z0-9]+", "_", title)[:70] + ".jpg"
        path = os.path.join(OUT, "img", name)
        if not os.path.exists(path) or os.path.getsize(path) == 0:
            data = get(info["thumb"])
            with open(path, "wb") as img:
                img.write(data)
            time.sleep(1)
        rows.append({"image": name, "path": "img/" + name, "subject": info["subject"], "cause": info["cause"],
                     "title": title, "labelSource": "hand review" if info["relabelled"] else "caption",
                     "caption": info["caption"], "source": info["page"], "license": "Public domain (FEMA)"})
        if i % 50 == 0:
            print(f"  downloaded {i}/{len(found)}", flush=True)
    with open(os.path.join(OUT, "labels.jsonl"), "w", encoding="utf-8", newline="\n") as f:
        f.writelines(json.dumps(r) + "\n" for r in rows)
    print(f"{len(rows)} labelled photos in {OUT}")


if __name__ == "__main__":
    main()
