# Guidewire Integration: how the engine would sit on ClaimCenter

> **Status: modelled, not verified.** This project has never run against a Guidewire tenant. The claims system here is
> a mock over real HTTP and a real database. Its flow is modelled on Guidewire's **public** documentation, cited below;
> no non-public material was used. Anything the public pages do not state is marked as not verified.

## The short version

The engine is built as an **extension of the claims system, not a replacement for it**:

```text
 Portal / agent ──> report the loss ──> CLAIMS SYSTEM (owns the claim, the queue, the decision)
                                           │  ▲                      │
                 signed claim.submitted    │  │ writes results back  │ signed claim.decided
                 (outbox, at least once)   ▼  │ onto the same claim  ▼
                                        AI ENGINE (analysis, audit, feedback loop; owns no claim)
```

- A loss is reported to the claims system, which issues the claim number and refuses a duplicate.
- Submitting it emits `claim.submitted`. The engine reacts, analyses, and writes the estimate, recommended reserve,
  routing, exposure, activities, advisory note and documents **onto that claim**.
- An adjuster's decision is recorded **in the claims system first**, which emits `claim.decided`. The engine's record,
  audit chain and feedback loop follow, including for a decision made in the claims system without the engine.
- If the engine is down, claims are still filed, listed under **Awaiting AI Analysis**, and analysed when it returns.
  If the claims system is down, a decision is refused (503) and nothing is recorded anywhere.

The AI recommends; it never pays or decides. **The human gate:** a filed claim is open, as on ClaimCenter, but the AI
posts no reserve. Its figure is a recommendation, in an activity and a note, and only a person's decision puts a reserve on
the exposure. The one exception is STP with advisory review turned off, an explicit business decision (ADR-009).

## How each piece maps to Guidewire Cloud

