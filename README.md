# AI-Powered Human-Governed FNOL Claims Intelligence Engine

An end-to-end First Notice of Loss (FNOL) claims intelligence platform for fast, explainable, and safe insurance claim handling. The system runs operational claim analysis and legal/compliance analysis in parallel, reconciles both branches through a safety gate, and gives a human adjuster a final review console with persistent decision states.

## End-to-End Flow

```text
1. FNOL Intake
   Claim report, policy data, claimant information, incident details, and evidence URIs.

2. Context Enrichment
   Policy details, coverage, prior claim history, and claim frequency risk.

3. Asynchronous Dual-Branch AI Analysis
   Branch A: damage, coverage, reserve.
   Branch B: PII redaction, statutory deadlines, subrogation.

4. Reconciliation and Safety Checks
   Guardrails, WHY factors, pass/fail scorecard, STP vs human-review routing.

5. Human Review and Decision Support
   Adjuster console, final decision buttons, decision notes, persisted review state.
```

## Current Implementation Status

| Area | Implemented |
|---|---|
| FNOL intake API | Yes |
| Input validation guardrails | Yes |
| Context enrichment from mock PolicyCenter | Yes |
| Async dual-branch processing with `CompletableFuture` | Yes |
| Operational branch: vision damage, coverage, reserve | Yes |
| Compliance branch: PII redaction, deadlines, subrogation | Yes |
| Reconciliation safety gate | Yes |
| Structured WHY panel | Yes |
| STP vs human-review routing | Yes |
| Adjuster review console | Yes |
| Persistent final adjuster decisions | Yes |
| Audit trail hashing | Yes |
| Demo scenarios | Yes |
| Docker Compose stack | Present, requires Docker Desktop running |

## Problem Statement

Insurance FNOL handling is often slow because damage assessment, coverage validation, compliance checks, and adjuster review happen in separate sequential steps. That creates delays, unclear decision rationale, and governance risk when AI is used without proper controls.

This project addresses that by using a dual-branch claims intelligence engine:

- The operational branch estimates damage and reserve exposure.
- The compliance branch protects sensitive data and regulatory obligations.
- A reconciliation safety gate combines the outputs.
- A human adjuster gets final authority through a review console.

The result is faster claim triage without giving AI autonomous payout authority.

## Architecture Overview

```text
Frontend React Console
  |
  | POST /api/v1/fnol/intake
  v
FNOL API
  |
  | validates request and enforces idempotency
  v
FNOL Orchestrator
  |
  | enriches policy and claim history context
  v
CompletableFuture fan-out
  |
  |-- Branch A: Operational Intelligence
  |     - VisionDamageAgent
  |     - PolicyValidatorAgent
  |     - ReserveCalculatorAgent
  |
  |-- Branch B: Legal and Compliance Intelligence
        - PiiRedactionAgent
        - StatutoryDeadlineAgent
        - SubrogationScorerAgent

Both branches join
  |
  v
Reconciliation Safety Gate
  |
  |-- Output guardrails
  |-- PII guardrails
  |-- Coverage validation
  |-- WHY factor generation
  |-- STP or human-review routing
  v
Claim persistence + audit trail
  |
  v
Adjuster Intelligence Console
  |
  | POST /api/v1/fnol/{claimId}/review
  v
Persisted final decision
```

## Repository Structure

```text
.
|-- pom.xml
|-- docker-compose.yml
|-- README.md
|
|-- fnol-common/
|   Shared Java records, request/response models, and custom exceptions.
|
|-- fnol-mock-guidewire/
|   Mock PolicyCenter and ClaimCenter services for local/demo use.
|
|-- fnol-enrichment/
|   Guidewire adapter layer for policy, coverage, claim history, and claim filing.
|
|-- fnol-agents/
|   AI and deterministic agents for damage, reserve, PII, deadlines, subrogation, and audit.
|
|-- fnol-guardrails/
|   Input, output, PII, and coverage validation guardrails.
|
|-- fnol-orchestrator/
|   The dual-branch `CompletableFuture` workflow and reconciliation safety gate.
|
|-- fnol-api/
|   Spring Boot REST API, persistence entities, repositories, Flyway migrations, API tests.
|
|-- frontend/
|   React + Vite adjuster console.
|
|-- demo-scenarios/
|   Ready-to-run request payloads and duplicate-submission script.
|
`-- docs/
    Supporting architecture, API, database, and Guidewire integration notes.
