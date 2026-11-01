"""Learns repair costs from this insurer's own claims, to replace the published repair-cost ranges.

Every adjuster decision is stored next to what the AI said (table ai_feedback). When a claim has a real repair cost
(an inspection's accident-related amount, or the repair cost the adjuster confirmed), it becomes a labelled example:
damaged area (the adjuster's confirmed type, else the AI's) x visible-extent band (on-premise model) -> repair cost.

Model, deliberately simple and auditable: per area x band cell, the log repair cost is the published range's
log-midpoint shrunk toward the cell's own claims, weighted as k pseudo-claims of prior:
    log estimate = (k * log published_midpoint + sum(log costs)) / (k + n)
k is chosen by leave-one-claim-out error. The range is the estimate times/divided by the residual spread (90%).

Measured honestly: every claim is predicted by a fit that never saw it (leave-one-out), against the published table
and against the AI's own estimate. Shipping rules for --write-app (all must hold):
  - at least --min-claims claims with a real cost (default 50);
  - lower leave-one-out median absolute percentage error than the published table;
  - at least as many claims within 25% as the published table.
Only cells with at least 3 claims are written; the app uses the published range for the rest.

Usage:  python -P fit_costs.py [--write-app]          (needs the compose stack running: reads ai_feedback with psql)
        python -P fit_costs.py --simulate 300         (pipeline self-test on SYNTHETIC costs; never writes the app)
Writes: data/costs.csv (gitignored: claim ids), results/report-costs.md,
        ../../fnol-agents/src/main/resources/repair-cost-learned.json (only with --write-app and the rules passed)
"""
import argparse
import csv
import io
import json
import math
import os
import random
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
RANGES = os.path.join(REPO, "fnol-agents", "src", "main", "resources", "repair-cost-ranges.json")
LEARNED = os.path.join(REPO, "fnol-agents", "src", "main", "resources", "repair-cost-learned.json")
EXPORT = os.path.join(HERE, "data", "costs.csv")
QUERY = ("SELECT claim_id, line_of_business, ai_damage_type, adjuster_damage_type, extent_band, ai_estimate, "
         "published_estimate, final_repair_cost, cost_source, decided_at FROM ai_feedback "
         "WHERE final_repair_cost IS NOT NULL AND final_repair_cost > 0 ORDER BY decided_at")
# Only costs backed by evidence teach the model: an adjuster's judgement (or an accepted AI figure) would let the model
# learn its own guesses, and a figure entered on a claim under investigation is held until the investigator reports
# (their figure arrives as INVESTIGATION). Must match AdjusterReviewRequest.EVIDENCE_SOURCES.
EVIDENCE = {"INVOICE", "BODY_SHOP_ESTIMATE", "INSPECTION", "INVESTIGATION"}
K_GRID = [1, 2, 5, 10, 20, 50]
MIN_CELL = 3
Z90 = 1.645


def published_table():
    t = json.load(open(RANGES, encoding="utf-8"))
    return {(area, band): math.sqrt(c["low"] * c["high"]) for area, bands in t["ranges"].items()
            for band, c in bands.items()}


def export():
    out = subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "fnol", "-d", "fnol", "--csv",
                          "-c", QUERY], cwd=REPO, capture_output=True, text=True, encoding="utf-8")
    if out.returncode != 0:
        raise SystemExit("Could not read ai_feedback (is the compose stack running?): " + out.stderr.strip())
    os.makedirs(os.path.dirname(EXPORT), exist_ok=True)
    with open(EXPORT, "w", encoding="utf-8", newline="\n") as f:
        f.write(out.stdout)
    return list(csv.DictReader(io.StringIO(out.stdout)))