Checked on 2 Nov 2026. "Documented" means a public Guidewire page says so, linked in [Sources](#sources). Paths and
behaviour can differ between releases; a real adapter must be built from the tenant's own API definition.

| In this project | On Guidewire Cloud | Status |
|---|---|---|
| Draft, then submit: `POST /cc/rest/claim/v1/claims`, `POST .../{id}/submit` | The same two steps: create a draft with `POST /claims` in the Claim API, then `POST /claim/v1/claims/{claimId}/submit` [1][2] | **Documented**; same shape, payload is this project's |
| `claim.submitted`, `claim.decided` events | App Events: business events published from ClaimCenter to downstream systems, without custom Gosu [3] | Concept documented; **the event names here are this project's**, and the real events must be chosen from the tenant's list |
| Outbox and retrying dispatcher | App Events' own delivery, documented as at least once, and safe-ordered by the primary object [3] | Same guarantee modelled; the engine tolerates repeats. **Ordering differs**: the mock does not hold later events behind a failing one, so the engine re-requests a decision that arrives early |
| `POST /api/v1/events/claimcenter` | A webhook subscription, the no-code route for a system that can expose an HTTP endpoint [3][4] | Documented; request format **not verified** |
| Integration Gateway | Not needed for this engine, which can expose an endpoint. It is the alternative where a target cannot: an app built on Apache Camel that reads events and calls the target [3][5] | Documented; nothing built |
| HMAC-SHA256 signature on events | How a real webhook is authenticated is not stated on the pages read | **Not verified**; the signature is this project's own and would follow whatever the platform provides |
| A thin event; the engine reads the report from the claim | Events carry a full claim graph snapshot (claim, incidents, exposures, parties, policy) [3][4] | **Differs**: the real event would itself carry personal data, so the engine's redaction would apply on receipt. No raw personal data would be stored, as now |
| `POST .../claims/{id}/activities` | `POST /claim/v1/claims/{claimId}/activities` appears in the documentation [6] | Documented; payload not checked |
| `POST .../claims/{id}/exposures` | `POST /claims/{claimId}/exposures` is documented, and an exposure **requires a coverage, claimant and incident** [7] | Path documented. **The mock is looser**: it needs no claimant or incident |
| `POST .../claims/{id}/notes` | The guide has a "Creating claim notes" topic; the path was not confirmed [8] | **Not verified** |
| `PATCH .../claims/{id}` for the AI's estimate and work queue | `PATCH /claims/{claimId}` is documented for drafts [1]. On a real open claim these would need another home | **Differs**; the AI writes no reserve and no lifecycle status here |
| Draft, open and closed; a draft number, then an open number | Documented: a submitted draft becomes open with an open claim number, and can then only be closed [1][2] | **Modelled to match**. The id stays the same throughout; in the mock it equals the open number |
| Reserve set by a person's decision, on the exposure | Reserves live on exposures. ClaimCenter's own authority rules for reserves were not on the pages read | Design choice; **authority limits not modelled** |
| An exposure needs a coverage, incident and claimant | Documented [7] | **Modelled to match**. The names (`VehicleIncident`, `FixedPropertyIncident`) are simple labels here, not linked incident records; **not verified** |
| `workQueue` field on the claim | Not a ClaimCenter field. The natural equivalents are assignment and activities in a group's queue, or a data-model extension | **This project's design**; to be decided with an implementer |
| Duplicate check when a loss is reported | ClaimCenter's own duplicate search is not described on the pages read | **Modelled on the idea; not verified** |
| Adjuster workspace (React) | A Jutro-based experience, or only an AI panel inside ClaimCenter, where adjusters already work | Nothing built |
| OAuth2 client credentials on calls | Guidewire Cloud APIs use OAuth2 tokens | Implemented in the prepared adapter; the mock takes a static token. The real request format is **not verified** |
| Payload shape in the adapter (`data.attributes`, `policyNumber`, `lossDate`, `id`, `claimNumber`) | The public pages read confirm the paths and that a draft returns an id and a number, not this shape | **Assumed**, from the project's reading of how the Cloud API presents resources; check first |

### Where a real deployment would differ most

These are the differences an implementer should weigh first. None is hidden by the mock's naming.

1. **Authority over reserves.** A submitted claim is open and can only be closed afterwards [2], and the mock now works
   this way, so the AI cannot hold a claim back from opening. The human gate is that it posts no reserve and only a person's
   decision does. On a real tenant, adjuster authority limits and approvals would govern that posting; those are not modelled
   here, and how they apply must be confirmed with an implementer.
2. **Review stage.** Here `workQueue` says where a claim stands in review (human review, awaiting inspection and so on). It
   is a design choice to keep the workspace's queues, not a ClaimCenter field; real work would be assignment and activities.
3. **Submission can fail validation** and leave the claim a draft [2]; in the base configuration it also needs a
   reporter [2]. The mock checks only the policy number and loss date, so the portal would need to handle this case.
4. **There is no single "decided" event.** `claim.decided` bundles things ClaimCenter represents separately: reserve
   changes, activity completion, closing a claim. It also carries `finalRepairCost` and `repairCostSource`, the evidence
   behind the cost model's learning, which have no standard home. A real version needs a mapping for each, probably
   several subscriptions and an extension or a structured note for the cost evidence.
5. **Policy lookup.** During draft creation ClaimCenter retrieves the policy itself, and unverified policies need a
   composite request [1]. The engine reads a mock PolicyCenter directly.

### Why there is no Gosu here

The usual in-core route is event messaging with Gosu rules and custom message transport plugins, where changes to the
integration logic require Gosu changes [3]. Guidewire's public material presents App Events and Integration Gateway as
the way to move that logic out of InsuranceSuite [3][5], and the engine already runs outside it, so it needs no Gosu in
the core for this flow. Gosu written here could not have been compiled or checked, so none is included rather than
shipping code that looks finished. A data-model extension for a field like `workQueue`, if one is kept, would be
configuration inside the tenant; it is not built.

## What is real in this repository

The mock is a stand-in; the integration behaviour around it is real code, tested and run:

| Behaviour | Where | Evidence |
|---|---|---|
| Change and event written in one transaction (outbox) | `ClaimEventOutbox`, `ClaimCenterController.update` | `ClaimEventFlowTest` |
| Signed delivery, retry with backoff, nothing sent without a secret | `ClaimEventDispatcher` | `ClaimEventFlowTest` |
| Signature check, repeats ignored, failures reach an adjuster | `ClaimEventController` | `ClaimEventExtensionTest` |
| Decisions first in the claims system; 503 and nothing recorded if it is down | `FNOLController.reviewInClaimsSystem` | `ClaimsSystemDecisionTest` |
| A decision counts once, using the claims system's time | `ClaimDecisionRecorder`, `ClaimRepository.applyClaimsSystemDecision` | `ClaimsSystemDecisionTest` |
| Queue read from the claims system, with a labelled fallback | `FNOLController.queueFromClaimsSystem` | `ClaimsSystemDecisionTest` |
| End to end, both intake paths and decisions | `demo-scenarios/run-all.sh` | Run against a stack |

The one-off live checks during development (not a benchmark, one laptop, mock claims system, claims without
photos): results written back about 1 s after submit; a decision made directly in the claims system reached the engine's
record about 1 s later; with the engine stopped a claim was filed, then analysed 14 s after the engine restarted.

## The claims-system contract

| Method and path | Purpose |
|---|---|
| `POST /cc/rest/claim/v1/claims` | Report a loss as a draft; 409 `DUPLICATE_FNOL` naming the original if the same policy, loss date and state was already reported, and the original gets an activity |
| `POST /cc/rest/claim/v1/claims/{id}/submit` | Draft to `OPEN` with its open number, queues `claim.submitted`; 409 if not a draft |
| `GET /cc/rest/claim/v1/claims/{id}/fnol` | The report as filed |
| `PATCH /cc/rest/claim/v1/claims/{id}` | The extension records routing, estimate, reserve and work queue; empty fields are left alone |
| `GET /cc/rest/claim/v1/claims?workQueue=A,B&page=&size=` | Work queue listing, oldest first |
| `PUT /cc/claims/{id}` | Status and notes. An adjuster decision queues `claim.decided`; a denied or closed claim refuses another status (409) |
| `GET /cc/events?claimId=&undelivered=` | Operations view of the outbox |
| `.../{id}/exposures`, `/activities`, `/notes`, `/documents`; `/cc/claims/{id}/attachments` | What the engine writes on the claim. An exposure without a coverage, incident or claimant is refused (400) |

**Events** are posted to `claims.events.subscribers` with `X-Claims-Event-Id` and
`X-Claims-Signature: sha256=<hex HMAC of the exact body>`. Field names follow CloudEvents:

```json
{ "id": "…", "type": "claim.decided", "source": "claims-system", "time": "…", "subject": "CLM-2026-000061",
  "data": { "claimId": "CLM-2026-000061", "decision": "REJECTED_BY_ADJUSTER", "adjusterId": "ADJ-001",
            "decidedAt": "2026-11-02T07:45:01.123Z", "adjustedReserve": null, "overrideReason": null,
            "confirmedDamageType": null, "finalRepairCost": 1500, "repairCostSource": "BODY_SHOP_ESTIMATE" } }
```

Neither event carries the claimant's details or free text; the engine reads them from the claim.

| Engine's answer | Meaning |
|---|---|
| `202` `accepted` / `applied` | Taken |
| `202` `duplicate` / `ignored` | A repeat, or an event it does not act on |
| `401` | Missing or wrong signature |
| `503` | No secret configured, too busy, or not ready yet (for example a decision on a claim still being analysed). The claims system delivers it again, with backoff up to 5 minutes |

**Modes.** With `CLAIMS_EVENT_SECRET` set on both services (Compose does this) new losses take the path above, and
`GET /api/v1/health` answers `"intake":"CLAIMS_SYSTEM"`. Without it the engine uses the older **direct intake**
(`POST /api/v1/fnol/intake`: analyse, then create the claim), answering `"intake":"DIRECT"`. The direct endpoint also stays
available in claims-system mode, for partner channels and the demo's comparison pass. A failed direct intake is saved as
`REJ-…`, which the claims system never held, so those claims are listed after its own in the queue.

### What the engine writes on a claim

| ClaimCenter object | What the engine puts there |
|---|---|
| Claim | AI estimate; routing as the work queue. **No reserve** |
| Exposure | One for the matched coverage (e.g. `VehicleDamage` / COLLISION, `Dwelling` / DWELLING) with its incident and claimant, and **an empty reserve** (cost type `claimcost`). ClaimCenter holds reserves on exposures, not on the claim |
| Activity | "Review recommended reserve", stating the AI's figure; one per AI review flag, or "Confirm AI advisory" for an STP-eligible claim; due by the statutory acknowledgement date; urgent when severity is HIGH |
| Note | The AI advisory, ending "advisory only: the adjuster decides". The claimant's description is not copied |
| Documents | The WHY panel, the evidence list and the acknowledgement letter |

When the adjuster decides, the claim's update completes the open review activities. An approval posts the reserve: their
figure, or the recommendation they approved. A denial closes the claim, and zeroes and closes the exposure. The work queue,
not the status, says where a claim stands in review. Interim steps (requesting photos, assigning an inspector) leave the
activities open and move the work queue.

## Seeded data and failure switches

| Policy | Line | State | Insured | Notes |
|---|---|---|---|---|
| `POL-AUTO-112233` | AUTO | CA | Avery Johnson | No claims in the last 12 months |
| `POL-AUTO-223344` | AUTO | NY | Morgan Lee | |
| `POL-AUTO-445566` | AUTO | CA | Jordan Rivera | Two claims in the last 150 days (HIGH frequency) |
| `POL-HOME-334455` | HOME | TX | Riley Smith | DWELLING and PERSONAL_PROPERTY coverage |

Policy terms start six months before today and run for a year, so the data never goes stale.

| Variable | Effect |
|---|---|
| `MOCK_GW_TIMEOUT=true` | `POST /cc/claims` waits 11 s (trips the API's 3 s read timeout) |
| `MOCK_PC_TIMEOUT=true` | `GET /pc/policies/{id}/coverages` waits 12 s (trips the 3 s read timeout inside Branch A) |
| `MOCK_CC_START_SEQUENCE=N` | Lowest first claim number. Claims are stored in the database, so numbering continues after the highest stored claim |
| `CLAIMS_EVENT_SUBSCRIBERS` | Where events are delivered |
| `CLAIMS_EVENT_SECRET` | Signs events; set the same value on both services. Compose's default is for local runs only |
| `CLAIMS_EVENT_POLL_MS` | How often due events are sent (default 1000) |

## Adapter seam

The engine reaches Guidewire only through two interfaces, so the mock clients are the swap point:

```text
   FNOL orchestrator, controllers
            |                   |
   PolicyCenterClient    ClaimCenterClient      <- interfaces
            |                   |
   MockPolicyCenterClient  MockClaimCenterClient  (HTTP, @Profile("!guidewire"))
```

## Contract tests

`GuidewireClientContractTest` (in `fnol-mock-guidewire`) exercises the engine's own client classes for what the engine
relies on, against the mock on a random port in every build. To point it at another server:

```bash
GUIDEWIRE_CONTRACT_BASE_URL=https://… GUIDEWIRE_CONTRACT_TOKEN=… GUIDEWIRE_CONTRACT_POLICY=…   mvn -pl fnol-mock-guidewire -am test -Dtest=GuidewireClientContractTest -Dsurefire.failIfNoSpecifiedTests=false
```

Against a real tenant this only means something once a real client maps these calls onto the tenant's paths; the
assertions describe the engine's needs, not Guidewire's wire format.

## Path to production

**It needs a sandbox tenant, which this project does not have:** [the request to send and the first-day steps](guidewire-sandbox-request.md).

**Prepared, never run on a tenant:** `GuidewireCloudClaimCenterClient` (profile `guidewire-cloud-claims`) does OAuth2, draft, submit, activities and notes, tested only against a stub of what it assumes; anything without a known mapping refuses instead of guessing. `tools/guidewire-check/check_sandbox.py` reports what a real tenant says to each assumption. The steps below are not built.

1. **Confirm the human gate** against the tenant's authority rules for reserves (difference 1 above). It comes first: the
   governance rule depends on it.
2. **Subscribe** to the real App Events by webhook, and replace `ClaimEventController`'s parsing with the real event
   format and authentication, reading the claim graph with the same redaction.
3. **Adapters.** `GuidewirePolicyCenterClient` and `GuidewireClaimCenterClient` under the `guidewire` profile, mapping
   these calls to the tenant's Cloud API, creating real incident records for exposures.
4. **Authentication.** Confirm the prepared OAuth2 flow against the tenant, and sign adjusters in through its identity provider.
5. **Map routing and decisions** to assignment, activities and ClaimCenter's own events, with a home for cost evidence.
6. **Adjuster experience.** Move the AI panel into ClaimCenter (Jutro), retiring the separate workspace's queue.
7. **Contract tests** against the sandbox, with field mappings checked against the tenant's API definitions.

What should not change: the agents, the reconciliation gate and its flags, the WHY panel, the audit chain, the rule that
the AI never pays, and the engine's analysis record. That is an expectation, to be confirmed against a tenant.

## Sources

Public pages, read on 2 Nov 2026:

1. [The FNOL process in Cloud API](https://docs.guidewire.com/cloud/is/202603/cloudapibf/cloudAPI/ClaimCenter/fnol/executing-fnol/c_the-FNOL-process-in-Cloud-API.html)
2. [Submitting a draft claim](https://docs.guidewire.com/cloud/is/202603/cloudapibf/cloudAPI/ClaimCenter/fnol/executing-fnol/c_submitting-a-draft-claim.html)
3. [App Events (Cloud Integration Basics)](https://docs.guidewire.com/education/cloud-integration-basics/latest/docs/integration_cloud_basics/appevents_overview/)
4. [Simplifying Outbound Integrations in InsuranceSuite Cloud, Part 1](https://www.guidewire.com/resources/blog/developers/simplifying-outbound-integrations-in-insurancesuite-cloud-part-1) (February 2022, written when App Events was in early access; [general availability announced November 2022](https://guidewire.com/blog/technology/simplify-event-driven-integrations-on-guidewire-cloud-with-app-events))
5. [Integration Gateway overview](https://docs.guidewire.com/education/cloud-integration-basics/latest/docs/integration_cloud_basics/integration_gateway_overview)
6. [Service request messages](https://docs.guidewire.com/cloud/cc/202507/cloudapibf/cloudAPI/topics/111-CCFNOL/06-service-requests/c_messages.html)
7. [Creating exposures](https://docs.guidewire.com/cloud/cc/202411/cloudapibf/cloudAPI/topics/111-CCFNOL/05-exposures/c_creating-exposures.html)
8. [Activities](https://docs.guidewire.com/cloud/cc/202511/cloudapibf/cloudAPI/topics/141-Framework/01_activities/c_activities.html) (lists the "Creating claim notes" topic in its navigation)
