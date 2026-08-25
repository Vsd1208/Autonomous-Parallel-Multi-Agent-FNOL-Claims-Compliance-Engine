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
   - Claim status inquiry (`GET /cc/claims/{claimId}`)

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
- `VisionDamageAgent`, `PolicyValidatorAgent`, or any other agent
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
