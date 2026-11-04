# Deployment

How to run this, and what a real deployment still has to supply. **This has only been run on Docker Compose on one laptop. It has never been deployed to a production environment, and no sizing or load test exists.**

## Local stacks

| Stack | Command | Uses |
|---|---|---|
| Lite: recorded AI answers, small images | `docker compose --env-file .env.lite -f docker-compose.yml -f docker-compose.lite.yml up --build` | No key, no vision service |
| Full demo: mock vision | `docker compose --env-file .env.demo up --build` | No key |
| Real Gemini | Copy `.env.example` to `.env`, and add your key | See [the model retirement](AI_MODEL_EVALUATION.md#the-model-retirement) first |

All scenarios: `bash demo-scenarios/run-all.sh` (see the [README](../README.md)).

| Service | Port |
|---|---|
| API | 8080 |
| Adjuster workspace | 5173 |
| Policyholder portal | 5174 |
| Mock Guidewire | 9090 |
| PostgreSQL | 5432 |

## Production settings

Set `FNOL_ENVIRONMENT=production`: the application then refuses to start on what follows if it is missing or a demo value, naming the setting ([details](SECURITY.md#secrets-and-configuration)).

| Setting | Why |
|---|---|
| `FNOL_ADJUSTER_TOKEN` | Service credential. Generate a long random value |
| `FNOL_USERS` | Local accounts, until single sign-on exists |
| `CLAIMS_EVENT_SECRET` | Signs claim events |
| `FNOL_PORTAL_TOKEN_SECRET` | Portal tokens across instances and restarts |
| `SPRING_DATASOURCE_*` | Database |
| `SPRING_PROFILES_ACTIVE=guidewire-cloud-claims,guidewire-cloud-policy` | The real systems. **These clients cannot carry the full flow yet:** [status](GUIDEWIRE_INTEGRATION.md) |
| `GUIDEWIRE_CLOUD_*` properties (`guidewire.cloud.base-url`, `token-url`, `client-id`, `client-secret`, `coverages-path`, `line-codes`) | The tenant |
| `GEMINI_API_KEY`, `GEMINI_VISION_MODEL` | A model that has been measured and calibrated |

**So production mode cannot start successfully and also work end to end today.** That is accurate: it needs a tenant and the mappings listed there.

Other settings: `FNOL_INBOX_*`, `FNOL_PORTAL_*`, and `FNOL_CORS_ALLOWED_ORIGINS`. See `fnol-api/src/main/resources/application.yml`.

## Database

PostgreSQL 16, migrated by Flyway at startup (V1 to V26). Hibernate validates the schema and never changes it.

- **Existing migrations are never edited.** A change is a new migration.
- **Back up first.** No down-migrations exist; restore from backup. No backup, restore or point-in-time recovery has been set up or tested.

## Health and readiness

Point the platform at `/actuator/health/liveness` and `/actuator/health/readiness`. Readiness includes the database. Scrape `/actuator/prometheus` with a credential. Metrics and first alerts are in [DISTRIBUTED_PROCESSING.md](DISTRIBUTED_PROCESSING.md#observability).

## Restarts

**Compose has no restart policy, so the platform must supply one.** If PostgreSQL goes away while the API is starting, the API exits with an error and stays down. Once running, it recovered from a PostgreSQL restart on its own in testing, and a claim filed afterwards was analysed normally.

## Running several instances

Designed for it, but only one API container has been run end to end:

- **Safe across instances:** the event inbox (`SKIP LOCKED`, leases), audit appends (head row lock), and portal attempt limits (database rows).
- **Per instance:** the analysis's in-memory duplicate guard (the database's unique row is the backstop), assignment's round-robin, the Guidewire token cache, and portal tokens if no secret is set.

## A deployment must also supply

TLS and a reverse proxy passing real client addresses; single sign-on; secret management and rotation; log shipping, with its own retention policy; Prometheus and alerts; **scheduled off-site copies of the audit anchor** ([why](AUDIT_INTEGRITY.md#what-it-cannot-prove)); a data retention and deletion schedule; backups; a legal review of the [rules](REGULATORY_RULES.md); and recalibration of the AI on the insurer's own photos ([why](AI_MODEL_EVALUATION.md)).
