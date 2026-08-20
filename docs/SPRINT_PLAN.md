# ??? Sprint Plan — Autonomous Parallel Multi-Agent FNOL Claims & Compliance Engine

> **Team Size:** 3 Students
> **Total Duration:** 4 Weeks
> **Methodology:** Agile Scrum (1-week sprints)
> **Submission Target:** Guidewire DEV-Summit

---

## Sprint Overview

| Sprint | Name | Theme | Week | Key Deliverable |
|---|---|---|---|---|
| Sprint 1 | **Blueprint** | Foundation & Scaffold | Week 1 | Running skeleton + Mock APIs live |
| Sprint 2 | **Dual Brain** | AI Agent Development | Week 2 | Vision + Compliance agents functional |
| Sprint 3 | **Safety Wall** | Guardrails + Parallel Engine | Week 3 | End-to-end flow under 15 seconds |
| Sprint 4 | **Show Time** | Polish, Demo & Pitch | Week 4 | Live demo + GitHub release + Pitch deck |

---

## Sprint 1 — "Blueprint"
> *Lay the architectural foundation. Nothing works until everything is scaffolded.*

**Duration:** Week 1
**Sprint Goal:** All three students have a running local environment. The Maven multi-module project is bootstrapped, mock Guidewire APIs are returning data, and the React app loads.

### Epics
- `EP-01` Project Initialization & Repository Setup
- `EP-02` Maven Multi-Module Scaffold
- `EP-03` Mock Guidewire API Server
- `EP-04` React Frontend Skeleton

### User Stories

| ID | Story | Assignee | Points |
|---|---|---|---|
| US-101 | As a dev, I want a working `docker compose up` that starts all services | Student 2 | 5 |
| US-102 | As a dev, I want `GET /api/v1/health` returning `{ status: UP }` | Student 2 | 2 |
| US-103 | As a dev, I want mock PolicyCenter returning coverage data for 3 test policies | Student 2 | 3 |
| US-104 | As a dev, I want mock ClaimCenter accepting `POST /cc/claims` and returning a claim ID | Student 2 | 3 |
| US-105 | As a user, I want to see the FNOL intake form render at `localhost:5173` | Student 1 | 3 |
| US-106 | As a dev, I want POJOs/DTOs for `FNOLPayload`, `BranchAResult`, `BranchBResult` | Student 3 | 3 |
| US-107 | As a dev, I want a PostgreSQL `claims` table created via Flyway migration | Student 2 | 2 |
| US-108 | As a dev, I want a `.env.example` with all required environment variables documented | Student 1 | 1 |

### Definition of Done
- [ ] `mvn clean install` passes with zero errors
- [ ] `docker compose up --build` starts all 4 services cleanly
- [ ] `GET http://localhost:9090/pc/policies/POL-AUTO-112233/coverages` returns valid JSON
- [ ] React app loads at `localhost:5173` without console errors
- [ ] Postman collection exported with all 8 planned endpoints

### Technical Tasks
```
Week 1 Checklist:
+-- Create GitHub repo + branch strategy (main / develop / feature/*)
+-- Set up Maven parent POM with Spring Boot 3.2 BOM
+-- Scaffold 7 Maven modules (common, mock-guidewire, agents, guardrails,
¦   enrichment, orchestrator, api, frontend)
+-- Configure Docker Compose (postgres, fnol-api, fnol-mock-guidewire, frontend)
+-- Seed mock data: 3 policies + 1 existing claim + 2 policy histories
+-- Set up Flyway with V1__init_claims_schema.sql
+-- Bootstrap React + Vite with Tailwind CSS
+-- Write README "Getting Started" section (already done ?)
```

---

## Sprint 2 — "Dual Brain"
> *Build the two AI agent branches independently. Make each agent testable in isolation before wiring them together.*

**Duration:** Week 2
**Sprint Goal:** Branch A (Vision + Policy + Reserve) and Branch B (PII + Deadlines + Subrogation + Audit) are fully functional as standalone modules with unit tests passing.

### Epics
- `EP-05` Branch A — Operational Agent Suite
- `EP-06` Branch B — Legal & Compliance Agent Suite
- `EP-07` FNOL Intake Endpoint (Sequential v1)
- `EP-08` Frontend — Intake Form Wired + Status Page

