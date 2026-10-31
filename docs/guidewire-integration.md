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
ClaimCenter stays the system of record. The mock ClaimCenter stores each object on a path modelled on the Cloud API
claim resources (not verified against them; see [What was checked against Guidewire's documentation](#what-was-checked-against-guidewires-documentation)):

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

## What was checked against Guidewire's documentation

Checked on 31 Oct 2026 against Guidewire's public Cloud API documentation (ClaimCenter Cloud API Consumer Guide). Only
what the pages state is listed as verified.

| Topic | Guidewire's Cloud API (documented) | This mock | Status |
|---|---|---|---|
| FNOL is a draft first | Create a draft claim with `POST /claims` in the Claim API; the response carries the claim's id and a **draft claim number**. Optionally `PATCH /claims/{claimId}` one or more times. ([The FNOL process in Cloud API](https://docs.guidewire.com/cloud/is/202603/cloudapibf/cloudAPI/ClaimCenter/fnol/executing-fnol/c_the-FNOL-process-in-Cloud-API.html)) | `POST /cc/claims` with `initialStatus: DRAFT_PENDING_REVIEW`; the claim number is final at once | Concept matches; path and numbering differ |
| Submitting the draft | `POST /claim/v1/claims/{claimId}/submit` turns the draft into an open claim with an open claim number and runs claim setup rules (segmentation, assignment, activities). A failed validation leaves the claim in draft. ([Submitting a draft claim](https://docs.guidewire.com/cloud/is/202603/cloudapibf/cloudAPI/ClaimCenter/fnol/executing-fnol/c_submitting-a-draft-claim.html)) | The adjuster's decision moves the draft to `OPEN` through `PUT /cc/claims/{id}` | Not modelled: a real adapter would call `submit` when the adjuster approves |
| Policy on the claim | During draft creation ClaimCenter retrieves the policy from the policy administration system; unverified policies need a composite request | The engine reads the policy from mock PolicyCenter itself | Differs |
| Exposures, activities, notes, documents | Documented as Claim API resources (the consumer guide has sections for each), but the exact paths were not confirmed | `/cc/rest/claim/v1/claims/{id}/exposures`, `/activities`, `/notes`, `/documents` | **Not verified**; the paths are this project's own |
| Authentication | Guidewire Cloud APIs use OAuth2 tokens | No auth on the mock; a configurable bearer token (`guidewire.auth.bearer-token`) is sent when set | Seam only; token fetching not built |

Endpoint paths can differ between Guidewire releases; a real adapter must be built from the tenant's own API
definition.

## Contract tests

`fnol-mock-guidewire/src/test/java/com/guidewire/fnol/mock/GuidewireClientContractTest.java` exercises the engine's
own client classes (`MockPolicyCenterClient`, `MockClaimCenterClient`) for everything the engine relies on: an
in-force policy with term, state and line of business; coverages with a limit and a deductible; claim history; a 404
for an unknown policy; a claim created as a draft that stays a draft until the adjuster's update; exposures,
activities, notes and documents stored with server ids; a 404 for writes to an unknown claim.

It runs against the mock on a random port in every build. To point it at another server:

```bash
GUIDEWIRE_CONTRACT_BASE_URL=https://… GUIDEWIRE_CONTRACT_TOKEN=… GUIDEWIRE_CONTRACT_POLICY=…   mvn -pl fnol-mock-guidewire -am test -Dtest=GuidewireClientContractTest -Dsurefire.failIfNoSpecifiedTests=false
```

Against a real tenant this only becomes meaningful once a `GuidewireClaimCenterClient` maps these calls onto the
tenant's Cloud API paths (see below); the assertions describe the engine's needs, not Guidewire's wire format.

## Path to production

**Nothing below is built.** It is the plan for connecting to a real Guidewire Cloud tenant.

What changes:

1. **Adapters.** New `GuidewirePolicyCenterClient` and `GuidewireClaimCenterClient` implementing the existing
   interfaces, active under the `guidewire` Spring profile (the mock clients are already `@Profile("!guidewire")`).
   They map the engine's calls onto the tenant's Cloud API: create the draft claim, add exposures, activities, notes
   and documents, then call `submit` when the adjuster approves.
2. **Authentication.** OAuth2 client credentials against the tenant's identity provider, with token caching and
   refresh, replacing the static bearer token in `GuidewireAuthInterceptor`.
3. **Events instead of polling.** Subscribe to ClaimCenter App Events (or webhooks) for claim creation and updates, so
   FNOL taken in ClaimCenter or a Guidewire portal triggers the engine, and adjuster actions in ClaimCenter flow back.
4. **Adjuster UI.** The React workspace would become a Jutro-based experience embedded in ClaimCenter; the engine's
   REST API stays the back end.
5. **Contract tests** re-pointed at a sandbox tenant through the adapters, plus field mappings validated against the
   tenant's OpenAPI definitions.

What does not change: the agents, the orchestrator, the reconciliation gate and its flags, the WHY panel, the audit
chain, the governance rules (the AI never pays; drafts until a person decides) and the engine's database.

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
