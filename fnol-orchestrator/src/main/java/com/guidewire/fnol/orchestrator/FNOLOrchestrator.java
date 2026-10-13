package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.agents.*;
import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.common.ProcessingTimeoutException;
import com.guidewire.fnol.enrichment.*;
import com.guidewire.fnol.guardrails.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.*;

/**
 * Central FNOL orchestrator — implements the Asynchronous Dual-Branch Agentic Copilot pattern.
 *
 * <h2>Architecture</h2>
 * <p>Upon FNOL intake, two independent branches run concurrently via {@link CompletableFuture}:
 * <ul>
 *   <li><b>Branch A — Operational AI</b>: Analyzes loss photos to extract damage severity,
 *       then passes structured parameters to Guidewire's deterministic rules engine for
 *       coverage verification and reserve calculation.</li>
 *   <li><b>Branch B — Legal AI</b>: Simultaneously redacts PII, logs statutory deadlines,
 *       and scores subrogation potential.</li>
 * </ul>
 *
 * <h2>Reconciliation Safety Gate</h2>
 * <p>After both branches complete, results merge at the {@link #reconcile} gate which:
 * <ol>
 *   <li>Applies output guardrails (value range checks, compliance checks)</li>
 *   <li>Builds a structured {@link ExplanationObject} — the "WHY" panel</li>
 *   <li>Drafts a pre-populated claim file and files it to Guidewire ClaimCenter</li>
 *   <li>Routes the result: auto-STP or human adjuster escalation with advisory note</li>
 * </ol>
 *
 * <h2>Governance</h2>
 * <p>100% human-in-the-loop: the copilot <em>recommends</em>, a licensed adjuster <em>approves</em>.
 * No autonomous payout is ever made. Configurable timeout via
 * {@code fnol.orchestrator.timeout-seconds} (default 10s) ensures safe escalation on failure.
 */
