# API Contracts & Endpoints Specification

## 1. Platform REST APIs (`fnol-api` on Port 8080)

### `GET /api/v1/health`
Service health check.
- **Response `200 OK`**:
```json
{
  "status": "UP"
}
```

---

### `POST /api/v1/fnol/intake`
Main FNOL intake and multi-agent intelligence evaluation endpoint.
- **Request Body**:
```json
{
  "policyNumber": "POL-AUTO-112233",
  "incidentDate": "2026-08-16",
  "state": "CA",
  "description": "Rear-ended by other driver. Police report filed. Claimant Avery Johnson DOB 01/02/1980 SSN 123-45-6789.",
  "claimantName": "Avery Johnson",
  "claimantDob": "01/02/1980",
  "claimantSsn": "123-45-6789",
  "evidenceUris": [
    "mock://photo/front-bumper.jpg"
  ]
}
```

- **Response `200 OK`**:
```json
{
  "claimId": "CLM-2026-000001",
  "status": "OPEN",
  "branchA": {
    "damageAssessment": {
      "damageType": "FRONT_BUMPER",
      "severity": "MODERATE",
      "estimatedDamage": 4200.00,
      "confidence": 0.91
    },
    "coverage": {
      "policyNumber": "POL-AUTO-112233",
      "policyFound": true,
      "policyActive": true,
      "relevantCoverageFound": true,
      "covered": true,
      "coverageType": "COLLISION",
      "coverageLimit": 50000.00,
      "deductible": 1000.00,
      "reasoning": [
        "Mock PolicyCenter policy found",
        "Policy status and effective dates evaluated",
        "Collision coverage checked using synthetic Sprint 2 rule"
      ]
    },
    "reserve": {
      "estimatedDamage": 4200.00,
      "factor": 1.15,
      "recommendedReserve": 4830.00
    }
  },
  "branchB": {
    "pii": {
      "redactedDescription": "Rear-ended by other driver. Police report filed. Claimant [REDACTED_NAME] DOB **/**/**** SSN ***-**-****.",
      "detectedTypes": ["SSN", "DOB", "FULL_NAME"],
      "piiDetected": true
    },
    "deadline": {
      "state": "CA",
      "deadline": "2026-09-15",
      "ruleSource": "MOCK_RULE",
      "disclaimer": "Synthetic prototype deadline; not legal advice."
    },
    "subrogation": {
      "score": 78,
      "recommendedAction": "INVESTIGATE",
      "factors": {
        "thirdPartyMention": 35,
        "policeReportSignal": 20,
        "damageSeverity": 23
      }
    },
    "audit": [
      {
        "eventId": "3c983a9d-b8d4-4a57-8df2-2c63ef9081e7",
        "claimId": "CLM-2026-000001",
        "agentName": "VisionDamageAgent",
        "timestamp": "2026-08-25T16:00:00Z",
        "inputHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "outputHash": "4a5c68b7...82f9",
        "status": "SUCCESS"
      }
    ]
  },
  "policyContext": {
    "totalPriorClaims": 0,
    "claimsLast6Months": 0,
    "claimsLast12Months": 0,
    "lastClaimDate": null,
    "lastClaimType": null,
    "lastClaimAmount": null,
    "openClaimsCount": 0,
    "claimFrequencyRisk": "LOW"
  },
  "explanation": {
    "decision": "HUMAN_REVIEW",
    "summary": "One or more criteria failed; escalate to human claim handler",
    "factors": [
      {
        "factor": "AI Confidence",
        "value": "0.91",
        "status": "PASS",
        "detail": "Confidence score must be >= 0.85"
      },
      {
        "factor": "Coverage Status",
        "value": "COLLISION",
        "status": "PASS",
        "detail": "Relevant coverage must be active and matched"
      },
      {
        "factor": "Compliance (PII)",
        "value": "DETECTED",
        "status": "FAIL",
        "detail": "PII must be redacted or absent from description"
      },
      {
        "factor": "Reserve Amount",
        "value": "$4830.00",
        "status": "PASS",
        "detail": "Reserve must be <= $25,000 for straight-through processing"
      },
      {
        "factor": "Claim History",
        "value": "LOW (0 in 12 months)",
        "status": "PASS",
        "detail": "Frequency risk must be LOW or MEDIUM"
      },
      {
        "factor": "Subrogation Risk",
        "value": "78",
        "status": "FAIL",
        "detail": "Subrogation score must be < 70"
      }
    ],
    "recommendedAction": "ESCALATE_TO_HUMAN"
  },
  "notes": [
    "AI-assisted decision support only; human claim handlers retain final decision authority.",
    "Coverage recommendation is based on Mock PolicyCenter contracts and synthetic Sprint 2 rules.",
    "Reserve equals estimated visual damage multiplied by 1.15.",
    "Subrogation score is deterministic and explained by contributing factors."
  ]
}
```