```

## Backend Modules

### `fnol-common`

Contains shared domain models:

- `FNOLPayload`
- `FNOLResponse`
- `BranchAResult`
- `BranchBResult`
- `DamageAssessment`
- `PolicyValidationResult`
- `ReserveResult`
- `PIIResult`
- `DeadlineResult`
- `SubrogationResult`
- `ExplanationObject`
- `AdjusterReviewRequest`
- `AdjusterReviewResponse`

These records define the contract between the orchestrator, API, tests, and frontend.

### `fnol-mock-guidewire`

Provides local mock services that stand in for Guidewire systems:

- PolicyCenter-like policy lookup.
- Policy coverages.
- Prior claim history.
- ClaimCenter-like claim creation and retrieval.

This lets the project run locally without real Guidewire credentials.

### `fnol-enrichment`

Contains adapter interfaces and mock implementations:

- `PolicyCenterClient`
- `ClaimCenterClient`
- `ClaimHistoryEnricher`

The orchestrator depends on these abstractions, so real Guidewire integrations can replace the mock clients later.

### `fnol-agents`

Implements the claim intelligence agents:

| Agent | Purpose |
|---|---|
| `VisionDamageAgent` | Converts evidence URIs into structured damage assessment. |
| `PolicyValidatorAgent` | Checks active policy and relevant collision coverage. |
| `ReserveCalculatorAgent` | Calculates reserve using deterministic monetary logic. |
| `PiiRedactionAgent` | Masks SSN, DOB, claimant names, and MRN-like values. |
| `StatutoryDeadlineAgent` | Calculates state-specific statutory deadlines. |
| `SubrogationScorerAgent` | Scores third-party recovery potential. |
| `AuditTrailAgent` | Creates SHA-256 input/output hash records per agent step. |

The project supports a deterministic mock vision provider for local testing and a Gemini provider path for AI-backed image analysis.

### `fnol-guardrails`

Enforces safety before persistence or final recommendation:

- `InputGuardrail`: policy format, valid state, non-future incident date, non-blank description.
- `OutputGuardrail`: reserve must not be negative; subrogation score must be between 0 and 100.
- `PIIGuardrail`: redaction confidence threshold, no raw SSN, MRN compliance block.
- `ValidationGuardrail`: claim cannot proceed if coverage is not valid.

### `fnol-orchestrator`

The core engine lives in `FNOLOrchestrator`.

The orchestrator:

1. Validates intake payload.
2. Enriches policy history context.
3. Starts two async branches using `CompletableFuture`.
4. Waits for both branches with a configurable timeout.
5. Applies safety checks.
6. Builds a structured explanation object.
7. Files/prepares the claim through the ClaimCenter adapter.
8. Creates audit records.
9. Routes the claim as STP or human review.

The reconciliation decision comes from `ExplanationBuilder`.

Possible routing outputs:

- `STRAIGHT_THROUGH_PROCESSING`
- `HUMAN_REVIEW`

Possible recommended actions:

- `APPROVE_AND_STP`
- `ESCALATE_TO_HUMAN`
- `ESCALATE_TO_HUMAN_HIGH_RISK_SEVERITY`

### `fnol-api`

Spring Boot REST API exposing:

- Health check.
- FNOL intake.
- Claim status lookup.
- Adjuster review persistence.
- Error handling for validation, duplicate requests, compliance blocks, timeouts, and downstream failures.

It also owns persistence:

- `ClaimEntity`
- `AuditEntity`
- `ClaimRepository`
- `AuditRepository`
- Flyway schema migrations.

## Frontend

The frontend is a React + Vite adjuster intelligence console.

It provides:

- FNOL intake form.
- Quick-load demo scenarios.
- Five-stage end-to-end flow strip.
- Claim dashboard.
- Branch A operational results.
- Branch B compliance results.
- WHY panel with pass/fail factors.
- Advisory banner.
- Audit trail page.
- PDF export for audit trail.
- Full adjuster final decision console.

## Human Review and Final Decision Lifecycle

The project includes a full adjuster review console.

When a claim routes to `HUMAN_REVIEW`, the adjuster can:

- Review operational findings.
- Review compliance findings.
- Review WHY factor pass/fail results.
- Enter adjuster ID.
- Enter final decision notes.
- Persist a final decision.

Supported final decision states:

| State | Meaning |
|---|---|
| `APPROVED_BY_ADJUSTER` | Adjuster reviewed and approved the recommendation. |
| `REJECTED_BY_ADJUSTER` | Adjuster rejected the recommendation. |
| `NEEDS_MORE_INFO` | Adjuster requires more documents, evidence, or investigation. |
| `CLOSED` | Claim review is closed. |

The decision is persisted on the `claims` table with:

- `adjuster_decision`
- `adjuster_id`
- `adjuster_notes`
- `reviewed_at`

Endpoint:

```http
POST /api/v1/fnol/{claimId}/review
Content-Type: application/json

