# API Contracts & Endpoints Specification

All platform endpoints are served by `fnol-api` on port 8080 under `/api/v1`. Dates are ISO-8601 (`2026-09-27`), timestamps are UTC instants, money is a JSON number in USD.

## Authentication and CORS

| Endpoint group | Auth |
|---|---|
| `GET /health`, `POST /fnol/intake`, `GET /fnol/status/{claimId}` | Open |
| `POST /fnol/{claimId}/review`, `GET /fnol/{claimId}/audit`, `GET /fnol/{claimId}/audit/pdf`, `GET /fnol/{claimId}/explanation`, `GET /fnol/{claimId}/documents`, `GET /fnol/{claimId}/documents/{documentId}/content`, `GET /fnol/queue` | Header `X-Adjuster-Token` must equal `fnol.security.adjuster-token` (env `FNOL_ADJUSTER_TOKEN`). Missing or wrong token → `401 UNAUTHORIZED`. If no token is configured, every request to these endpoints is rejected. |

CORS allows only the origins in `fnol.cors.allowed-origins` (env `FNOL_CORS_ALLOWED_ORIGINS`, default `http://localhost:5173`), methods GET/POST/OPTIONS, headers `Content-Type` and `X-Adjuster-Token`.

## Claim statuses

| Status | Meaning |
|---|---|
| `PENDING_ADVISORY_REVIEW` | Met every STP rule and was pre-filed in ClaimCenter, but `fnol.stp.require-advisory-review=true` (default): waits for an adjuster to confirm. |
| `STRAIGHT_THROUGH_PROCESSING` | Met every STP rule and the advisory-review flag is off: filed with no human step. |
| `HUMAN_REVIEW` | A factor failed, severity is HIGH, an intake flag was raised, or (REJ- claims) the policy was not covered or processing timed out. |
| `BLOCKED_COMPLIANCE` | REJ- claim: the PII guardrail blocked the intake (e.g. a medical record number). Not reviewable. |
| `APPROVED_BY_ADJUSTER`, `REJECTED_BY_ADJUSTER`, `CLOSED` | Final adjuster decisions. Not reviewable again. |
| `NEEDS_MORE_INFO` | Adjuster asked for more information; reviewable again. |
| `INVESTIGATION_REQUIRED` | Adjuster required additional investigation; reviewable again. |

Claim IDs: `CLM-2026-NNNNNN` are issued by ClaimCenter; `REJ-xxxxxxxx` are failed intakes saved for human attention (no ClaimCenter claim).

## Errors

All errors use this body (`claimId` is present only when a failed intake was saved as a REJ- claim):

```json
{
  "timestamp": "2026-10-23T14:44:09Z",
  "status": 422,
  "error": "COMPLIANCE_BLOCK",
  "message": "Medical Record Number (MRN) detected. Compliance violation.",
  "path": "/api/v1/fnol/intake",
  "claimId": "REJ-3433000d"
}
```

