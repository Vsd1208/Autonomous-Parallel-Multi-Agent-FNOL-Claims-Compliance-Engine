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
 * Central orchestrator for FNOL claim processing.
 *
 * <p>Executes two independent branches in parallel via {@link CompletableFuture}:
 * <ul>
 *   <li><b>Branch A</b> — Damage Assessment → Policy Validation → Reserve Calculation</li>
 *   <li><b>Branch B</b> — PII Redaction → Statutory Deadline → Subrogation Scoring</li>
 * </ul>
 *
 * <p>After both branches complete, results are merged, guardrails are applied,
 * the claim is created in ClaimCenter, and audit records are generated.
 *
 * <p>Timeout is configurable via {@code fnol.orchestrator.timeout-seconds} (default 10).
 */
@Component
public class FNOLOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FNOLOrchestrator.class);

    private final InputGuardrail input;
    private final OutputGuardrail output;
    private final PIIGuardrail piiGuard;
    private final ValidationGuardrail validation;
    private final VisionDamageAgent vision;
    private final PolicyValidatorAgent policy;
    private final ReserveCalculatorAgent reserve;
    private final PiiRedactionAgent pii;
    private final StatutoryDeadlineAgent deadline;
    private final SubrogationScorerAgent subro;
    private final AuditTrailAgent audit;
    private final ClaimCenterClient cc;
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
            @Value("${fnol.orchestrator.timeout-seconds:10}") long timeoutSeconds
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
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("fnol-branch-" + t.getId());
            return t;
        });
    }

    /**
     * Process an FNOL payload through the full claims intelligence pipeline.
     *
     * @param payload the validated FNOL intake payload
     * @return the complete FNOL response with branch results and explanation
     * @throws IllegalArgumentException      if input guardrails fail
     * @throws ProcessingTimeoutException    if parallel branches exceed timeout
     */
    public FNOLResponse process(FNOLPayload payload) {
        // ── Pre-flight: Input validation ──
        input.validate(payload);

        log.info("Starting parallel FNOL processing for policy {}", payload.policyNumber());
        long startTime = System.currentTimeMillis();

        // ── Launch Branch A and Branch B in parallel ──
        CompletableFuture<BranchAResult> futureA = CompletableFuture.supplyAsync(
                () -> runBranchA(payload), executor
        );
        CompletableFuture<BranchBResult> futureB = CompletableFuture.supplyAsync(
                () -> runBranchB(payload), executor
        );

        // ── Wait for both branches with configurable timeout ──
        BranchAResult branchA;
        BranchBResult branchB;
        try {
            CompletableFuture.allOf(futureA, futureB).get(timeoutSeconds, TimeUnit.SECONDS);
            branchA = futureA.get();
            branchB = futureB.get();
        } catch (TimeoutException e) {
            futureA.cancel(true);
            futureB.cancel(true);
            throw new ProcessingTimeoutException(
                    "FNOL processing timed out after " + timeoutSeconds + " seconds. " +
                    "Claim escalated for manual review.", e
            );
        } catch (ExecutionException e) {
            // Unwrap the real cause (e.g., guardrail failure, external call failure)
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("FNOL processing failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessingTimeoutException("FNOL processing was interrupted", e);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Parallel branches completed in {}ms for policy {}", elapsed, payload.policyNumber());

        // ── Post-flight: Output guardrails ──
        output.validate(branchA.reserve(), branchB.subrogation());

        // ── Claim creation in ClaimCenter ──
        ClaimCreateResponse created = cc.createClaim(new ClaimCreateRequest(
                payload.policyNumber(),
                payload.incidentDate(),
                payload.state(),
                branchA.damageAssessment().estimatedDamage(),
                branchA.reserve().recommendedReserve()
        ));

        // ── Audit trail ──
        List<AuditRecord> records = List.of(
                audit.record(created.claimId(), "VisionDamageAgent", payload, branchA.damageAssessment(), "SUCCESS"),
                audit.record(created.claimId(), "PolicyValidatorAgent", payload.policyNumber(), branchA.coverage(), "SUCCESS"),
                audit.record(created.claimId(), "ReserveCalculatorAgent", branchA.damageAssessment(), branchA.reserve(), "SUCCESS"),
                audit.record(created.claimId(), "ComplianceAgents", branchB.pii(), branchB.subrogation(), "SUCCESS")
        );

        // ── Assemble final response ──
        BranchBResult branchBWithAudit = new BranchBResult(
                branchB.pii(), branchB.deadline(), branchB.subrogation(), records
        );

        return new FNOLResponse(
                created.claimId(),
                created.status(),
                branchA,
                branchBWithAudit,
                List.of(
                        "AI-assisted decision support only; human claim handlers retain final decision authority.",
                        "Coverage recommendation is based on Mock PolicyCenter contracts and synthetic Sprint 2 rules.",
                        "Reserve equals estimated visual damage multiplied by 1.15.",
                        "Subrogation score is deterministic and explained by contributing factors.",
                        "Processing time: " + elapsed + "ms (parallel execution)"
                )
        );
    }

    /**
     * Branch A: Vision-based damage assessment → Policy validation → Reserve calculation.
     * These three agents are sequential within the branch because each depends on the previous.
     */
    private BranchAResult runBranchA(FNOLPayload payload) {
        log.debug("Branch A started for policy {}", payload.policyNumber());

        DamageAssessment damage = vision.execute(payload);
        PolicyValidationResult coverage = policy.execute(payload.policyNumber());
        validation.requireCovered(coverage);
        ReserveResult res = reserve.execute(damage);

        log.debug("Branch A completed: damage={}, reserve={}", damage.severity(), res.recommendedReserve());
        return new BranchAResult(damage, coverage, res);
    }

    /**
     * Branch B: PII redaction → Statutory deadline → Subrogation scoring.
     * These agents are independent of Branch A and can run concurrently.
     */
    private BranchBResult runBranchB(FNOLPayload payload) {
        log.debug("Branch B started for policy {}", payload.policyNumber());

        PIIResult piiResult = pii.execute(payload);
        piiGuard.validate(piiResult);
        DeadlineResult deadlineResult = deadline.execute(payload);

        // Subrogation needs the damage assessment from vision — but for parallel execution,
        // we use a lightweight assessment since the full one comes from Branch A.
        // The scorer only uses the payload description text for keyword analysis.
        DamageAssessment lightDamage = new DamageAssessment(
                "PENDING", "MODERATE", java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO
        );
        SubrogationResult sub = subro.execute(payload, lightDamage);

        log.debug("Branch B completed: piiDetected={}, subrogation={}", piiResult.piiDetected(), sub.score());
        return new BranchBResult(piiResult, deadlineResult, sub, List.of());
    }
}
