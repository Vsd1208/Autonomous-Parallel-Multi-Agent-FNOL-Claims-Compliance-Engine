# Fraud anomaly score: synthetic evaluation

`fraud_eval.py` generates a synthetic claim population, fits the fraud anomaly score's reference profile without
labels, evaluates it on held-out claims and (with `--write-app`) writes the parameters the app uses
(`fnol-orchestrator/src/main/resources/fraud-anomaly.json`, read by `FraudAnomalyScorer`). Standard library only.

```bash
cd tools/fraud-eval
python -P fraud_eval.py               # writes results/report.md
python -P fraud_eval.py --write-app   # and the app parameters
```

## Why synthetic, and what that means

No labelled fraud data is publicly available. The generator (seed 20261031, 20,000 claims, 4% fraudulent) encodes
this project's own assumptions:

- **Genuine claims:** claimed amount close to the photo estimate; mostly older policies, with 15% of genuine losses
  in a policy's first six months; usually reported within days, with 10% reported 1–6 weeks late; few prior claims;
  reused photos are rare (0.3%). **Real, not assumed:** the second-opinion disagreement is drawn from the model's
  out-of-fold outputs on the 441 labelled car photos (`second-opinion-disagreement.json`, median 0.51).
- **Fraudulent claims:** each has one to three of six patterns: an inflated amount, a loss soon after the policy
  starts, a late report, a repeat claimant, photos the second opinion barely supports (disagreement 0.80–0.99; the two
  wrong Gemini answers measured had 0.87), a reused photo. The other
  features are drawn as for genuine claims.

So the evaluation shows that the score recovers the patterns it was designed for. **It says nothing about how well
it finds real fraud.** That needs labelled claims from an insurer's SIU.

## Method

For each continuous feature, `points = clamp(direction × (transform(x) − median) / scale, 0, 4)`, where median and
scale (1.4826 × median absolute deviation, with a floor) are fitted on the training half **without labels**. A reused
photo adds a fixed 3 points (an expert prior, not fitted). The score is the sum. The threshold is the 95th percentile
of training scores: a 5% referral budget. Labels are used only for evaluation. The app's `FraudAnomalyScorer` uses
the same formula; a parity test checks the Java scores against reference cases this script writes.

Features: amount claimed / AI estimate, days from policy start to loss, days from loss to report, claims in the last
12 months, the second opinion's disagreement with the AI damage type, reused photo. **Not used, by design:** reporting
channel, time of day, state or ZIP code, and any personal or demographic attribute.

The first version drew the disagreement from an assumed distribution (median 0.27). On a live claim with real photos the
second opinion's usual ~70% disagreement then pushed an ordinary claim to 5.90 against a 5.91 threshold; fitting this
feature on real outputs fixed it.

Each claim also gets a **percentile** against the training scores (`referenceQuantiles`), checked on held-out claims.

## Results

[results/report.md](results/report.md); summarised with caveats in [docs/metrics.md](../../docs/metrics.md#7-fraud-anomaly-score-synthetic-data-only).
