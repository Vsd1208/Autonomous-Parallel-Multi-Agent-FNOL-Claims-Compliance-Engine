# FNOL Intelligence Platform — System Architecture

## Overview
The **Autonomous Parallel Multi-Agent FNOL Claims & Compliance Engine** is a decision-support platform for P&C First Notice of Loss. It runs operational analysis (damage, coverage, reserve) and legal analysis (PII, deadlines) in parallel, reconciles them through a safety gate that explains its reasoning, pre-files the claim in ClaimCenter, and gives an adjuster a console and a queue for the final decision. Failed intakes are saved and escalated rather than dropped.

## Request flow

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
       ClaimCenter createClaim as DRAFT_PENDING_REVIEW (OPEN only for unreviewed STP)
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
        -> ClaimCenter PUT /cc/claims/{id}: OPEN | DENIED | PENDING_INFORMATION | UNDER_INVESTIGATION | CLOSED,
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
| `fnol-api` | REST controller, error mapping, adjuster-token and CORS filters, `AuditService` (hash chain), `FailedIntakeRecorder`, `DuplicateSubmissionRecorder`, `IdempotencyStore`, JPA entities, Flyway V1–V7, PDF report |
| `frontend` | React + Vite adjuster workspace (`src/`: internal intake, claim dashboard, queue, audit trail) and policyholder portal (`portal/`: report, track, add requested photos; built without the adjuster token) |

## Human-in-the-loop
The platform recommends; adjusters decide. With the default `fnol.stp.require-advisory-review=true`, every claim — including STP-eligible ones — waits in the adjuster queue for a human decision. Turning the flag off lets STP-eligible claims be filed without a human step; that is a business decision, see ADR-009. Statutory deadlines, coverage rules and subrogation weights are synthetic prototype values.
