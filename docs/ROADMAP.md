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
- Mock PolicyCenter and ClaimCenter over HTTP: draft claim, exposure with the reserve, activities, notes, documents.
- Adjuster workspace: queue, review with a final reserve, photo requests, inspections, audit trail and PDF, AI
  governance page with fairness monitoring.
- Failed intakes saved and queued; duplicates flagged; SHA-256 hash-chained audit trail.
- Evaluation tooling and model cards for every AI component; CI with coverage reports.

## Next

| Item | Why |
|---|---|
| Run the HOME prompt evaluation on the 53 prepared FEMA photos | The property prompt is not measured yet |
| Grow the vehicle evaluation to the 120 prepared photos (66 done; the free API tier allows about 16–20 a day) and add multi-photo claims | 12 wrong answers is a small sample for calibration; real claims send five photos, the evaluation one |
| Real adjuster authentication (OAuth2 / OIDC) and roles (adjuster, inspector, SIU) | The shared token is a prototype control; `adjusterId` comes from the request body |
| Rate limiting on the portal's claim tracking | Claim number + policy number lookups are not throttled |
| Scheduled ClaimCenter retry job | Failed syncs are marked and can be retried by hand only |
| Database-backed idempotency | The in-memory layer is per JVM |
| Contract tests against a real Guidewire Cloud API sandbox | The mock is modelled on, not verified against, the Cloud API ([guidewire-integration.md](guidewire-integration.md)) |
| Legal review of the claim clock rules | Three recovery limits are from background knowledge; no lawyer has reviewed the rule set |
| Stakeholder interviews with claims professionals ([template](STAKEHOLDER_INTERVIEW_TEMPLATE.md)) | To check the pain points and the business case against practice |
| Fraud score on real labelled claims | Its calibration is synthetic ([model-cards.md](model-cards.md), card 6) |

## Known limits

- Guidewire is mocked; `PolicyCenterClient` and `ClaimCenterClient` are the swap point for real clients.
- Coverage rules and subrogation weights are synthetic; policies and claimants are invented.
- Gemini's repair-cost estimates and severity are not validated; no dataset with repair costs was found.
- Gemini's free tier was slow in evaluation (p95 34 s); a claim whose call exceeds the 8 s read timeout goes to a
  person.
- Photos travel inline as data URIs (≤ 5 MB each) or by allow-listed URL; there is no separate document store.
- The console is React, not Guidewire Jutro; the branches use `FutureTask`s, not `CompletableFuture` (ADR-011).
