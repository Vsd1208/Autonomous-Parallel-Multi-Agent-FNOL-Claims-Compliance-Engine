"""Exports the replay set: real recorded model answers, so a quick review without any model shows the checks working.

For each photo the recorded Gemini run answered: Gemini's structured answer exactly as returned, the second-opinion model's
out-of-fold output for that photo, the ground-truth label, and a downscaled copy of the photo (CC0 1.0, so it may be
redistributed). Nothing else from the run is kept: no usage, latency, request or account data. Deterministic: the same run gives
the same bytes, because the app matches a submitted photo to its answer by the SHA-256 of those bytes.

Also picks a small sample set for the console and run-all.sh: answers that pass, one AMBIGUOUS_DAMAGE_TYPE stops and one
MODEL_DISAGREEMENT stops, chosen by rule from the recorded answers.

Usage:  python -P export_replay.py --run v3-subject
Writes: demo-scenarios/replay/{manifest.json,answers.json,photos/}, demo-scenarios/replay/scenario-*.json,
        demo-scenarios/sample-images/fnol-set-replay-*/
"""
import argparse
import hashlib
import io
import json
import os
import shutil
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from calibrate import correctness, load, margin  # noqa: E402
from gate_ablation import severity_ok  # noqa: E402

REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT = os.path.join(REPO, "demo-scenarios", "replay")
SETS = os.path.join(REPO, "demo-scenarios", "sample-images")
OOF = os.path.join(REPO, "tools", "second-opinion", "results", "oof-auto.jsonl")
TRUST = os.path.join(REPO, "fnol-agents", "src", "main", "resources", "trust-score.json")
MAX_SIDE, QUALITY = 512, 75
MIN_MARGIN, MIN_AGREEMENT, MIN_TRUST = 0.30, 0.10, 0.85     # fnol.stp.*
SPECIFIC = {"FRONT_BUMPER", "REAR_BUMPER", "SIDE_PANEL", "WINDSHIELD", "ROOF"}
SET_FILES = ["front.jpg", "rear.jpg", "left.jpg", "right.jpg", "close-up.jpg"]


def downscale(path):
    with Image.open(path) as im:
        im = im.convert("RGB")
        im.thumbnail((MAX_SIDE, MAX_SIDE), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=QUALITY, optimize=True, progressive=False)
    return buf.getvalue()


