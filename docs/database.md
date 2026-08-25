# Database Schema & Persistence Architecture

## Database Engine
- **Target**: PostgreSQL 15+
- **Migration Framework**: Flyway Versioned Migrations (`src/main/resources/db/migration/V1__init_claims_schema.sql`)
- **ORM**: Spring Data JPA / Hibernate 6.x

---

## Schema Definitions

### 1. `claims` Table
Stores high-level claim identification, status, and calculated financial summaries.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `claim_id` | `VARCHAR(32)` | `PRIMARY KEY` | Unique claim identifier (e.g. `CLM-2026-000001`) |
| `policy_number` | `VARCHAR(32)` | `NOT NULL` | Associated policy identifier |
| `status` | `VARCHAR(32)` | `NOT NULL` | Claim status (`OPEN`, `PENDING_REVIEW`, etc.) |
| `incident_date` | `DATE` | `NOT NULL` | Date of the loss event |
| `state` | `CHAR(2)` | `NOT NULL` | US state code (e.g. `CA`, `NY`, `TX`) |
| `estimated_damage` | `NUMERIC(14,2)` | `NOT NULL, CHECK (>= 0)` | Visual damage estimate in USD |
| `reserve` | `NUMERIC(14,2)` | `NOT NULL, CHECK (>= 0)` | Recommended initial reserve in USD |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Record creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Record last modification timestamp |

**Indexes**:
- `idx_claims_policy_number` ON `claims(policy_number)`

---

### 2. `audit_trail` Table
Stores immutable, cryptographically verifiable audit events conforming to the NAIC Model Audit Rule.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `event_id` | `VARCHAR(64)` | `PRIMARY KEY` | UUID for the specific audit record |
| `claim_id` | `VARCHAR(32)` | `NOT NULL` | Associated claim ID |
| `agent_name` | `VARCHAR(128)` | `NOT NULL` | Agent/Component name (e.g. `VisionDamageAgent`) |
| `timestamp` | `TIMESTAMPTZ` | `NOT NULL` | Exact UTC timestamp of the evaluation |
| `input_hash` | `VARCHAR(64)` | `NOT NULL` | SHA-256 hash of the input payload |
| `output_hash` | `VARCHAR(64)` | `NOT NULL` | SHA-256 hash of the output result |
| `status` | `VARCHAR(32)` | `NOT NULL` | Processing status (`SUCCESS`, `REJECTED`, etc.) |

**Indexes**:
- `idx_audit_trail_claim_id` ON `audit_trail(claim_id)`

---

## Security & PII Isolation
Raw Personally Identifiable Information (SSNs, unredacted names, raw contact notes) is **never** persisted directly into the relational store. Redaction occurs upstream via `PiiRedactionAgent` and is validated by `PIIGuardrail` prior to JPA entity persistence.