| HTTP | `error` | When | Saved as |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` | Bean validation or input guardrail (policy format `POL-TYPE-123456`, state CA/NY/TX/FL/IL, date not in future, description present); invalid review decision; bad queue paging | — |
| 401 | `UNAUTHORIZED` | Adjuster endpoint without a valid `X-Adjuster-Token` | — |
| 409 | `DUPLICATE_FNOL` | Same policy + incident date + state already in flight or stored (REJ- rows are ignored). `claimId` = the original claim, which gets a `DUPLICATE_SUBMISSION` gate flag and a `DuplicateCheck` audit event; an original in `STRAIGHT_THROUGH_PROCESSING` goes back to `HUMAN_REVIEW` | — |
| 409 | `CLAIM_ID_CONFLICT` | ClaimCenter returned a claim ID already in the database; the stored claim is not overwritten | — |
| 409 | `INVALID_STATE` | Review of a claim that is not `HUMAN_REVIEW`, `PENDING_ADVISORY_REVIEW`, `NEEDS_MORE_INFO` or `INVESTIGATION_REQUIRED` | — |
| 422 | `COMPLIANCE_BLOCK` | PII guardrail: redaction confidence < 0.90, raw SSN left, or MRN detected | `REJ-…` `BLOCKED_COMPLIANCE` |
| 502 | `EXTERNAL_OR_PROCESSING_ERROR` | Policy not found, inactive or without a qualifying coverage (`CoverageNotEligibleException`) | `REJ-…` `HUMAN_REVIEW` |
| 502 | `EXTERNAL_OR_PROCESSING_ERROR` | Any other processing error during intake (e.g. ClaimCenter rejects the claim) | `REJ-…` `HUMAN_REVIEW` |
| 504 | `PROCESSING_TIMEOUT` | Orchestrator deadline exceeded, executor full, or a Guidewire call timed out / was unreachable | `REJ-…` `HUMAN_REVIEW` |

---

## `GET /api/v1/health`
`200 {"status":"UP"}`

## `POST /api/v1/fnol/intake`

Request (required: `policyNumber`, `incidentDate`, `state`, `description`):

```json
{
  "policyNumber": "POL-AUTO-112233",
  "incidentDate": "2026-09-27",
  "state": "CA",
  "description": "Vehicle involved in a minor collision. Front bumper damaged.",
  "claimantName": "Avery Johnson",
  "claimantDob": "01/02/1980",
  "claimantSsn": "123-45-6789",
  "claimantPhone": "555-0100",
  "claimantEmail": "avery@example.com",
  "evidenceUris": ["mock://photo/normal-front-bumper.jpg"],
  "channel": "WEB",
  "claimedAmount": 3000
}
```

- `claimedAmount` (optional, ≥ 0): what the claimant asks for. The recommended payment is
  `min(claimedAmount, AI estimate) − deductible`, capped at the coverage limit. More than 10% above the AI estimate
  raises `CLAIMED_EXCEEDS_AI_ESTIMATE` and the claim goes to `HUMAN_REVIEW`; it is never approved automatically.

- `evidenceUris`: with Gemini enabled, only `data:image/(jpeg|png|webp|gif);base64,…` (≤ 5 MB) and `https://` URLs on hosts listed in `fnol.evidence.allowed-hosts` are fetched; anything else gets the low-confidence fallback assessment. `mock://` URIs are read only by the mock vision provider.
- `channel`: `WEB` | `MOBILE` | `PHONE` | `API` (optional).
- Name, DOB, SSN, phone, email and the raw description are never persisted or hashed.

Response `200` (abridged):