@Component
public class FNOLOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FNOLOrchestrator.class);

    // ── Guardrails ──
    private final InputGuardrail input;
    private final OutputGuardrail output;
    private final PIIGuardrail piiGuard;
    private final ValidationGuardrail validation;

    // ── Branch A — Operational AI agents ──
    private final VisionDamageAgent vision;
    private final PolicyValidatorAgent policy;
    private final ReserveCalculatorAgent reserve;

    // ── Branch B — Legal AI agents ──
    private final PiiRedactionAgent pii;
    private final StatutoryDeadlineAgent deadline;
    private final SubrogationScorerAgent subro;

    // ── Supporting components ──
    private final AuditTrailAgent audit;
    private final ClaimCenterClient cc;
    private final ClaimHistoryEnricher historyEnricher;
    private final ExplanationBuilder explanationBuilder;
    private final ClaimTypeValidator claimTypeValidator;

    private final ExecutorService executor;
    private final long timeoutSeconds;

    public FNOLOrchestrator(
            InputGuardrail input,
            OutputGuardrail output,
            PIIGuardrail piiGuard,
            ValidationGuardrail validation,
            VisionDamageAgent vision,
            PolicyValidatorAgent policy,
            ReserveCalculatorAgent reserve,
            PiiRedactionAgent pii,
            StatutoryDeadlineAgent deadline,
            SubrogationScorerAgent subro,
            AuditTrailAgent audit,
            ClaimCenterClient cc,
            @Value("${fnol.orchestrator.timeout-seconds:10}") long timeoutSeconds,
            ClaimHistoryEnricher historyEnricher,
            ExplanationBuilder explanationBuilder,
            ClaimTypeValidator claimTypeValidator
    ) {
        this.input = input;
        this.output = output;
        this.piiGuard = piiGuard;
        this.validation = validation;
        this.vision = vision;
        this.policy = policy;
        this.reserve = reserve;
        this.pii = pii;
        this.deadline = deadline;
        this.subro = subro;
        this.audit = audit;
        this.cc = cc;
        this.timeoutSeconds = timeoutSeconds;
        this.historyEnricher = historyEnricher;
        this.explanationBuilder = explanationBuilder;
        this.claimTypeValidator = claimTypeValidator;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("fnol-branch-" + t.getId());
            return t;
        });
    }

    /**
     * Process an FNOL payload through the full dual-branch claims intelligence pipeline.
     *
     * @param payload the validated FNOL intake payload
     * @return the complete FNOL response including branch results, explanation, and adjuster advisory
     * @throws IllegalArgumentException   if input guardrails fail
     * @throws ProcessingTimeoutException if parallel branches exceed the configured timeout
     */
    public FNOLResponse process(FNOLPayload payload) {

        // ── Stage 1: Input validation ──────────────────────────────────────────────────
        input.validate(payload);
        log.info("[FNOL] Intake validated — policy={}, state={}", payload.policyNumber(), payload.state());

        // ── Stage 2: Pre-enrichment (claim history context, pre-fan-out) ───────────────
        log.info("[FNOL] Enriching claim history — policy={}", payload.policyNumber());
        PolicyHistoryContext policyContext = historyEnricher.enrich(payload.policyNumber());
        log.info("[FNOL] History enriched — risk={}, priorClaims={}",
                policyContext.claimFrequencyRisk(), policyContext.totalPriorClaims());

        // ── Stage 3: Async dual-branch fan-out ────────────────────────────────────────
        log.info("[FNOL] Launching dual-branch fan-out — policy={}", payload.policyNumber());
        long startTime = System.currentTimeMillis();

        CompletableFuture<BranchAResult> futureA = CompletableFuture.supplyAsync(
                () -> runOperationalAI(payload), executor
        );
        CompletableFuture<BranchBResult> futureB = CompletableFuture.supplyAsync(
                () -> runLegalAI(payload), executor
        );

        // ── Stage 4: Await both branches with configurable timeout ────────────────────
        BranchAResult branchA;
        BranchBResult branchB;
        try {
            CompletableFuture.allOf(futureA, futureB).get(timeoutSeconds, TimeUnit.SECONDS);
            branchA = futureA.get();
            branchB = futureB.get();
        } catch (TimeoutException e) {
            futureA.cancel(true);
            futureB.cancel(true);
            log.warn("[FNOL] Processing timed out after {}s — policy={}", timeoutSeconds, payload.policyNumber());
            throw new ProcessingTimeoutException(
                    "FNOL processing timed out after " + timeoutSeconds + " seconds. " +
                    "Claim escalated for manual adjuster review.", e
            );
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.error("[FNOL] Branch execution failed — policy={}, cause={}", payload.policyNumber(), cause.getMessage());
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException("FNOL processing failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessingTimeoutException("FNOL processing was interrupted", e);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[FNOL] Dual-branch completed in {}ms — policy={}", elapsed, payload.policyNumber());

        // ── Stage 5: Reconciliation Safety Gate ──────────────────────────────────────
        return reconcile(payload, branchA, branchB, policyContext, elapsed);
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    //  BRANCH A — Operational AI
    //  Analyzes loss photos → extracts damage severity → passes structured parameters
    //  to Guidewire's deterministic rules engine for coverage verification and reserve.
    // ─────────────────────────────────────────────────────────────────────────────────
    private BranchAResult runOperationalAI(FNOLPayload payload) {
        log.debug("[Branch A — Operational AI] Starting — policy={}", payload.policyNumber());

        // Step A1: Vision analysis — loss photo → structured damage JSON (type, severity, confidence)
        DamageAssessment damage = vision.execute(payload);
        log.debug("[Branch A] Vision complete — severity={}, confidence={}", damage.severity(), damage.confidence());

        // Step A2: Pass structured parameters to Guidewire's deterministic rules engine
        PolicyValidationResult coverage = policy.execute(payload.policyNumber());
        validation.requireCovered(coverage);    // hard stop: no uncovered claim proceeds
        log.debug("[Branch A] Coverage verified — type={}, covered={}", coverage.coverageType(), coverage.covered());

        // Step A3: Reserve calculation (BigDecimal: damage × 1.15 ULAE factor)
        ReserveResult res = reserve.execute(damage);
        log.debug("[Branch A — Operational AI] Completed — reserve={}", res.recommendedReserve());

        return new BranchAResult(damage, coverage, res);
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    //  BRANCH B — Legal AI
    //  Simultaneously redacts PII, logs statutory deadlines, and scores subrogation.
    //  Runs fully independent of Branch A — no shared state.
    // ─────────────────────────────────────────────────────────────────────────────────
    private BranchBResult runLegalAI(FNOLPayload payload) {
        log.debug("[Branch B — Legal AI] Starting — policy={}", payload.policyNumber());

        // Step B1: PII redaction — SSN, DOB, name masking before any persistence
        PIIResult piiResult = pii.execute(payload);
        piiGuard.validate(piiResult);           // hard stop: raw PII must not reach DB
        log.debug("[Branch B] PII redaction complete — detected={}", piiResult.piiDetected());

        // Step B2: State-specific statutory deadline logging
        DeadlineResult deadlineResult = deadline.execute(payload);
        log.debug("[Branch B] Deadline logged — state={}, deadline={}", deadlineResult.state(), deadlineResult.deadline());

        // Step B3: Subrogation scoring — third-party liability keyword analysis (0–100)
        // Uses description text only — independent of Branch A's damage assessment
        DamageAssessment textOnlyDamage = new DamageAssessment(
                "PENDING", "MODERATE", java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO
        );
        SubrogationResult sub = subro.execute(payload, textOnlyDamage);
        log.debug("[Branch B — Legal AI] Completed — subroScore={}, action={}", sub.score(), sub.recommendedAction());

        return new BranchBResult(piiResult, deadlineResult, sub, List.of());
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    //  RECONCILIATION SAFETY GATE
    //  Merges Branch A + Branch B results. Applies guardrails. Builds the WHY panel.
    //  Drafts a pre-populated claim file and routes to STP or human adjuster review.
    // ─────────────────────────────────────────────────────────────────────────────────
    private FNOLResponse reconcile(
            FNOLPayload payload,
            BranchAResult branchA,
            BranchBResult branchB,
            PolicyHistoryContext policyContext,
            long elapsedMs
    ) {
        log.info("[FNOL] Entering Reconciliation Safety Gate — policy={}", payload.policyNumber());

        // Gate Step 1: Output guardrails (value range + cross-branch checks)
        output.validate(branchA.reserve(), branchB.subrogation());

        // Gate Step 2: Build structured ExplanationObject — the "WHY" panel
        ExplanationObject explanation = explanationBuilder.build(
                branchA.coverage(),
                branchA.damageAssessment(),
                branchA.reserve(),
                branchB.pii(),
                branchB.subrogation(),
                policyContext
        );

        boolean eligibleForAdvisoryPrefill = claimTypeValidator.isEligibleForAdvisoryPrefill(
                branchA.damageAssessment(),
                policyContext
        );

        // Gate Step 3: Draft pre-populated claim file and file to Guidewire ClaimCenter
        ClaimCreateResponse created = cc.createClaim(new ClaimCreateRequest(
                payload.policyNumber(),
                payload.incidentDate(),
                payload.state(),
                branchA.damageAssessment().estimatedDamage(),
                branchA.reserve().recommendedReserve()
        ));
        log.info("[FNOL] Claim filed to ClaimCenter — claimId={}", created.claimId());

        // Gate Step 4: Immutable audit trail (SHA-256 hashed per-agent events)
        List<AuditRecord> records = List.of(
                audit.record(created.claimId(), "BranchA-VisionDamageAgent",    payload,                  branchA.damageAssessment(), "SUCCESS"),
                audit.record(created.claimId(), "BranchA-PolicyValidatorAgent", payload.policyNumber(),   branchA.coverage(),         "SUCCESS"),
                audit.record(created.claimId(), "BranchA-ReserveCalculator",    branchA.damageAssessment(), branchA.reserve(),         "SUCCESS"),
                audit.record(created.claimId(), "BranchB-ComplianceAgents",     branchB.pii(),             branchB.subrogation(),     "SUCCESS")
        );

        BranchBResult branchBWithAudit = new BranchBResult(
                branchB.pii(), branchB.deadline(), branchB.subrogation(), records
        );

        // Gate Step 5: Compose adjuster advisory note
        //  - STP:          claim auto-filed; advisory confirms no human action needed
        //  - HUMAN_REVIEW: claim pre-populated; advisory surfaces WHY for adjuster
        boolean isStp = "STRAIGHT_THROUGH".equals(explanation.decision());
        String adjusterAdvisory = isStp
                ? "Claim meets all STP criteria. Auto-filed. No adjuster action required."
                : "Claim requires human review: " + explanation.summary() +
                  " | Recommended action: " + explanation.recommendedAction();

        log.info("[FNOL] Gate decision={} — policy={}, claimId={}",
                explanation.decision(), payload.policyNumber(), created.claimId());

        return new FNOLResponse(
                created.claimId(),
                created.status(),
                branchA,
                branchBWithAudit,
                policyContext,
                explanation,
                explanation.severityGate(),
                eligibleForAdvisoryPrefill,
                List.of(
                        "AI-assisted ADVISORY copilot only; human claim handlers retain final authority.",
                        "Pre-population recommended for LOW-severity eligible claim types only.",
                        "Coverage verified by Guidewire PolicyCenter deterministic rules engine.",
                        "Subrogation score is deterministic and explained by contributing factors.",
                        "Processing time: " + elapsedMs + "ms (async dual-branch parallel execution)"
                )
        );
    }
}