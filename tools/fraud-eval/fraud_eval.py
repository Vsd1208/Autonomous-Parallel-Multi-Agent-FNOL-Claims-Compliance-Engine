"""Fraud anomaly score: synthetic data, unsupervised fit, held-out evaluation, app parameters.

    python -P fraud_eval.py                 # report only (results/report.md)
    python -P fraud_eval.py --write-app     # also writes fnol-orchestrator/src/main/resources/fraud-anomaly.json

No real fraud labels are available, so the data is SYNTHETIC: a documented generator with a fixed seed. The generator
encodes this project's own assumptions about how fraudulent claims differ from genuine ones; the evaluation shows that
the score recovers those assumed patterns, not that it detects real fraud. Results on real claims are unknown.

One feature is not synthetic: the second-opinion disagreement of genuine claims is drawn from the real model's
out-of-fold outputs on 441 labelled car photos (second-opinion-disagreement.json).

The score (same formula as the app's FraudAnomalyScorer):
    for each continuous feature:  points = clamp(direction * (transform(x) - median) / scale, 0, cap)
    for a binary feature:         points = weight when it is 1
    score = sum of points; FRAUD_ANOMALY when score >= threshold
median and scale (1.4826 x median absolute deviation, with a floor) are fitted on the training claims WITHOUT labels;
the threshold is the 95th percentile of training scores (a 5% referral budget). Labels are used only to evaluate.

Standard library only (Python 3.11+), so it runs anywhere, including CI.
"""
import argparse
import json
import math
import random
import statistics
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
APP_FILE = ROOT / "fnol-orchestrator" / "src" / "main" / "resources" / "fraud-anomaly.json"
# REAL measured values: the second-opinion model's out-of-fold disagreement with the true damaged area on 441 labelled
# car photos. Genuine claims draw this feature from here, so its reference profile matches what the app's model outputs.
REAL_DISAGREEMENT = json.loads((HERE / "second-opinion-disagreement.json").read_text(encoding="utf-8"))["values"]
REPORT = HERE / "results" / "report.md"

SEED = 20261031
N_CLAIMS = 20000
FRAUD_RATE = 0.04
REFERRAL_BUDGET = 0.05          # share of training claims the threshold flags
CAP = 4.0                       # most points one continuous feature can add
PHOTO_REUSED_WEIGHT = 3.0       # expert prior, not fitted: a reused photo counts like a 3-scale deviation

# name, label, direction (+1: high is unusual, -1: low is unusual), transform, scale floor
FEATURES = [
    ("claimRatio", "Amount claimed / AI estimate", 1, "log", 0.05),
    ("policyAgeDays", "Days from policy start to loss", -1, "log1p", 0.25),
    ("reportLagDays", "Days from loss to report", 1, "log1p", 0.25),
    ("priorClaims12m", "Claims on the policy in the last 12 months", 1, "none", 1.0),
    ("secondOpinionDisagreement", "Second opinion's disagreement with the AI damage type", 1, "none", 0.05),
]
BINARY = [("photoReused", "Photo already used on another claim", PHOTO_REUSED_WEIGHT)]

# Deliberately NOT used (fairness and relevance): reporting channel, time of day of the report, state or ZIP code,
# claimant name, age or any demographic, vehicle age or value. See docs/model-cards.md, card 6.

PATTERNS = ["inflation", "earlyPolicy", "lateReport", "repeatClaimant", "photoMismatch", "photoReused"]


# ── Synthetic data ──────────────────────────────────────────────────────────────────────────────────────────────
def genuine(rng):
    estimate = math.exp(rng.gauss(8.2, 0.7))                       # about $3,600 median
    young = rng.random() < 0.15                                    # new customers have genuine losses too
    age = rng.uniform(0, 180) if young else rng.uniform(180, 2500)
    lag = rng.uniform(7, 45) if rng.random() < 0.10 else min(rng.expovariate(1 / 3), 365)
    prior = poisson(rng, 0.25)
    return {
        "estimate": estimate,
        "claimRatio": math.exp(rng.gauss(0.0, 0.12)),
        "policyAgeDays": age,
        "reportLagDays": lag,
        "priorClaims12m": prior,
        "priorClaims6m": binomial(rng, prior, 0.5),
        "secondOpinionDisagreement": rng.choice(REAL_DISAGREEMENT),
        "photoReused": 1 if rng.random() < 0.003 else 0,
        "fraud": 0,
        "patterns": [],
    }


