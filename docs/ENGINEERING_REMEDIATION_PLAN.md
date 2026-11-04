# Engineering remediation plan

Each item below was checked against the code on 4 Nov 2026 before being listed. Status values: **Planned**, **In progress**, **Done** (implemented and tested here), **Done locally, not externally verified**, **Blocked** (with what blocks it). See [FINAL_ENGINEERING_AUDIT.md](FINAL_ENGINEERING_AUDIT.md) for the outcome.

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
| **Status** | Planned |

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
| **Tests** | `ServiceCredentialTest`: approve, reject, final decision, maker-checker, impersonation; ingestion still works |
| **Acceptance** | No decision can be recorded without a signed-in person |
| **Status** | Planned |

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
| **Status** | Planned |

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
| **Status** | Planned. Keeping branch results across retries (Scenario B) needs the orchestrator split into steps; see the audit for how far this went |

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
| **Status** | Planned |

## 6. Coverage at the date of loss

| | |
|---|---|
| **Problem** | `PolicyValidatorAgent` tests the term against **today**. A loss reported after expiry looks uncovered. A loss before inception on a policy active today looks covered, stopped only by an intake flag |
| **Solution** | Evaluate at the incident date, with cancellation and endorsement dates. Separate status from coverage. Missing dates give `UNVERIFIED`, which routes to a person |
| **Database** | None |
| **API** | Additive fields on `Policy` and the validation result. A claim with a policy that was in force at the loss now proceeds where it was refused |
| **Tests** | The nine cases in the brief |
| **Status** | Planned |

## 7. PII

| | |
|---|---|
| **Problem** | Confidence is the constant 0.95, and the PII gate and a WHY factor read it. Only SSN, date of birth, MRN and exact matches are found |
| **Solution** | Report detector and category, no invented confidence. More patterns, exact known values, output scan. Fail closed before an external text call |
| **API** | `redactionConfidence` stays nullable for old rows and is not produced as a number by a rule. The factor reads validation status |
| **Tests** | Detection, false-positive guard, fail-closed |
| **Status** | Planned. Names in free text beyond the known claimant are not reliably detectable by rules; that limit will be documented |

## 8. AI evaluation

| | |
|---|---|
| **Problem** | No final holdout; photo-level only; no abstention state |
| **Solution** | Group-aware split with a leakage check, a holdout kept apart, abstention as an explicit state routing to a person, reliability tables |
| **Blocked** | New Gemini runs need quota. Claim-level multi-photo and human comparison need labelled claims that do not exist. No repair invoices exist, so the reserve stays table-based and experimental |
| **Status** | Planned for what is local |

## 9. Regulatory rules

| | |
|---|---|
| **Problem** | Rules lack effective dates, status classes and holiday support |
| **Solution** | Versioned fields added to the existing JSON, effective-dated selection, configurable holidays |
| **Blocked** | Filling in true dates needs legal sources. Unknown stays marked unknown |
| **Status** | Planned |

## 10. Guidewire adapter

| | |
|---|---|
| **Problem** | The adapter covers part of ClaimCenter, nothing for PolicyCenter, and uses a static bearer token |
| **Solution** | Explicit mock or cloud selection, OAuth2 client credentials with caching, a labelled contract simulator, an operations matrix |
| **Blocked** | **No tenant, so nothing can be marked verified.** Field shapes not on public pages stay labelled assumed |
| **Status** | Planned |

## 11. Frontend

Role-aware navigation, the inspector's limited view, processing and dead-letter visibility. No frontend test runner exists today, so one must be added. **Status:** Planned.

## 12. Observability

Correlation ids, Micrometer metrics, liveness and readiness, startup configuration checks. **Status:** Planned. OpenTelemetry and dashboards are evaluated in docs only unless needed.

## 13. Refactoring and docs

`FNOLController` is 808 lines and `FNOLOrchestrator` 961. Extract only where it serves the items above: a large rewrite this close to presenting is the largest regression risk here. **Status:** Planned.