### User Stories

| ID | Story | Assignee | Points |
|---|---|---|---|
| US-201 | As the system, I want `VisionDamageAgent` to return structured damage JSON from a photo | Student 3 | 8 |
| US-202 | As the system, I want `PolicyValidatorAgent` to call mock PolicyCenter and return coverage status | Student 3 | 5 |
| US-203 | As the system, I want `ReserveCalculatorAgent` to compute `reserve = damage x 1.15` | Student 3 | 3 |
| US-204 | As the system, I want `PiiRedactionAgent` to detect and mask SSN, DOB, Full Name | Student 3 | 5 |
| US-205 | As the system, I want `StatutoryDeadlineAgent` to return deadline dates for CA, NY, TX, FL, IL | Student 3 | 5 |
| US-206 | As the system, I want `SubrogationScorerAgent` to return a 0-100 score + action | Student 3 | 5 |
| US-207 | As the system, I want `AuditTrailAgent` to write a SHA-256 hashed record to PostgreSQL | Student 3 | 5 |
| US-208 | As a user, I want to submit the FNOL form and receive a `claim_id` in response | Student 1 | 5 |
| US-209 | As a user, I want to see the claim status page polling `/status/{claimId}` | Student 1 | 3 |
| US-210 | As a dev, I want `POST /api/v1/fnol/intake` to call agents sequentially and return combined JSON | Student 2 | 8 |

### Definition of Done
- [ ] `VisionDamageAgent` returns valid JSON on 3 different test images
- [ ] `PiiRedactionAgent` masks all 6 entity types in test text
- [ ] `StatutoryDeadlineAgent` returns correct deadlines for all 5 states
- [ ] `POST /api/v1/fnol/intake` returns 200 OK with `branchA` + `branchB` blocks
- [ ] Unit tests pass for all 7 agent classes (aim >= 80% coverage)
- [ ] FNOL form submission navigates to status page

### Technical Tasks
```
Week 2 Checklist:
+-- Branch A
¦   +-- VisionDamageAgent.java (LangChain4j + GPT-4o Vision)
¦   +-- PolicyValidatorAgent.java (WebClient ? mock PC API)
¦   +-- ReserveCalculatorAgent.java (formula + vendor dispatch)
+-- Branch B
¦   +-- PiiRedactionAgent.java (Regex + LLM structured output)
¦   +-- StatutoryDeadlineAgent.java (state rules engine)
¦   +-- SubrogationScorerAgent.java (LLM prompt scoring)
¦   +-- AuditTrailAgent.java (SHA-256 + JPA write)
+-- fnol-api: POST /intake ? sequential agent calls
+-- React: wire form submit + status polling page
+-- Write agent unit tests (JUnit 5 + Mockito)
```

---

## Sprint 3 — "Safety Wall"
> *Wire the two branches in parallel. Insert the Guardrail Layer. Implement the Reconciliation Gate. Add Claim History Enrichment.*

**Duration:** Week 3
**Sprint Goal:** Full end-to-end async pipeline operational. Both branches fire simultaneously via `Mono.zip()`. The Guardrail Chain validates all outputs. The Reconciliation Gate decides STP vs. Escalation. Target: < 15 seconds.

### Epics
- `EP-09` Async Fan-Out Engine (Mono.zip())
- `EP-10` AI Guardrail Layer (5 guards)
- `EP-11` Reconciliation Gate + STP Execution
- `EP-12` Claim History Context Enricher
- `EP-13` Decision Explainability Object

### User Stories

