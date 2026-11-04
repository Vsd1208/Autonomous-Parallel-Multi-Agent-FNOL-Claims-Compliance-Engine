# Final engineering audit

Written 4 Nov 2026 on branch `engineering-remediation`, for an engineer deciding whether this work is credible. Every figure comes from a command run on this branch. Nothing here has run against a real Guidewire tenant or real insurer claims.

## Executive summary

**This is not ready for a production pilot, and passing tests do not make it so.** The human-governance design, the security model, the durable event path and the honesty about AI limits are credible engineering. What stands in the way is integration and evidence, not more code: no Guidewire tenant, no lawyer's review, no real claims, and an AI model about to be retired.

The core rule holds: **the AI cannot deny a claim, post a reserve on its own by default, or record a decision.** Only a signed-in person can decide. When the AI has no reliable answer it says so and hands the claim to a person.

## The answer

> *Would a principal Guidewire engineer consider this implementation technically credible, secure, explainable and sufficiently integrated to justify a production pilot?*

| Question | Answer | Why |
|---|---|---|
| Technically credible? | **Yes, as a prototype** | Sound design, tested failure paths on a real database. Single API instance, no load test, no real deployment |
| Secure? | **Reasonable for a prototype; not assessed** | Real object-level access, portal limits and audit chain. No external penetration test, no single sign-on, no retention schedule |
| Explainable? | **Yes** | Each recommendation shows its factors, flags, evidence, abstention reasons and how far each rule was checked. This is the project's strongest area |
| Sufficiently integrated? | **No** | Nothing has run on a tenant. The cloud claims client refuses most operations, and every field mapping is assumed |
| **Justifies a production pilot?** | **No, not yet** | It would justify a **sandbox trial**, which is the next step and needs a tenant |

## Before and after