def claims_from(rows, table):
    """(area, band, cost, ai_estimate) for evidence-backed vehicle claims whose area and band have a published cell.
    One label per claim: its latest decision with a cost (rows arrive oldest first)."""
    latest = {}
    for r in rows:
        latest.pop(r.get("claim_id"), None)
        latest[r.get("claim_id")] = r
    out = []
    for r in latest.values():
        if r.get("cost_source") not in EVIDENCE:
            continue
        if (r.get("line_of_business") or "AUTO") != "AUTO":
            continue
        area = r.get("adjuster_damage_type") or r.get("ai_damage_type")
        band = r.get("extent_band")
        if (area, band) not in table:
            continue
        ai = float(r["ai_estimate"]) if r.get("ai_estimate") else None
        out.append((area, band, float(r["final_repair_cost"]), ai))
    return out


def simulate(n, table, seed=20261101):
    """SYNTHETIC claims for a pipeline self-test: true cost = published midpoint x a per-area bias x noise."""
    rng = random.Random(seed)
    bias = {a: math.exp(rng.uniform(-0.6, 0.6)) for a, _ in table}
    cells = sorted(table)
    out = []
    for _ in range(n):
        area, band = rng.choice(cells)
        cost = table[(area, band)] * bias[area] * math.exp(rng.gauss(0, 0.5))
        out.append((area, band, round(cost, 2), round(cost * math.exp(rng.gauss(0.5, 0.8)), 2)))
    return out


def fit(claims, table, k):
    """Per-cell shrunk log estimate and pooled residual spread."""
    sums, counts = {}, {}
    for area, band, cost, _ in claims:
        sums[(area, band)] = sums.get((area, band), 0.0) + math.log(cost)
        counts[(area, band)] = counts.get((area, band), 0) + 1
    est = {cell: (k * math.log(mid) + sums.get(cell, 0.0)) / (k + counts.get(cell, 0)) for cell, mid in table.items()}
    resid = [math.log(cost) - est[(area, band)] for area, band, cost, _ in claims]
    sd = math.sqrt(sum(r * r for r in resid) / max(1, len(resid) - 1)) if resid else 0.5
    return est, counts, sd


def loo_predictions(claims, table, k):
    preds = []
    for i in range(len(claims)):
        est, _, _ = fit(claims[:i] + claims[i + 1:], table, k)
        area, band, _, _ = claims[i]
        preds.append(math.exp(est[(area, band)]))
    return preds


def ape(pred, actual):
    return abs(pred - actual) / actual


