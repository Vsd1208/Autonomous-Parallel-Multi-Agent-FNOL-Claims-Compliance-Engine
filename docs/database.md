# Database Schema & Persistence Architecture

## Database Engine
- **Target**: PostgreSQL 16 (docker-compose image `postgres:16-alpine`)
- **Migrations**: Flyway, `fnol-api/src/main/resources/db/migration`, V1–V7; Hibernate runs with `ddl-auto: validate`
- **ORM**: Spring Data JPA / Hibernate 6
- **Tests**: H2 in PostgreSQL mode with `ddl-auto: create-drop` (Flyway disabled); the migrations are exercised by `docker compose up`

## Migrations

| Version | File | Change |
|---|---|---|
| V1 | `V1__init_claims_schema.sql` | `claims` and `audit_trail` tables |
| V2 | `V2__add_adjuster_review_fields.sql` | `adjuster_decision`, `adjuster_id`, `adjuster_notes`, `reviewed_at` |
| V3 | `V3__add_explanation_json_and_channel.sql` | `explanation_json`, `channel` |
| V4 | `V4__add_failure_reason.sql` | `failure_reason` for failed intakes saved for human attention |
| V5 | `V5__add_audit_chain.sql` | `audit_trail.chain_hash`, `seq`, `actor`; index `(claim_id, seq)` |
| V6 | `V6__add_queue_and_cc_sync_fields.sql` | `severity_gate`, `input_flags`, `cc_sync_status`; indexes `(status, created_at)`, `(cc_sync_status)` |
| V7 | `V7__add_gate_flags_and_adjuster_override.sql` | `gate_flags`, `adjuster_reserve`, `override_reason` |

## `claims`

| Column | Type | Null | Description |
|---|---|---|---|
| `claim_id` | `VARCHAR(32)` PK | no | `CLM-2026-NNNNNN` (from ClaimCenter) or `REJ-xxxxxxxx` (failed intake, no ClaimCenter claim) |
| `policy_number` | `VARCHAR(32)` | no | Policy identifier |
| `status` | `VARCHAR(32)` | no | `PENDING_ADVISORY_REVIEW`, `STRAIGHT_THROUGH_PROCESSING`, `HUMAN_REVIEW`, `BLOCKED_COMPLIANCE`, `NEEDS_MORE_INFO`, `INVESTIGATION_REQUIRED`, `APPROVED_BY_ADJUSTER`, `REJECTED_BY_ADJUSTER`, `CLOSED` |
| `incident_date` | `DATE` | no | Date of loss |
| `state` | `VARCHAR(255)` | no | US state code (`CA`, `NY`, `TX`, `FL`, `IL`) |
| `estimated_damage` | `NUMERIC(14,2)` | no, `>= 0` | Vision estimate in USD (0 for REJ- claims) |
| `reserve` | `NUMERIC(14,2)` | no, `>= 0` | Recommended reserve (damage × 1.15; 0 for REJ- claims) |
| `created_at` | `TIMESTAMPTZ` | no | |
| `updated_at` | `TIMESTAMPTZ` | no | |
| `adjuster_decision` | `VARCHAR(64)` | yes | Last adjuster decision (V2) |
| `adjuster_id` | `VARCHAR(128)` | yes | (V2) |
| `adjuster_notes` | `VARCHAR(2000)` | yes | Free text entered by the adjuster (V2) |
| `reviewed_at` | `TIMESTAMPTZ` | yes | (V2) |
| `explanation_json` | `TEXT` | yes | Serialised WHY panel (V3) |
| `channel` | `VARCHAR(32)` | yes | `WEB`, `MOBILE`, `PHONE`, `API` (V3) |
| `failure_reason` | `VARCHAR(500)` | yes | Error message for REJ- claims (V4) |
| `severity_gate` | `VARCHAR(16)` | yes | `LOW`, `MEDIUM`, `HIGH` (V6) |
| `input_flags` | `VARCHAR(500)` | yes | Comma-separated Human Review Point 1 flags (V6) |
| `cc_sync_status` | `VARCHAR(16)` | yes | `SYNCED`, `FAILED` (pick up for retry), `NOT_FILED` (REJ-) (V6) |
| `gate_flags` | `VARCHAR(500)` | yes | Comma-separated gate consistency flags and `DUPLICATE_SUBMISSION` (V7) |
| `adjuster_reserve` | `NUMERIC(14,2)` | yes, `>= 0` | Reserve set by the adjuster when modifying the AI finding; `reserve` keeps the AI value (V7) |
| `override_reason` | `VARCHAR(500)` | yes | Why the adjuster changed the AI finding (V7) |

Indexes: `idx_claims_policy_number`, `idx_claims_status_created`, `idx_claims_cc_sync_status`.

Duplicate check (`ClaimRepository.existsByPolicyNumberAndIncidentDateAndState`): a row with the same policy, incident date and state, **excluding** REJ- rows in `BLOCKED_COMPLIANCE` or `HUMAN_REVIEW`, so a corrected resubmission of a failed intake is accepted.

Reviews are applied with one conditional `UPDATE … WHERE status IN ('HUMAN_REVIEW','PENDING_ADVISORY_REVIEW','NEEDS_MORE_INFO','INVESTIGATION_REQUIRED')`, so two concurrent reviews cannot both record a final decision.

## `audit_trail`

| Column | Type | Null | Description |
|---|---|---|---|
| `event_id` | `VARCHAR(64)` PK | no | UUID |
| `claim_id` | `VARCHAR(32)` | no | |
| `agent_name` | `VARCHAR(128)` | no | `VisionDamageAgent`, `PolicyValidatorAgent`, `ReserveCalculatorAgent`, `PiiRedactionAgent`, `StatutoryDeadlineAgent`, `SubrogationScorerAgent`, `ReconciliationGate`, `Intake` (failed intake), `DuplicateCheck` (duplicate submission), `AdjusterReview` |
| `timestamp` | `TIMESTAMPTZ` | no | |
| `input_hash` | `VARCHAR(64)` | no | SHA-256 (hex) of the sanitized input |
| `output_hash` | `VARCHAR(64)` | no | SHA-256 (hex) of the output |
| `status` | `VARCHAR(32)` | no | `SUCCESS` for agents; the claim status for `ReconciliationGate`; the error code for `Intake`; the decision for `AdjusterReview` |
| `chain_hash` | `VARCHAR(64)` | yes | SHA-256(previous `chain_hash` + `input_hash` + `output_hash`) (V5) |
| `seq` | `INTEGER` | yes | 1-based position in the claim's chain (V5) |
| `actor` | `VARCHAR(128)` | yes | `SYSTEM` or the adjuster ID (V5) |

Indexes: `idx_audit_trail_claim_id`, `idx_audit_trail_claim_seq`.

### Audit chain
`AuditService.append` reads the claim's last `chain_hash` and appends events in order; `GET /api/v1/fnol/{claimId}/audit` recomputes every link and returns `chainValid`. Editing any stored hash, or deleting or reordering an event, makes `chainValid` false. Rows written before V5 have no chain and report `chainValid=false`. The newest event plus its own chain hash could be rewritten consistently, so the PDF report prints the chain head for anchoring outside the database.

## PII isolation
The database never stores the incident description, SSN, DOB, claimant name, phone or email. Audit hashes are computed from a `SanitizedIntake` (policy number, state, incident date, redacted description) or from agent outputs, never from the raw payload. Adjuster notes are stored on the claim row (they are written by the adjuster) and only their hash goes into the audit trail.