{
  "decision": "APPROVED_BY_ADJUSTER",
  "adjusterId": "ADJ-001",
  "notes": "Reviewed WHY factors and approved final reserve."
}
```

Example response:

```json
{
  "claimId": "CLM-2026-000001",
  "status": "APPROVED_BY_ADJUSTER",
  "adjusterDecision": "APPROVED_BY_ADJUSTER",
  "adjusterId": "ADJ-001",
  "adjusterNotes": "Reviewed WHY factors and approved final reserve.",
  "reviewedAt": "2026-10-13T14:40:34.612Z"
}
```

## WHY Panel and Safety Gate

The safety gate produces six explanation factors:

| Factor | Pass Requirement |
|---|---|
| AI Confidence | Confidence must be at least 0.85. |
| Coverage Status | Active matching coverage must exist. |
| Compliance (PII) | PII must be absent or properly redacted. |
| Reserve Amount | Reserve must be at or below the STP threshold. |
| Claim History | Claim frequency risk must not be high. |
| Subrogation Risk | Subrogation score must be below escalation threshold. |

For low-severity claims where all factors pass, the system can return:

```text
status: STRAIGHT_THROUGH_PROCESSING
decision: STRAIGHT_THROUGH
recommendedAction: APPROVE_AND_STP
```

If any factor fails, or if severity is medium/high, the system returns human review:

```text
status: HUMAN_REVIEW
decision: ADVISORY_RECOMMENDED_ACTION
recommendedAction: ESCALATE_TO_HUMAN
```

For medium/high severity:

```text
recommendedAction: ESCALATE_TO_HUMAN_HIGH_RISK_SEVERITY
```

## API Reference

### Health

```http
GET /api/v1/health
```

Response:

```json
{
  "status": "UP"
}
```

### Submit FNOL

```http
POST /api/v1/fnol/intake
Content-Type: application/json
```

Request:

```json
{
  "policyNumber": "POL-AUTO-112233",
  "incidentDate": "2026-09-15",
  "state": "CA",
  "description": "Rear-ended at traffic light. Other driver admitted fault. Police report filed.",
  "claimantName": "Avery Johnson",
  "claimantDob": "01/02/1980",
  "claimantSsn": "123-45-6789",
  "evidenceUris": ["mock://photo/front-bumper.jpg"]
}
```

Important response fields:

```json
{
  "claimId": "CLM-2026-000001",
  "status": "HUMAN_REVIEW",
  "branchA": {
    "damageAssessment": {},
    "coverage": {},
    "reserve": {}
  },
  "branchB": {
    "pii": {},
    "deadline": {},
    "subrogation": {},
    "audit": []
  },
  "policyContext": {},
  "explanation": {
    "decision": "ADVISORY_RECOMMENDED_ACTION",
    "summary": "One or more criteria failed; escalate to human claim handler for review",
    "factors": [],
    "recommendedAction": "ESCALATE_TO_HUMAN",
    "severityGate": "LOW"
  },
  "severityGate": "LOW",
  "eligibleForAdvisoryPrefill": false,
  "notes": []
}
```

### Get Claim Status

```http
GET /api/v1/fnol/status/{claimId}
```

Returns the persisted claim row, including final adjuster decision fields once review is completed.

### Submit Adjuster Review

```http
POST /api/v1/fnol/{claimId}/review
Content-Type: application/json
```

Allowed decisions:

- `APPROVED_BY_ADJUSTER`
- `REJECTED_BY_ADJUSTER`
- `NEEDS_MORE_INFO`
- `CLOSED`

## Error Handling

| HTTP Status | Error | Trigger |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Invalid payload, unsupported review decision, blank required fields. |
| 409 | `DUPLICATE_FNOL` | Same policy/date/state submitted twice. |
| 422 | `COMPLIANCE_BLOCK` | PII/compliance guardrail blocks processing. |
| 504 | `PROCESSING_TIMEOUT` | Branch processing exceeds timeout. |
| 502 | `EXTERNAL_OR_PROCESSING_ERROR` | Unexpected downstream or processing failure. |

## Database Schema

The database is managed by Flyway migrations in:

```text
fnol-api/src/main/resources/db/migration/
```

### `claims`

Stores claim summary, routing status, and adjuster decision fields.

Key columns:

- `claim_id`
- `policy_number`
- `status`
- `incident_date`
- `state`
- `estimated_damage`
- `reserve`
- `adjuster_decision`
- `adjuster_id`
- `adjuster_notes`
- `reviewed_at`
- `created_at`
- `updated_at`

### `audit_trail`

Stores immutable per-agent audit events:

- `event_id`
- `claim_id`
- `agent_name`
- `timestamp`
- `input_hash`
- `output_hash`
- `status`

## Demo Scenarios

The `demo-scenarios/` directory includes payloads for common paths:

| Scenario | Purpose |
|---|---|
| `scenario_1_clean_stp.json` | Clean low-risk claim path. |
| `scenario_2_low_confidence.json` | Low AI confidence path. |
| `scenario_3_compliance_block.json` | Compliance-blocking input. |
| `scenario_4_api_timeout.json` | Timeout/manual escalation path. |
| `scenario_5_duplicate.sh` | Duplicate FNOL/idempotency path. |

## Running Locally

### Prerequisites

- Java 17 or newer.
- Maven 3.9 or newer.
- Node.js 18 or newer.
- Docker Desktop if running the full containerized stack.

### Run Tests

Backend:

```powershell
mvn.cmd -q test
```

Frontend:

```powershell
cd frontend
npm.cmd install
npm.cmd run build
```

### Run Frontend Only

```powershell
cd frontend
npm.cmd run dev -- --port 5173
```

Open:

```text
http://localhost:5173
```

The frontend expects the API at:

```text
http://localhost:8080
```

### Run Full Stack With Docker Compose

Start Docker Desktop first. Then:

```powershell
docker compose up -d --build
```

Services:

| Service | URL |
|---|---|
| Frontend | `http://localhost:5173` |
| API health | `http://localhost:8080/api/v1/health` |
| Mock PolicyCenter | `http://localhost:9090/pc/policies/POL-AUTO-112233` |
| PostgreSQL | `localhost:5432` |

