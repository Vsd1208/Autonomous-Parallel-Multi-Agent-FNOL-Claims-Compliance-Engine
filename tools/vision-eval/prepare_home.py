"""Builds a labelled home-damage photo set for the property prompt from public-domain FEMA photos on Wikimedia
Commons. FEMA photographers caption every photo ("Two trees fell on this home ...", "This home is surrounded by
flood water ..."), so the cause of loss is labelled from the caption by keyword rules, and any caption that names
two causes, or is about people rather than damage, is skipped.

Usage:  python -P prepare_home.py --per-cause 15
Writes: data/home/labels.jsonl, data/home/sample.txt (causes interleaved, so a partial run stays balanced),
        data/home/img/<file>.jpg (1024 px). Downloads only from commons.wikimedia.org / upload.wikimedia.org.

Labels: FLOOD, FIRE_SMOKE, WIND_STORM, FALLEN_TREE (the property prompt's causes that show outside the house).
WATER_DAMAGE (a burst pipe inside) has no FEMA captions to label from, so it is not in this set.
Caption labels are noisier than hand labels; a hand review of every photo (EXCLUDE, RELABEL below) removed
photos without visible damage and fixed tree-on-house photos captioned as tornado damage. A rerun can pull in
replacement photos that nobody has reviewed: check the new ones before relying on the numbers.
"""
import argparse
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "data", "home")
API = "https://commons.wikimedia.org/w/api.php"
UA = {"User-Agent": "fnol-vision-eval/1.0 (academic project; low volume)"}

SEARCHES = {
    "FLOOD": ["FEMA home surrounded by flood water", "FEMA flooded home", "FEMA flood damaged house"],
    "FIRE_SMOKE": ["FEMA home destroyed by wildfire", "FEMA fire damaged home", "FEMA burned home"],
    "WIND_STORM": ["FEMA tornado damaged home", "FEMA hurricane roof damage home", "FEMA wind damaged house roof"],
    "FALLEN_TREE": ["FEMA tree fell on home", "FEMA tree on roof house", "FEMA fallen tree damaged home"],
}
CUES = {
    "FLOOD": r"\bflood(ed|ing|s|waters?)?\b|\bstorm surge\b|\bhigh water\b",
    "FIRE_SMOKE": r"\b(wild)?fires?\b|\bburn(ed|t)\b",
    "WIND_STORM": r"\btornado(es)?\b|\bhurricane\b|\bwinds?\b|\bhail\b",
    "FALLEN_TREE": r"\btrees?\b.{0,40}\b(fell|fallen|on (the|this|a) (home|house|roof))\b|\bfallen trees?\b",
}
# Hand review of the caption labels (contact sheet of every photo, 2026-10-28): photos where no damage can be seen
# (people, paperwork, an undamaged-looking house) are excluded; tornado photos showing a tree on the house are
# relabelled, because in a claim the tree is the cause of the damage.
EXCLUDE = {
    "FEMA - 30687 - Resident shows a FEMA worker a photograph of the flood.jpg",
    "FEMA - 31130 - Flood Damaged home in Texas.jpg",
    "FEMA - 31285 - FEMA damage inspector in resident's home in Texas.jpg",
    "FEMA - 33563 - Residents receiving assistance information from FEMA workers in California.jpg",
    "FEMA - 33732 - Fire victims in a FEMA supplied temporary housing in California.jpg",
    "FEMA - 34652 - FEMA PDA team in Arkansas.jpg",
    "FEMA - 35106 - Firefighter surveys damage in New Mexico.jpg",
    "FEMA - 33273 - FEMA disaster assistance workers in California.jpg",
    "FEMA - 33717 - California residents inside FEMA provided mobile home.jpg",
    "FEMA - 7257 - Photograph by Anita Westervelt taken on 04-27-2002 in Missouri.jpg",
    "FEMA - 40843 - Tornado Damage in Arkansas.jpg",
    "FEMA - 44054 - Tornado damaged home in Oklahoma City.jpg",
}
RELABEL = {
    "FEMA - 40809 - Damaged home in Arkansas.jpg": "FALLEN_TREE",
    "FEMA - 40810 - Damaged home in Arkansas.jpg": "FALLEN_TREE",
    "FEMA - 40842 - FEMA assessing tornado damage in Arkansas.jpg": "FALLEN_TREE",
}
HOME = r"\b(home|house|residence|roof|trailer|mobile home)\b"
PEOPLE = r"\b(meeting|interview|speaks?|talks?|briefs?|administrator|secretary|governor|senator|volunteers?|workshop|"\
         r"press|conference|recovery center|DRC|registers?|applicants?)\b"


