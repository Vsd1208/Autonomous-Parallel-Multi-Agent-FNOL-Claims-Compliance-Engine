# AI-Powered Human-Governed FNOL Claims Intelligence Engine

An end-to-end First Notice of Loss (FNOL) claims intelligence platform for fast, explainable and safe insurance claim handling. It runs operational analysis and legal/compliance analysis in parallel, reconciles both through a safety gate that explains its reasoning, pre-files the claim in (mock) Guidewire ClaimCenter, and gives a human adjuster a queue and a review console with persistent, audited decisions.

## Governance statement

**By default every claim gets a human decision.** With `fnol.stp.require-advisory-review=true` (env `FNOL_STP_REQUIRE_ADVISORY_REVIEW`, the default) a claim that meets every straight-through-processing (STP) rule is pre-filed in ClaimCenter but saved as `PENDING_ADVISORY_REVIEW`; it counts as filed only after an adjuster confirms it. Setting the flag to `false` lets STP-eligible claims be filed as `STRAIGHT_THROUGH_PROCESSING` with no human step — a business decision (see [ADR-009](docs/architecture-decisions.md)).

- AI analyzes, recommends and explains; it never pays.
- Any failed factor, HIGH severity or intake flag routes the claim to `HUMAN_REVIEW`.
- Blocked, uncovered and timed-out intakes are saved as `REJ-…` claims and queued for a human instead of being dropped.
- Every agent step, failure and adjuster decision is recorded in a SHA-256 hash-chained audit trail. Raw PII is never stored or hashed.
- Statutory deadlines, coverage rules and subrogation weights are **synthetic prototype values**; Compliance must confirm real rules before production use.

## End-to-end flow

```text
1. FNOL intake               claim, policy, claimant, evidence URIs, channel
2. Input validation (HRP1)   format guardrail + intake review flags
3. Context enrichment        policy, claim history, frequency risk
4. Dual-branch analysis      Branch A: damage, coverage, reserve | Branch B: PII, deadlines
5. Reconciliation gate       subrogation, guardrails, six-factor WHY panel, ClaimCenter pre-filing, audit
6. Routing                   PENDING_ADVISORY_REVIEW | STRAIGHT_THROUGH_PROCESSING | HUMAN_REVIEW
7. Human review (HRP2/HRP3)  adjuster queue, decision, ClaimCenter update, audit
```

See [docs/architecture.md](docs/architecture.md) for the full diagram, and [docs/diagram-traceability.md](docs/diagram-traceability.md) for how each of the 23 diagram components maps to code, endpoints and tests.

## Implementation status

| Area | Status |
|---|---|
| FNOL intake API with channel, optional claimant phone/email | Done |
| Input guardrail + intake review flags (Human Review Point 1) | Done |
| Claim-history enrichment (frequency risk) | Done |
| Parallel branches on a bounded executor, one deadline, interrupting cancel | Done |
| Vision: Gemini provider with SSRF-safe evidence loading; deterministic mock provider | Done |
| Coverage by line of business (AUTO, HOME) | Done (synthetic rules) |
| Reserve (damage × 1.15, BigDecimal) | Done |
| PII redaction (SSN, DOB, MRN, name, phone, email) + PII guardrail | Done |
| Statutory deadlines: acknowledge-by, decide-by, urgency flag | Done (synthetic rules) |
| Subrogation scored at the gate with Branch A's real severity | Done |
| Six-factor WHY panel, configurable STP thresholds (`fnol.stp.*`) | Done |
| Advisory review before STP counts as filed (Human Review Point 2) | Done, on by default |
| Adjuster review with state checks, token, audit event (Human Review Point 3) | Done |
| Failed intakes persisted and escalated (`REJ-…`) | Done |
| Hash-chained audit trail + `chainValid` + PDF report | Done |
| ClaimCenter create, update, attachments; sync status for retry | Done (retry job not built) |
| Adjuster queue (API + console page) | Done |
| Console wired to live data (no sample data) | Done |
| Reproducible demo (`.env.demo`, `run-all.sh`, 7 scenarios) | Done |
| Authentication | Shared adjuster token only (prototype) |

## Repository structure