```json
{
  "claimId": "CLM-2026-000001",
  "status": "PENDING_ADVISORY_REVIEW",
  "branchA": {
    "damageAssessment": { "damageType": "FRONT_BUMPER", "severity": "MODERATE", "estimatedDamage": 4200,
                          "confidence": 0.91, "photoQuality": "CLEAR", "damageTypeProbabilities": { "FRONT_BUMPER": 0.91 } },
    "coverage": { "policyNumber": "POL-AUTO-112233", "policyFound": true, "policyActive": true,
                  "relevantCoverageFound": true, "covered": true, "coverageType": "COLLISION",
                  "coverageLimit": 50000, "deductible": 1000,
                  "reasoning": ["PolicyCenter policy found: line of business AUTO", "...",
                                "Coverage rule: AUTO requires COLLISION or COMPREHENSIVE (SYNTHETIC_PROTOTYPE_RULE)",
                                "Matched coverage COLLISION (limit 50000, deductible 1000)"] },
    "reserve": { "estimatedDamage": 4200, "factor": 1, "recommendedReserve": 2000,
                 "confidence": 0.91, "confidenceBasis": "vision confidence 0.91 × photo quality CLEAR weight 1.00",
                 "claimedAmount": 3000, "settlementBasis": 3000, "deductible": 1000, "coverageLimit": 50000 }
  },
  "branchB": {
    "pii": { "redactedDescription": "...", "detectedTypes": [], "piiDetected": false, "redactionConfidence": 0.95 },
    "deadline": { "state": "CA", "deadline": "2026-10-27", "ruleSource": "MOCK_RULE",
                  "disclaimer": "PROTOTYPE PLACEHOLDER: ...", "acknowledgeBy": "2026-10-27",
                  "decisionBy": "2026-12-06", "urgencyFlag": "OK" },
    "subrogation": { "score": 30, "recommendedAction": "MONITOR",
                     "factors": { "thirdPartyMention": 10, "policeReportSignal": 5, "damageSeverity": 15 },
                     "complianceNotes": ["No third party identified in the description: subrogation recovery is unlikely.",
                                         "Score 30 < 70: monitor; no subrogation referral recommended."] },
    "audit": [ { "eventId": "…", "claimId": "CLM-2026-000001", "agentName": "VisionDamageAgent",
                 "timestamp": "…", "inputHash": "…", "outputHash": "…", "status": "SUCCESS" } ]
  },
  "policyContext": { "totalPriorClaims": 2, "claimsLast6Months": 0, "claimsLast12Months": 0,
                     "claimFrequencyRisk": "LOW", "...": "..." },
  "explanation": {
    "decision": "STRAIGHT_THROUGH",
    "summary": "All criteria met for automated straight-through processing",
    "factors": [ { "factor": "AI Confidence", "value": "0.91", "status": "PASS", "detail": "Vision confidence must be >= 0.85" } ],
    "recommendedAction": "APPROVE_AND_STP",
    "severityGate": "LOW"
  },
  "severityGate": "LOW",
  "eligibleForAdvisoryPrefill": true,
  "requiresHumanValidation": false,
  "notes": ["Claim meets all STP criteria and is pre-filed in ClaimCenter. An adjuster confirmation is required before it counts as filed.", "..."],
  "inputFlags": [],
  "ccSyncStatus": "SYNCED",
  "gateFlags": []
}
```

- `branchB.audit` holds seven records: `VisionDamageAgent`, `PolicyValidatorAgent`, `ReserveCalculatorAgent`, `PiiRedactionAgent`, `StatutoryDeadlineAgent`, `SubrogationScorerAgent`, `ReconciliationGate` (status = claim status).
- `explanation.decision`: `STRAIGHT_THROUGH` or `ADVISORY_RECOMMENDED_ACTION`. `recommendedAction`: `APPROVE_AND_STP`, `ESCALATE_TO_HUMAN`, `ESCALATE_TO_HUMAN_HIGH_RISK_SEVERITY`.
- `inputFlags` (Human Review Point 1): `MISSING_EVIDENCE`, `CLAIMANT_NAME_MISMATCH`, `INCIDENT_OUTSIDE_POLICY_PERIOD`, `INCIDENT_NEAR_POLICY_START`, `HIGH_CLAIM_FREQUENCY`. Any flag routes to `HUMAN_REVIEW`.
- `ccSyncStatus`: `SYNCED` if the WHY panel and evidence list were attached in ClaimCenter, `FAILED` otherwise (intake still succeeds).
- `gateFlags` (reconciliation-gate consistency checks): `SEVERITY_ESTIMATE_MISMATCH`, `RESERVE_EXCEEDS_COVERAGE_LIMIT`, `DAMAGE_TYPE_LINE_MISMATCH`, `CONFIDENCE_PHOTO_QUALITY_MISMATCH`, `CLAIMED_EXCEEDS_AI_ESTIMATE`. Any flag routes to `HUMAN_REVIEW`.
- `branchB.subrogation` is assessed in Branch B (third-party liability and `complianceNotes`) and finalised at the gate with Branch A's severity.
- The ClaimCenter claim is created as `DRAFT_PENDING_REVIEW` (or `OPEN` for STP with advisory review off).

