"""Contact sheets for reviewing caption labels by eye: 20 numbered photos per sheet, each with its labels and the
start of its caption. Decisions go into property_review.json:
  "exclude": {"<title>": "<why>"}            no visible damage, people or paperwork, not property
  "relabel": {"<title>": ["SUBJECT", "CAUSE"]} the label the photo actually shows
then prepare_property.py is re-run (it keeps downloaded photos) and the reviewed set is final.

Usage:  python -P make_contact_sheets.py [--set property] [--only-unreviewed]
Writes: data/<set>/sheets/sheet-NNN.jpg and data/<set>/sheets/index.json (sheet, tile -> title)
"""
import argparse
import json
import os
import textwrap

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
COLS, ROWS, TILE_W, TILE_H, TEXT_H = 5, 4, 300, 220, 64


def font(size):
    for name in ("arial.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", default="property")
    ap.add_argument("--only-unreviewed", action="store_true")
    ap.add_argument("--file", default="labels.jsonl", help="label file in the set's folder (e.g. review_set.jsonl)")
    args = ap.parse_args()
    base = os.path.join(HERE, "data", args.set)
    rows = [json.loads(l) for l in open(os.path.join(base, args.file), encoding="utf-8")]
    if args.only_unreviewed:
        review_file = os.path.join(HERE, f"{args.set}_review.json")
        done = set(json.load(open(review_file, encoding="utf-8")).get("reviewed", [])) if os.path.exists(review_file) else set()
        rows = [r for r in rows if (r.get("title") or r["image"]) not in done]
    out = os.path.join(base, "sheets")
    os.makedirs(out, exist_ok=True)
    small, tiny = font(15), font(12)
    index = {}
    per = COLS * ROWS
    for s in range(0, len(rows), per):
        sheet = Image.new("RGB", (COLS * TILE_W, ROWS * (TILE_H + TEXT_H)), "white")
        draw = ImageDraw.Draw(sheet)
        n = s // per + 1
        for k, r in enumerate(rows[s:s + per]):
            x, y = (k % COLS) * TILE_W, (k // COLS) * (TILE_H + TEXT_H)
            img = Image.open(os.path.join(base, r["path"])).convert("RGB")
            img.thumbnail((TILE_W - 6, TILE_H - 6))
            sheet.paste(img, (x + 3, y + 3))
            draw.rectangle([x + 3, y + 3, x + 40, y + 24], fill="black")
            draw.text((x + 7, y + 5), f"{k + 1}", fill="yellow", font=small)
            label = f"{r.get('subject', '')} {r.get('cause', '')}".strip() or \
                f"{r.get('primaryZone', '')} (all: {', '.join(r.get('zones', []))})"
            draw.text((x + 4, y + TILE_H), label, fill="darkred", font=small)
            caption = textwrap.shorten(r.get("caption", ""), 110, placeholder="...")
            for j, line in enumerate(textwrap.wrap(caption, 48)[:2]):
                draw.text((x + 4, y + TILE_H + 20 + 15 * j), line, fill="black", font=tiny)
            index.setdefault(f"sheet-{n:03d}", {})[str(k + 1)] = r.get("title") or r["image"]
        sheet.save(os.path.join(out, f"sheet-{n:03d}.jpg"), quality=80)
    with open(os.path.join(out, "index.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(index, f, indent=1)
    print(f"{len(index)} sheets for {len(rows)} photos in {out}")


if __name__ == "__main__":
    main()
