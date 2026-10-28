# Guidewire Integration & Adapter Architecture

## Simulation Scope & Honesty Statement

> **Important Honesty Requirement**: This prototype does **not** assume or claim live connectivity to a production Guidewire Cloud Platform tenant. It uses contract-first mock implementations of the required PolicyCenter and ClaimCenter integration boundaries over actual HTTP.

## What is Simulated and Why

Guidewire PolicyCenter and ClaimCenter are enterprise core systems with extensive API surfaces. For this prototype FNOL intelligence platform, we isolate and simulate the exact subset of capabilities necessary to execute an FNOL workflow:

1. **PolicyCenter (Policy Inquiry)**:
   - Policy verification and active status checking (`GET /pc/policies/{policyNumber}`)
   - Coverages, limits, and deductible extraction (`GET /pc/policies/{policyNumber}/coverages`)
   - Prior loss and claim frequency history (`GET /pc/policies/{policyNumber}/history`)

2. **ClaimCenter (Claim File Ingestion)**:
   - Initial claim file creation with preliminary reserve allocations (`POST /cc/claims`)
   - Claim inquiry, including notes and attachments (`GET /cc/claims/{claimId}`)
   - Adjuster decision and notes (`PUT /cc/claims/{claimId}`)
   - Attached documents: the WHY-panel advisory and the evidence list (`POST /cc/claims/{claimId}/attachments`)

ClaimCenter calls after the claim exists (attachments, decision updates) are best effort: a failure is logged and the claim's `cc_sync_status` is set to `FAILED` for a retry job, so a ClaimCenter outage never fails an intake or a review.

### Seeded data and failure switches

| Policy | Line | State | Insured | Notes |
|---|---|---|---|---|
| `POL-AUTO-112233` | AUTO | CA | Avery Johnson | No claims in the last 12 months |
| `POL-AUTO-223344` | AUTO | NY | Morgan Lee | |
| `POL-AUTO-445566` | AUTO | CA | Jordan Rivera | Two claims in the last 150 days (HIGH frequency) |
| `POL-HOME-334455` | HOME | TX | Riley Smith | DWELLING and PERSONAL_PROPERTY coverage |

Policy terms start six months before today and run for a year, so the data never goes stale.

| Variable | Effect |
|---|---|
| `MOCK_GW_TIMEOUT=true` | `POST /cc/claims` waits 11 s (trips the API's 3 s read timeout after the fan-out) |
| `MOCK_PC_TIMEOUT=true` | `GET /pc/policies/{id}/coverages` waits 12 s (trips the 3 s read timeout inside Branch A) |
| `MOCK_CC_START_SEQUENCE=N` | Lowest first claim number. Claims are stored in the database (`MOCK_CC_DB_URL`; Postgres schema `claimcenter` under Compose), so numbering continues after the highest stored claim and a restart neither loses claims nor reissues IDs |

## Adapter Abstraction Architecture

The FNOL application NEVER directly interacts with the mock services or mock classes. All communication occurs strictly through clean Java interfaces:

```text
                     FNOL ORCHESTRATOR / AGENTS
                                  |
            +---------------------+---------------------+
            |                                           |
            v                                           v
    PolicyCenterClient                          ClaimCenterClient
       (Interface)                                 (Interface)
            |                                           |
            v                                           v
  MockPolicyCenterClient                      MockClaimCenterClient
    (HTTP RestTemplate)                         (HTTP RestTemplate)
            |                                           |
            v (HTTP on :9090)                           v (HTTP on :9090)
    Mock PolicyCenter                           Mock ClaimCenter
```

## What the engine files in ClaimCenter

The AI does not keep its own claim: it files the claim in ClaimCenter and fills it the way an adjuster works with it.
ClaimCenter stays the system of record. The mock ClaimCenter stores each object on a path shaped like the Cloud API
claim resources:

| ClaimCenter object | Mock path | What the engine puts there |
|---|---|---|
| Claim | `POST /cc/claims` | Policy, loss date, state, AI estimate; `DRAFT_PENDING_REVIEW` until a person decides (`OPEN` only for unreviewed STP) |
| Exposure | `POST /cc/rest/claim/v1/claims/{id}/exposures` | One exposure for the matched coverage (e.g. `VehicleDamage` / COLLISION, `Dwelling` / DWELLING) carrying the reserve line (cost type `claimcost`). ClaimCenter holds reserves on exposures, not on the claim |
| Activity | `POST /cc/rest/claim/v1/claims/{id}/activities` | One per AI review flag ("Review AI flag: claimed exceeds ai estimate", with the reason), or "Confirm AI advisory" for an STP-eligible claim; due by the statutory acknowledgement date; urgent when severity is HIGH; assigned to the FNOL adjuster queue |
| Note | `POST /cc/rest/claim/v1/claims/{id}/notes` | The AI advisory: summary, every review note, "advisory only: the adjuster decides". The claimant's description is not copied (PII stays out) |
| Documents | `POST /cc/claims/{id}/attachments`, `GET /cc/rest/claim/v1/claims/{id}/documents` | The WHY panel and the evidence list |

When the adjuster decides, the update to the claim completes the open review activities (completed by the adjuster),
sets the exposure reserve to the adjuster's final reserve, or zeroes and closes it on a denial. Interim steps
(requesting photos, assigning an inspector) leave the activities open. The console's **In ClaimCenter** page shows all
of this for a claim (`GET /api/v1/fnol/{id}/claimcenter`, adjusters only).

In a real deployment the same calls go to ClaimCenter's Cloud API, and a ClaimCenter app event on claim creation (for
FNOL taken in ClaimCenter or a customer portal) would trigger the engine instead of the engine creating the claim.

## Production Replacement Path

When connecting to an authentic Guidewire Cloud environment, the replacement is seamless and isolated to the adapter layer:

```
MockPolicyCenterClient  ----->  GuidewirePolicyCenterClient (OAuth2 / Cloud API REST)
MockClaimCenterClient   ----->  GuidewireClaimCenterClient  (OAuth2 / Cloud API REST)
```

No changes are required in:
- `VisionDamageAgent`, `PolicyValidatorAgent`, or any other agent (coverage rules live in `CoverageRuleProvider`)
- `FNOLOrchestrator`
- `FNOLController` or REST endpoints
- Guardrails or validation layers
- PostgreSQL JPA entities or Flyway migrations
- Frontend React contracts

## Verification Assumptions

Before deploying production adapters:
1. Verify authentication mechanisms (Guidewire Cloud Platform OAuth2 / JWT client credentials).
2. Validate field mappings against the customer's specific PolicyCenter and ClaimCenter Cloud API OpenAPI specifications.
3. Review line-of-business (LOB) coverage structures (Personal Auto, Commercial Auto, Homeowners).