## `GET /api/v1/fnol/status/{claimId}`
The saved claim row, or `404`:

```json
{
  "claimId": "CLM-2026-000001", "policyNumber": "POL-AUTO-112233", "status": "APPROVED_BY_ADJUSTER",
  "incidentDate": "2026-09-27", "state": "CA", "estimatedDamage": 4200.00, "reserve": 2000.00,
  "claimedAmount": 3000.00, "deductible": 1000.00,
  "adjusterDecision": "APPROVED_BY_ADJUSTER", "adjusterId": "ADJ-001", "adjusterNotes": "Photos match.",
  "reviewedAt": "…", "createdAt": "…", "updatedAt": "…",
  "explanationJson": "{…}", "channel": "WEB", "failureReason": null,
  "severityGate": "LOW", "inputFlags": null, "ccSyncStatus": "SYNCED",
  "gateFlags": null, "adjusterReserve": null, "overrideReason": null
}
```

## `GET /api/v1/fnol/{claimId}/explanation` (token)
The saved WHY panel (`ExplanationObject`). `404` if the claim does not exist or has no explanation (REJ- claims).

## `GET /api/v1/fnol/{claimId}/documents` (token)
Evidence kept from the intake, oldest first; `404` for an unknown claim. Uploaded photos
(`data:image/(jpeg|png|webp|gif);base64`, at most 5 MB, at most 5 per claim) are stored and `viewable`;
`https://` and `mock://` evidence is kept as a reference (`sourceUri`) and is not downloaded. Anything else,
including SVG, is not stored. Image bytes never appear in this JSON.

```json
[ { "documentId": "…", "claimId": "CLM-2026-000008", "name": "damage-photo-1.jpg", "contentType": "image/jpeg",
    "sizeBytes": 31447, "sha256": "0650afe5…", "source": "UPLOAD", "sourceUri": null,
    "uploadedAt": "…", "viewable": true } ]
```

Each stored item is also written to the audit chain as an `EvidenceStore` event (`STORED` or `REFERENCED`)
whose output hash covers the document's SHA-256, so the photo shown to the adjuster can be proven unchanged.

## `GET /api/v1/fnol/{claimId}/documents/{documentId}/content` (token)
The stored image with its original content type, `Content-Disposition: inline`, `X-Content-Type-Options: nosniff`,
`Content-Security-Policy: default-src 'none'; sandbox` and `Cache-Control: private, max-age=300`. `404` if the
document does not belong to that claim or is a reference without bytes.

## `GET /api/v1/fnol/{claimId}/audit` (token)
The claim's audit events in chain order and whether the chain recomputes. `404` for an unknown claim.

```json
{
  "claimId": "CLM-2026-000001",
  "chainValid": true,
  "records": [
    { "eventId": "…", "claimId": "CLM-2026-000001", "agentName": "VisionDamageAgent", "timestamp": "…",
      "inputHash": "…", "outputHash": "…", "status": "SUCCESS", "chainHash": "…", "seq": 1, "actor": "SYSTEM" },
    { "agentName": "AdjusterReview", "status": "APPROVED_BY_ADJUSTER", "seq": 8, "actor": "ADJ-001", "...": "..." }
  ]
}
```

`chainHash = SHA-256(previous chainHash + inputHash + outputHash)` (hex, empty previous for `seq` 1).

## `GET /api/v1/fnol/{claimId}/audit/pdf` (token)
`application/pdf` attachment `FNOL_Audit_{claimId}.pdf`: claim metadata (incl. failure reason, severity, intake flags, ClaimCenter sync), WHY panel, every audit event with its chain hash, the chain status and chain head, and the adjuster decision. `404` for an unknown claim.

## `POST /api/v1/fnol/{claimId}/review` (token)

```json
{ "decision": "APPROVED_BY_ADJUSTER", "adjusterId": "ADJ-001", "notes": "Photos match.",
  "adjustedReserve": 6100, "overrideReason": "Body shop estimate is higher than the photo estimate" }
```