```text
fnol-common/          Shared records (Models.java) and exceptions
fnol-mock-guidewire/  Mock PolicyCenter + ClaimCenter on port 9090
fnol-enrichment/      PolicyCenter/ClaimCenter clients, ClaimHistoryEnricher
fnol-agents/          Agents, vision providers, EvidenceImageLoader, rule providers
fnol-guardrails/      Input, output, PII and coverage guardrails
fnol-orchestrator/    FNOLOrchestrator, IntakeReviewService, ExplanationBuilder, STP settings
fnol-api/             REST API, security filters, audit chain, persistence, Flyway V1–V6, PDF
frontend/             React + Vite console: intake, claim dashboard, queue, audit trail
demo-scenarios/       Seven scenario payloads, run-all.sh, duplicate script
postman/              Request collection
docs/                 Architecture, ADRs, agents, API, database, Guidewire, traceability
```

## Routing rules

Severity from estimated damage: **LOW** ≤ $5,000, **MEDIUM** ≤ $20,000, **HIGH** above.

| Factor | Passes when |
|---|---|
| AI Confidence | Vision confidence ≥ 0.85 |
| Coverage Status | Policy active and in term, with a coverage the line-of-business rule accepts |
| Compliance (PII) | No PII detected, or PII redacted with confidence ≥ 0.90 |
| Reserve Amount | Recommended reserve ≤ $25,000 |
| Claim History | Claim frequency risk LOW or MEDIUM |
| Subrogation Risk | Score < 70 |

A claim is STP-eligible when severity is LOW or MEDIUM, all six factors pass and no intake flag is raised (`MISSING_EVIDENCE`, `CLAIMANT_NAME_MISMATCH`, `INCIDENT_OUTSIDE_POLICY_PERIOD`, `INCIDENT_NEAR_POLICY_START`, `HIGH_CLAIM_FREQUENCY`). HIGH severity always goes to a human. All thresholds are properties under `fnol.stp`.

## API

