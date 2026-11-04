# Engineering remediation plan

Each item below was checked against the code on 4 Nov 2026 before being listed. Status values: **Planned**, **In progress**, **Done** (implemented and tested here), **Done locally, not externally verified**, **Blocked** (with what blocks it). No item below is still Planned. See [FINAL_ENGINEERING_AUDIT.md](FINAL_ENGINEERING_AUDIT.md) for the outcome.

**Where the brief and the code differ.** `FNOLService`, `InspectionService` and `ReviewService` do not exist: that logic lives in `FNOLController`, `InspectionController` and `ReserveApprovalService`. Checks go where the logic is. Claims are open from submission (ADR-013), not drafts until decided; the human gate is that only a person posts a reserve.

**What stays.** A modular monolith on Spring Boot and PostgreSQL. No Kafka, Redis or new services: nothing here shows a need for them.

## 1. Inspector object-level authorization

| | |
|---|---|
| **Problem** | An inspector can read every claim: analysis, reserve, fraud score, audit trail |
| **Files** | `AdjusterTokenFilter.refusal` (any GET but governance passes), `FNOLController`, `InspectionController.list` |
| **Root cause** | Authorization is by role and path. Nothing compares the person with the claim |
| **Solution** | `ClaimAccessPolicy` (`requireClaimAccess`, `requireInspectionAssignment`, `requirePermission`), applied to every `/fnol/{claimId}/...` route by an interceptor that denies an undeclared route for an inspector. A limited `inspection-view` for assigned work. SIU-segment claims restricted to the assignee, senior adjusters and supervisors |
| **Database** | None |
| **API** | An inspector gets 404 on an unassigned claim and on internal data. New `GET /fnol/{claimId}/inspection-view`. The queue returns an inspector's own assignments only. **Behaviour change, documented** |
| **Security** | No existence leak: 404 for every claim the person may not see |
| **Tests** | `ClaimAccessTest`: the nine cases in the brief |
| **Acceptance** | An inspector cannot read any data outside their assignment |
| **Status** | **Done.** Also passes on PostgreSQL 16.14 |

## 2. Service credentials and human decisions

| | |
|---|---|
| **Problem** | The service token can record a decision, under any name in the request |
| **Files** | `Actor.idOr`, `FNOLController.review`, `InspectionController`, `UploadRequestController` |
| **Root cause** | "No person signed in" falls back to a caller-supplied name |
| **Solution** | `ActorType` HUMAN, SERVICE, SYSTEM. A service caller has one fixed identity and never a claimed name. `/review` requires a person, checked where the decision is applied |
| **Database** | None here; audit actor type comes with item 5 |
| **API** | **Breaking for scripts:** `/review` with the service token now gets 403. `run-all.sh` and tests sign in instead |
| **Security** | Closes impersonation and a route around maker-checker |
| **Tests** | In `ClaimAccessTest` and the updated decision tests, not a separate `ServiceCredentialTest`; `run-all.sh` checks the refusal on a real stack |
| **Acceptance** | No decision can be recorded without a signed-in person |
| **Status** | **Done** |

## 3. Portal protection

| | |
|---|---|
| **Problem** | Public tracking can be tried without limit; responses can reveal what exists |
| **Files** | `PortalController`, `UploadRequestController`, `EvidenceStore` |
| **Root cause** | No rate limit; upload checks read the declared type only |
| **Solution** | Database-backed attempt limiter, so it holds across instances without Redis. Short-lived signed, revocable claim-access tokens holding no claim data. Uniform 404. Upload checks by file signature. Security event log |
| **Database** | `V24` `portal_attempts`, `portal_access_tokens` |
| **API** | Added: `POST /portal/claims/{claimId}/access`, revoke. Existing GET kept, now limited (429) |
| **Security** | Brute force, enumeration, replay after revocation, malformed uploads |
| **Tests** | `PortalSecurityTest` |
| **Acceptance** | Attempts beyond the threshold are refused; a wrong claim and a wrong policy look the same |
| **Status** | **Done.** Also passes on PostgreSQL 16.14. Needs a proxy passing real client addresses in production |

## 4. Durable event processing