def summary(preds, actual):
    errs = sorted(ape(p, a) for p, a in zip(preds, actual) if p is not None)
    if not errs:
        return None, 0, 0
    m = errs[len(errs) // 2] if len(errs) % 2 else (errs[len(errs) // 2 - 1] + errs[len(errs) // 2]) / 2
    return m, sum(e <= 0.25 for e in errs), len(errs)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write-app", action="store_true")
    ap.add_argument("--min-claims", type=int, default=50)
    ap.add_argument("--simulate", type=int, default=0, help="SYNTHETIC self-test with N claims; never writes the app")
    args = ap.parse_args()
    table = published_table()
    synthetic = args.simulate > 0
    rows = [] if synthetic else export()
    claims = simulate(args.simulate, table) if synthetic else claims_from(rows, table)
    actual = [c[2] for c in claims]

    lines = ["# Learned repair costs" + (" - SYNTHETIC SELF-TEST" if synthetic else ""), ""]
    if synthetic:
        lines += ["**These claims are synthetic** (published midpoint x a random per-area bias x noise). This run only "
                  "checks that the pipeline recovers a known bias; it says nothing about real repair costs and never "
                  "writes the app file.", ""]
    if not synthetic:
        per_claim = {r.get("claim_id"): r for r in rows}
        held = sum(1 for r in per_claim.values() if r.get("cost_source") == "INVESTIGATION_HOLD")
        judged = sum(1 for r in per_claim.values() if r.get("cost_source") not in EVIDENCE) - held
        lines.append(f"{len(per_claim)} claims with a repair cost ({len(rows)} decisions; one label per claim, the "
                     f"latest); {judged} rest on the adjuster's judgement and {held} are held until an investigator "
                     "reports: neither is learned from.")
    lines.append(f"{len(claims)} evidence-backed vehicle claims (invoice, body-shop estimate or inspection) with a "
                 "published cell (area x extent band).")
    verdict = []
    if len(claims) < max(args.min_claims, 2):
        verdict.append(f"fewer than {args.min_claims} claims with a real repair cost")
        report(lines, verdict, synthetic)
        return

    loss = {k: summary(loo_predictions(claims, table, k), actual)[0] for k in K_GRID}
    k = min(loss, key=loss.get)
    learned = loo_predictions(claims, table, k)
    published = [table[(a, b)] for a, b, _, _ in claims]
    ai = [c[3] for c in claims]
    rows = [("Learned (leave-one-out, k = %d)" % k, summary(learned, actual)),
            ("Published table", summary(published, actual)),
            ("AI's own estimate", summary(ai, actual))]
    lines += ["", "Median absolute percentage error, and claims within 25% of the real cost; every learned prediction "
              "comes from a fit that never saw the claim.", "",
              "| Estimate | Median error | Within 25% | Claims |", "|---|---|---|---|"]
    for name, (m, w, n) in rows:
        lines.append(f"| {name} | {m:.0%} | {w}/{n} | {n} |" if m is not None else f"| {name} | - | - | 0 |")
    (lm, lw, _), (pm, pw, _) = rows[0][1], rows[1][1]
    if lm >= pm:
        verdict.append(f"not better than the published table (median error {lm:.0%} vs {pm:.0%})")
    if lw < pw:
        verdict.append(f"fewer claims within 25% than the published table ({lw} vs {pw})")
    if len(claims) < args.min_claims:
        verdict.append(f"fewer than {args.min_claims} claims")

    est, counts, sd = fit(claims, table, k)
    lines += ["", "| Area | Band | Claims | Published estimate | Learned estimate | Learned 90% range |", "|---|---|---|---|---|---|"]
    cells = {}
    for (area, band), mid in sorted(table.items()):
        n = counts.get((area, band), 0)
        e = math.exp(est[(area, band)])
        lines.append(f"| {area} | {band} | {n} | ${mid:,.0f} | ${e:,.0f} | ${e / math.exp(Z90 * sd):,.0f}-${e * math.exp(Z90 * sd):,.0f} |")
        if n >= MIN_CELL:
            cells.setdefault(area, {})[band] = {"low": round(e / math.exp(Z90 * sd), 2), "high": round(e * math.exp(Z90 * sd), 2),
                                                "estimate": round(e, 2), "n": n}
    report(lines, verdict, synthetic)
    if args.write_app and not verdict and not synthetic:
        payload = {"version": 1, "basis": "LEARNED", "claims": len(claims), "k": k,
                   "residualLogSd": round(sd, 4), "minClaimsPerCell": MIN_CELL,
                   "metrics": {"learnedMedianAbsPercentError": round(lm, 4), "publishedMedianAbsPercentError": round(pm, 4),
                               "learnedWithin25": lw, "publishedWithin25": pw},
                   "cells": cells}
        with open(LEARNED, "w", encoding="utf-8", newline="\n") as f:
            json.dump(payload, f, indent=1)
        print(f"wrote {LEARNED}; rebuild the API to use it")


def report(lines, verdict, synthetic):
    if synthetic:
        lines += ["", "Verdict: self-test only (synthetic), the app file is never written."]
    else:
        lines += ["", "Verdict: " + ("passes the shipping rules (written with --write-app)." if not verdict
                                     else "not written to the app: " + "; ".join(verdict) + ".")]
    os.makedirs(os.path.join(HERE, "results"), exist_ok=True)
    name = "report-costs-selftest.md" if synthetic else "report-costs.md"
    with open(os.path.join(HERE, "results", name), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    sys.exit(main())