| ID | Story | Assignee | Points |
|---|---|---|---|
| US-301 | As the system, I want Branch A and Branch B to fire simultaneously using `Mono.zip()` | Student 2 | 8 |
| US-302 | As the system, I want `OutputSchemaGuard` to block malformed agent responses | Student 3 | 5 |
| US-303 | As the system, I want `ConfidenceThresholdGuard` to block claims below per-agent thresholds | Student 3 | 5 |
| US-304 | As the system, I want `BusinessRuleGuard` to block reserves > $50,000 from STP | Student 3 | 3 |
| US-305 | As the system, I want `ComplianceGuard` to hard-block if PII redaction confidence < 90% | Student 3 | 5 |
| US-306 | As the system, I want `FraudPatternGuard` to trigger SIU referral on velocity anomalies | Student 3 | 5 |
| US-307 | As the system, I want `ReconciliationGate` to execute STP when all 5 conditions pass | Student 2 | 8 |
| US-308 | As the system, I want STP to auto-call `POST /cc/claims` + `PUT /cc/claims/{id}/reserve` | Student 2 | 5 |
| US-309 | As the system, I want `ClaimHistoryAgent` to fetch prior claims and policy history pre-fan-out | Student 3 | 5 |
| US-310 | As the system, I want every decision to produce a structured `ExplanationObject` | Student 2 | 5 |
| US-311 | As a user, I want `GET /api/v1/fnol/{id}/explain` to return the decision explanation | Student 2 | 3 |
| US-312 | As a user, I want the Adjuster Dashboard to show WHY THIS DECISION breakdown | Student 1 | 8 |
| US-313 | As a dev, I want end-to-end latency confirmed < 15 seconds on STP path | Student 2 | 3 |

### Definition of Done
- [ ] Mono.zip() fan-out confirmed in logs (both branches start simultaneously)
- [ ] All 5 guardrails tested individually + as a chain
- [ ] STP path creates a claim in mock ClaimCenter and sets reserve
- [ ] Escalation path populates the Adjuster Dashboard with explanation
- [ ] End-to-end STP latency < 15,000ms (verified with Spring metrics)
- [ ] /explain endpoint returns correct ExplanationObject for both STP and HUMAN_REVIEW

### Technical Tasks
```
Week 3 Checklist:
+-- FNOLOrchestrator.java: refactor to Mono.zip() parallel fan-out
+-- fnol-guardrails module:
¦   +-- GuardrailChain.java
¦   +-- OutputSchemaGuard.java
¦   +-- ConfidenceThresholdGuard.java
¦   +-- BusinessRuleGuard.java
¦   +-- ComplianceGuard.java
¦   +-- FraudPatternGuard.java
+-- ReconciliationGate.java: STP conditions + escalation
+-- ClaimHistoryAgent.java: fnol-enrichment module
+-- ExplanationBuilder.java: structured ExplanationObject
+-- AdjusterDashboard.jsx: WHY THIS DECISION panel
+-- GET /explain endpoint
+-- Performance test: measure P95 latency on STP path
```

---

## Sprint 4 — "Show Time"
> *Polish the demo. Build the 5 failure scenarios. Deploy. Record the walkthrough. Prepare the pitch.*

**Duration:** Week 4
**Sprint Goal:** System is demo-ready with 5 runnable scenarios, deployed to a live URL, GitHub tagged at v1.0.0, and the team can deliver a compelling 10-minute pitch.

### Epics
- `EP-14` Demo Scenario Suite (5 scenarios)
- `EP-15` UI Polish & Audit Viewer
- `EP-16` Deployment & GitHub Release
- `EP-17` Pitch Deck & Demo Video

### User Stories

| ID | Story | Assignee | Points |
|---|---|---|---|
| US-401 | As a demo, I want Scenario 1 (Clean STP) submittable via one command | Student 2 | 3 |
| US-402 | As a demo, I want Scenario 2 (Low Confidence) to trigger ConfidenceThresholdGuard | Student 2 | 3 |
| US-403 | As a demo, I want Scenario 3 (Compliance Block) to hard-block with PII explanation | Student 2 | 3 |
| US-404 | As a demo, I want Scenario 4 (API Timeout) via MOCK_GW_TIMEOUT=true flag | Student 2 | 3 |
| US-405 | As a demo, I want Scenario 5 (Duplicate Event) to return 409 Conflict on re-submission | Student 2 | 5 |
| US-406 | As a user, I want the Audit Trail Viewer page at `/audit/:claimId` | Student 1 | 5 |
| US-407 | As a user, I want to export the audit trail as a PDF | Student 1 | 3 |
| US-408 | As a dev, I want `docker compose up` to start with demo seed data pre-loaded | Student 2 | 5 |
| US-409 | As a team, I want the project deployed to Railway/Render with a live public URL | Student 2 | 5 |
| US-410 | As a team, I want a `COMPLIANCE.md` explaining GDPR/CCPA/NAIC coverage | Student 3 | 3 |
| US-411 | As a team, I want a 5-minute Loom demo video of the full claim flow | Student 1 | 3 |
| US-412 | As a team, I want a 10-slide pitch deck covering problem, architecture, demo, and ROI | All | 8 |
| US-413 | As a team, I want the GitHub repo tagged `v1.0.0-devsummit` | Student 2 | 1 |