Full contracts: [docs/api-contracts.md](docs/api-contracts.md). 🔒 = requires header `X-Adjuster-Token`.

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/v1/health` | Health check |
| POST | `/api/v1/fnol/intake` | Submit an FNOL and receive the full analysis |
| GET | `/api/v1/fnol/status/{claimId}` | Saved claim row (status, decision, flags, failure reason, sync status) |
| GET 🔒 | `/api/v1/fnol/{claimId}/explanation` | Saved WHY panel |
| GET 🔒 | `/api/v1/fnol/{claimId}/audit` | Audit events in chain order + `chainValid` |
| GET 🔒 | `/api/v1/fnol/{claimId}/audit/pdf` | PDF audit report |
| POST 🔒 | `/api/v1/fnol/{claimId}/review` | Record an adjuster decision |
| GET 🔒 | `/api/v1/fnol/queue?status=HUMAN_REVIEW&page=0&size=20` | Adjuster queue, oldest first |

Errors: 400 `VALIDATION_ERROR`, 401 `UNAUTHORIZED`, 409 `DUPLICATE_FNOL` / `CLAIM_ID_CONFLICT` / `INVALID_STATE`, 422 `COMPLIANCE_BLOCK`, 502 `EXTERNAL_OR_PROCESSING_ERROR`, 504 `PROCESSING_TIMEOUT`. Failed intakes return the saved `REJ-…` ID in `claimId`.

## Running locally

Prerequisites: Java 17+, Maven 3.9+, Node 18+, Docker.

### Demo stack (no API key needed)

```bash
docker compose --env-file .env.demo up -d --build
bash demo-scenarios/run-all.sh        # posts all 7 scenarios, exits non-zero on any mismatch
```

If port 5432 is already in use on your machine, add `POSTGRES_HOST_PORT=5433` in front of the compose command.

| Service | URL |
|---|---|
| Console | http://localhost:5173 (queue at `/queue`) |
| API health | http://localhost:8080/api/v1/health |
| Mock PolicyCenter | http://localhost:9090/pc/policies/POL-AUTO-112233 |

`.env.demo` sets `GEMINI_VISION_ENABLED=false` (deterministic `MockVisionProvider`) and the demo adjuster token `demo-adjuster-token`, which the console build also uses. Stop with `docker compose down` (add `-v` to wipe the database).

### With real Gemini vision

Copy `.env.example` to `.env`, set `GEMINI_API_KEY`, `FNOL_ADJUSTER_TOKEN` and, if photos come from URLs, `FNOL_EVIDENCE_ALLOWED_HOSTS`, then `docker compose up -d --build`. Without a key the provider falls back to a 0.60-confidence assessment, so every claim goes to a human.

### Tests and builds

```bash
mvn -q test                                   # backend: 173 tests
cd frontend && npm install && npm run build    # console
```

## Demo scenarios

Run with mock vision (`.env.demo`). `run-all.sh` substitutes a fresh incident date per run.

| # | File | Expected |
|---|---|---|
| 1 | `scenario_1_clean_stp.json` | 200, `PENDING_ADVISORY_REVIEW`, decision `STRAIGHT_THROUGH` (subrogation 30) |
| 2 | `scenario_2_low_confidence.json` | 200, `HUMAN_REVIEW` (blurry photo, confidence 0.61) |
| 3 | `scenario_3_compliance_block.json` | 422 `COMPLIANCE_BLOCK` (MRN); saved as `REJ-…` `BLOCKED_COMPLIANCE` |
| 4 | `scenario_4_api_timeout.json` | 504 `PROCESSING_TIMEOUT` (slow photo exceeds the 10 s deadline); saved as `REJ-…` `HUMAN_REVIEW` |
| 5 | `scenario_5_duplicate.json` / `.sh` | 200, then 409 `DUPLICATE_FNOL` |
| 6 | `scenario_6_high_claim_frequency.json` | 200, `HUMAN_REVIEW`, flag `HIGH_CLAIM_FREQUENCY` |
| 7 | `scenario_7_home_policy.json` | 200, `PENDING_ADVISORY_REVIEW` (home policy covered by DWELLING) |

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8080` | API port |
| `MOCK_GUIDEWIRE_BASE_URL` | `http://localhost:9090` | Mock Guidewire address |
| `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` | local `fnol` database | PostgreSQL |
| `FNOL_ORCHESTRATOR_TIMEOUT_SECONDS` | `10` | Deadline for both branches |
| `FNOL_ORCHESTRATOR_POOL_SIZE` / `_QUEUE_SIZE` | `8` / `32` | Branch executor bounds (2 tasks per intake) |
| `FNOL_STP_REQUIRE_ADVISORY_REVIEW` | `true` | STP-eligible claims wait for an adjuster |
| `FNOL_ADJUSTER_TOKEN` | empty (all adjuster requests rejected) | Shared secret for adjuster endpoints; also baked into the console build |
| `FNOL_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Allowed browser origins |
| `FNOL_EVIDENCE_ALLOWED_HOSTS` | empty | https hosts evidence photos may be downloaded from |
| `GEMINI_API_KEY` | empty | Gemini key (sent as `x-goog-api-key` header) |
| `GEMINI_VISION_ENABLED` | `true` | `false` = deterministic mock vision |
| `GEMINI_VISION_MODEL` | `gemini-2.5-flash` | Model name |
| `GEMINI_VISION_READ_TIMEOUT_SECONDS` | `8` | Gemini read timeout (below the orchestrator deadline) |
| `MOCK_GW_TIMEOUT` | `false` | Mock ClaimCenter create waits 11 s |
| `MOCK_PC_TIMEOUT` | `false` | Mock PolicyCenter coverages wait 12 s |
| `MOCK_CC_START_SEQUENCE` | `1` | First mock ClaimCenter claim number (raise after a mock restart) |
| `POSTGRES_HOST_PORT` | `5432` | Host port for the compose Postgres |
| `VITE_API_BASE_URL` | `http://localhost:8080` | API address for the console |

## Known limits

- Guidewire is mocked; `PolicyCenterClient` and `ClaimCenterClient` are the swap point for real clients ([docs/guidewire-integration.md](docs/guidewire-integration.md)).
- The adjuster token is a shared secret embedded in the console bundle — a prototype control, not authentication. `adjusterId` is taken from the request body.
- Claims with `cc_sync_status=FAILED` are marked for retry, but no retry job exists yet.
- Photos are referenced by URI or inline data URI; there is no file upload.
- The idempotency guard's in-memory layer is per JVM; run one API instance or move it to the database.
