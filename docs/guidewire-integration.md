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
