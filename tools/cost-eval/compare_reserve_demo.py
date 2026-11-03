"""The demo scenarios through both reserve methods, on a running stack with mock vision.

Mock vision's figures are written by this project, so this shows how routing and the reserve change between the methods. It
says nothing about accuracy (see compare_reserve_methods.py for the replay on real Gemini answers).

Usage: start the stack with FNOL_RESERVE_METHOD set, then, for each method:
    python -P compare_reserve_demo.py --method AVERAGE_TABLE --api http://127.0.0.1:8080 --token demo-adjuster-token
    python -P compare_reserve_demo.py --method AI_ESTIMATE ...
    python -P compare_reserve_demo.py --report
Writes: results/demo-reserves-<method>.json, results/reserve-methods-demo.md
"""
import argparse
import datetime
import glob
import json
import os
import random
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
SCENARIOS = os.path.join(HERE, "..", "..", "demo-scenarios")
RESULTS = os.path.join(HERE, "results")
METHODS = ("AI_ESTIMATE", "AVERAGE_TABLE")


def post(api, token, payload):
    req = urllib.request.Request(api + "/api/v1/fnol/intake", json.dumps(payload).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "X-Adjuster-Token": token})
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(body)
        except ValueError:
            return e.code, {}


def run(method, api, token):
    out = {}
    for path in sorted(glob.glob(os.path.join(SCENARIOS, "scenario_*.json"))):
        spec = json.load(open(path, encoding="utf-8"))
        lo, hi = spec.get("daysAgoMin", 1), spec.get("daysAgoMax", 28)
        days = list(range(lo, hi + 1))
        random.shuffle(days)
        for d in days:     # a fresh date; a date already used on this policy is a duplicate
            payload = dict(spec["payload"], incidentDate=(datetime.date.today() - datetime.timedelta(days=d)).isoformat())
            code, body = post(api, token, payload)
            if code != 409:
                break
        a = body.get("branchA") or {}
        reserve, damage = a.get("reserve") or {}, a.get("damageAssessment") or {}
        name = os.path.basename(path)[len("scenario_"):-len(".json")]
        out[name] = {"http": code, "status": body.get("status"), "estimate": damage.get("estimatedDamage"),
                     "claimed": reserve.get("claimedAmount"), "reserve": reserve.get("recommendedReserve"),
                     "flags": sorted((body.get("inputFlags") or []) + (body.get("gateFlags") or []))}
    with open(os.path.join(RESULTS, f"demo-reserves-{method}.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, indent=1)
    print(method, "scenarios:", len(out))


def report():
    old, new = [json.load(open(os.path.join(RESULTS, f"demo-reserves-{m}.json"), encoding="utf-8")) for m in METHODS]
    lines = ["# Demo scenarios through both reserve methods", "",
             "Mock vision, whose figures this project wrote: this shows what changes, not which is more accurate.", "",
             "| Scenario | AI estimate | Claimed | Reserve: `AI_ESTIMATE` | Reserve: `AVERAGE_TABLE` | Status: `AI_ESTIMATE` | Status: `AVERAGE_TABLE` | Flags that differ |",
             "|---|---|---|---|---|---|---|---|"]
    changed_status = changed_reserve = 0
    key = lambda n: (int(n.split("_")[0]), n)     # noqa: E731
    for name in sorted(old, key=key):
        o, n = old[name], new[name]
        diff = sorted(set(o["flags"]) ^ set(n["flags"]))
        changed_status += o["status"] != n["status"] or o["http"] != n["http"]
        changed_reserve += o["reserve"] != n["reserve"]
        lines.append(f"| {name} | {n['estimate'] or '-'} | {n['claimed'] or '-'} | {o['reserve'] or '-'} | {n['reserve'] or '-'} | "
                     f"{o['status'] or o['http']} | {n['status'] or n['http']} | {', '.join(diff) or '-'} |")
    lines += ["", f"Of {len(old)} scenarios: reserve differs on {changed_reserve}, routing differs on {changed_status}."]
    path = os.path.join(RESULTS, "reserve-methods-demo.md")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--method", choices=METHODS)
    ap.add_argument("--api", default="http://localhost:8080")
    ap.add_argument("--token", default="demo-adjuster-token")
    ap.add_argument("--report", action="store_true")
    args = ap.parse_args()
    report() if args.report else run(args.method, args.api, args.token)
