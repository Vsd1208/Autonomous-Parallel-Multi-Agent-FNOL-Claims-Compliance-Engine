# Distributed processing

How a claim event gets from the claims system to an analysed claim, and what happens when something dies. This is a modular monolith on Spring Boot and PostgreSQL. No Kafka, Redis or extra services: nothing measured shows a need for them, and PostgreSQL holds the queue.

## The path of an event

```text
Claims system  --signed event-->  ClaimEventController  --commit-->  fnol_event_inbox  -->  202
                                                                           |
                       EventInboxWorker claims due rows (FOR UPDATE SKIP LOCKED, under a lease)
                                                                           |
                                 analyse: Branch A + Branch B  --> reconcile --> write back
                                                                           |
                                         COMPLETED | RETRY_PENDING | DEAD_LETTER
```

- **A 202 means committed.** The row is saved before the answer, so a crash after a 202 loses nothing.
- **Duplicates.** The same event ID makes one row, even delivered at the same moment. The same ID with different content is refused and recorded as a security event.
- **Workers.** `FOR UPDATE SKIP LOCKED` means several instances can poll without taking the same row. A lease (`fnol.inbox.lease-seconds`, default 120) means a worker that died gives the row up when the lease runs out.
- **Finished branches are kept.** A retry runs only what did not finish, so a later failure does not pay for Gemini twice.
- **Failure.** Transient failures back off, doubling with jitter up to `backoff-max-seconds`. After `max-attempts` (default 5) the row goes to the dead letter and an adjuster is told. A permanent failure goes straight to an adjuster.
- **Replay.** A supervisor can replay a dead letter from the Operations page, or with `POST /api/v1/admin/event-inbox/{id}/replay`. Replaying re-runs analysis; it decides nothing.

Outbound, the mock claims system uses an outbox (`ClaimEventOutbox`), retried with backoff, so an event is not lost while the engine is down.

## Evidence

`DurableInboxTest`, on H2 in every build and on PostgreSQL 16.14:

| Scenario | Result |
|---|---|
| The 202 is backed by a committed row before any work; a later worker picks it up | Pass |
| A retry reuses a finished branch: vision is not called twice | Pass |
| A duplicate makes one workflow; the same ID with other content is refused | Pass |
| The same event at the same moment makes one row | Pass |
| A dead worker's lease expires and another takes the job; a live lease is left alone | Pass |
| With the claims system down, the analysis is kept and the write-back succeeds later | Pass |
| Attempts used up: dead letter, an adjuster told, a supervisor can replay, an adjuster cannot | Pass |
| Two workers racing never hold the same job | Pass |

The crash test on a real stack is in [FINAL_ENGINEERING_AUDIT.md](FINAL_ENGINEERING_AUDIT.md#crash-test).

## What running it for real found

Unit tests and H2 passed throughout. These four came from running the stacks, and each is fixed with a regression test:

| Found by | Problem | Fix |
|---|---|---|
| `run-all.sh`, every run | A timed-out analysis retried for over two minutes with no one told. Durability had made a claim go quiet | A person is told the first time a failure is seen, and can act at once. A later success adds the analysis |
| `DurableInboxTest`, written for that fix | The claim's own failure row made its retry look like a duplicate of itself | The check ignores the claim's own row |
| Killing the API mid-event | A worker that died before telling anyone left the next failing attempt silent, because "first" was judged by attempt number | Judged by whether a person has been told |
| Repeated `run-all.sh`, about 1 run in 2 | Two events due together overflowed the worker's one-slot queue. One row was claimed but refused a thread, and looked taken until its lease ran out, with three threads idle | A row is claimed only with a slot reserved for it; one that cannot start goes straight back, without using an attempt |

Also: a retry must not overwrite a decision a person made while it was retrying. It now leaves that claim alone.

## Known limits

- **At-least-once, not exactly once, on the claims-system side.** A worker that dies after writing an exposure, note or activity but before finishing will write it again on retry, because the mock's API has no idempotency key for those writes. A real tenant needs a checkpoint per write, or idempotent writes.
- **Same-JVM lease expiry.** The analysis's duplicate guard has an in-memory layer per JVM. A lease expiring while its worker is still alive in the same JVM can meet that guard. Several instances rely on the database's unique row.
- **Slow worker.** A worker slower than its lease can overlap with the next one. The lease must exceed the longest analysis; there is no heartbeat.
- **`ClaimAssignmentService` uses `synchronized`,** which is per JVM: round-robin assignment may be uneven across instances. It does not break correctness.
- **Only tested with one API instance end to end.** Concurrency was tested with two workers against one PostgreSQL inside one process, not with two API containers under load. No load test has been run.
- **No partitioning or purge.** The inbox table grows without bound.

## Observability

Prometheus at `/actuator/prometheus`, with a credential:

| Metric | Meaning |
|---|---|
| `fnol_inbox_depth{status}` | Queue depth, including `DEAD_LETTER`. Not a number if the database is away, rather than a false zero |
| `fnol_inbox_events_total{outcome}` | Retries and dead letters |
| `fnol_branch_duration_seconds{branch,outcome}` | Each branch's time and failures |
| `fnol_provider_duration_seconds{provider,outcome}` | Gemini's latency and failures |

- **Probes:** `/actuator/health/liveness`, and `/readiness`, which includes the database.
- **Tracing:** each request gets `X-Correlation-ID` (a caller's is kept only if it looks like one, so a header cannot forge log lines). Worker runs use the event's ID, so retries can be followed across restarts. Both reach the branch threads and go to Guidewire calls. Logs are key-value lines with `correlationId`, `claimId` and `eventId`.
- **OpenTelemetry was evaluated and not added.** Correlation IDs and metrics cover a single service. Micrometer Tracing can export to it when there is a collector and a second service to trace.
- **No dashboards or alerts ship,** because this project runs no Prometheus or Grafana. First alerts to set: `fnol_inbox_depth{status="DEAD_LETTER"} > 0`, a growing `PENDING`, and Gemini failures.