def fraudulent(rng):
    c = genuine(rng)
    k = rng.choices([1, 2, 3], weights=[0.45, 0.35, 0.20])[0]
    chosen = rng.sample(PATTERNS, k)
    for p in chosen:
        if p == "inflation":
            c["claimRatio"] = math.exp(rng.gauss(0.35, 0.20))
        elif p == "earlyPolicy":
            c["policyAgeDays"] = rng.uniform(0, 90)
        elif p == "lateReport":
            c["reportLagDays"] = rng.uniform(14, 120)
        elif p == "repeatClaimant":
            c["priorClaims12m"] = poisson(rng, 1.8)
            c["priorClaims6m"] = binomial(rng, c["priorClaims12m"], 0.6)
        elif p == "photoMismatch":
            # Photos that do not show this loss: the second opinion barely supports the claimed area. The two wrong
            # Gemini answers measured in tools/second-opinion both had a disagreement of 0.87.
            c["secondOpinionDisagreement"] = rng.uniform(0.80, 0.99)
        elif p == "photoReused":
            c["photoReused"] = 1
    c["fraud"], c["patterns"] = 1, chosen
    return c


def poisson(rng, lam):
    n, p, limit = 0, 1.0, math.exp(-lam)
    while True:
        p *= rng.random()
        if p <= limit:
            return n
        n += 1


def binomial(rng, n, p):
    return sum(1 for _ in range(n) if rng.random() < p)


def dataset():
    rng = random.Random(SEED)
    claims = [fraudulent(rng) if rng.random() < FRAUD_RATE else genuine(rng) for _ in range(N_CLAIMS)]
    rng.shuffle(claims)
    half = len(claims) // 2
    return claims[:half], claims[half:]


# ── Score (mirrors FraudAnomalyScorer.java) ─────────────────────────────────────────────────────────────────────
def transform(x, how):
    if how == "log":
        return math.log(x)
    if how == "log1p":
        return math.log1p(max(x, 0.0))
    return float(x)


def fit(train):
    params = []
    for name, label, direction, how, floor in FEATURES:
        values = [transform(c[name], how) for c in train]
        med = statistics.median(values)
        mad = statistics.median(abs(v - med) for v in values)
        params.append({"name": name, "label": label, "type": "continuous", "direction": direction,
                       "transform": how, "median": round(med, 6), "scale": round(max(1.4826 * mad, floor), 6)})
    for name, label, weight in BINARY:
        params.append({"name": name, "label": label, "type": "binary", "weight": weight})
    return params


def contributions(c, params):
    out = []
    for p in params:
        x = c.get(p["name"])
        if x is None:
            continue
        if p["type"] == "binary":
            pts = p["weight"] if x else 0.0
        else:
            z = p["direction"] * (transform(x, p["transform"]) - p["median"]) / p["scale"]
            pts = min(max(z, 0.0), CAP)
        out.append((p["name"], pts))
    return out


def score(c, params):
    return sum(pts for _, pts in contributions(c, params))


# ── Rules the app already has (for comparison) ──────────────────────────────────────────────────────────────────
def fraud_rules(c):
    """PHOTO_REUSED, HIGH_CLAIM_FREQUENCY, INCIDENT_NEAR_POLICY_START (IntakeReviewService, ClaimHistoryEnricher)."""
    high_freq = c["priorClaims12m"] >= 2 or (c["priorClaims12m"] == 1 and c["priorClaims6m"] >= 1)
    return c["photoReused"] == 1 or high_freq or c["policyAgeDays"] < 30


def all_rules(c):
    """Plus CLAIMED_EXCEEDS_AI_ESTIMATE (> 10%) and MODEL_DISAGREEMENT (second opinion < 0.15)."""
    return fraud_rules(c) or c["claimRatio"] > 1.10 or (1 - c["secondOpinionDisagreement"]) < 0.15