`decision` ∈ `APPROVED_BY_ADJUSTER`, `REJECTED_BY_ADJUSTER`, `NEEDS_MORE_INFO`, `INVESTIGATION_REQUIRED`, `CLOSED`. `adjustedReserve` (optional, ≥ 0) modifies the AI finding and requires `overrideReason` (else `400`); the AI `reserve` is kept and the override is stored as `adjusterReserve`. Allowed only while the claim is `HUMAN_REVIEW`, `PENDING_ADVISORY_REVIEW`, `NEEDS_MORE_INFO` or `INVESTIGATION_REQUIRED` (else `409 INVALID_STATE`). Writes an `AdjusterReview` audit event (actor = adjusterId; notes and override reason stored only as hashes) and calls ClaimCenter `PUT /cc/claims/{id}` with the mapped status — `APPROVED_BY_ADJUSTER`→`OPEN`, `REJECTED_BY_ADJUSTER`→`DENIED`, `NEEDS_MORE_INFO`→`PENDING_INFORMATION`, `INVESTIGATION_REQUIRED`→`UNDER_INVESTIGATION`, `CLOSED`→`CLOSED` — plus the action, adjuster and adjusted reserve, which ClaimCenter tracks as a review action. A ClaimCenter failure does not fail the review and sets `ccSyncStatus=FAILED` (REJ- claims: `NOT_FILED`).

Response `200`:

```json
{ "claimId": "CLM-2026-000001", "status": "APPROVED_BY_ADJUSTER", "adjusterDecision": "APPROVED_BY_ADJUSTER",
  "adjusterId": "ADJ-001", "adjusterNotes": "Photos match.", "reviewedAt": "…",
  "adjustedReserve": 6100, "overrideReason": "Body shop estimate is higher than the photo estimate" }
```

## `GET /api/v1/fnol/queue?status=HUMAN_REVIEW&page=0&size=20` (token)
Claims in the given statuses (comma-separated, default `HUMAN_REVIEW`), oldest first. `size` 1–100.

```json
{
  "items": [ { "claimId": "REJ-3433000d", "status": "BLOCKED_COMPLIANCE", "policyNumber": "POL-AUTO-112233",
               "reserve": 0, "severityGate": null, "inputFlags": null, "gateFlags": null,
               "failureReason": "Medical Record Number (MRN) detected. Compliance violation.", "createdAt": "…" } ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
}
```

---

## Mock Guidewire (`fnol-mock-guidewire`, port 9090)

| Method | Path | Notes |
|---|---|---|
| GET | `/pc/policies/{policyNumber}` | Seeded: `POL-AUTO-112233` (CA, Avery Johnson), `POL-AUTO-223344` (NY, Morgan Lee), `POL-AUTO-445566` (CA, Jordan Rivera, two claims in the last 150 days), `POL-HOME-334455` (TX, Riley Smith). Terms run from 6 months ago for one year. 404 otherwise. |
| GET | `/pc/policies/{policyNumber}/coverages` | Delayed 12 s when `MOCK_PC_TIMEOUT=true` |
| GET | `/pc/policies/{policyNumber}/history` | Prior claims |
| POST | `/cc/claims` | Creates `CLM-2026-NNNNNN`, continuing after the highest stored claim (at least `MOCK_CC_START_SEQUENCE`, default 1) with `initialStatus` (default `OPEN`); delayed 11 s when `MOCK_GW_TIMEOUT=true` |
| GET | `/cc/claims/{claimId}` | Claim with `notes`, `attachments` and `reviewActions` |
| PUT | `/cc/claims/{claimId}` | `{ "status", "notes", "action", "adjusterId", "adjustedReserve" }`; applies the status and reserve and appends a review action |
| POST | `/cc/claims/{claimId}/attachments` | `{ "name", "contentType", "body" }` → `201` |
