# Roadmap

What has been delivered, what comes next, and the known limits. The original 4-week sprint plan is kept as a
historical record in [SPRINT_PLAN.md](SPRINT_PLAN.md); this page replaces it. Measured results: [metrics.md](metrics.md).

## Delivered

- FNOL intake from a separate policyholder portal and from internal (phone / agent) intake, with guardrails and intake
  review flags.
- Two branches in parallel under one deadline: operational (Gemini vision with a second-opinion model and a measured
  trust score, coverage, reserve) and legal (PII redaction, a cited state claim clock, subrogation, attorney check).
- Reconciliation gate with consistency flags and a six-factor WHY panel; advisory review before any claim counts as
  filed straight through.
- Mock PolicyCenter and ClaimCenter over HTTP: draft then open claim, exposure, activities, notes, documents. Only a
  person's decision posts a reserve (ADR-013).
- The engine as an extension of the claims system: losses and decisions recorded there first, signed events with
  retries, results written back onto the claim, queues read from it (ADR-012).
- Adjuster workspace: queue, review with a final reserve, photo requests, inspections, audit trail and PDF, AI
  governance page with fairness monitoring.
- Failed intakes saved and queued; duplicates flagged; SHA-256 hash-chained audit trail.
- Evaluation tooling and model cards for every AI component; CI with coverage reports.

## Next

| Item | Why |
|---|---|
| Run the HOME prompt evaluation on the 53 prepared FEMA photos | The property prompt is not measured yet |
| Grow the vehicle evaluation to the 120 prepared photos (66 done; the free API tier allows about 16–20 a day) and add multi-photo claims | 12 wrong answers is a small sample for calibration; real claims send five photos, the evaluation one |
| Insurer identity provider (OIDC) and more roles (SIU, supervisor) | Sign-in and adjuster and inspector roles are built (ADR-014), but accounts are local |
| Rate limiting on the portal's claim tracking | Claim number + policy number lookups are not throttled |
| Scheduled ClaimCenter retry job | Decisions no longer need one in claims-system mode, but photo-request and inspection steps, and direct intake, can still be left out of step by an outage, and are retried by hand only |
| Database-backed idempotency | The in-memory layer is per JVM |
| Contract tests against a real Guidewire Cloud API sandbox | The mock is modelled on, not verified against, the Cloud API ([guidewire-integration.md](guidewire-integration.md)) |
| Check the human gate against ClaimCenter's reserve authority rules | ADR-013 makes a person's decision what posts a reserve, but authority limits are not modelled |
| Legal review of the claim clock rules | Three recovery limits are from background knowledge; no lawyer has reviewed the rule set |
| A study with real adjusters ([kit](adjuster-study.md), [interview template](STAKEHOLDER_INTERVIEW_TEMPLATE.md)) | Nobody has tried this with real adjusters. The product now measures it, but the results are empty |
| Sandbox access ([request and first-day steps](guidewire-sandbox-request.md)) | Nothing has run on a real tenant. Only Guidewire can grant this |
| Costed estimates: ask Gemini for parts and labour, and re-measure | Its dollar figures mostly restate the severity band, but changing the prompt invalidates the fitted trust score, so it needs a re-run of about 66 photos (days at the free tier's daily limit) |
| Fraud score on real labelled claims | Its calibration is synthetic ([model-cards.md](model-cards.md), card 6) |

## Known limits

- Guidewire is mocked; `PolicyCenterClient` and `ClaimCenterClient` are the swap point for real clients.
- Coverage rules and subrogation weights are synthetic; policies and claimants are invented.
- Gemini's repair-cost estimates and severity are not validated; no dataset with repair costs was found. Its figures are shown as rough: 86% of 66 recorded answers fall on five values.
- Gemini's free tier was slow in evaluation (p95 34 s); a claim whose call exceeds the 8 s read timeout goes to a
  person.
- Photos travel inline as data URIs (≤ 5 MB each) or by allow-listed URL; there is no separate document store.
- The console is React, not Guidewire Jutro; the branches use `FutureTask`s, not `CompletableFuture` (ADR-011).
