package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.agents.*;
import com.guidewire.fnol.common.CoverageNotEligibleException;
import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.common.ProcessingTimeoutException;
import com.guidewire.fnol.enrichment.*;
import com.guidewire.fnol.guardrails.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * Central FNOL orchestrator — implements the Asynchronous Dual-Branch Agentic Copilot pattern.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li><b>Input guardrail</b>: policy number format, supported state, date, description.</li>
 *   <li><b>Enrichment</b>: claim-history context (frequency risk) and the PolicyCenter policy.</li>
 *   <li><b>Human Review Point 1</b>: {@link IntakeReviewService} flags missing evidence, claimant
 *       mismatch, incidents outside or near the start of the policy term and high claim frequency.</li>
 *   <li><b>Dual-branch fan-out</b> on a bounded executor ({@code fnol.orchestrator.*}), both branches
 *       awaited against one overall deadline; on timeout the branch threads are interrupted.
 *     <ul>
 *       <li>Branch A — Operational AI: vision damage, coverage check (hard stop), reserve.</li>
 *       <li>Branch B — Legal AI: PII redaction (hard stop on guardrail failure), statutory deadlines,
 *           third-party liability assessment with compliance notes.</li>
 *     </ul></li>
 *   <li><b>Reconciliation Safety Gate</b> ({@link #reconcile}): subrogation finalised with Branch A's
 *       real severity, output guardrails, cross-branch consistency checks, six-factor WHY panel,
 *       routing, a ClaimCenter claim pre-populated as a draft (OPEN only for unreviewed STP), and one
 *       audit record per agent plus the gate decision.</li>
 * </ol>
 *
 * <h2>Governance</h2>
 * <p>The copilot recommends; it never pays. A claim is eligible for straight-through processing only
 * when severity is LOW or MEDIUM, all six factors pass and no intake flag is raised. With
 * {@code fnol.stp.require-advisory-review=true} (default) even an eligible claim is saved as
 * PENDING_ADVISORY_REVIEW and counts as filed only after an adjuster confirms it; with the flag off
 * it is STRAIGHT_THROUGH_PROCESSING without a human step. Every other claim is HUMAN_REVIEW.
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

    // ── Branch B liability assessment, finalised at the reconciliation gate ──
    private final SubrogationScorerAgent subro;
    private final GateConsistencyChecker consistency;

    // ── Supporting components ──
    private final AuditTrailAgent audit;
    private final ClaimCenterClient cc;
    private final PolicyCenterClient policyCenter;
    private final ClaimHistoryEnricher historyEnricher;
    private final IntakeReviewService intakeReview;
    private final ExplanationBuilder explanationBuilder;
    private final ClaimTypeValidator claimTypeValidator;

    private final StpProperties stp;
    private final ThreadPoolExecutor executor;
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
            PolicyCenterClient policyCenter,
            ClaimHistoryEnricher historyEnricher,
            IntakeReviewService intakeReview,
            ExplanationBuilder explanationBuilder,
            ClaimTypeValidator claimTypeValidator,
            GateConsistencyChecker consistency,
            OrchestratorProperties properties,
            StpProperties stp
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
        this.policyCenter = policyCenter;
        this.historyEnricher = historyEnricher;
        this.intakeReview = intakeReview;
        this.explanationBuilder = explanationBuilder;
        this.claimTypeValidator = claimTypeValidator;
        this.consistency = consistency;
        this.stp = stp;
        this.timeoutSeconds = properties.getTimeoutSeconds();
        // Bounded pool + bounded queue shared by all requests. AbortPolicy makes a full queue
        // fail fast (RejectedExecutionException -> 504) instead of piling up work.
        this.executor = new ThreadPoolExecutor(
                properties.getPoolSize(), properties.getPoolSize(),
                60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.getQueueSize()),
                r -> {
                    Thread t = new Thread(r);
                    t.setDaemon(true);
                    t.setName("fnol-branch-" + t.getId());
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Process an FNOL payload through the full dual-branch claims intelligence pipeline.
     *
     * @throws IllegalArgumentException     if input guardrails fail
     * @throws CoverageNotEligibleException if the policy is unknown, inactive or not covered
     * @throws ProcessingTimeoutException   if the branches exceed the deadline or the executor is full
     */
    public FNOLResponse process(FNOLPayload payload) {

        // ── Stage 1: Input validation ──────────────────────────────────────────────────
        input.validate(payload);
        log.info("[FNOL] Intake validated — policy={}, state={}", payload.policyNumber(), payload.state());

        // ── Stage 2: Pre-enrichment (claim history + policy, pre-fan-out) ──────────────
        PolicyHistoryContext policyContext = historyEnricher.enrich(payload.policyNumber());
        log.info("[FNOL] History enriched — risk={}, priorClaims={}",
                policyContext.claimFrequencyRisk(), policyContext.totalPriorClaims());
        Policy policyRecord = lookUpPolicy(payload.policyNumber());

        // ── Human Review Point 1: intake review flags ─────────────────────────────────
        List<String> inputFlags = intakeReview.review(payload, policyRecord, policyContext);
        boolean highRiskEscalation = "HIGH".equalsIgnoreCase(policyContext.claimFrequencyRisk());
        if (!inputFlags.isEmpty()) {
            log.warn("[FNOL] Intake review flags {} — human validation required: policy={}",
                    inputFlags, payload.policyNumber());
        }

        // ── Stage 3: Async dual-branch fan-out ────────────────────────────────────────
        log.info("[FNOL] Launching dual-branch fan-out — policy={}", payload.policyNumber());
        long startTime = System.currentTimeMillis();

        // executor.submit returns a FutureTask, whose cancel(true) interrupts the running
        // branch thread (CompletableFuture.cancel does not), so a hung call frees its thread.
        Future<BranchAResult> futureA;
        Future<BranchBResult> futureB;
        try {
            futureA = executor.submit(() -> runOperationalAI(payload, policyRecord.policyType()));
        } catch (RejectedExecutionException e) {
            throw capacityExceeded(payload, e);
        }
        try {
            futureB = executor.submit(() -> runLegalAI(payload));
        } catch (RejectedExecutionException e) {
            futureA.cancel(true);
            throw capacityExceeded(payload, e);
        }

        // ── Stage 4: Await both branches against one overall deadline ─────────────────
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        BranchAResult branchA;
        BranchBResult branchB;
        try {
            branchA = futureA.get(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
            branchB = futureB.get(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            futureA.cancel(true);
            futureB.cancel(true);
            log.warn("[FNOL] Processing timed out after {}s — policy={}", timeoutSeconds, payload.policyNumber());
            throw new ProcessingTimeoutException(
                    "FNOL processing timed out after " + timeoutSeconds + " seconds. " +
                    "Claim escalated for manual adjuster review.", e
            );
        } catch (ExecutionException e) {
            futureA.cancel(true);
            futureB.cancel(true);
            Throwable cause = e.getCause();
            log.error("[FNOL] Branch execution failed — policy={}, cause={}", payload.policyNumber(), cause.getMessage());
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException("FNOL processing failed", cause);
        } catch (InterruptedException e) {
            futureA.cancel(true);
            futureB.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProcessingTimeoutException("FNOL processing was interrupted", e);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[FNOL] Dual-branch completed in {}ms — policy={}", elapsed, payload.policyNumber());

        // ── Stage 5: Reconciliation Safety Gate ──────────────────────────────────────
        return reconcile(payload, policyRecord, branchA, branchB, policyContext, inputFlags, elapsed, highRiskEscalation);
    }

    private Policy lookUpPolicy(String policyNumber) {
        Policy found;
        try {
            found = policyCenter.getPolicy(policyNumber);
        } catch (HttpClientErrorException.NotFound e) {
            found = null;
        }
        if (found == null) {
            throw new CoverageNotEligibleException("Policy " + policyNumber + " was not found in PolicyCenter");
        }
        return found;
    }

    /** Explains PHOTO_SUBJECT_MISMATCH: what the photos show versus what the policy insures. */
    static String photoSubjectNote(String subject, String lineOfBusiness) {
        if ("NONE".equals(subject)) {
            return "the photos show no damaged property; request photos of the damage.";
        }
        String seen = switch (subject == null ? "" : subject) {
            case "VEHICLE" -> "a vehicle";
            case "BUILDING" -> "a building";
            default -> "other property (not a vehicle or a building)";
        };
        if ("HOME".equals(lineOfBusiness) && "OTHER_PROPERTY".equals(subject)) {
            return "the photos show " + seen + "; confirm whether it is covered as another structure (e.g. a fence) "
                    + "or as personal property before relying on the estimate.";
        }
        return "the photos show " + seen + ", but the policy line is " + lineOfBusiness + "; confirm the policy number "
                + "and the photos before any decision.";
    }

    private static long remainingNanos(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    private ProcessingTimeoutException capacityExceeded(FNOLPayload payload, RejectedExecutionException e) {
        log.warn("[FNOL] Branch executor saturated (pool={}, queued={}) — policy={}",
                executor.getPoolSize(), executor.getQueue().size(), payload.policyNumber());
        return new ProcessingTimeoutException(
                "FNOL processing capacity exceeded. Claim escalated for manual adjuster review.", e);
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    //  BRANCH A — Operational AI
    //  Analyzes loss photos → extracts damage severity → passes structured parameters
    //  to Guidewire's deterministic rules engine for coverage verification and reserve.
    // ─────────────────────────────────────────────────────────────────────────────────
    private BranchAResult runOperationalAI(FNOLPayload payload, String lineOfBusiness) {
        log.debug("[Branch A — Operational AI] Starting — policy={}", payload.policyNumber());

        // Step A1: Vision analysis — loss photos → structured damage JSON (type, severity, confidence);
        // vehicle damage areas for AUTO, cause of loss for HOME
        DamageAssessment damage = vision.execute(payload, lineOfBusiness);
        log.debug("[Branch A] Vision complete — severity={}, confidence={}", damage.severity(), damage.confidence());

        // Step A2: Coverage verification against the line-of-business coverage rule
        PolicyValidationResult coverage = policy.execute(payload.policyNumber());
        validation.requireCovered(coverage);    // hard stop: no uncovered claim proceeds
        log.debug("[Branch A] Coverage verified — type={}, covered={}", coverage.coverageType(), coverage.covered());

        // Step A3: Reserve = what the insurer owes: min(claimed, AI estimate) − deductible, capped at the limit
        ReserveResult res = reserve.calculate(damage, coverage, payload.claimedAmount());
        log.debug("[Branch A — Operational AI] Completed — reserve={}", res.recommendedReserve());

        return new BranchAResult(damage, coverage, res);
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    //  BRANCH B — Legal AI
    //  Redacts PII, computes statutory deadlines and assesses third-party liability,
    //  fully independent of Branch A. The subrogation score is finalised at the gate,
    //  once Branch A's damage severity is known.
    // ─────────────────────────────────────────────────────────────────────────────────
    private BranchBResult runLegalAI(FNOLPayload payload) {
        log.debug("[Branch B — Legal AI] Starting — policy={}", payload.policyNumber());

        // Step B1: PII redaction — SSN, DOB, MRN, name, phone, email masking before any persistence
        PIIResult piiResult = pii.execute(payload);
        piiGuard.validate(piiResult);           // hard stop: raw PII must not reach DB
        log.debug("[Branch B] PII redaction complete — detected={}", piiResult.piiDetected());

        // Step B2: State-specific statutory deadlines (synthetic prototype rules)
        DeadlineResult deadlineResult = deadline.execute(payload);
        log.debug("[Branch B] Deadlines — state={}, acknowledgeBy={}, urgency={}",
                deadlineResult.state(), deadlineResult.acknowledgeBy(), deadlineResult.urgencyFlag());

        // Step B3: Subrogation — third-party liability potential and compliance notes
        SubrogationResult liability = subro.assessLiability(payload);
        log.debug("[Branch B — Legal AI] Completed — liability points={}", liability.score());

        return new BranchBResult(piiResult, deadlineResult, liability, List.of());
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    //  RECONCILIATION SAFETY GATE
    //  Merges Branch A + Branch B results. Applies guardrails and consistency checks.
    //  Builds the WHY panel, routes the claim and pre-populates it in ClaimCenter.
    // ─────────────────────────────────────────────────────────────────────────────────
    private FNOLResponse reconcile(
            FNOLPayload payload,
            Policy policyRecord,
            BranchAResult branchA,
            BranchBResult branchB,
            PolicyHistoryContext policyContext,
            List<String> inputFlags,
            long elapsedMs,
            boolean highRiskEscalation
    ) {
        log.info("[FNOL] Entering Reconciliation Safety Gate — policy={}", payload.policyNumber());

        // Gate Step 1: Finalise Branch B's subrogation assessment with Branch A's damage severity
        SubrogationResult subrogation = subro.finalizeScore(branchB.subrogation(), branchA.damageAssessment());

        // Gate Step 2: Output guardrails (value ranges) and cross-branch consistency checks
        output.validate(branchA.reserve(), subrogation);
        List<String> gateFlags = consistency.check(policyRecord, branchA);
        List<String> reviewFlags = new ArrayList<>(inputFlags);
        reviewFlags.addAll(gateFlags);

        // Gate Step 3: Build structured ExplanationObject — the "WHY" panel
        ExplanationObject explanation = explanationBuilder.build(
                branchA.coverage(),
                branchA.damageAssessment(),
                branchA.reserve(),
                branchB.pii(),
                subrogation,
                policyContext
        );
        if ("STRAIGHT_THROUGH".equals(explanation.decision()) && !reviewFlags.isEmpty()) {
            // All six factors passed, but intake review or the consistency checks raised flags:
            // the WHY panel must say so.
            explanation = new ExplanationObject(
                    "ADVISORY_RECOMMENDED_ACTION",
                    "All six factors passed, but review flags were raised (" + String.join(", ", reviewFlags)
                            + "); escalate to human claim handler for review",
                    explanation.factors(),
                    "ESCALATE_TO_HUMAN",
                    explanation.severityGate());
        }

        boolean eligibleForAdvisoryPrefill = claimTypeValidator.isEligibleForAdvisoryPrefill(
                branchA.damageAssessment(),
                policyContext
        );

        // Gate Step 4: Route — STP only when every factor passes and no review flag was raised
        boolean isStp = "STRAIGHT_THROUGH".equals(explanation.decision()) && reviewFlags.isEmpty();
        boolean requiresHumanValidation = !isStp;
        // Human Review Point 2: with fnol.stp.require-advisory-review (default) an STP-eligible claim
        // waits for an adjuster to confirm the advisory before it counts as filed.
        String status = !isStp ? "HUMAN_REVIEW"
                : stp.isRequireAdvisoryReview() ? "PENDING_ADVISORY_REVIEW" : "STRAIGHT_THROUGH_PROCESSING";

        // Gate Step 5: Pre-populate the claim in Guidewire ClaimCenter. It stays a draft until a human
        // decides; only an STP claim with no advisory review goes straight to OPEN (standard process).
        String initialCcStatus = "STRAIGHT_THROUGH_PROCESSING".equals(status) ? "OPEN" : "DRAFT_PENDING_REVIEW";
        ClaimCreateResponse created = cc.createClaim(new ClaimCreateRequest(
                payload.policyNumber(),
                payload.incidentDate(),
                payload.state(),
                branchA.damageAssessment().estimatedDamage(),
                branchA.reserve().recommendedReserve(),
                initialCcStatus
        ));
        String claimId = created.claimId();
        log.info("[FNOL] Claim pre-populated in ClaimCenter — claimId={}, ccStatus={}", claimId, initialCcStatus);
        String ccSyncStatus = attachAdvisoryAndEvidence(claimId, explanation, payload);

        // Gate Step 6: Audit trail — one SHA-256 hashed record per agent plus the gate decision.
        // Inputs are hashed from the sanitized intake only, never the raw payload (SSN, DOB, name).
        SanitizedIntake intake = SanitizedIntake.of(payload, branchB.pii().redactedDescription());
        List<AuditRecord> records = List.of(
                audit.record(claimId, "VisionDamageAgent",      intake,                     branchA.damageAssessment(), "SUCCESS"),
                audit.record(claimId, "PolicyValidatorAgent",   payload.policyNumber(),     branchA.coverage(),         "SUCCESS"),
                audit.record(claimId, "ReserveCalculatorAgent", branchA.damageAssessment(), branchA.reserve(),          "SUCCESS"),
                audit.record(claimId, "PiiRedactionAgent",      intake,                     branchB.pii(),              "SUCCESS"),
                audit.record(claimId, "StatutoryDeadlineAgent", intake,                     branchB.deadline(),         "SUCCESS"),
                audit.record(claimId, "SubrogationScorerAgent", intake,                     subrogation,                "SUCCESS"),
                audit.record(claimId, "ReconciliationGate",     explanation,
                        "status=" + status + ";requiresHumanValidation=" + requiresHumanValidation
                                + ";inputFlags=" + inputFlags + ";gateFlags=" + gateFlags, status)
        );

        BranchBResult branchBWithAudit = new BranchBResult(
                branchB.pii(), branchB.deadline(), subrogation, records
        );

        String adjusterAdvisory = switch (status) {
            case "STRAIGHT_THROUGH_PROCESSING" -> "Claim meets all STP criteria. Auto-filed. No adjuster action required.";
            case "PENDING_ADVISORY_REVIEW" -> "Claim meets all STP criteria and is pre-populated in ClaimCenter as a draft. "
                    + "An adjuster confirmation is required before it counts as filed.";
            default -> "Claim requires human review: " + explanation.summary()
                    + " | Recommended action: " + explanation.recommendedAction();
        };

        log.info("[FNOL] Gate decision={} status={} inputFlags={} gateFlags={} — policy={}, claimId={}",
                explanation.decision(), status, inputFlags, gateFlags, payload.policyNumber(), claimId);

        List<String> notes = new ArrayList<>();
        if (highRiskEscalation) {
            notes.add("⚠ HIGH-RISK POLICY: This claim has been flagged for mandatory human validation " +
                      "due to elevated claim frequency risk. Auto-STP is disabled.");
        }
        if (inputFlags.contains(IntakeReviewService.MISSING_EVIDENCE)) {
            notes.add("MISSING EVIDENCE: no photos or documents were submitted. Request evidence from the claimant "
                    + "before relying on the damage estimate.");
        }
        if (inputFlags.contains(IntakeReviewService.INCOMPLETE_PHOTO_SET)) {
            notes.add("INCOMPLETE PHOTO SET: missing " + String.join(", ",
                    IntakeReviewService.missingPhotoAngles(payload, policyRecord))
                    + ". Request the missing photos: every side shows the full extent and any earlier damage.");
        }
        if (inputFlags.contains(IntakeReviewService.INCIDENT_OUTSIDE_POLICY_PERIOD)) {
            notes.add("INCIDENT OUTSIDE POLICY PERIOD: the incident date is not within the policy term. "
                    + "Confirm coverage dates before any decision.");
        }
        for (String flag : gateFlags) {
            notes.add("CONSISTENCY CHECK " + flag + ": " + switch (flag) {
                case GateConsistencyChecker.SEVERITY_ESTIMATE_MISMATCH ->
                        "the damage severity label does not match the estimated amount; verify the estimate.";
                case GateConsistencyChecker.RESERVE_EXCEEDS_COVERAGE_LIMIT ->
                        "the loss after the deductible is above the coverage limit; the reserve is capped at the limit.";
                case GateConsistencyChecker.CLAIMED_EXCEEDS_AI_ESTIMATE ->
                        "the claimant asks $" + branchA.reserve().claimedAmount() + " but the photos support $"
                                + branchA.damageAssessment().estimatedDamage() + "; request more photos, send to "
                                + "investigation, or approve at the AI estimate. Not approved automatically.";
                case GateConsistencyChecker.DAMAGE_TYPE_LINE_MISMATCH ->
                        "the damage type does not fit the policy's line of business; confirm the right policy and photos.";
                case GateConsistencyChecker.PHOTO_SUBJECT_MISMATCH -> photoSubjectNote(
                        branchA.damageAssessment().photoSubject(), policyRecord.policyType());
                case GateConsistencyChecker.POSSIBLE_FLOOD_EXCLUSION ->
                        "the photos show flood (rising surface water), which standard homeowners policies exclude; "
                                + "confirm the cause of loss and check for a separate flood policy before any payment.";
                case GateConsistencyChecker.AMBIGUOUS_DAMAGE_TYPE ->
                        "the AI could not settle on one damage type (its top answers were close); check the photos "
                                + "yourself before relying on the damage type or estimate.";
                case GateConsistencyChecker.CONFIDENCE_PHOTO_QUALITY_MISMATCH ->
                        "high AI confidence was reported for a poor-quality photo; do not rely on the estimate alone.";
                default -> "review before any decision.";
            });
        }
        ReserveResult r = branchA.reserve();
        if (r.claimedAmount() != null && r.claimedAmount().compareTo(branchA.damageAssessment().estimatedDamage()) <= 0) {
            notes.add("RESERVE: the claimant asks $" + r.claimedAmount() + ", within the AI estimate of $"
                    + branchA.damageAssessment().estimatedDamage() + "; the claimed amount is used, less the $"
                    + r.deductible() + " deductible = reserve $" + r.recommendedReserve() + ".");
        }
        if (!"OK".equals(branchB.deadline().urgencyFlag())) {
            notes.add("DEADLINE " + branchB.deadline().urgencyFlag() + ": acknowledge by "
                    + branchB.deadline().acknowledgeBy() + " (synthetic prototype rule).");
        }
        if ("FAILED".equals(ccSyncStatus)) {
            notes.add("ClaimCenter attachment of the advisory or evidence failed; it is marked for retry.");
        }
        notes.add(adjusterAdvisory);
        notes.add("AI-assisted ADVISORY copilot only; human claim handlers retain final authority.");
        notes.add("Pre-population recommended for LOW-severity eligible claim types only.");
        notes.add("Coverage verified against synthetic line-of-business coverage rules.");
        notes.add("Subrogation score is deterministic and explained by contributing factors.");
        notes.add("Processing time: " + elapsedMs + "ms (async dual-branch parallel execution)");

        return new FNOLResponse(
                claimId,
                status,
                branchA,
                branchBWithAudit,
                policyContext,
                explanation,
                explanation.severityGate(),
                eligibleForAdvisoryPrefill,
                requiresHumanValidation,
                Collections.unmodifiableList(notes),
                inputFlags,
                ccSyncStatus,
                gateFlags
        );
    }

    /**
     * Attaches the WHY panel and the evidence list to the pre-filed ClaimCenter claim. Best effort:
     * the claim already exists, so a failure here is logged and reported as FAILED for a retry
     * rather than failing the intake.
     */
    private String attachAdvisoryAndEvidence(String claimId, ExplanationObject explanation, FNOLPayload payload) {
        try {
            cc.attachDocument(claimId, new ClaimAttachment("ai-advisory-why-panel.json", "application/json", explanation));
            cc.attachDocument(claimId, new ClaimAttachment("evidence-uris.json", "application/json",
                    java.util.Map.of("evidenceUris", summarizeEvidence(payload.evidenceUris()))));
            return "SYNCED";
        } catch (RuntimeException e) {
            log.warn("[FNOL] ClaimCenter attachment failed for claimId={} — marked FAILED for retry: {}",
                    claimId, e.getMessage());
            return "FAILED";
        }
    }

    /** Inline images are summarised (type and size) rather than copied into ClaimCenter twice. */
    private static List<String> summarizeEvidence(List<String> uris) {
        if (uris == null) return List.of();
        return uris.stream()
                .map(u -> u != null && u.startsWith("data:")
                        ? EvidenceImageLoader.describe(u) + " (inline, " + u.length() + " chars)"
                        : u)
                .toList();
    }
}