Stop the stack:

```powershell
docker compose down
```

## Configuration

| Environment Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | API server port. |
| `MOCK_GUIDEWIRE_BASE_URL` | `http://localhost:9090` | Mock Guidewire base URL. |
| `FNOL_ORCHESTRATOR_TIMEOUT_SECONDS` | `10` | Max wait for async branch completion. |
| `MOCK_GW_TIMEOUT` | `false` | Simulates Guidewire timeout behavior. |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/fnol` | PostgreSQL JDBC URL. |
| `SPRING_DATASOURCE_USERNAME` | `fnol` | DB username. |
| `SPRING_DATASOURCE_PASSWORD` | `fnol` | DB password. |
| `VITE_API_BASE_URL` | `http://localhost:8080` | Frontend API base URL. |

## Verification Performed

The implementation has been verified with:

```powershell
npm.cmd run build
mvn.cmd -q test
```

Both commands passed after adding the adjuster review console and persisted final decision workflow.

## Runtime Note

The full Docker stack requires Docker Desktop to be running. If Docker is stopped, `docker compose up -d --build` will fail because the Docker engine pipe is unavailable.

PostgreSQL does not need to be installed separately for the Docker workflow. It is already defined as a service in `docker-compose.yml`.

## Governance Model

This project is intentionally human-governed:

- AI can analyze.
- AI can recommend.
- AI can explain.
- AI can route low-risk claims.
- A human adjuster retains final authority for review decisions.

The adjuster console is the final governance checkpoint for claims that require review.