| | |
|---|---|
| **Problem** | `claim.submitted` gets 202 after going to an in-memory executor; a crash loses it |
| **Files** | `ClaimEventController`, `FNOLOrchestrator` |
| **Root cause** | Acceptance is not durable, and duplicates are caught only in memory or by the claim row |
| **Solution** | `fnol_event_inbox`, written before the 202. Worker claiming rows with `FOR UPDATE SKIP LOCKED` under a lease. Backoff with jitter, dead letter, manual replay. Same id with a different hash is refused and logged |
| **Database** | `V25` |
| **API** | Unchanged on acceptance. Added: inbox read and replay for supervisors |
| **Security** | Hash mismatch is a recorded security event |
| **Tests** | Scenarios A to E in the brief |
| **Acceptance** | A 202 survives a restart |
| **Status** | **Done.** Branch results are kept across retries through a small checkpoint interface, without splitting the orchestrator. Tested on PostgreSQL and by killing the API on a real stack. That testing found four bugs of my own, now fixed: [list](DISTRIBUTED_PROCESSING.md#what-running-it-for-real-found). Writes to the claims system are at-least-once |

## 5. Audit integrity

| | |
|---|---|
| **Problem** | The hash covers only input and output hashes: actor, time, status and sequence can be edited undetected. `synchronized` does nothing across instances |
| **Files** | `AuditService`, `AuditEntity` |
| **Solution** | Versioned canonical event with length-prefixed encoding, `H(i)=SHA256(H(i-1) || canonical(i))`. Unique `(claim_id, seq)`, a claim-level lock row. Verifier naming each failure. Old rows keep verifying under their own algorithm id |
| **Database** | `V26`, new columns, never rewriting existing rows |
| **API** | Trail gains a list of problems; `chainValid` kept |
| **Security** | A hash chain shows tampering; it does not make a database immutable. Whoever can rewrite the whole chain can forge it unless the head is anchored elsewhere |
| **Tests** | One tamper test per field; concurrent appends |
| **Acceptance** | Each listed modification is detected |
| **Status** | **Done.** Also passes on PostgreSQL 16.14. **Off-site anchoring is not automated:** a full rewrite by someone with database access is detected only if a deployment keeps the anchor elsewhere |

## 6. Coverage at the date of loss

| | |
|---|---|
| **Problem** | `PolicyValidatorAgent` tests the term against **today**. A loss reported after expiry looks uncovered. A loss before inception on a policy active today looks covered, stopped only by an intake flag |
| **Solution** | Evaluate at the incident date, with cancellation and endorsement dates. Separate status from coverage. Missing dates give `UNVERIFIED`, which routes to a person |
| **Database** | None |
| **API** | Additive fields on `Policy` and the validation result. A claim with a policy that was in force at the loss now proceeds where it was refused |
| **Tests** | `PolicyAtDateOfLossTest`: the nine cases in the brief |
| **Status** | **Done** against the policy data this project has. Real contract wording is not modelled |

## 7. PII

| | |
|---|---|
| **Problem** | Confidence is the constant 0.95, and the PII gate and a WHY factor read it. Only SSN, date of birth, MRN and exact matches are found |
| **Solution** | Report detector and category, no invented confidence. More patterns, exact known values, output scan. Fail closed before an external text call |
| **API** | `redactionConfidence` stays nullable for old rows and is not produced as a number by a rule. The factor reads validation status |
| **Tests** | Detection, false-positive guard, fail-closed |
| **Status** | **Done, with a stated limit.** A name not given on the claim is not found, and a test records that. The photo review also found sideways faces were almost never detected; it now checks four orientations. Plate and face recall remain unknown |

## 8. AI evaluation

| | |
|---|---|
| **Problem** | No final holdout; photo-level only; no abstention state |
| **Solution** | Group-aware split with a leakage check, a holdout kept apart, abstention as an explicit state routing to a person, reliability tables |
| **Blocked** | **A successor to the retiring Gemini model:** two trials answered 0 of 5 calls. Claim-level multi-photo and human comparison need labelled claims that do not exist. No repair invoices exist, so the reserve stays table-based and experimental |
| **Status** | **Done for what is local; the rest blocked as above.** [Results](AI_MODEL_EVALUATION.md). Most important: accuracy on a new photo source falls to about 64–68% |

## 9. Regulatory rules

| | |
|---|---|
| **Problem** | Rules lack effective dates, status classes and holiday support |
| **Solution** | Versioned fields added to the existing JSON, effective-dated selection, configurable holidays |
| **Blocked** | A lawyer's review of every rule. True dates for most rules: unknown stays marked unknown. Texas holidays: the state's schedule refused the connection |
| **Status** | **Done locally, not legally verified.** [Details](REGULATORY_RULES.md) |

## 10. Guidewire adapter

| | |
|---|---|
| **Problem** | The adapter covers part of ClaimCenter, nothing for PolicyCenter, and uses a static bearer token |
| **Solution** | Explicit mock or cloud selection, OAuth2 client credentials with caching, a labelled contract simulator, an operations matrix |
| **Blocked** | **No tenant, so nothing can be marked verified.** Field shapes not on public pages stay labelled assumed |
| **Status** | **Done locally, not externally verified.** Simulator tests only. **The cloud claims client refuses most operations, so it cannot yet carry the full flow.** [Matrix](GUIDEWIRE_INTEGRATION.md) |

## 11. Frontend

Role-aware navigation, the inspector's limited view, processing and dead-letter visibility, abstention and honest confidence labels, and how far each rule was checked. **Status: Done,** with Vitest and 18 tests added. Those tests caught abstention never reaching the screen. Component tests only: no browser end-to-end tests exist.

## 12. Observability

Correlation IDs, Micrometer metrics, liveness and readiness, startup configuration checks. **Status: Done,** and checked on a real stack. OpenTelemetry was evaluated and not added. No dashboards or alerts ship, because this project runs no Prometheus or Grafana.

## 13. Refactoring and docs

`FNOLController` is 808 lines and `FNOLOrchestrator` 961. Extract only where it serves the items above: a large rewrite this close to presenting is the largest regression risk here. **Status: Docs done. Refactoring deliberately not done,** beyond small extractions such as `AbstentionPolicy`, `ClaimAccessPolicy` and the Guidewire transport. Both classes remain large.
