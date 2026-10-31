# Selection-readiness audit

An audit of this repository as a Guidewire DEVSummit reviewer would read it, written on branch `selection-readiness`
(from `main` at `e988953`, 31 Oct 2026). Guidewire has not published judging criteria, so the rubric below is our own
assumption of what reviewers value. Every finding cites a file; every number comes from a command we ran or a file in
the repository.

**Baseline commands (31 Oct 2026, before any change):**

| Command | Result |
|---|---|
| `mvn -B verify` | BUILD SUCCESS. 253 tests, 0 failures: fnol-agents 84, fnol-orchestrator 72, fnol-api 61, fnol-guardrails 13, fnol-mock-guidewire 13, fnol-enrichment 10, fnol-common 0. 14 build warnings, all the same: "Using platform encoding … build is platform dependent" |
| `cd frontend && npm ci && npm run build && npm run build:portal` | Both builds succeed with no warnings |
| Python tests for `fnol-vision-ml` / `tools/*` | None exist |
| `docker compose … up` | Not run for the audit: Docker Desktop was not running on the audit machine |

## 1. Rubric scorecard

Scores are 1 (weak) to 5 (strong). The "After" column is filled in at the end of the work ([section 5](#5-after)).

| # | Criterion | Before | Evidence | What a skeptical judge would say | Fix |
|---|---|---|---|---|---|
| a | Insurance-domain understanding | 4 | Reserve on the ClaimCenter exposure, `min(claimed, AI estimate) − deductible`, capped at the limit (`fnol-agents/.../ReserveCalculatorAgent.java:13-17`); cited claim clock per state (`docs/legal-compliance.md` §1, `claim-regulations.json`); subrogation with recovery limitation deadline; attorney-representation routing; flood exclusion flag; acknowledgement letter | "Lots of insurance terms, but where is it explained how these fit an FNOL lifecycle? No bodily injury, total loss or liability split." | `docs/DOMAIN.md`: lifecycle, terms, mapping to classes; state the scope (first-party property damage, AUTO and HOME) |
| b | Real versus hardcoded models | 4 | Gemini called for real (`GeminiVisionProvider`); second-opinion CLIP head trained in `tools/second-opinion`; trust score fitted and measured leave-one-image-out (`tools/vision-eval/trust_score.py`); `MockVisionProvider` is deterministic for demos and tests | "50 photos is small; are the dollar estimates real? Which numbers come from which run?" | `docs/metrics.md` as the single source of truth with sample size, run and caveat per number; keep "dollar estimates not validated" prominent |
| c | Fraud detection depth | 2 | Duplicate FNOL (`DuplicateSubmissionRecorder`), photo reuse by SHA-256 / dHash (`EvidenceStore.java:132`), claim frequency and incident-near-policy-start (`IntakeReviewService.java:26-27,71`), claimed-above-estimate (`GateConsistencyChecker.java:92`) | "Every fraud signal is a hand-written rule. Nothing combines them or is measured." | Phase 4: an explainable anomaly score over intake features, measured on a documented synthetic set, routing only (`FRAUD_ANOMALY`) |
| d | Real versus simulated data and integrations | 3 | Honest statement in `docs/guidewire-integration.md`; seeded mock policies; real NHTSA/FEMA/CC0 photos with credits | "README says deadlines are synthetic, the legal doc says they are cited. The mock uses `/cc/rest/claim/v1/...` paths: are those Guidewire's real ones?" | A "What is real vs simulated" table at the top of the README; reword the mock paths as "modelled on, not verified against" unless verified |
| e | Code quality and tests | 4 | 253 tests across 6 modules, deterministic providers, Testcontainers-free | "No CI, no coverage report, a committed `.pyc`, an encoding warning, `FNOLOrchestrator` is 732 lines, `ApiExceptionHandler` lives inside `FNOLController.java`" | CI workflow with JaCoCo report and badge; fix the encoding warning; remove build artefacts |
| f | Architecture and resilience | 4 | Two branches on a bounded executor under one deadline with interrupting cancel (`FNOLOrchestrator.java:34`); failed intakes saved as `REJ-…` (ADR-008); best-effort ClaimCenter calls with `cc_sync_status`; fail-closed redaction | "The idempotency guard's memory layer is per JVM; there is no retry job; Guidewire clients have no contract tests" | Contract tests for the Guidewire clients with configurable base URL and auth; document limits in `docs/ROADMAP.md` |
| g | Security and privacy | 3 | SSRF-safe evidence loading (`EvidenceImageLoader.java:26-31`); PII redaction + guardrail; face/plate blurring before the external model; portal separate from the workspace and without the adjuster token; hash-chained audit | "A shared adjuster token baked into a browser bundle; `adjusterId` comes from the request body; portal tracking has no rate limit" | Keep these in Known limits (already partly there); list them in `ROADMAP.md` as next steps. Not fixed here: real authentication is a larger change |
| h | Explainability and governance | 4 | Six-factor WHY panel (`ExplanationBuilder`); SHA-256 audit chain (`AuditService.java:19`); model cards with NAIC bulletin mapping; four-fifths fairness monitor (`AiGovernanceService.java:183-195`); adjuster feedback stored next to the AI view | "Fairness is monitoring of proxies only; the trust score's calibration error is still high" | Already stated honestly in `docs/model-cards.md`; extend fairness wording to cover the new fraud score |
| i | Usefulness to an insurer | 3 | Governance page reports leakage kept from over-payment, routing, adjuster agreement, acknowledgement on time | "What does this save an insurer? No business case." | Business-case section in `DOMAIN.md` from measured outputs only (Phase 5, needs approval) |
| j | Presentation and clarity of the repo | 2 | `README.md` is 228 lines and starts with a governance statement; no problem statement, picture, results or "real vs simulated" on the first screen; several stale numbers (section 2); `docs/SPRINT_PLAN.md` has broken characters (`???`, `�`); no licence; no CI badge | "I cannot tell in five minutes what it does, what is real, and how well it works" | Phase 2 README top rewrite; `docs/metrics.md`; fix every inconsistency; `ROADMAP.md`; licence (needs the team's choice) |

## 2. Consistency report

Where docs, README, comments and code disagree. The code is taken as correct.

| # | Where | Says | Reality (source) |
|---|---|---|---|
| 1 | `README.md:72` | Flyway V1–V8 | 17 migrations, V1–V17 (`fnol-api/src/main/resources/db/migration`) |
| 2 | `docs/architecture.md:70` | Flyway V1–V7 | V1–V17 |
| 3 | `docs/database.md:5` and its migration table | V1–V13 | V14 (photo perceptual hash), V15 (`ai_feedback`), V16 (regulatory deadlines), V17 (feedback vision source) are not documented |
| 4 | `README.md:60`, `:131`, `:76` | "7 scenarios", "Seven scenario payloads" | 9 scenario files (`demo-scenarios/scenario_1…9`); `run-all.sh:72` runs every `scenario_*.json`; the README table omits scenario 8 (`scenario_8_claim_above_estimate.json`) |
| 5 | README API table | 20 endpoints | Controllers also expose `GET /api/v1/ai/metrics`, `GET /api/v1/ai/feedback` (`AiGovernanceController.java:23,29`) and `GET /api/v1/fnol/{id}/claimcenter` (`FNOLController.java:218`) |
| 6 | `README.md:14`, `:48`; `docs/architecture.md:74` | "Statutory deadlines … are synthetic prototype values" | Deadlines come from a cited, versioned rule set for CA, NY, TX, FL, IL (`docs/legal-compliance.md` §1); three recovery limits are marked "not verified against the statute text". Coverage rules and subrogation weights are still synthetic |
| 7 | `tools/vision-eval/README.md:82-83` | "On 33 photos the calibrated map does not yet beat Gemini's raw confidence … even at 50 photos `calibrate.py` will only write the map if that changes" | The margin map was written at 50 photos (`vision-calibration.json`: signal `probabilityMargin`, 50 images, 30 Oct 2026). The paragraph is stale |
| 8 | `tools/vision-eval/README.md`, gate-ablation table | `v3-subject (32)` | `v3-subject` is complete at 50 answers (same README, "Final result at 50 photos"); `results/gate-ablation.md` was not regenerated |
| 9 | `tools/vision-eval/README.md` (second-opinion paragraph) | "of the 46 answers that pass … On these 80 answers" | `tools/second-opinion/README.md:39` and `results/disagreement.md:3`: 56 answers passing the checks (runs v2-multiphoto + v3-subject) |
| 10 | Trust score status | Commit `88d0277` says "inactive until 50 evaluated photos" | Active: `trust-score.json` has `images: 50`, `TrustScorer` logs "Trust score active"; `docs/model-cards.md` card 3 says "Active since 30 Oct 2026". Consistent now; no change needed beyond `metrics.md` |
| 11 | Intake and gate flag list (`README.md` routing rules) | 8 intake + 9 gate flags | Matches `IntakeReviewService` and `GateConsistencyChecker` constants. No change needed |
| 12 | `docs/guidewire-integration.md` | "The mock ClaimCenter stores each object on a path shaped like the Cloud API claim resources" | Not verified against Guidewire's published Cloud API in this repository; the mock mixes `/cc/claims` and `/cc/rest/claim/v1/claims/{id}/…` styles |
| 13 | `README.md` "Implementation status" | "ClaimCenter … sync status for retry: Done (retry job not built)" | A manual retry endpoint exists (`POST /fnol/{id}/claimcenter-sync`, `FNOLController.java:279`); no scheduled job |
| 14 | `README.md`, Vision evaluation | "operational 3.9 s with Gemini, legal about 1 ms" | One observed claim, no recorded source. Needs a source or the word "example" |
| 15 | `docs/SPRINT_PLAN.md` | Plan for LangChain4j / GPT-4o, broken characters in headings | Already marked historical in its first lines, but the encoding damage makes it look unmaintained |
| 16 | Test counts | No doc states a count today (an earlier README said 249) | 253 (`mvn -B verify`, 31 Oct 2026) |

## 3. Hygiene findings

| Finding | Where | Action |
|---|---|---|
| Committed Python bytecode | `fnol-vision-ml/redaction/__pycache__/redact.cpython-312.pyc` | Remove; ignore `__pycache__/`, `*.pyc` |
| Unrelated editor-extension tooling committed | `.github/modernize/java-upgrade/**` (a VS Code "app modernization" hook recorder) | Remove from the repo (it is not part of the project) |
| No CI | `.github/workflows` does not exist | Add backend, frontend and Python checks |
| JaCoCo declared but never run | root `pom.xml:37` (pluginManagement only) | Bind `prepare-agent` and `report` |
| Build warning | 14 × "Using platform encoding" | Set `project.build.sourceEncoding` / `project.reporting.outputEncoding` to UTF-8 |
| No licence | repository root | Add once the team chooses one |
| Third-party attribution spread out | `demo-scenarios/sample-images/CREDITS.md` (photos), `docs/model-cards.md` (CLIP, YuNet, OpenCV), `tools/vision-eval/README.md` (dataset) | One root `CREDITS.md` that links them |
| `.pyc` / eval data | `tools/vision-eval/__pycache__/` exists locally (ignored), `tools/vision-eval/data` ignored | OK |
| TODO / FIXME | none found in Java, TS or Python | OK |
| Unpinned versions | Frontend uses caret ranges (`"react": "^18.3.1"`) but `package-lock.json` pins them; Python Dockerfile pins every package; Maven pins via Spring Boot BOM | OK |
| Large classes | `FNOLOrchestrator.java` 732 lines, `FNOLController.java` 538 lines including the `ApiExceptionHandler` class | Noted; not refactored here (risk without benefit for this round) |

## 4. Top 10 changes, ranked by points gained per hour

| Rank | Change | Criteria | Effort | Status |
|---|---|---|---|---|
| 1 | Fix every stale number and statement in section 2 | j, d | 1 h | see section 5 |
| 2 | README first screen: problem, solution, Mermaid diagram, real-vs-simulated table, key results, 3-command quick start | j, d, b | 2 h | see section 5 |
| 3 | `docs/metrics.md` as the single source of truth for measured numbers | b, j | 1.5 h | see section 5 |
| 4 | Remove build artefacts and unrelated tooling; fix the encoding warning | e | 0.5 h | see section 5 |
| 5 | CI workflow (Maven verify + JaCoCo artifact, frontend builds, Python compile check) with README badge | e | 1 h | see section 5 |
| 6 | `docs/DOMAIN.md`: FNOL lifecycle and insurance terms mapped to classes | a, i | 1.5 h | see section 5 |
| 7 | Explainable fraud anomaly score with a documented synthetic evaluation and `FRAUD_ANOMALY` routing flag | c | 5 h | see section 5 |
| 8 | Guidewire: honest "modelled on" wording plus client contract tests with configurable base URL and auth | d, f | 2 h | see section 5 |
| 9 | Root `CREDITS.md` and a licence (licence needs the team's decision) | j | 0.5 h | see section 5 |
| 10 | `docs/ROADMAP.md`; mark `SPRINT_PLAN.md` historical and repair its characters | j | 0.5 h | see section 5 |

## 5. After

_Filled in after the work._