# ── Metrics ─────────────────────────────────────────────────────────────────────────────────────────────────────
def auc(scores, labels):
    """Probability a random fraud claim scores above a random genuine one (ties count half)."""
    ranked = sorted(zip(scores, labels))
    pos = sum(labels)
    neg = len(labels) - pos
    rank_sum, i = 0.0, 0
    while i < len(ranked):
        j = i
        while j < len(ranked) and ranked[j][0] == ranked[i][0]:
            j += 1
        avg = (i + j + 1) / 2
        rank_sum += avg * sum(lbl for _, lbl in ranked[i:j])
        i = j
    return (rank_sum - pos * (pos + 1) / 2) / (pos * neg)


def confusion(flags, labels):
    tp = sum(1 for f, y in zip(flags, labels) if f and y)
    fp = sum(1 for f, y in zip(flags, labels) if f and not y)
    fn = sum(1 for f, y in zip(flags, labels) if not f and y)
    flagged = tp + fp
    return {"flagged": flagged, "flagRate": flagged / len(labels),
            "precision": tp / flagged if flagged else 0.0, "recall": tp / (tp + fn) if tp + fn else 0.0,
            "tp": tp, "fp": fp, "fn": fn}


def percentile(values, q):
    s = sorted(values)
    return s[min(len(s) - 1, int(math.ceil(q * len(s))) - 1)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write-app", action="store_true")
    args = ap.parse_args()

    train, test = dataset()
    params = fit(train)
    train_scores = [score(c, params) for c in train]
    threshold = round(percentile(train_scores, 1 - REFERRAL_BUDGET), 4)
    # The reference distribution of scores (0th..100th percentile): the app reports each claim's percentile, i.e. the
    # share of reference claims that are less unusual. Checked below on the held-out half.
    quantiles = [round(percentile(train_scores, q / 100) if q else min(train_scores), 4) for q in range(101)]

    y = [c["fraud"] for c in test]
    s = [score(c, params) for c in test]
    by_score = confusion([v >= threshold for v in s], y)
    by_fraud_rules = confusion([fraud_rules(c) for c in test], y)
    by_all_rules = confusion([all_rules(c) for c in test], y)
    by_either = confusion([fraud_rules(c) or v >= threshold for c, v in zip(test, s)], y)
    test_auc = round(auc(s, y), 4)
    # Percentile calibration on held-out claims: the share scoring above the reference p-th percentile should be 1 - p.
    pct_check = [(q, sum(1 for v in s if v > quantiles[q]) / len(s)) for q in (50, 80, 90, 95, 99)]

    per_pattern = {}
    for p in PATTERNS:
        idx = [i for i, c in enumerate(test) if p in c["patterns"]]
        per_pattern[p] = (len(idx), sum(1 for i in idx if s[i] >= threshold), sum(1 for i in idx if fraud_rules(test[i])))
    single = [i for i, c in enumerate(test) if len(c["patterns"]) == 1]
    multi = [i for i, c in enumerate(test) if len(c["patterns"]) >= 2]

    metrics = {"auc": test_auc, "precision": round(by_score["precision"], 4), "recall": round(by_score["recall"], 4),
               "flagRate": round(by_score["flagRate"], 4), "testClaims": len(test), "testFraud": sum(y)}

    reference = []
    for c in test[:6]:
        reference.append({k: (round(c[k], 6) if isinstance(c[k], float) else c[k])
                          for k in [f[0] for f in FEATURES] + [b[0] for b in BINARY]}
                         | {"expectedScore": round(score(c, params), 6)})

    write_report(params, threshold, metrics, by_score, by_fraud_rules, by_all_rules, by_either, per_pattern,
                 single, multi, s, y, test, pct_check)
    print(f"threshold {threshold}  AUC {test_auc}  precision {by_score['precision']:.3f}  "
          f"recall {by_score['recall']:.3f}  flag rate {by_score['flagRate']:.3f}")

    if args.write_app:
        app = {
            "version": 1,
            "description": "Fraud anomaly score: robust z-scores of intake features against a fitted reference "
                           "profile. Routes to an adjuster and suggests an SIU referral; never denies.",
            "dataset": f"SYNTHETIC, tools/fraud-eval/fraud_eval.py, seed {SEED}, {N_CLAIMS} claims, "
                       f"{FRAUD_RATE:.0%} fraud. Results on real claims are unknown.",
            "createdAt": date.today().isoformat(),
            "cap": CAP,
            "threshold": threshold,
            "referralBudget": REFERRAL_BUDGET,
            "referenceQuantiles": quantiles,
            "features": params,
            "metrics": metrics,
            "referenceCases": reference,
        }
        APP_FILE.parent.mkdir(parents=True, exist_ok=True)
        APP_FILE.write_text(json.dumps(app, indent=2) + "\n", encoding="utf-8")
        print(f"wrote {APP_FILE}")


def write_report(params, threshold, metrics, sc, fr, ar, ei, per_pattern, single, multi, s, y, test, pct_check):
    def row(name, m):
        return (f"| {name} | {m['flagged']} ({m['flagRate']:.1%}) | {m['precision']:.1%} | {m['recall']:.1%} |")

    def recall(idx, flag):
        hit = sum(1 for i in idx if flag(i))
        return f"{hit}/{len(idx)} ({hit / len(idx):.0%})" if idx else "—"

    lines = [
        "# Fraud anomaly score: synthetic evaluation",
        "",
        f"**Synthetic data only.** Generator and seed: `tools/fraud-eval/fraud_eval.py`, seed {SEED}, {N_CLAIMS} "
        f"claims, {FRAUD_RATE:.0%} fraudulent, split in half (fit on the first half without labels, evaluated on the "
        "second). The generator encodes this project's assumptions about fraud; these numbers show the score finds "
        "the patterns it was designed for. **Performance on real claims is unknown.**",
        "",
        f"Held-out claims: {metrics['testClaims']}, of which {metrics['testFraud']} fraudulent. Threshold "
        f"{threshold} points (95th percentile of training scores, a {REFERRAL_BUDGET:.0%} referral budget).",
        "",
        f"**AUC {metrics['auc']:.3f}** (ranking fraud above genuine claims; 0.5 = chance).",
        "",
        "| Method | Claims flagged (rate) | Precision | Recall |",
        "|---|---|---|---|",
        row("Anomaly score ≥ threshold", sc),
        row("Existing fraud rules (photo reused, high frequency, loss < 30 days after policy start)", fr),
        row("Fraud rules + claimed > 10% above estimate + model disagreement", ar),
        row("Fraud rules or anomaly score", ei),
        "",
        "## Recall by fraud pattern (held-out)",
        "",
        "| Pattern | Claims with it | Caught by the score | Caught by the fraud rules |",
        "|---|---|---|---|",
    ]
    for p, (n, by_s, by_r) in per_pattern.items():
        lines.append(f"| {p} | {n} | {by_s}/{n} ({by_s / n:.0%}) | {by_r}/{n} ({by_r / n:.0%}) |" if n else f"| {p} | 0 | — | — |")
    lines += [
        "",
        f"Fraud claims with one pattern: score {recall(single, lambda i: s[i] >= threshold)}, rules "
        f"{recall(single, lambda i: fraud_rules(test[i]))}. With two or more: score "
        f"{recall(multi, lambda i: s[i] >= threshold)}, rules {recall(multi, lambda i: fraud_rules(test[i]))}.",
        "",
        "## Percentile calibration (held-out)",
        "",
        "The app reports each claim's percentile against the training scores. If that is calibrated, the share of "
        "held-out claims above the p-th percentile is 1 − p:",
        "",
        "| Percentile | Expected share above | Observed share above |",
        "|---|---|---|",
    ] + [f"| {q}th | {1 - q / 100:.1%} | {obs:.1%} |" for q, obs in pct_check] + [
        "",
        "## Fitted reference profile",
        "",
        "| Feature | Direction | Transform | Median | Scale |",
        "|---|---|---|---|---|",
    ]
    for p in params:
        if p["type"] == "binary":
            lines.append(f"| {p['label']} | yes = unusual | binary | — | weight {p['weight']} (expert prior) |")
        else:
            lines.append(f"| {p['label']} | {'high' if p['direction'] > 0 else 'low'} is unusual | {p['transform']} | "
                         f"{p['median']} | {p['scale']} |")
    lines += [
        "",
        f"Each continuous feature adds at most {CAP} points. Not used, by design: channel, time of day, state or ZIP "
        "code, and any personal or demographic attribute (fairness; see docs/model-cards.md).",
        "",
    ]
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    main()