def get(url):
    for attempt in range(5):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60).read()
        except urllib.error.HTTPError as e:
            if e.code != 429:
                raise
            time.sleep(15 * (attempt + 1))
        except (TimeoutError, OSError):
            time.sleep(10)
    raise SystemExit("Wikimedia Commons is rate limiting; try again later (it resumes).")


def query(params):
    params.update(format="json")
    return json.loads(get(API + "?" + urllib.parse.urlencode(params)))


def label(caption):
    """The single cause the caption names, or None when it names none, several, or is about people."""
    text = caption.lower()
    if not re.search(HOME, text) or re.search(PEOPLE, text, re.I):
        return None
    causes = [c for c, cue in CUES.items() if re.search(cue, text)]
    if causes == ["WIND_STORM", "FALLEN_TREE"] or causes == ["FALLEN_TREE", "WIND_STORM"]:
        causes = ["FALLEN_TREE"]            # "the wind brought a tree down on the house": the tree did the damage
    return causes[0] if len(causes) == 1 else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-cause", type=int, default=15)
    args = ap.parse_args()
    os.makedirs(os.path.join(OUT, "img"), exist_ok=True)

    found = {c: {} for c in SEARCHES}
    for cause, terms in SEARCHES.items():
        for term in terms:
            if len(found[cause]) >= args.per_cause:
                break
            r = query(dict(action="query", generator="search", gsrsearch=term + " filetype:bitmap", gsrnamespace=6,
                           gsrlimit=50, prop="imageinfo", iiprop="extmetadata|size|url", iiurlwidth=1024,
                           iiextmetadatafilter="LicenseShortName|ImageDescription|Artist|DateTimeOriginal"))
            for p in r.get("query", {}).get("pages", {}).values():
                title, ii = p["title"][5:], p["imageinfo"][0]
                meta = ii.get("extmetadata", {})
                caption = re.sub(r"\s+", " ", re.sub("<[^>]+>", "", meta.get("ImageDescription", {}).get("value", "")))
                if not title.startswith("FEMA") or meta.get("LicenseShortName", {}).get("value") != "Public domain":
                    continue
                got = RELABEL.get(title, label(caption))   # the photo's own label, whichever search found it
                if ii.get("width", 0) < 800 or title in EXCLUDE or got not in found:
                    continue
                if any(title in f for f in found.values()) or len(found[got]) >= args.per_cause:
                    continue
                found[got][title] = {"caption": caption[:400], "relabelled": title in RELABEL, "thumb": ii.get("thumburl"),
                                       "page": ii.get("descriptionurl") or "https://commons.wikimedia.org/wiki/File:"
                                       + urllib.parse.quote(title.replace(" ", "_"))}
            time.sleep(3)

    rows = []
    for cause, items in found.items():
        for title, info in items.items():
            name = re.sub(r"[^A-Za-z0-9]+", "_", title)[:70] + ".jpg"
            path = os.path.join(OUT, "img", name)
            if not os.path.exists(path) or os.path.getsize(path) == 0:
                data = get(info["thumb"])           # fetch first, so a failed download leaves no empty file
                with open(path, "wb") as img:
                    img.write(data)
                time.sleep(1)
            rows.append({"image": name, "path": "img/" + name, "cause": cause, "title": title,
                         "labelSource": "hand review" if info["relabelled"] else "caption",
                         "caption": info["caption"], "source": info["page"], "license": "Public domain (FEMA)"})
        print(f"{cause}: {len(items)}")

    with open(os.path.join(OUT, "labels.jsonl"), "w", encoding="utf-8", newline="\n") as f:
        f.writelines(json.dumps(r) + "\n" for r in rows)
    by_cause = {c: [r["image"] for r in rows if r["cause"] == c] for c in SEARCHES}
    order = [x for group in zip(*by_cause.values()) for x in group]                   # interleave causes
    order += [r["image"] for r in rows if r["image"] not in order]
    with open(os.path.join(OUT, "sample.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(order) + "\n")
    print(f"{len(rows)} labelled photos in {OUT}")


if __name__ == "__main__":
    main()