| Area | Before | After | Evidence |
|---|---|---|---|
| Authorization | Inspectors could read every claim. A service token could decide under any name | Object-level checks that fail closed. Only a signed-in person decides | `ClaimAccessTest`, on PostgreSQL too; `run-all.sh` |
| Event durability | A 202 meant an in-memory queue; a crash lost the event | A 202 means committed. Leases, retries, dead letter, replay | `DurableInboxTest`, `EventInboxWorkerDispatchTest`; [crash tests](#crash-test) |
| Audit integrity | Hash covered input and output only. Actor, time and status could be edited unseen | Versioned canonical chain, head lock across instances, verifier naming each problem | `AuditIntegrityTest`, on PostgreSQL too. **Off-site anchoring not automated** |
| Policy validation | Tested the policy against today, not the loss date | Judged at the date of loss, with cancellation and endorsements. Unknown goes to a person | `PolicyAtDateOfLossTest` |
| PII | Constant 0.95 "confidence"; few identifier types | No invented confidence. More types, and a re-scan that blocks. Sideways faces now found | `PiiDetectionTest`, `ObservabilityTest`, `test_redact.py`. **A stranger's name in free text is not found** |
| ML | No abstention, no holdout, photo-level only, no leakage check | Explicit abstention, leakage and holdout check, claim-level report, substitute-model detection | [AI_MODEL_EVALUATION.md](AI_MODEL_EVALUATION.md). **Accuracy on a new source falls to about 64–68%** |
| Guidewire | Part of ClaimCenter, a static token, nothing for PolicyCenter | Token renewal, typed outages, rate limits, a policy client on documented paths, a data map | `GuidewireSimulatorContractTest`. **Simulator only; no tenant** |
| Reliability | No metrics, correlation IDs or probes; no startup checks | All of those, checked on a real stack | `ObservabilityTest`, `StartupConfigValidatorTest`. No dashboards or load test |

## Initial findings

Each was confirmed in the code before being listed. Full detail: [ENGINEERING_REMEDIATION_PLAN.md](ENGINEERING_REMEDIATION_PLAN.md).

**Vulnerabilities fixed:**
- Inspectors could read any claim's analysis, reserve, fraud score and audit trail.
- A service credential could record a decision under any adjuster's name, which also got round maker-checker.
- The public portal could be guessed at without limit, and its answers revealed what existed.
- Uploads were trusted by their declared type.
- Most of an audit event could be edited undetected, and `synchronized` protected only one JVM.
- The dead-letter API returned whole inbox rows, including saved claim content.

**Correctness:** a loss reported after a policy expired looked uncovered, while a loss before inception on a policy active today looked covered.

**Honesty:** a constant 0.95 was presented as a redaction confidence, and a missing Gemini confidence silently became 0.75.

## Bugs this work introduced, and how they were found

This belongs in an honest audit. **Unit tests passed through all of these except the second.** Running the real system found the rest:

| Bug | Found by |
|---|---|
| A timed-out analysis retried for over two minutes with no adjuster told | `run-all.sh` on a real stack |
| The fix for that made a claim's retry look like a duplicate of itself | A new test |
| A worker dying before telling anyone left later failures silent | Killing the API mid-event |
| Two events due together could leave one looking taken until its lease ran out. It failed about 1 lite run in 2 | Repeating `run-all.sh` and reading the logs |
| Abstention was computed but never reached the screen | A new frontend test |
| A sideways face was almost never detected | A new robustness measurement |

## Larger findings from evaluation

- **`gemini-2.5-flash` is retiring.** It is closed to new users and re-routed from 1 December 2026. A substitute is now detected and sent to a person, though that could not be tested against a real re-route. No successor has been measured: two trials answered 0 of 5 calls.
- **New photo sources.** The second-opinion model falls from 78% to about 64–68%, and its calibration error rises from 0.03 to 0.15–0.22. Near-duplicates are not the cause.
- **Severity is rough in both directions.** In one visual check, about 7 of 24 HIGH answers looked over-called and about 5 of 42 others under-called. Left unchanged deliberately: [details](AI_MODEL_EVALUATION.md#severity-a-visual-check).

## Migrations

Added, none edited: `V24__add_portal_protection.sql`, `V25__add_event_inbox.sql`, `V26__audit_integrity.sql`. Flyway builds V1 to V26 on PostgreSQL 16.14, and Hibernate validates the result.

## API changes

Listed in [api-contracts.md](api-contracts.md#behaviour-changes-in-the-engineering-remediation-nov-2026). **Three break a script relying on old behaviour:** a service credential can no longer decide; inspectors get 404 outside their assignments; the portal answers 429 past its limit.

## Files

Main additions, beyond edits to existing classes:
- **Security:** `ClaimAccessPolicy`, `ClaimAccessInterceptor`, `ActorType`, `PortalProtection`.
- **Processing:** `EventInboxService`, `EventInboxWorker`, `BranchCheckpoint`, `AdminEventInboxController`.
- **Audit:** `AuditChainHeadEntity`, `AdminAuditController`.
- **AI:** `AbstentionPolicy`, `tools/vision-eval/claim_eval.py`, `tools/second-opinion/leakage_check.py`.
- **Rules:** `regulatory-holidays.json`, versioned `claim-regulations.json`.
- **Guidewire:** `GuidewireTokenProvider`, `GuidewireCloudHttp`, `GuidewireCloudPolicyCenterClient`.
- **Operations:** `observability/`, `Operations.tsx`, `RoleGuard.tsx`.

## Tests executed

All on 4 Nov 2026, on the final code, one laptop, run in sequence with nothing else touching the containers.

| Suite | Command | Result |
|---|---|---|
| Backend | `mvn -B clean verify` | **597 passed**, 0 failed, 0 skipped: fnol-api 232, fnol-agents 177, fnol-orchestrator 109, fnol-enrichment 35, fnol-mock-guidewire 31, fnol-guardrails 13 |
| Database-sensitive, real PostgreSQL 16.14, each on a fresh database built by Flyway V1 to V26 | `DurableInboxTest`, `AuditIntegrityTest`, `PortalSecurityTest`, `ClaimAccessTest` | **57 passed** (14, 20, 7, 16) |
| Lite stack | `demo-scenarios/run-all.sh` | **130 passed**, 0 failed, in 7 runs out of 7 |
| Full stack, mock vision | `demo-scenarios/run-all.sh` | **112 passed**, 0 failed |
| Frontend | `npm test`, `npm run build`, `npm run build:portal` | **18 passed**; both builds pass; portal bundle clean |
| Python | `pytest tools/test_evaluation.py fnol-vision-ml/redaction/test_redact.py` | **30 passed** |

**On the 7 lite runs:** before the worker fix, the same scenario failed in 2 of 4 runs. Seven clean runs is strong evidence and not proof that a race is gone, but the cause was found in the logs and has its own test.

**On the real stack:** liveness and readiness were up; metrics refused without a credential; no policy number or name in the metrics; no unmasked policy number in the API's log.

## Crash test

Real containers on the full stack, with the API killed by `SIGKILL`:

| Crash | What happened | Result |
|---|---|---|
| **A.** API dead when a claim is filed | The claim was filed with the API down; no inbox row existed yet. The claims system held the event and delivered it after restart | Analysed on the first attempt. One exposure, no duplicates |
| **B.** API killed mid-processing | The row survived, held by the dead worker. After restart it was left alone until the lease ran out (25 s in this test, 120 s by default), then another worker took it. This claim's photos are deliberately too slow, so the attempt timed out | Retry scheduled. The claim moved to `HUMAN_REVIEW` with one activity, so **a person was told** |
| **C.** PostgreSQL restarted under the API | The API stayed up and recovered by itself | A claim filed afterwards was analysed; audit chain valid, no problems |

**Not tested:** a kill between two writes to the claims system, which is the at-least-once limit below. In crash B the analysis had written nothing yet.

## Tests not executed, and why

| Not run | Why |
|---|---|
| Anything on a real Guidewire tenant | No tenant. `tools/guidewire-check/check_sandbox.py` is ready and has never run |
| Real Gemini in these regression runs | Mock or recorded answers keep runs repeatable. The Gemini figures are from recorded runs |
| The new CI PostgreSQL job on GitHub | It has not run until this branch is pushed |
| Load and soak tests | Not built. No throughput figure exists |
| Two API containers at once | Concurrency tested with workers racing in one process, not two containers |
| Browser end-to-end tests | Frontend tests are component-level |
| CSRF tests | The API uses header credentials, not cookies. Not separately tested |
| External penetration test | Needs a third party |
| ML on real claims; comparison with adjusters | No data, no adjusters |

## Known limitations

- **At-least-once writes.** A worker dying after writing to the claims system repeats that write on retry.
- **Coverage ignores real contract wording.** It works from the policy data this project has.
- **Synthetic values.** Reserve tables' authority limits and coverage rules are prototypes. The fraud score is on synthetic data.
- **No linter or static analysis** is configured for any language: only compilers, type checks and tests.
- **`FNOLOrchestrator` and `FNOLController` remain large.** Refactoring was deliberately skipped, since a rewrite this late is a greater risk than the size.
- **A claim is open from submission** ([ADR-013](architecture-decisions.md)), rather than a draft until decided. The human gate is that only a person posts a reserve.

More: [SECURITY.md](SECURITY.md), [DISTRIBUTED_PROCESSING.md](DISTRIBUTED_PROCESSING.md#known-limits), [REGULATORY_RULES.md](REGULATORY_RULES.md#not-done), and the [README](../README.md#known-limits).

## Deployment

[DEPLOYMENT.md](DEPLOYMENT.md). Run only on Docker Compose on one laptop. With `FNOL_ENVIRONMENT=production` the application refuses to start on demo settings or a mock system, so **it cannot currently start in production mode and also work end to end.** That is correct: it needs a tenant.

## What it would take to reach a pilot

In order:

1. **A Guidewire sandbox.** Run the check, correct the assumptions, map the refused operations, and connect real App Events.
2. **A lawyer's review** of every rule.
3. **A current AI model,** measured, then refitted, and recalibrated on the insurer's own photos.
4. **Real adjusters,** to grade severity, compare decisions and measure override rates.
5. **Production basics:** single sign-on, TLS, a retention schedule, off-site audit anchoring, alerts, a load test and a penetration test.

## Evidence matrix

| Claim | Status | Evidence |
|---|---|---|
| AI never denies or decides | Implemented and tested | `ClaimAccessTest`, `AbstentionPolicyTest`, `run-all.sh` |
| Inspector sees only assigned work | Implemented and tested | `ClaimAccessTest`, on PostgreSQL |
| A 202 survives a crash | Implemented and tested | `DurableInboxTest`, crash tests |
| Audit tampering is detected | Implemented and tested | `AuditIntegrityTest`, on PostgreSQL |
| A full rewrite with database access is detected | **Only with an off-site anchor, which is not automated** | `AUDIT_INTEGRITY.md` |
| No personal data in logs | Implemented and tested for known values | `ObservabilityTest`, container log check |
| Works on a Guidewire tenant | **Not verified** | Simulator and mock only |
| Rules are legally correct | **Not verified** | No lawyer's review |
| AI accuracy on insurer photos | **Not measured** | Public datasets only; expect about 64–68% |
| Severity and reserve accuracy | **Not measured** | No labelled data |
| Fraud score works on real claims | **Not measured** | Synthetic data only |
| Handles production load | **Not measured** | No load test |
