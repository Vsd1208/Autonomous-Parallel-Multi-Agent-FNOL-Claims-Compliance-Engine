# Audit integrity

What the audit chain proves, and what it cannot.

## What it does

Every significant step on a claim writes an audit event. Events on one claim form a chain:

```text
H(i) = SHA-256( H(i-1) || canonical(event i) )
```

- **The canonical event** binds claim, sequence, agent, action, actor, actor type and role, time, input and output hashes, status, model version and ruleset version. Fields are length-prefixed, so moving characters between fields changes the hash, and null differs from empty.
- **The algorithm is named on each row** (`FNOL-AUDIT-SHA256-V2`). Rows from before version 2 keep verifying under their own rule, and new events chain on from them. No existing row was rewritten. Migration `V26__audit_integrity.sql`.
- **Appends across instances.** A head row per claim (`audit_chain_heads`) is locked with `SELECT ... FOR UPDATE`, and `(claim_id, seq)` is unique, so two API instances cannot fork a chain. The earlier `synchronized` block protected one JVM only.
- **The verifier names each problem**, so a failed check says which event and what is wrong. `GET /fnol/{id}/audit` returns `chainValid` and `problems`.

## Evidence

`AuditIntegrityTest`, on H2 in every build and on PostgreSQL 16.14 ([results](FINAL_ENGINEERING_AUDIT.md#tests-executed)):

| Tampering | Detected |
|---|---|
| Editing any one field of any event | Yes, each field tested |
| Deleting from the middle, the end, or all events | Yes |
| Rewriting an event and recomputing its own hash | Yes: the next link breaks |
| Moving characters between fields | Yes |
| Concurrent appends to one claim | Cannot fork the chain |

## What it cannot prove

**A hash chain shows tampering. It does not make the database immutable.** Someone with write access to the database can rewrite a whole chain, including the head row, from the first event onward, and it will verify, because no secret is involved.

The defence is an anchor kept elsewhere. `GET /api/v1/admin/audit/anchor` (supervisors) returns a digest that changes when any chain changes. A copy stored outside this database's control would expose a full rewrite. **Nothing here schedules that or stores the copy:** a deployment must, for example by writing it daily to object storage with a retention lock. Until then this protects against accidental or partial changes, not a determined database administrator.

Also not covered: the time on an event comes from the application server's clock, and the audit proves what the system recorded, not that what it was told was true.
