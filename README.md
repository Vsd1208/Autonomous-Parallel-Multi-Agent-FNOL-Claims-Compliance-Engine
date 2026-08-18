# 🏛️ Autonomous Parallel Multi-Agent FNOL Claims & Compliance Engine

<div align="center">

![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white)
![React](https://img.shields.io/badge/React-18-61DAFB?style=for-the-badge&logo=react&logoColor=black)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=for-the-badge&logo=docker&logoColor=white)
![Guidewire](https://img.shields.io/badge/Guidewire-Cloud_Platform-FF6600?style=for-the-badge&logo=data:image/svg+xml;base64,&logoColor=white)
![Guardrails](https://img.shields.io/badge/AI_Guardrails-Enabled-8B0000?style=for-the-badge&logo=shield&logoColor=white)

**A dual-branch asynchronous AI engine for Property & Casualty insurance FNOL processing**  
*Built for Guidewire DEV-Summit | Java + Spring WebFlux + LangChain4j*

[Architecture](#-architecture) • [Tech Stack](#-tech-stack) • [Getting Started](#-getting-started) • [API Reference](#-api-reference) • [Guardrails](#-ai-guardrails)

</div>

---

## 📌 Overview

Traditional FNOL (First Notice of Loss) workflows process claims **sequentially over 3–7 business days**. This engine introduces a **parallel agentic architecture** that fires both operational and legal evaluation **simultaneously** at the moment of FNOL intake — resolving straight-through-processable claims in **under 15 seconds**.

```
LEGACY:  FNOL → Policy Check → Damage Review → PII Redaction → Compliance → Reserve
                 ──────────────────── 3 to 7 days ────────────────────────────────

OURS:    FNOL → ┬── Branch A: Operational  ──┬── Reconciliation Gate → STP / Escalate
                └── Branch B: Compliance   ──┘
                 ──────────────── < 15 seconds ─────────────────────────────────────
```

### Core Innovation

| Capability | Description |
|---|---|
| ⚡ **Async Fan-Out** | Both branches execute via `Mono.zip()` — true parallel, non-blocking |
| 🔍 **Vision LLM Damage Scoring** | GPT-4o Vision analyzes crash photos → structured damage JSON in ~4s |
| 🛡️ **Automated PII/PHI Redaction** | GDPR/CCPA compliant redaction before any data persistence |
| ⚖️ **Statutory Deadline Engine** | State-specific settlement deadline tracking with urgency flags |
| 🔁 **Subrogation Discovery** | LLM-scored third-party liability assessment |
| 📋 **Regulatory Audit Trail** | SHA-256 hashed, immutable, NAIC Model Audit Rule compliant |
| 🚦 **Reconciliation Safety Gate** | Auto STP execution or human adjuster escalation with pre-compiled legal notes |
| 🔒 **AI Guardrail Layer** | Multi-tier validation wall between LLM outputs and financial actions — blocks hallucinated, out-of-bounds, or non-compliant agent results |
| 📚 **Claim History Enrichment** | Pre-enriches every FNOL with prior claims, policy history, incident frequency, and loss patterns before agents run |
| 💬 **Decision Explainability** | Every decision surfaces structured human-readable reason codes — not just a verdict, but exactly *why* |
| 🎬 **Demo Failure Scenarios** | Four documented failure paths (low confidence, compliance block, API timeout, duplicate event) alongside the happy path |

---

## 🏗️ Architecture

```
╔══════════════════════════════════════════════════════════════════════════╗
║          AUTONOMOUS PARALLEL MULTI-AGENT FNOL ENGINE                    ║
║                  Guidewire Cloud Platform Integration                    ║
╚══════════════════════════════════════════════════════════════════════════╝

  ┌─────────────────────────────────────────┐
  │      JUTRO DIGITAL PORTAL (React)        │
  │  FNOL Form · Photo Upload · Status Track │
  └──────────────────┬──────────────────────┘
                     │ POST /api/v1/fnol/intake
                     ▼
  ┌─────────────────────────────────────────┐
  │   FNOL ORCHESTRATOR  (Spring WebFlux)    │
  │   Spring Boot 3 · Maven Multi-Module     │
   └────────────┬────────────────────────────┘
               │
               ▼
   ┌─────────────────────────────────────────┐
   │    CLAIM HISTORY ENRICHER               │
   │  ClaimHistoryAgent (pre-fan-out)        │
   │  · Previous claims on this policy       │
   │  · Loss frequency & patterns            │
   │  · Prior incidents & at-fault history   │
   │  · Policy standing & amendment history  │
   └────────────┬────────────────────────────┘
               │ Enriched FNOLContext passed to both branches
     ───────────┴───────────────
     │    Mono.zip() FAN-OUT    │
     ──────────┬────────────────
              │
   ┌──────────┴─────────────┐      ┌───────────────────────────┐
   │     BRANCH A           │      │     BRANCH B              │
   │   Operational          │      │   Legal & Compliance      │
   ├────────────────────────┤      ├───────────────────────────┤
   │ VisionDamageAgent      │      │ PiiRedactionAgent         │
   │ PolicyValidatorAgent   │      │ StatutoryDeadlineAgent    │
   │ ReserveCalculatorAgent │      │ SubrogationScorerAgent    │
   └──────────┬─────────────┘      │ AuditTrailAgent           │
              │                    └───────────────┬───────────┘
              └──────────────┬─────────────────────┘
                             │
              ┌──────────────▼───────────────────┐
              │       AI GUARDRAIL LAYER          │
              │  ┌─────────────────────────────┐  │
              │  │ OutputSchemaGuard           │  │
              │  │ ConfidenceThresholdGuard    │  │
              │  │ BusinessRuleGuard           │  │
              │  │ ComplianceGuard             │  │
              │  │ FraudPatternGuard           │  │
              │  └─────────────────────────────┘  │
              │  PASS ──────────────── BLOCK       │
              └──────┬──────────────────┬──────────┘
                     │                  │
                     │           ┌──────▼──────────┐
                     │           │  GUARDRAIL FAIL  │
                     │           │  → Force Escalate│
                     │           │  → Log Violation │
                     │           └──────────────────┘
                             ▼
              ┌──────────────────────────────┐
              │    RECONCILIATION GATE       │
              │  STP Decision Logic          │
              └──────────┬───────────────────┘
                         │
          ┌──────────────┴──────────────┐
          ▼                             ▼
  ┌───────────────┐           ┌─────────────────────┐
  │   STP PATH    │           │  ESCALATION PATH    │
  │  Auto-Execute │           │  Adjuster Dashboard │
  │  ClaimCenter  │           │  + Legal Notes Pack │
  │  Reserve API  │           └─────────────────────┘
  └───────────────┘
          │
          ▼
  ┌──────────────────────────────────┐
  │  PostgreSQL · Flyway Migrations  │
  │  Audit Trail · PII Logs          │
  │  Statutory Deadlines · Reserves  │
  └──────────────────────────────────┘
```

### Module Structure (Maven Multi-Module)

```
fnol-engine/
├── pom.xml                          ← Parent POM (Spring Boot 3.2 BOM)
│
├── fnol-common/                     ← Shared DTOs, Enums, Constants
├── fnol-mock-guidewire/             ← WireMock: ClaimCenter + PolicyCenter APIs
├── fnol-enrichment/                 ← Claim History & Context Enricher (pre-fan-out)
├── fnol-agents/                     ← AI Agent implementations (LangChain4j)
├── fnol-guardrails/                 ← AI Guardrail Layer (validation wall)
├── fnol-orchestrator/               ← Async fan-out + Reconciliation Gate
├── fnol-api/                        ← Spring Boot REST API (main app)
└── fnol-frontend/                   ← React + Vite (Jutro Portal simulation)
```

---

## 🛠️ Tech Stack

### Backend

| Component | Technology | Version |
|---|---|---|
| Language | Java | 17 LTS |
| Framework | Spring Boot | 3.2.x |
| Reactive Engine | Spring WebFlux (Project Reactor) | 3.6.x |
| AI Orchestration | LangChain4j | 0.32.x |
| LLM Provider | OpenAI GPT-4o Vision | API v1 |
| Mock APIs | WireMock (Java-native) | 3.x |
| ORM | Spring Data JPA + Hibernate | 6.x |
| DB Migrations | Flyway | 10.x |
| API Docs | SpringDoc OpenAPI (Swagger) | 2.x |
| Build | Apache Maven | 3.9.x |

### Frontend

| Component | Technology | Version |
|---|---|---|
| Framework | React | 18.x |
| Build Tool | Vite | 5.x |
| Styling | Tailwind CSS | 3.x |
| HTTP Client | Axios + React Query | Latest |
| File Upload | react-dropzone | 14.x |
| Charts | Recharts | 2.x |

### Infrastructure

| Component | Technology |
|---|---|
| Database | PostgreSQL 15 |
| Containers | Docker + Docker Compose |
| CI/CD | GitHub Actions |
| Deployment | Docker (self-hosted / Railway) |

---

## 🚀 Getting Started

### Prerequisites

| Tool | Version | Check |
|---|---|---|
| Java JDK | 17+ | `java -version` |
| Apache Maven | 3.9+ | `mvn -version` |
| Docker Desktop | Latest | `docker -v` |
| Node.js | 18+ | `node -v` |
| Git | Any | `git -v` |

### 1. Clone the Repository

```bash
git clone https://github.com/Vsd1208/Autonomous-Parallel-Multi-Agent-FNOL-Claims-Compliance-Engine.git
cd Autonomous-Parallel-Multi-Agent-FNOL-Claims-Compliance-Engine
```

### 2. Configure Environment Variables

```bash
cp .env.example .env
```

Edit `.env` and fill in your values:

```env
# OpenAI (required for Vision + Compliance agents)
OPENAI_API_KEY=sk-...

# PostgreSQL
POSTGRES_DB=fnol_db
POSTGRES_USER=fnol_user
POSTGRES_PASSWORD=fnol_secret
POSTGRES_PORT=5432

# Spring Boot API
API_PORT=8080

# Mock Guidewire APIs
MOCK_GW_PORT=9090

# Frontend
VITE_API_BASE_URL=http://localhost:8080/api/v1
```

> **No OpenAI key?** Set `MOCK_LLM_ENABLED=true` in `.env` — all agents will use pre-built stub responses so the full flow still works for demo purposes.

### 3. Start All Services (One Command)

```bash
docker compose up --build
```

This starts:
| Service | URL |
|---|---|
| FNOL REST API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Mock Guidewire APIs | http://localhost:9090 |
| React Frontend | http://localhost:5173 |
| PostgreSQL | localhost:5432 |

### 4. Build Locally (Without Docker)

```bash
# Build all Maven modules
mvn clean install

# Start mock Guidewire APIs first
cd fnol-mock-guidewire && mvn spring-boot:run &

# Start main API
cd fnol-api && mvn spring-boot:run &

# Start frontend
cd fnol-frontend && npm install && npm run dev
```

---

## 📡 API Reference

### Core Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/v1/health` | Service health check |
| `POST` | `/api/v1/fnol/intake` | Submit FNOL claim (multipart) |
| `GET` | `/api/v1/fnol/{claimId}/status` | Get claim processing status |
| `GET` | `/api/v1/fnol/{claimId}/audit` | Get regulatory audit trail |
| `POST` | `/api/v1/fnol/{claimId}/approve` | Adjuster manual approval |
| `GET` | `/api/v1/fnol/{claimId}/deadlines` | Get statutory deadlines |
| `GET` | `/api/v1/fnol/{claimId}/explain` | Get human-readable decision explanation |
| `GET` | `/api/v1/policies/{policyId}/history` | Get enriched claim history context |

### Submit a Test FNOL Claim

```bash
curl -X POST http://localhost:8080/api/v1/fnol/intake \
  -H "Content-Type: multipart/form-data" \
  -F "policyNumber=POL-AUTO-112233" \
  -F "lossDate=2026-08-15" \
  -F "lossState=CA" \
  -F "lossType=COLLISION" \
  -F "claimantNotes=My vehicle was rear-ended at the intersection of 5th and Main." \
  -F "photo=@./docs/sample-images/damage_test.jpg"
```

### Sample Successful STP Response

```json
{
  "claimId": "CLM-2026-00847",
  "fnolReference": "FNOL-20260818-9923",
  "processingTimeMs": 11240,
  "stpEligible": true,
  "reconciliationDecision": "STRAIGHT_THROUGH_PROCESSING",
  "claimContext": {
    "previousClaimsCount": 1,
    "lastClaimDate": "2024-03-10",
    "policyStanding": "GOOD",
    "claimFrequencyRisk": "LOW"
  },
  "branchA": {
    "damageType": "COLLISION",
    "severityScore": 6.2,
    "estimatedRepairCostUsd": 10200.00,
    "initialReserveUsd": 11730.00,
    "coverageStatus": "VALID",
    "confidence": 0.91
  },
  "branchB": {
    "complianceFlag": "CLEAR",
    "piiEntitiesRedacted": 3,
    "subrogationScore": 42,
    "subrogationAction": "MONITOR",
    "statutoryUrgency": "OK",
    "acknowledgeDeadline": "2026-09-02",
    "decisionDeadline": "2026-09-27"
  },
  "explanation": {
    "decision": "STRAIGHT_THROUGH_PROCESSING",
    "summary": "Claim meets all STP criteria and passed all guardrails.",
    "factors": [
      { "factor": "AI Confidence", "value": "91%", "status": "PASS" },
      { "factor": "Coverage Status", "value": "VALID — Collision covered", "status": "PASS" },
      { "factor": "Compliance Check", "value": "CLEAR — PII redacted (3 entities)", "status": "PASS" },
      { "factor": "Reserve Amount", "value": "$11,730 — within STP threshold", "status": "PASS" },
      { "factor": "Claim History", "value": "1 prior claim (2024) — low frequency risk", "status": "PASS" },
      { "factor": "Subrogation Risk", "value": "Score 42 — monitoring only", "status": "PASS" }
    ]
  },
  "actionsExecuted": [
    "CLAIM_CREATED_IN_CLAIMCENTER",
    "RESERVE_SET_USD_11730",
    "VENDOR_DISPATCH_DRAFTED",
    "AUDIT_TRAIL_WRITTEN"
  ]
}
```

### Mock Guidewire API Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/pc/policies/{policyId}/coverages` | PolicyCenter coverage lookup |
| `POST` | `/cc/claims` | ClaimCenter: create claim |
| `GET` | `/cc/claims/{claimId}` | ClaimCenter: get claim |
| `PUT` | `/cc/claims/{claimId}/reserve` | ClaimCenter: set reserve |

---

## 🤖 AI Agents

### Branch A — Operational Agents

#### `VisionDamageAgent`
- **Model:** GPT-4o Vision API
- **Input:** Base64-encoded JPEG/PNG damage photo
- **Output:** `{ damageType, severityScore, affectedComponents[], estimatedRepairCostUsd, confidence }`
- **SLA:** < 8 seconds

#### `PolicyValidatorAgent`
- **Input:** `policyNumber`, `lossDate`, `lossType`
- **Output:** `{ coverageStatus: VALID|EXCLUDED|LAPSED, applicableCoverages[], deductibleUsd }`
- **Source:** Mock PolicyCenter REST API

#### `ReserveCalculatorAgent`
- **Formula:** `initialReserve = estimatedDamageCost × 1.15` (15% ULAE buffer)
- **Output:** Reserve amount + vendor dispatch recommendation

---

### Branch B — Compliance Agents

#### `PiiRedactionAgent`
- **Method:** Regex patterns + GPT-4o structured output for edge cases
- **Entities Detected:** SSN, DOB, Driver's License, Medical Record #, Credit Card #, Full Name
- **Compliance:** GDPR Article 25, CCPA §1798.100
- **Output:** Redacted text + `redactionManifest[]`

#### `StatutoryDeadlineAgent`
- **Supported States:** CA, NY, TX, FL, IL
- **Rules Example (CA):** Acknowledge ≤ 15 days, Accept/Deny ≤ 40 days
- **Output:** `{ acknowledgeBy, decisionBy, paymentBy, urgencyFlag: OK|WARNING|CRITICAL }`

#### `SubrogationScorerAgent`
- **Input:** Incident report text, third-party details
- **Output:** `{ score: 0-100, action: PURSUE|MONITOR|DECLINE }`

#### `AuditTrailAgent`
- **Compliance:** NAIC Model Audit Rule
- **Storage:** Immutable PostgreSQL record per agent action
- **Hashing:** SHA-256 of input and output payloads

---

## 🔒 AI Guardrails

The **Guardrail Layer** is a dedicated validation wall that sits between all agent outputs and any downstream financial or legal action. Every agent result — from either Branch A or Branch B — must pass all applicable guardrails before reaching the Reconciliation Gate. A single guardrail failure **forces escalation** and **logs a violation** to the audit trail, regardless of other results.

> This design ensures no hallucinated, out-of-bounds, or non-compliant LLM output can ever trigger an automated financial transaction.

### Guardrail Types

#### 1. `OutputSchemaGuard`
Enforces that every agent returns a response conforming to its declared JSON schema.
- If `VisionDamageAgent` returns prose instead of structured JSON → **BLOCK**
- Implemented via JSON Schema validation (Jackson + `jsonschema-validator`)

```
Agent Output → JSON Schema Validator → PASS / BLOCK
```

#### 2. `ConfidenceThresholdGuard`
Rejects agent outputs where the confidence score falls below acceptable thresholds.

| Agent | Min Confidence | Action on Failure |
|---|---|---|
| `VisionDamageAgent` | 0.70 | Block STP, flag for adjuster review |
| `SubrogationScorerAgent` | 0.65 | Downgrade to MONITOR, notify legal |
| `PiiRedactionAgent` | 0.90 | Hard block — claim cannot proceed |

#### 3. `BusinessRuleGuard`
Enforces hard financial and operational limits that override any AI decision.

| Rule | Condition | Action |
|---|---|---|
| Reserve Cap | `initialReserveUsd > $50,000` | Force escalation — never auto-STP |
| Severity Ceiling | `severityScore >= 9.0` | Flag as catastrophic loss — SIU referral |
| Negative Reserve | `initialReserveUsd <= 0` | Hard block — agent error |
| Coverage Mismatch | `lossType ∉ applicableCoverages[]` | Immediate denial pipeline |

#### 4. `ComplianceGuard`
Prevents any claim data from proceeding if mandatory compliance checks have not passed.

- If `PiiRedactionAgent` failed on any mandatory field → **Hard block**
- If `StatutoryDeadlineAgent` returned `urgencyFlag = CRITICAL` → **Force human review**
- If redaction manifest is missing or incomplete → **Block + alert**

#### 5. `FraudPatternGuard`
Detects combinations of signals that statistically indicate potential fraud before STP executes.

| Pattern | Signal Combination | Action |
|---|---|---|
| Low Confidence + High Reserve | `confidence < 0.55` AND `reserve > $15,000` | SIU referral |
| Repeat Claimant | Same policy + loss type within 90 days | Flag + manual review |
| Velocity Anomaly | > 3 claims from same policy in 6 months | SIU referral |

### Guardrail Execution Flow

```
Branch A Result ─────┐
                      ├──► GuardrailChain.evaluate()
 Branch B Result ────┘         │
                          ┌────┴─────────────────────┐
                          │  OutputSchemaGuard        │
                          │  ConfidenceThresholdGuard │
                          │  BusinessRuleGuard        │
                          │  ComplianceGuard          │
                          │  FraudPatternGuard        │
                          └────┬─────────────────────┘
                    ALL PASS   │   ANY FAIL
                  ─────────────┴──────────────
                  │                           │
                  ▼                           ▼
        Reconciliation Gate         Force Escalation
        (STP eligible path)         + Violation logged
                                    + Guardrail reason
                                      code in audit trail
```

### Guardrail Violation — Audit Record Example

```json
{
  "auditId": "grd-8821-ffab",
  "claimId": "CLM-2026-00847",
  "agentId": "VISION_DAMAGE_AGENT",
  "guardrailTriggered": "ConfidenceThresholdGuard",
  "violationReason": "Confidence 0.48 below minimum threshold 0.70",
  "actionTaken": "FORCE_ESCALATION",
  "stpBlocked": true,
  "timestamp": "2026-08-18T16:45:22Z"
}
```

### Maven Module: `fnol-guardrails`

```
fnol-guardrails/
└── src/main/java/com/guidewire/fnol/guardrails/
    ├── GuardrailChain.java            ← Executes all guards in sequence
    ├── GuardrailResult.java           ← PASS | BLOCK + reason codes
    ├── OutputSchemaGuard.java
    ├── ConfidenceThresholdGuard.java
    ├── BusinessRuleGuard.java
    ├── ComplianceGuard.java
    └── FraudPatternGuard.java
```

---

## 🚦 Reconciliation Gate — STP Decision Logic

```
IF (
    branchA.coverageStatus       == "VALID"        AND
    branchA.damageConfidence     >= 0.85            AND
    branchB.complianceFlag       == "CLEAR"         AND
    branchB.subrogationScore     <  70              AND
    branchA.initialReserveUsd    <= 25000.00
)
THEN → Execute STP
       ├── POST /cc/claims         (Create claim)
       ├── PUT  /cc/claims/{id}/reserve  (Set reserve)
       └── Write audit trail

ELSE → Escalate to Human Adjuster
       ├── Reason codes attached
       ├── Pre-compiled legal notes
       └── SIU referral if fraud indicators present
```

### SIU Escalation Triggers

| Trigger | Condition |
|---|---|
| Low Confidence | `damageConfidence < 0.50` with reserve > $15,000 |
| High Subrogation | `subrogationScore > 85` |
| Deadline Critical | `statutoryUrgency == "CRITICAL"` |
| PII Redaction Failure | Required field redaction error |
| High-Value Claim | `estimatedReserve > $50,000` |

---

## 📚 Claim History Context Enrichment

Before either branch fires, the **`ClaimHistoryAgent`** (part of the `fnol-enrichment` module) queries historical data for the incoming policy and enriches the `FNOLContext` object that both Branch A and Branch B receive. This makes every downstream AI decision **context-aware** rather than treating each FNOL in isolation.

### What Gets Fetched

| Data Point | Source | Used By |
|---|---|---|
| Previous claims on this policy | Mock ClaimCenter history endpoint | FraudPatternGuard, ReserveCalculatorAgent |
| Claim frequency (rolling 6 / 12 months) | PostgreSQL aggregation | FraudPatternGuard, SubrogationScorerAgent |
| Prior at-fault incidents | Mock PolicyCenter incident log | SubrogationScorerAgent, VisionDamageAgent context |
| Policy amendment history | Mock PolicyCenter policy log | PolicyValidatorAgent |
| Open / pending claims on same policy | Mock ClaimCenter | BusinessRuleGuard (blocks STP if open claim exists) |

### Enriched FNOLContext — Sample Payload

```json
{
  "policyId": "POL-AUTO-112233",
  "enrichedAt": "2026-08-18T16:44:58Z",
  "claimHistory": {
    "totalPriorClaims": 1,
    "claimsLast6Months": 0,
    "claimsLast12Months": 1,
    "lastClaimDate": "2024-03-10",
    "lastClaimType": "COLLISION",
    "lastClaimAmount": 4200.00,
    "openClaimsCount": 0
  },
  "policyHistory": {
    "policyStanding": "GOOD",
    "yearsActive": 5,
    "priorAtFaultIncidents": 0,
    "lastAmendmentDate": "2025-01-01",
    "amendmentReason": "RENEWAL"
  },
  "riskSignals": {
    "claimFrequencyRisk": "LOW",
    "velocityFlag": false,
    "repeatLossTypeFlag": false
  }
}
```

### Impact on Agent Decisions

- **`VisionDamageAgent`** — receives prior damage photos for the same vehicle as reference context in its prompt
- **`ReserveCalculatorAgent`** — adjusts ULAE buffer upward if claim frequency is HIGH
- **`FraudPatternGuard`** — uses `claimsLast6Months` and `repeatLossTypeFlag` directly
- **`SubrogationScorerAgent`** — prior at-fault history informs third-party liability weighting

---

## 💬 Decision Explainability

Every decision this engine makes — whether STP, escalation, or guardrail block — is accompanied by a structured **Explanation Object** that surfaces exactly *why* the system decided what it did. This is surfaced on the Adjuster Dashboard, in the API response, and in the audit trail.

> The system never simply says **HUMAN REVIEW**. It always says **HUMAN REVIEW + why**.

### Explanation Object Structure

```json
{
  "decision": "HUMAN_REVIEW",
  "summary": "Claim escalated to human adjuster. Three conditions prevented straight-through processing.",
  "factors": [
    {
      "factor": "AI Confidence",
      "value": "62%",
      "threshold": "85% required for STP",
      "status": "FAIL",
      "detail": "Image quality insufficient for high-confidence damage assessment."
    },
    {
      "factor": "Claim Amount",
      "value": "$31,400 estimated reserve",
      "threshold": "$25,000 STP ceiling",
      "status": "FAIL",
      "detail": "High-value claim requires adjuster sign-off per business rule BR-007."
    },
    {
      "factor": "Third-Party Involvement",
      "value": "Subrogation score: 74",
      "threshold": "Score < 70 required for STP",
      "status": "FAIL",
      "detail": "Possible third-party liability detected. Legal review recommended before reserve is set."
    },
    {
      "factor": "Coverage Check",
      "value": "VALID",
      "status": "PASS",
      "detail": "Collision coverage active, deductible $1,000 applied."
    },
    {
      "factor": "Compliance Check",
      "value": "CLEAR — PII redacted (2 entities)",
      "status": "PASS",
      "detail": "All mandatory fields redacted. GDPR Article 25 satisfied."
    }
  ],
  "recommendedAction": "Assign to senior adjuster — high-value claim with third-party exposure.",
  "escalationPriority": "HIGH",
  "auditReference": "audit-trail-CLM-2026-00847"
}
```

### How It Appears in the Adjuster Dashboard

```
┌──────────────────────────────────────────────────────────────┐
│  CLM-2026-00847  ·  COLLISION  ·  2026-08-15  ·  CA          │
│  ⚠️  HUMAN REVIEW REQUIRED                                    │
├──────────────────────────────────────────────────────────────┤
│  WHY THIS DECISION?                                          │
│                                                              │
│  ✗  AI Confidence      62%  (need ≥ 85% for STP)            │
│  ✗  Claim Amount       $31,400  (STP ceiling: $25,000)       │
│  ✗  Third-Party Risk   Subrogation score 74  (threshold: 70) │
│  ✓  Coverage           VALID — Collision covered             │
│  ✓  Compliance         CLEAR — PII redacted                  │
│                                                              │
│  Recommended: Senior adjuster — high-value + 3rd party       │
│  Priority: HIGH  ·  Deadline: 2026-09-02 (acknowledge by)    │
├──────────────────────────────────────────────────────────────┤
│  [ Approve STP ]    [ Refer to SIU ]    [ View Audit Trail ] │
└──────────────────────────────────────────────────────────────┘
```

### API Endpoint

```bash
GET /api/v1/fnol/{claimId}/explain
```

Returns the full `ExplanationObject` for any processed claim, independently queryable for UI rendering and audit purposes.

---

## 🎬 Demo Scenarios

The demo is designed to walk evaluators through **five distinct scenarios** — one happy path and four failure paths — to demonstrate the system's intelligence, resilience, and safety characteristics.

---

### ✅ Scenario 1 — Happy Path: Clean STP

> *"A straightforward collision claim with good photos, valid coverage, and a low reserve — processed in 11 seconds with no human touch."*

| Signal | Value |
|---|---|
| Damage Confidence | 91% |
| Coverage | VALID |
| Reserve | $11,730 |
| Subrogation Score | 42 |
| Compliance | CLEAR |
| **Decision** | **STRAIGHT_THROUGH_PROCESSING** |

**Demo trigger:** Submit `demo-scenarios/scenario_1_clean_stp.json` with a clear damage photo.

---

### ⚠️ Scenario 2 — Low AI Confidence → Human Review

> *"A blurry dashcam still and partial vehicle visibility drops confidence to 62%. The system refuses to auto-reserve and escalates with a full explanation."*

| Signal | Value |
|---|---|
| Damage Confidence | 62% *(below 85% STP threshold)* |
| Reserve Estimate | $8,200 |
| Guardrail Triggered | `ConfidenceThresholdGuard` |
| **Decision** | **HUMAN_REVIEW** |

**Explanation shown:**
```
✗  AI Confidence: 62% — image quality too low for STP
✓  Coverage: VALID
✓  Compliance: CLEAR
✓  Reserve: within threshold
→  Escalated: adjuster to re-assess with higher-quality photos
```

**Demo trigger:** Submit `demo-scenarios/scenario_2_low_confidence.json` with a blurry test image.

---

### 🛑 Scenario 3 — Compliance Block → Human Review

> *"PII redaction fails on a medical record number embedded in claimant notes. The ComplianceGuard hard-blocks the entire claim — no data is persisted until the field is clean."*

| Signal | Value |
|---|---|
| PII Detected | Medical Record # in notes field |
| Redaction Confidence | 61% *(below 90% hard threshold)* |
| Guardrail Triggered | `ComplianceGuard` |
| **Decision** | **HARD_BLOCK → HUMAN_REVIEW** |

**Explanation shown:**
```
✗  Compliance: FAIL — PII redaction confidence 61% on 'claimantNotes'
   Entity: MEDICAL_RECORD_NUMBER (score: 0.61, threshold: 0.90)
→  Hard block: no data persisted. Manual redaction required.
→  GDPR Article 25 violation prevented.
```

**Demo trigger:** Submit `demo-scenarios/scenario_3_compliance_block.json` with raw medical data in notes.

---

### ⏱️ Scenario 4 — API Timeout → Human Review

> *"The mock PolicyCenter API simulates a 10-second timeout. The system detects the failure, records it in the audit trail, and safely escalates rather than proceeding with incomplete data."*

| Signal | Value |
|---|---|
| PolicyCenter Response | TIMEOUT (10s) |
| Coverage Status | UNKNOWN |
| Guardrail Triggered | `BusinessRuleGuard` — coverage unverified |
| **Decision** | **HUMAN_REVIEW** |

**Explanation shown:**
```
✗  Coverage: UNKNOWN — PolicyCenter API timed out after 10s
→  System cannot verify coverage without policy data
→  Escalated: adjuster to manually verify POL-AUTO-112233
→  Timeout event logged to audit trail
```

**Demo trigger:** Set `MOCK_GW_TIMEOUT=true` in `.env` before submitting `scenario_4_api_timeout.json`.

---

### 🔁 Scenario 5 — Duplicate Event → Idempotency Block (No Duplicate Payment)

> *"The same FNOL reference is submitted twice (simulating a network retry). The system detects the duplicate via idempotency key check and rejects the second submission — no duplicate claim or reserve is created."*

| Signal | Value |
|---|---|
| FNOL Reference | `FNOL-20260818-9923` (already processed) |
| Idempotency Check | DUPLICATE DETECTED |
| **Decision** | **REJECTED — 409 Conflict** |

**Response:**
```json
{
  "status": 409,
  "error": "DUPLICATE_FNOL_REFERENCE",
  "message": "FNOL reference FNOL-20260818-9923 was already processed on 2026-08-18T16:45:00Z.",
  "existingClaimId": "CLM-2026-00847",
  "action": "No new claim or reserve created. Original claim unchanged."
}
```

**Demo trigger:** Submit the same payload twice in sequence using `demo-scenarios/scenario_5_duplicate.sh`.

---

### Demo Scenario Summary

| # | Scenario | Key Signal | Decision |
|---|---|---|---|
| 1 | Clean STP | Confidence 91%, all pass | ✅ STRAIGHT_THROUGH_PROCESSING |
| 2 | Low AI Confidence | Confidence 62% | ⚠️ HUMAN_REVIEW |
| 3 | Compliance Block | PII redaction fail | 🛑 HARD_BLOCK → HUMAN_REVIEW |
| 4 | API Timeout | PolicyCenter timeout | ⏱️ HUMAN_REVIEW |
| 5 | Duplicate Event | Same FNOL ref | 🔁 409 REJECTED |

---

## 🗄️ Database Schema

### Tables

| Table | Purpose |
|---|---|
| `claims` | Core claim records |
| `audit_trail` | Immutable per-agent decision log (NAIC compliant) |
| `pii_redaction_log` | GDPR/CCPA redaction records |
| `statutory_deadlines` | State deadline tracking per claim |

Migrations managed by **Flyway** — scripts located in `fnol-api/src/main/resources/db/migration/`.

---

## 🏛️ Guidewire Alignment Notes

This project is built to mirror how real Guidewire Cloud integrations are developed:

| Guidewire Pattern | Our Implementation |
|---|---|
| App Events (pub/sub fan-out) | `Mono.zip()` reactive parallel execution |
| Cloud API REST surface | WireMock stubs matching official GW JSON schemas |
| Integration Plugin architecture | Maven module-per-concern (`fnol-agents`, `fnol-orchestrator`) |
| Jutro Digital Portal | React + Vite simulating Jutro component patterns |
| Zero Gosu core modifications | 100% REST API surface — no internal GW code touched |
| Flyway-style migrations | Flyway SQL scripts in Spring Boot module |
| Validation Framework | `fnol-guardrails` module — multi-tier guard chain before any financial action |
| Claim 360 Context | `fnol-enrichment` module mirrors GW's Claim 360 pre-enrichment pattern |
| Decision Explainability | Structured `ExplanationObject` on every decision — satisfies GW audit requirements |
| Idempotency | Duplicate FNOL detection via reference key — mirrors GW's event deduplication model |

---

## 📁 Repository Structure

```
.
├── README.md
├── COMPLIANCE.md                    ← GDPR / CCPA / NAIC compliance notes
├── pom.xml                          ← Parent Maven POM
├── docker-compose.yml
├── .env.example
├── .gitignore
│
├── fnol-common/                     ← Shared DTOs & enums
├── fnol-mock-guidewire/             ← Mock ClaimCenter + PolicyCenter
├── fnol-enrichment/                 ← Claim History Context Enricher
├── fnol-agents/                     ← AI agent implementations
├── fnol-guardrails/                 ← AI Guardrail Layer (5-guard chain)
├── fnol-orchestrator/               ← Async fan-out + gate
├── fnol-api/                        ← Main Spring Boot app
├── fnol-frontend/                   ← React + Vite app
│
└── demo-scenarios/                  ← Pre-built demo payloads (5 scenarios)
│
└── docs/
    ├── PRD.md                       ← Full Product Requirement Document
    ├── SPRINT_PLAN.md               ← 4-week sprint breakdown
    ├── ARCHITECTURE.md              ← Detailed architecture notes
    └── sample-images/               ← Test damage photos for demo
```

---

## 📜 License

This project is submitted for academic/competition purposes for the **Guidewire DEV-Summit**.  
All Guidewire API schemas referenced are based on publicly available Guidewire Cloud API documentation.

---

<div align="center">

**Built with ☕ Java · 🍃 Spring · ⚛️ React · 🤖 LangChain4j**  
*Guidewire DEV-Summit 2026*

</div>
