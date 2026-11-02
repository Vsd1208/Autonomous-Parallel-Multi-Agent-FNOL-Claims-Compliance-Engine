# FNOL Intelligence Platform — System Architecture

## Overview
The **Autonomous Parallel Multi-Agent FNOL Claims & Compliance Engine** (autonomous analysis, human decision; the terms are defined in [agent-architecture.md](agent-architecture.md)) is a decision-support platform for P&C First Notice of Loss. It runs operational analysis (damage, coverage, reserve) and legal analysis (PII, deadlines) in parallel, reconciles them through a safety gate that explains its reasoning, pre-files the claim in ClaimCenter, and gives an adjuster a console and a queue for the final decision. Failed intakes are saved and escalated rather than dropped.

## How a claim flows

The claims system (mock ClaimCenter) owns the claim, the work queue and the decision; the engine is an extension of it
(ADR-012, [guidewire-integration.md](guidewire-integration.md)). The analysis below is the same on either path.

```
  Portal / phone intake  POST /portal/claims, POST /fnol/report
        |  InputGuardrail ............................................ 400, nothing filed
        v
  Claims system: draft (draft number) -> duplicate check (409) -> submit: OPEN (open claim number)
        |  outbox: claim.submitted, signed, delivered at least once
        v
  ClaimEventController (401 bad signature; repeats ignored) -> FNOLOrchestrator (below), on the existing claim
        |  written back: estimate, exposure with no reserve, activities (the recommended reserve), note, documents
        |  failure: claim stays in review with a high-priority activity; recorded and audited
        v
  Analysis record + audit saved -> only then the claim's work queue is set

  Adjuster: GET /fnol/queue (read from the claims system, with the AI's flags)
     POST /fnol/{id}/review -> claims system first (503 if down, nothing recorded; 409 if closed)
        |  an approval posts the reserve: the adjuster's figure, or the recommendation they approved
        |  outbox: claim.decided (also for a decision made directly in the claims system)
        v
  ClaimDecisionRecorder: analysis record, audit chain, feedback loop; counted once by the decision's time
```

## Analysis flow (direct intake shown)

`POST /fnol/intake` runs this inline and then creates the claim. Without `CLAIMS_EVENT_SECRET` it is the only path.

```
  React console (Vite)                        X-Adjuster-Token on review / audit / explanation / queue
        |  POST /api/v1/fnol/intake
        v
  FNOLController ── duplicate check (IdempotencyStore: in-memory + DB, REJ- rows ignored) ──> 409
        |
        v
  FNOLOrchestrator
    1. InputGuardrail ............................................ 400 VALIDATION_ERROR
    2. ClaimHistoryEnricher (PolicyCenter /history) -> frequency risk
       PolicyCenter getPolicy ....................................... unknown policy -> NOT ELIGIBLE
    3. IntakeReviewService (Human Review Point 1) -> inputFlags
    4. Bounded executor (pool 8, queue 32), one 10 s deadline, interrupt on timeout
          |                                         |
     BRANCH A  Operational                     BRANCH B  Legal
     VisionDamageAgent (Gemini | mock)         PiiRedactionAgent -> PIIGuardrail ...... 422 BLOCK
       └ EvidenceImageLoader (SSRF guard)      StatutoryDeadlineAgent (ack / decide / urgency)
     PolicyValidatorAgent + CoverageRuleProvider   SubrogationScorerAgent.assessLiability
       └ ValidationGuardrail ............ 502       (liability + compliance notes)
     ReserveCalculatorAgent (reserve + confidence)
          |                                         |
          +-------------------+---------------------+
                              v
    5. Reconciliation Safety Gate
       Subrogation finalised with Branch A severity -> OutputGuardrail
       GateConsistencyChecker -> gateFlags (severity/estimate, reserve vs limit, damage vs line, confidence vs photo)
       ExplanationBuilder: 6 factors + severity (fnol.stp.*)  -> WHY panel
       Route: STP-eligible and no flags -> PENDING_ADVISORY_REVIEW (default) | STRAIGHT_THROUGH_PROCESSING
              otherwise                 -> HUMAN_REVIEW
       ClaimCenter createClaim as OPEN, with no reserve (posted only for unreviewed STP, ADR-009)
                   + attach WHY panel and evidence list (best effort)
       AuditTrailAgent: 7 hashed records (sanitized inputs only)
                              |
                              v
  FNOLController: CLAIM_ID_CONFLICT check -> save claim -> AuditService.append (hash chain)

  Failure lane: 422 / 502 (not eligible or any processing error) / 504 (deadline, full executor, Guidewire timeout)
     -> FailedIntakeRecorder: REJ-xxxxxxxx claim (BLOCKED_COMPLIANCE | HUMAN_REVIEW),
        failure_reason, "Intake" audit event; HTTP error returned with claimId

  Duplicate lane: 409 DUPLICATE_FNOL -> original claim flagged DUPLICATE_SUBMISSION + DuplicateCheck audit
     (an unreviewed STP original goes back to HUMAN_REVIEW for verification)

  Adjuster (Human Review Points 2 and 3)
     GET /fnol/queue  ->  console claim page  ->  POST /fnol/{id}/review
        decision (approve / reject / more info / investigation / close), optional adjusted reserve + reason
        state check (409 INVALID_STATE) -> conditional update -> AdjusterReview audit event
        -> ClaimCenter PUT /cc/claims/{id}: OPEN, or CLOSED on a denial or closure; approval posts the reserve;
           tracked as a review action (failure => cc_sync_status=FAILED, review still succeeds)
     GET /fnol/{id}/audit (chainValid)  ·  GET /fnol/{id}/audit/pdf  ·  GET /fnol/{id}/explanation
```

## Maven modules

| Module | Responsibility |
|---|---|
| `fnol-common` | Records in `Models.java`; `ComplianceBlockException`, `CoverageNotEligibleException`, `ProcessingTimeoutException` |
| `fnol-mock-guidewire` | Spring Boot service on 9090 imitating PolicyCenter and ClaimCenter (create, read, update, attachments) with failure switches |
| `fnol-enrichment` | `PolicyCenterClient`, `ClaimCenterClient`, their HTTP mock clients, `ClaimHistoryEnricher`, the primary Guidewire `RestTemplate` (2 s / 3 s) |
| `fnol-agents` | Agents, vision providers, `EvidenceImageLoader`, deadline / coverage / subrogation providers, Gemini `RestTemplate` |
| `fnol-guardrails` | Input, output, PII and validation guardrails |
| `fnol-orchestrator` | `FNOLOrchestrator`, `IntakeReviewService`, `GateConsistencyChecker`, `ExplanationBuilder`, `ClaimTypeValidator`, `OrchestratorProperties`, `StpProperties` |
| `fnol-api` | REST controller, error mapping, adjuster-token and CORS filters, `AuditService` (hash chain), `FailedIntakeRecorder`, `DuplicateSubmissionRecorder`, `IdempotencyStore`, JPA entities, Flyway V1–V17, PDF report |
| `frontend` | React + Vite adjuster workspace (`src/`: internal intake, claim dashboard, queue, audit trail) and policyholder portal (`portal/`: report, track, add requested photos; built without the adjuster token) |

## Human-in-the-loop
The platform recommends; adjusters decide. With the default `fnol.stp.require-advisory-review=true`, every claim — including STP-eligible ones — waits in the adjuster queue for a human decision. Turning the flag off lets STP-eligible claims be filed without a human step; that is a business decision, see ADR-009. Coverage rules and subrogation weights are synthetic prototype values; the state claim clock cites real regulations but has not been legally reviewed ([legal-compliance.md](legal-compliance.md)).
