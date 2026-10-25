# Architecture Decision Records (ADRs)

---

## ADR-001: Multi-Module Maven Architecture
- **Status**: Accepted
- **Context**: The platform consists of independent concerns: contracts, mock Guidewire systems, enrichment adapters, AI agents, guardrails, orchestration, API entry point, and UI.
- **Decision**: Use a Maven multi-module structure with a single parent POM managing versions (Java 17, Spring Boot 3.2.x, Jackson, JUnit 5) and dependency management.
- **Consequences**: Strict separation of concerns; prevents leakage between mock services and platform code; ensures clean build and deployment boundaries.

---

## ADR-002: Guidewire Integration Abstraction via Client Adapters
- **Status**: Accepted
- **Context**: Access to a live Guidewire Cloud Platform tenant is not assumed. The architecture must remain honest about simulation while providing a friction-free path to production integration.
- **Decision**: Define pure Java interfaces (`PolicyCenterClient`, `ClaimCenterClient`). The orchestrator and agents interact *only* with these interfaces. `MockPolicyCenterClient` and `MockClaimCenterClient` communicate over HTTP to the `fnol-mock-guidewire` service.
- **Consequences**: Swapping to live Guidewire Cloud APIs requires only implementing `GuidewirePolicyCenterClient` and `GuidewireClaimCenterClient` without modifying agents, orchestrators, controllers, or frontend code.

---

## ADR-003: Contract-First Mock Guidewire Services
- **Status**: Accepted
- **Context**: The simulation must behave as an authentic external dependency, exercising network serialization, HTTP error handling, and timeout behavior.
- **Decision**: Implement `fnol-mock-guidewire` as an isolated Spring Boot HTTP server exposing realistic endpoints (`/pc/policies/...`, `/cc/claims`).
- **Consequences**: Real HTTP client-server boundaries are exercised; Postman tests and integration suites validate authentic wire contracts.

---

## ADR-004: Sequential Agent Orchestration with Branch DTOs
- **Status**: Superseded by ADR-007 (the branches now run in parallel; the Branch DTOs are kept)
- **Context**: Premature concurrency can obscure debugging, introduce race conditions, and complicate audit logging.
- **Decision**: Execute Branch A (Operational) and Branch B (Compliance) sequentially in Sprint 2 while returning structured `BranchAResult` and `BranchBResult` DTOs.
- **Consequences**: Deterministic, observable, easily testable execution flow. Provides an effortless upgrade path to `Mono.zip()` / `CompletableFuture` parallel fan-out in Sprint 3 without breaking DTO contracts.

---

## ADR-005: Deterministic AI Providers for Testability
- **Status**: Accepted
- **Context**: Non-deterministic LLM calls during automated unit tests lead to flaky builds and unpredictable demo runs.
- **Decision**: Implement deterministic mock providers (`MockVisionProvider`, `SyntheticDeadlineRuleProvider`, `DeterministicSubrogationScorer`) behind clean provider interfaces.
- **Consequences**: Tests and the demo (`.env.demo`, `GEMINI_VISION_ENABLED=false`) are fully repeatable. The production vision provider is `GeminiVisionProvider` (Google Gemini, `gemini.vision.enabled=true`); `MockVisionProvider` reads keywords in `mock://` evidence URIs (`normal`, `blurry`, `severe`, `water`, `slow`).

---

## ADR-006: Auditability & Explainability by Design
- **Status**: Accepted
- **Context**: Insurance regulatory standards (NAIC Model Audit Rule, GDPR, CCPA) require explainable decisions, PII protection, and immutable records.
- **Decision**: Generate SHA-256 hashes of all agent inputs and outputs, record every event in the `audit_trail` table, enforce output explainability strings, and sanitize PII before persistence.
- **Consequences**: Decisions are transparent, verifiable, and explainable to adjusters and regulatory reviewers. ADR-010 adds the hash chain and the rule that only a sanitized intake is ever hashed.

---

## ADR-007: Parallel Branch Fan-Out on a Bounded Executor
- **Status**: Accepted (supersedes ADR-004)
- **Context**: The first parallel version used `CompletableFuture.supplyAsync` on a fixed pool of 2 threads shared by every request. `CompletableFuture.cancel(true)` does not interrupt the running task, so one hung vendor call could hold a thread forever and two slow requests could stall the whole API.
- **Decision**: `FNOLOrchestrator` submits Branch A and Branch B with `executor.submit` to a `ThreadPoolExecutor` sized by `fnol.orchestrator.pool-size` (default 8) with a bounded queue `fnol.orchestrator.queue-size` (default 32) and `AbortPolicy`. Both futures are awaited against one overall deadline, `fnol.orchestrator.timeout-seconds` (default 10). On timeout both `FutureTask`s are cancelled with interruption. A full queue raises `ProcessingTimeoutException` (HTTP 504) instead of queueing without limit. Per-call timeouts sit below the overall deadline: Guidewire 2 s connect / 3 s read, Gemini 3 s connect / `gemini.vision.read-timeout-seconds` (default 8) read, on its own qualified `RestTemplate`.
- **Consequences**: A stuck branch frees its thread; overload degrades into fast 504s that are persisted for human attention (ADR-008). Subrogation, which needs Branch A's damage severity, is scored at the reconciliation gate instead of in Branch B.

---

