# Database

PostgreSQL is managed by Flyway migration `V1__init_claims_schema.sql`.

Tables:

- `claims`: claim status and monetary assessment summary
- `audit_trail`: event ID, claim ID, agent, timestamp, SHA-256 input/output hashes, status

Raw PII is not intentionally persisted in these tables.