---

### `GET /api/v1/fnol/status/{claimId}`
Inquiry endpoint for status polling.
- **Response `200 OK`**: Returns stored `ClaimEntity` summary.
- **Response `404 Not Found`**: Returned when claim ID is unknown.

---

## 2. Mock Guidewire APIs (`fnol-mock-guidewire` on Port 9090)

### PolicyCenter Simulator
- `GET /pc/policies/{policyNumber}`: Retrieves policy standing and dates.
- `GET /pc/policies/{policyNumber}/coverages`: Returns list of active coverages, limits, and deductibles.
- `GET /pc/policies/{policyNumber}/history`: Returns historical loss records for the policy.

### ClaimCenter Simulator
- `POST /cc/claims`: Accepts `ClaimCreateRequest` and generates a new `CLM-2026-XXXXXX` claim ID.
- `GET /cc/claims/{claimId}`: Retrieves synthetic claim file.

---

## 3. Error Contract (`ApiError`)
Consistent error payload across all endpoints:
```json
{
  "timestamp": "2026-08-25T16:00:00Z",
  "status": 400,
  "error": "VALIDATION_ERROR",
  "message": "State must be one of CA, NY, TX, FL, IL",
  "path": "/api/v1/fnol/intake"
}
```
Stack traces, credentials, database internals, and raw PII are never exposed in error responses.

### Part 2 Changes (Context & Explainability)

#### policyContext (NEW)
Object containing historical claim context for the policy:
- **totalPriorClaims**: Total number of prior claims on the policy
- **claimsLast6Months**: Number of claims filed in the last 6 months
- **claimsLast12Months**: Number of claims filed in the last 12 months
- **lastClaimDate**: Date of the most recent claim (null if none)
- **lastClaimType**: Type of the most recent claim (e.g., "COLLISION", "THEFT")
- **lastClaimAmount**: Amount of the most recent claim
- **openClaimsCount**: Number of currently open claims
- **claimFrequencyRisk**: Risk assessment based on claim frequency
    - `LOW`: 0 claims in last 12 months
    - `MEDIUM`: 1 claim in last 12 months AND 0 in last 6 months
    - `HIGH`: 2+ claims in 12 months OR 1+ in last 6 months

#### explanation (CHANGED from List<String> to Object)
Structured decision explanation with 6 pass/fail factors:
- **decision**: Final decision on claim eligibility
    - `STRAIGHT_THROUGH`: All 6 factors PASS → eligible for automatic approval
    - `HUMAN_REVIEW`: Any 1+ factors FAIL → requires manual review
- **summary**: Human-readable summary of the decision
- **factors**: Array of exactly 6 decision factors:
    1. **AI Confidence**: Damage assessment confidence >= 0.85
    2. **Coverage Status**: Relevant coverage found and active
    3. **Compliance (PII)**: No personally identifiable information detected
    4. **Reserve Amount**: Recommended reserve <= $25,000
    5. **Claim History**: Frequency risk is LOW or MEDIUM (not HIGH)
    6. **Subrogation Risk**: Subrogation score < 70

  Each factor contains:
    - **factor**: Name of the criterion
    - **value**: Actual value evaluated
    - **status**: `PASS` or `FAIL`
    - **detail**: Explanation of the threshold or rule

- **recommendedAction**: Next action to take
    - `APPROVE_AND_STP`: Approve and process automatically (all factors pass)
    - `ESCALATE_TO_HUMAN`: Escalate to human claim handler (any factor fails)

#### notes (UNCHANGED)
Array of disclaimer strings unchanged from Part 1.