## ADR-008: Failed Intakes Are Persisted and Escalated
- **Status**: Accepted
- **Context**: A compliance block (422), an uncovered policy (502) or a timeout (504) used to return before anything was saved or audited, while the error message claimed the claim had been escalated.
- **Decision**: `FNOLController` hands these failures to `FailedIntakeRecorder`, which saves a claim with ID `REJ-` + 8 hex characters, status `BLOCKED_COMPLIANCE` (compliance) or `HUMAN_REVIEW` (coverage, timeout), `failure_reason` = the error message, `cc_sync_status = NOT_FILED`, and one `Intake` audit event whose input hash covers only the sanitized intake. The HTTP status and error code are unchanged; the error body carries the `claimId`. The duplicate check ignores these REJ rows so a corrected resubmission is accepted. If the database save fails *after* ClaimCenter created a claim, the idempotency key stays held so a retry cannot file a second ClaimCenter claim; a ClaimCenter ID that already exists in the database is refused with 409 `CLAIM_ID_CONFLICT` instead of overwriting.
- **Consequences**: No claim disappears; every failure shows up in the adjuster queue with an audit trail.

---

## ADR-009: Advisory Review Before STP Counts as Filed
- **Status**: Accepted
- **Context**: The console states that AI provides decision support only, yet straight-through claims were filed with no human step.
- **Decision**: Property `fnol.stp.require-advisory-review` (env `FNOL_STP_REQUIRE_ADVISORY_REVIEW`, default `true`). When true, a claim that meets every STP rule is still pre-filed in ClaimCenter but saved as `PENDING_ADVISORY_REVIEW` and appears in the adjuster queue; it counts as filed only after an adjuster records a decision. When false it is `STRAIGHT_THROUGH_PROCESSING` with no human step. Reviews are allowed only from `HUMAN_REVIEW`, `PENDING_ADVISORY_REVIEW` or `NEEDS_MORE_INFO` (otherwise 409 `INVALID_STATE`), are applied with a single conditional `UPDATE` so two concurrent reviews cannot both record a final decision, write an `AdjusterReview` audit event and are pushed to ClaimCenter best-effort.
- **Consequences**: With the default, every claim has a human decision; the business can switch STP on per environment.

---

## ADR-010: Hash-Chained Audit Trail and Adjuster Token
- **Status**: Accepted
- **Context**: Per-event hashes alone cannot detect a deleted or edited row, and hashing `String.valueOf(payload)` put the raw SSN into the hash input.
- **Decision**: Each event stores `chain_hash = SHA-256(previous chain_hash + input_hash + output_hash)` and a per-claim `seq`; `GET /fnol/{claimId}/audit` recomputes the chain and returns `chainValid`. Hash inputs are a `SanitizedIntake` (policy number, state, incident date, redacted description) — never the raw payload. Adjuster endpoints (review, audit, audit PDF, explanation, queue) require header `X-Adjuster-Token` equal to `fnol.security.adjuster-token`; with no token configured they reject every request. CORS is limited to `fnol.cors.allowed-origins`.
- **Consequences**: Tampering with any stored hash, or removing or reordering events, is detected. The chain cannot detect a rewrite of the newest event together with its own chain hash, so the PDF prints the chain head for out-of-band anchoring. The shared token is a prototype control — it is embedded in the console bundle — and production needs per-adjuster authentication (e.g. OIDC) so `adjusterId` comes from the identity rather than the request body.

---

## ADR-011: Alignment with the Architecture Diagram
- **Status**: Accepted
- **Context**: A line-by-line check against the architecture diagram found promises the code did not keep: no reserve confidence score, subrogation outside Branch B and without compliance notes, no consistency validation at the gate, unexpected errors and duplicates not escalated to a human, no way for the adjuster to modify findings or require investigation, and ClaimCenter claims created as `OPEN` before any human review.
- **Decision**:
  - `ReserveResult.confidence` = vision confidence × photo-quality weight (CLEAR 1.00, PARTIAL 0.85, BLURRY 0.70, UNUSABLE 0.50, unknown 0.80).
  - Subrogation runs in Branch B as a liability assessment with compliance notes (`SubrogationScorer.assessLiability`); the gate adds Branch A's severity (`applySeverity`). Branch B stays independent of Branch A.
  - `GateConsistencyChecker` flags `SEVERITY_ESTIMATE_MISMATCH`, `RESERVE_EXCEEDS_COVERAGE_LIMIT`, `DAMAGE_TYPE_LINE_MISMATCH`, `CONFIDENCE_PHOTO_QUALITY_MISMATCH` and `CLAIMED_EXCEEDS_AI_ESTIMATE`. Gate flags block STP like intake flags.
  - ClaimCenter claims are pre-populated as `DRAFT_PENDING_REVIEW` and move to `OPEN` (approved), `DENIED`, `PENDING_INFORMATION`, `UNDER_INVESTIGATION` or `CLOSED` when an adjuster decides; every decision is tracked as a review action. Only STP with advisory review switched off is created `OPEN`.
  - Adjusters can set `adjustedReserve` (with a required `overrideReason`) and choose `INVESTIGATION_REQUIRED`.
  - Every processing failure except invalid input (400) is saved as a `REJ-` claim for human review.
  - A duplicate submission is refused (409) and flags and audits the original claim (`DUPLICATE_SUBMISSION`); an original filed straight through goes back to `HUMAN_REVIEW`.
- **Deliberate differences from the diagram**: the branches run as `FutureTask`s on a bounded executor rather than `CompletableFuture`, because `CompletableFuture.cancel` cannot interrupt a stuck branch (ADR-007). The adjuster console is React, standing in for Guidewire Jutro, which is proprietary. Human Review Point 1 is automated flagging that routes the claim to an adjuster rather than a separate manual step before processing.
- **Consequences**: Each of the 23 diagram components maps to code and tests (`docs/diagram-traceability.md`).