def trust_score(p, agreement, model):
    m = margin(p)
    specific = p.get("damageType") in SPECIFIC
    z = model["intercept"]
    values = {"namedMargin": m if specific else 0.0, "agreement": agreement if specific else 0.0, "specific": 1.0 if specific else 0.0}
    for name, coef in zip(model["features"], model["coef"]):
        z += coef * values[name]
    import math
    return 1 / (1 + math.exp(-z))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", default="v3-subject")
    args = ap.parse_args()
    second = {}
    with open(OOF, encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            second[r["image"]] = r
    with open(TRUST, encoding="utf-8") as f:
        trust = json.load(f)["withSecondOpinion"]

    shutil.rmtree(os.path.join(OUT, "photos"), ignore_errors=True)
    os.makedirs(os.path.join(OUT, "photos"))
    answers, meta = [], None
    for r in sorted(load(args.run), key=lambda x: x["image"]):
        p = r.get("prediction")
        if not p:
            continue
        s = second[r["image"]]
        data = downscale(os.path.join(HERE, "data", "hitl", r["label"]["path"]))
        sha = hashlib.sha256(data).hexdigest()
        with open(os.path.join(OUT, "photos", sha[:16] + ".jpg"), "wb") as f:
            f.write(data)
        meta = {"model": r["model"], "thinkingBudget": r["thinkingBudget"], "promptSha256": r["promptSha256"]}
        zone = p.get("damageType", "UNKNOWN")
        answers.append({"sha256": sha, "photo": "photos/" + sha[:16] + ".jpg", "image": r["image"], "gemini": p,
                        "secondOpinion": {"probabilities": s["probabilities"], "extentBand": s.get("extentBand")},
                        "label": {"zones": r["label"].get("zones"), "primaryZone": r["label"].get("primaryZone")},
                        "licence": "CC0-1.0", "source": "Humans in the Loop, Car Parts and Car Damages"})
        answers[-1]["_right"] = correctness(zone, r["label"])[0]

    def stops(a):
        p, zone = a["gemini"], a["gemini"].get("damageType")
        agreement = a["secondOpinion"]["probabilities"].get(zone, 0.0)
        return {"margin": margin(p), "agreement": agreement, "specific": zone in SPECIFIC,
                "trust": trust_score(p, agreement, trust), "severityOk": severity_ok(p)}

    def label_agrees(a):
        return {"MINOR": "SMALL", "MODERATE": "MEDIUM", "SEVERE": "LARGE"}.get(a["gemini"].get("severity")) == a["secondOpinion"]["extentBand"]

    passing = [a for a in answers if a["_right"] and stops(a)["specific"] and stops(a)["margin"] >= MIN_MARGIN and stops(a)["severityOk"]
               and stops(a)["agreement"] >= MIN_AGREEMENT and stops(a)["trust"] >= MIN_TRUST and label_agrees(a)
               and a["gemini"].get("severity") != "SEVERE"]
    ambiguous = [a for a in answers if stops(a)["margin"] < MIN_MARGIN and stops(a)["severityOk"]]
    disagree = [a for a in answers if stops(a)["specific"] and stops(a)["margin"] >= MIN_MARGIN and stops(a)["severityOk"]
                and stops(a)["agreement"] < MIN_AGREEMENT and not a["_right"]]
    if len(passing) < 3 or not ambiguous or not disagree:
        raise SystemExit(f"Not enough samples: {len(passing)} passing, {len(ambiguous)} ambiguous, {len(disagree)} disagreement")
    chosen = [("pass-1", passing[0], None), ("pass-2", passing[len(passing) // 2], None), ("pass-3", passing[-1], None),
              ("ambiguous", ambiguous[0], "AMBIGUOUS_DAMAGE_TYPE"), ("disagreement", disagree[0], "MODEL_DISAGREEMENT")]

    for a in answers:
        a.pop("_right")
    for old in os.listdir(OUT):
        if old.startswith("scenario-"):
            os.remove(os.path.join(OUT, old))
    for name, a, flag in chosen:
        spec = {"photo": a["photo"], "sha256": a["sha256"], "recordedDamageType": a["gemini"].get("damageType"),
                "recordedSeverity": a["gemini"].get("severity")}
        if flag:
            spec["expectedGateFlag"] = flag
        with open(os.path.join(OUT, f"scenario-{name}.json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump(spec, f, indent=2)
            f.write("\n")
        folder = os.path.join(SETS, f"fnol-set-replay-{name}")
        shutil.rmtree(folder, ignore_errors=True)
        os.makedirs(folder)
        for file in SET_FILES:      # The recorded answer is for one photo: every slot holds it, so the set is complete.
            shutil.copyfile(os.path.join(OUT, a["photo"]), os.path.join(folder, file))

    manifest = dict(meta, source="Humans in the Loop, Car Parts and Car Damages (Hugging Face copy DrBimmer/car-parts-and-damage-dataset)",
                    licence="CC0-1.0", run=args.run, photos=len(answers),
                    geminiRecorded="50 answers on 2026-10-30, 16 on 2026-10-31 (docs/metrics.md section 1)",
                    secondOpinionRecorded="2026-11-01, out of fold: the model that judged a photo was not trained on it (docs/metrics.md section 4)",
                    recordedLabel="recorded 30-31 Oct 2026", photoSize=f"longest side {MAX_SIDE} px, JPEG quality {QUALITY}",
                    note="Recorded answers, not live analysis. A photo outside this set gets a placeholder and goes to a person.")
    with open(os.path.join(OUT, "manifest.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    with open(os.path.join(OUT, "answers.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(answers, f, indent=1)
        f.write("\n")
    size = sum(os.path.getsize(os.path.join(d, x)) for d, _, fs in os.walk(OUT) for x in fs)
    set_size = sum(os.path.getsize(os.path.join(d, x)) for n, _, _ in chosen
                   for d, _, fs in os.walk(os.path.join(SETS, f"fnol-set-replay-{n}")) for x in fs)
    print(f"{len(answers)} answers; bundle {size / 1e6:.2f} MB; console sets {set_size / 1e6:.2f} MB; "
          f"candidates: {len(passing)} passing, {len(ambiguous)} ambiguous, {len(disagree)} disagreement")
    for name, a, flag in chosen:
        print(name, a["image"], a["gemini"].get("damageType"), a["gemini"].get("severity"), a["secondOpinion"]["extentBand"], flag,
              {k: round(v, 3) if isinstance(v, float) else v for k, v in stops(a).items()})


if __name__ == "__main__":
    main()