### Definition of Done
- [ ] All 5 demo scenarios run successfully with one command each
- [ ] Audit trail PDF exports cleanly for Scenario 1
- [ ] Live URL accessible (Vercel for frontend, Railway for backend)
- [ ] Postman collection exported and attached to GitHub release
- [ ] Loom video recorded and linked in README
- [ ] Pitch deck covers: Problem, Architecture, Demo, Guardrails, Compliance, ROI, Roadmap
- [ ] GitHub tagged v1.0.0-devsummit

### Technical Tasks
```
Week 4 Checklist:
+-- demo-scenarios/ folder:
¦   +-- scenario_1_clean_stp.json
¦   +-- scenario_2_low_confidence.json
¦   +-- scenario_3_compliance_block.json
¦   +-- scenario_4_api_timeout.json
¦   +-- scenario_5_duplicate.sh
+-- Idempotency key check in POST /intake (409 on duplicate FNOL ref)
+-- MOCK_GW_TIMEOUT flag in mock Guidewire server
+-- AuditViewer.jsx + jsPDF export
+-- Seed script: docker compose with 5 pre-loaded demo claims
+-- Deploy: frontend ? Vercel, backend ? Railway
+-- COMPLIANCE.md
+-- Postman collection export
+-- Loom recording (5 min)
+-- Pitch deck (Google Slides / Canva)
```

---

## Velocity & Story Point Summary

| Sprint | Name | Total Points | Focus |
|---|---|---|---|
| Sprint 1 | Blueprint | 22 pts | Infrastructure |
| Sprint 2 | Dual Brain | 52 pts | AI Agents |
| Sprint 3 | Safety Wall | 69 pts | Architecture |
| Sprint 4 | Show Time | 51 pts | Demo & Pitch |
| **Total** | | **194 pts** | |

---

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| OpenAI API key unavailable | Medium | High | Use MOCK_LLM_ENABLED=true stub mode |
| GPT-4o Vision latency spikes | Medium | Medium | Add 10s timeout + fallback to LOW_CONFIDENCE |
| PolicyCenter mock not matching real schema | Low | Medium | Validate against Guidewire public OpenAPI spec |
| Docker networking issues on demo day | Low | High | Pre-record demo video as backup |
| Deployment quota limits (Railway free tier) | Medium | Low | Keep Docker Compose local as primary demo |

---

## Branch & Git Strategy

```
main                <- production-ready, tagged releases only
  +-- develop       <- integration branch, all sprints merge here
        +-- sprint-1/blueprint      <- Sprint 1 feature branches
        +-- sprint-2/dual-brain     <- Sprint 2 feature branches
        +-- sprint-3/safety-wall    <- Sprint 3 feature branches
        +-- sprint-4/show-time      <- Sprint 4 feature branches
```

### Commit Convention
```
feat:  new feature
fix:   bug fix
docs:  documentation
test:  unit/integration tests
chore: config, build, tooling
demo:  demo scenario files
```

---

## Final Deliverables Checklist

```
? GitHub repo tagged v1.0.0-devsummit
? README.md (architecture + API + guardrails + demo scenarios)
? COMPLIANCE.md (GDPR / CCPA / NAIC)
? SPRINT_PLAN.md (this document)
? docker-compose.yml (one-command startup)
? demo-scenarios/ (5 pre-built scenario payloads)
? Postman collection (.json export)
? Live demo URL (Vercel + Railway)
? 5-min Loom demo video
? Sample audit trail PDF
? 10-slide pitch deck
```

---

*Sprint Plan — Guidewire DEV-Summit 2026*
