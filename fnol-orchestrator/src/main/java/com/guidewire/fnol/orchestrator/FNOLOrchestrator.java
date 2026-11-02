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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    /** Photos stored on earlier claims, for the PHOTO_REUSED check; none unless the application provides it. */
    private PhotoHistory photoHistory = PhotoHistory.NONE;
    /** Calibrated AI confidence (tools/vision-eval/trust_score.py); passes confidence through unless configured. */
    private TrustScorer trustScorer = TrustScorer.none();
    private FraudAnomalyScorer fraudScorer = FraudAnomalyScorer.none();
    /** Independent vision model (fnol-vision-ml), asked in parallel with Gemini; disabled unless configured. */
    private SecondOpinionAgent secondOpinion = SecondOpinionAgent.disabled();
    private RepairCostEstimator repairCosts = new RepairCostEstimator();
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
        return process(payload, null);
    }

    /**
     * As an extension of the claims system: the claim already exists there (the FNOL was submitted to it and its
     * "claim submitted" event started this run), so the results are written onto that claim instead of creating one.
     *
     * @param existingClaimId the claims system's claim number, or null to create the claim (direct intake)
     */
    public FNOLResponse process(FNOLPayload payload, String existingClaimId) {

        // ── Stage 1: Input validation ──────────────────────────────────────────────────
        input.validate(payload);
        log.info("[FNOL] Intake validated — policy={}, state={}", payload.policyNumber(), payload.state());

        // ── Stage 2: Pre-enrichment (claim history + policy, pre-fan-out) ──────────────
        PolicyHistoryContext policyContext = historyEnricher.enrich(payload.policyNumber());
        log.info("[FNOL] History enriched — risk={}, priorClaims={}",
                policyContext.claimFrequencyRisk(), policyContext.totalPriorClaims());
        Policy policyRecord = lookUpPolicy(payload.policyNumber());

        // ── Human Review Point 1: intake review flags ─────────────────────────────────
        List<String> inputFlags = new ArrayList<>(intakeReview.review(payload, policyRecord, policyContext));
        // Fraud indicator: a photo already submitted on another claim (same file, or resized/recompressed)
        List<String> reusedFrom = photoHistory.claimsWithSamePhotos(payload);
        if (!reusedFrom.isEmpty()) {
            inputFlags.add(IntakeReviewService.PHOTO_REUSED);
        }
        inputFlags = List.copyOf(inputFlags);
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
        // Each branch times itself: the legal branch runs alongside the operational one, so it adds no waiting.
        java.util.concurrent.atomic.AtomicLong operationalMs = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong legalMs = new java.util.concurrent.atomic.AtomicLong();
        // The decision clock of most states starts at a complete proof of loss: not yet when photos are missing.
        boolean proofOfLossComplete = !inputFlags.contains(IntakeReviewService.MISSING_EVIDENCE)
                && !inputFlags.contains(IntakeReviewService.INCOMPLETE_PHOTO_SET);
        // One overall deadline for both branches, from submission; Branch A also uses it to cap its waits.
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            futureA = executor.submit(() -> timed(operationalMs,
                    () -> runOperationalAI(payload, policyRecord.policyType(), deadlineNanos)));
        } catch (RejectedExecutionException e) {
            throw capacityExceeded(payload, e);
        }
        try {
            futureB = executor.submit(() -> timed(legalMs,
                    () -> runLegalAI(payload, policyRecord.policyType(), proofOfLossComplete)));
        } catch (RejectedExecutionException e) {
            futureA.cancel(true);
            throw capacityExceeded(payload, e);
        }

        // ── Stage 4: Await both branches against one overall deadline ─────────────────
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
        log.info("[FNOL] Dual-branch completed in {}ms (operational {}ms, legal {}ms, in parallel) — policy={}",
                elapsed, operationalMs.get(), legalMs.get(), payload.policyNumber());

        // ── Stage 5: Reconciliation Safety Gate ──────────────────────────────────────
        return reconcile(payload, policyRecord, branchA, branchB, policyContext, inputFlags, elapsed, highRiskEscalation,
                reusedFrom, operationalMs.get(), legalMs.get(), existingClaimId);
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

    /** Explains MODEL_DISAGREEMENT with both models' answers. */
    static String modelDisagreementNote(BranchAResult branchA) {
        String type = branchA.damageAssessment().damageType();
        SecondOpinion so = branchA.secondOpinion();
        String top = so.damageProbabilities().entrySet().stream().max(Map.Entry.comparingByValue())
                .map(e -> e.getKey() + " (" + e.getValue().movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP) + "%)")
                .orElse("—");
        return "Gemini says " + type + ", but the independently trained second-opinion model gives that only "
                + so.probabilityOf(type).movePointRight(2).setScale(1, java.math.RoundingMode.HALF_UP)
                + "% and favours " + top + "; check the photos before relying on the damage type or estimate.";
    }

    /** Explains ON_PREMISE_VISION: what the on-premise model saw, and what it cannot tell. */
    static String onPremiseNote(BranchAResult branchA) {
        DamageAssessment d = branchA.damageAssessment();
        DamageExtent extent = branchA.secondOpinion() == null ? null : branchA.secondOpinion().extent();
        return "Gemini was unavailable, so the on-premise vision model read the photos: " + d.damageType() + " ("
                + d.confidence().movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP) + "% likely)"
                + (extent == null ? "" : ", visible damage extent " + extent.band()) + ". "
                + (branchA.repairCost() == null
                    ? "It does not estimate cost, so the $" + d.estimatedDamage() + " estimate is a placeholder; "
                        + "set the estimate from the photos or an inspection."
                    : "The $" + d.estimatedDamage() + " estimate is " + costBasis(branchA.repairCost())
                        + "; confirm it from the photos or an inspection.");
    }

    /** Where a repair-cost range comes from, in one phrase. */
    static String costBasis(RepairCostRange r) {
        String span = "$" + r.low() + "-$" + r.high();
        return "LEARNED".equals(r.basis())
                ? "learned from this insurer's own repair costs (" + span + ")"
                : "the middle of published repair costs for this area" + (r.extentBand() == null ? "" : " and extent")
                        + " (" + span + ", " + r.sources().size() + " sources)";
    }

    /** Explains ESTIMATE_OUTSIDE_PUBLISHED_RANGE: Gemini's dollars against the range for what the photos show. */
    static String costRangeNote(BranchAResult branchA) {
        RepairCostRange r = branchA.repairCost();
        return "Gemini estimates $" + branchA.damageAssessment().estimatedDamage() + " for " + r.area()
                + (r.extentBand() == null ? "" : " with " + r.extentBand() + " visible damage")
                + ", more than " + (int) RepairCostEstimator.TOLERANCE + "x outside " + costBasis(r)
                + ". Check the estimate against the photos or an inspection before relying on it.";
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

    /** Runs a branch and records how long it took, also when it fails. */
    private static <T> T timed(java.util.concurrent.atomic.AtomicLong ms, Callable<T> branch) throws Exception {
        long started = System.nanoTime();
        try {
            return branch.call();
        } finally {
            ms.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
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
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPhotoHistory(PhotoHistory photoHistory) {
        this.photoHistory = photoHistory;
    }

    /** Quick-review mode with no vision service: each claim says the second opinion did not run. */
    private boolean liteMode;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLiteMode(@org.springframework.beans.factory.annotation.Value("${fnol.lite-mode:false}") boolean liteMode) {
        this.liteMode = liteMode;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTrustScorer(TrustScorer trustScorer) {
        this.trustScorer = trustScorer;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setFraudScorer(FraudAnomalyScorer fraudScorer) {
        this.fraudScorer = fraudScorer;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSecondOpinion(SecondOpinionAgent secondOpinion) {
        this.secondOpinion = secondOpinion;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRepairCosts(RepairCostEstimator repairCosts) {
        this.repairCosts = repairCosts;
    }

    /** How long Branch A waits for the second opinion after Gemini and coverage are done. */
    static final long SECOND_OPINION_WAIT_MS = 2500;
    /**
     * The wait when Gemini could not answer: the on-premise model is then the only reader, so it gets longer. Measured
     * in the vision-ml container: about 1 s warm, 3-10 s on the first call after the container sat idle.
     */
    static final long ON_PREMISE_WAIT_MS = 10_000;

    /** Time kept back from the overall deadline for Branch A's steps after the second opinion (cost range, reserve). */
    static final long BRANCH_A_FINISH_MS = 500;

    private BranchAResult runOperationalAI(FNOLPayload payload, String lineOfBusiness, long deadlineNanos) {
        log.debug("[Branch A — Operational AI] Starting — policy={}", payload.policyNumber());
        // Step A0: ask the second-opinion model now, so it runs while Gemini works
        CompletableFuture<SecondOpinion> second = secondOpinion.start(payload, lineOfBusiness);

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

        // Step A4: collect the second opinion; a slow or failed one is dropped, never waited for past the deadline
        SecondOpinion opinion;
        try {
            // Never past the claim's overall deadline: a slow model must not turn into a timed-out intake.
            long wait = Math.min(OnPremiseVision.isPlaceholder(damage) ? ON_PREMISE_WAIT_MS : SECOND_OPINION_WAIT_MS,
                    TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()) - BRANCH_A_FINISH_MS);
            opinion = second.get(Math.max(0, wait), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            opinion = null;
        } catch (ExecutionException | TimeoutException e) {
            log.warn("[Branch A] Second opinion not ready in time — continuing without it");
            opinion = null;
        }
        // Step A5: replace Gemini's self-reported confidence (nearly constant) with the calibrated trust score
        damage = trustScorer.apply(damage, opinion, lineOfBusiness);
        // The reserve's confidence is built on the vision confidence, so it follows the trust score; the amount does not change.
        res = reserve.calculate(damage, coverage, payload.claimedAmount());
        // Step A6: Gemini could not answer on usable photos → the on-premise model's reading replaces the placeholder
        // (vehicle claims; the ON_PREMISE_VISION gate flag always sends the claim to an adjuster)
        DamageAssessment onPremise = OnPremiseVision.substitute(damage, opinion, lineOfBusiness);
        if (onPremise != damage) {
            log.warn("[Branch A] Gemini unavailable — on-premise model read the photos: {} ({})",
                    onPremise.damageType(), onPremise.confidence());
            damage = onPremise;
        }
        // Step A7: what repairing this area usually costs at the visible extent the on-premise model saw (vehicle
        // claims). An on-premise reading has no estimate of its own, so it is priced from the range and the reserve
        // is recalculated; a Gemini estimate far outside the range is flagged at the gate.
        RepairCostRange cost = "AUTO".equalsIgnoreCase(lineOfBusiness)
                ? repairCosts.range(damage.damageType(), opinion == null || opinion.extent() == null ? null
                        : opinion.extent().band())
                : null;
        if (cost != null && OnPremiseVision.isOnPremise(damage)) {
            damage = damage.withEstimate(cost.estimate(), severityFor(cost.estimate()));
            res = reserve.calculate(damage, coverage, payload.claimedAmount());
        }
        // Step A8: the initial reserve by the configured method. The AI may move the table's base only when its reading holds up.
        res = reserve.reserve(damage, coverage, payload.claimedAmount(), lineOfBusiness,
                reserveAdjustment(new BranchAResult(damage, coverage, res, opinion, cost), lineOfBusiness));
        return new BranchAResult(damage, coverage, res, opinion, cost);
    }

    /**
     * Whether the AI may move the base reserve: only when the trust score passes and no vision check objects. Its figure is
     * then held inside the published repair-cost range when there is one, so one stock dollar figure cannot carry the reserve.
     */
    private ReserveCalculatorAgent.Adjustment reserveAdjustment(BranchAResult interim, String lineOfBusiness) {
        DamageAssessment damage = interim.damageAssessment();
        if (damage.confidence() == null || damage.confidence().compareTo(stp.getConfidence()) < 0) {
            return ReserveCalculatorAgent.Adjustment.blocked("AI confidence " + damage.confidence() + " is below "
                    + stp.getConfidence() + ", so the table's base stands");
        }
        List<String> objections = consistency.check(lineOfBusiness, interim).stream()
                .filter(GateConsistencyChecker.VISION_FLAGS::contains).toList();
        if (!objections.isEmpty()) {
            return ReserveCalculatorAgent.Adjustment.blocked("A vision check objected (" + String.join(", ", objections)
                    + "), so the table's base stands");
        }
        BigDecimal signal = damage.estimatedDamage();
        RepairCostRange range = interim.repairCost();
        if (range != null) {
            signal = signal.max(range.low()).min(range.high());
        }
        return ReserveCalculatorAgent.Adjustment.allowed(signal, "AI confidence " + damage.confidence()
                + " passed and no vision check objected; the AI's figure"
                + (range == null ? "" : ", held inside the published repair-cost range,") + " moved the base");
    }

    /** The severity band that goes with an estimate (the bands GateConsistencyChecker checks). */
    static String severityFor(java.math.BigDecimal estimate) {
        return estimate.compareTo(GateConsistencyChecker.MINOR_MAX) < 0 ? "MINOR"
                : estimate.compareTo(GateConsistencyChecker.MODERATE_MAX) <= 0 ? "MODERATE" : "SEVERE";
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    //  BRANCH B — Legal AI
    //  Redacts PII, computes statutory deadlines and assesses third-party liability,
    //  fully independent of Branch A. The subrogation score is finalised at the gate,
    //  once Branch A's damage severity is known.
    // ─────────────────────────────────────────────────────────────────────────────────
    private BranchBResult runLegalAI(FNOLPayload payload, String lineOfBusiness, boolean proofOfLossComplete) {
        log.debug("[Branch B — Legal AI] Starting — policy={}", payload.policyNumber());

        // Step B1: PII redaction — SSN, DOB, MRN, name, phone, email masking before any persistence
        PIIResult piiResult = pii.execute(payload);
        piiGuard.validate(piiResult);           // hard stop: raw PII must not reach DB
        log.debug("[Branch B] PII redaction complete — detected={}", piiResult.piiDetected());

        // Step B2: State regulatory deadlines from the cited rule set (claim-regulations.json)
        DeadlineResult deadlineResult = deadline.execute(payload, lineOfBusiness, proofOfLossComplete);
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
            boolean highRiskEscalation,
            List<String> reusedFrom,
            long operationalMs,
            long legalMs,
            String existingClaimId
    ) {
        log.info("[FNOL] Entering Reconciliation Safety Gate — policy={}", payload.policyNumber());

        // Gate Step 1: Finalise Branch B's subrogation assessment with Branch A's damage severity
        SubrogationResult subrogation = subro.finalizeScore(branchB.subrogation(), branchA.damageAssessment());

        // Gate Step 2: Output guardrails (value ranges) and cross-branch consistency checks
        output.validate(branchA.reserve(), subrogation);
        List<String> checked = new ArrayList<>(consistency.check(policyRecord, branchA));
        // Advisory fraud anomaly score: routes to an adjuster and suggests an SIU referral; never denies.
        FraudAnomaly fraud = fraudScorer.score(FraudAnomalyScorer.features(payload, policyRecord, policyContext, branchA,
                inputFlags.contains(IntakeReviewService.PHOTO_REUSED), LocalDate.now()));
        if (fraud != null && fraud.anomalous()) {
            checked.add(FraudAnomalyScorer.FRAUD_ANOMALY);
        }
        List<String> gateFlags = List.copyOf(checked);
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

        // Gate Step 5: The claim in the claims system. A filed claim is open; the human gate is that the AI only
        // recommends: no reserve is posted until a person decides. Only STP with no advisory review (a business
        // decision, ADR-009) posts its reserve without one.
        boolean unreviewedStp = "STRAIGHT_THROUGH_PROCESSING".equals(status);
        BigDecimal postedReserve = unreviewedStp ? branchA.reserve().recommendedReserve() : null;
        String claimId;
        if (existingClaimId == null) {
            ClaimCreateResponse created = cc.createClaim(new ClaimCreateRequest(
                    payload.policyNumber(),
                    payload.incidentDate(),
                    payload.state(),
                    branchA.damageAssessment().estimatedDamage(),
                    postedReserve,
                    "OPEN",
                    status
            ));
            claimId = created.claimId();
            log.info("[FNOL] Claim filed in ClaimCenter — claimId={}, workQueue={}", claimId, status);
        } else {
            // Extension mode: the claims system owns the claim, already open from its submission; record the AI's
            // estimate on it. Its work queue is set by the caller once the analysis record is saved, so an adjuster
            // can never pick up a claim that has no record yet.
            claimId = existingClaimId;
            cc.patchClaim(claimId, new ClaimPatch(null, branchA.damageAssessment().estimatedDamage(), postedReserve));
            log.info("[FNOL] AI results recorded on the claims system's claim — claimId={}", claimId);
        }
        String adjusterAdvisory = switch (status) {
            case "STRAIGHT_THROUGH_PROCESSING" -> "Claim meets all STP criteria. Auto-filed. No adjuster action required.";
            case "PENDING_ADVISORY_REVIEW" -> "Claim meets all STP criteria and is open in ClaimCenter with no reserve posted. "
                    + "An adjuster confirmation is required before the recommended reserve is posted.";
            default -> "Claim requires human review: " + explanation.summary()
                    + " | Recommended action: " + explanation.recommendedAction();
        };

        log.info("[FNOL] Gate decision={} status={} inputFlags={} gateFlags={} — policy={}, claimId={}",
                explanation.decision(), status, inputFlags, gateFlags, payload.policyNumber(), claimId);

        List<String> notes = new ArrayList<>();
        Map<String, String> flagReasons = new LinkedHashMap<>();   // flag → note text, for ClaimCenter activities
        if (highRiskEscalation) {
            notes.add("⚠ HIGH-RISK POLICY: This claim has been flagged for mandatory human validation " +
                      "due to elevated claim frequency risk. Auto-STP is disabled.");
        }
        if (inputFlags.contains(IntakeReviewService.MISSING_EVIDENCE)) {
            addFlagNote(notes, flagReasons, IntakeReviewService.MISSING_EVIDENCE, "MISSING EVIDENCE: no photos or documents were submitted. Request evidence from the claimant "
                    + "before relying on the damage estimate.");
        }
        if (inputFlags.contains(IntakeReviewService.INCOMPLETE_PHOTO_SET)) {
            addFlagNote(notes, flagReasons, IntakeReviewService.INCOMPLETE_PHOTO_SET, "INCOMPLETE PHOTO SET: missing " + String.join(", ",
                    IntakeReviewService.missingPhotoAngles(payload, policyRecord))
                    + ". Request the missing photos: every side shows the full extent and any earlier damage.");
        }
        if (inputFlags.contains(IntakeReviewService.PHOTO_REUSED)) {
            addFlagNote(notes, flagReasons, IntakeReviewService.PHOTO_REUSED, "PHOTO REUSED: at least one photo matches a "
                    + "photo already submitted on claim " + String.join(", ", reusedFrom) + " (same picture, possibly "
                    + "resized). Compare the claims and confirm the photos show this loss before any payment.");
        }
        if (inputFlags.contains(IntakeReviewService.INCIDENT_OUTSIDE_POLICY_PERIOD)) {
            addFlagNote(notes, flagReasons, IntakeReviewService.INCIDENT_OUTSIDE_POLICY_PERIOD, "INCIDENT OUTSIDE POLICY PERIOD: the incident date is not within the policy term. "
                    + "Confirm coverage dates before any decision.");
        }
        for (String flag : gateFlags) {
            addFlagNote(notes, flagReasons, flag, "CONSISTENCY CHECK " + flag + ": " + switch (flag) {
                case GateConsistencyChecker.SEVERITY_ESTIMATE_MISMATCH ->
                        "the damage severity label does not match the estimated amount; verify the estimate.";
                case GateConsistencyChecker.RESERVE_EXCEEDS_COVERAGE_LIMIT ->
                        "the loss after the deductible is above the coverage limit; the reserve is capped at the limit.";
                case GateConsistencyChecker.CLAIMED_EXCEEDS_AI_ESTIMATE ->
                        "the claimant asks $" + branchA.reserve().claimedAmount() + " but the reserve supports $"
                                + (branchA.reserve().breakdown() != null ? branchA.reserve().breakdown().supportedAmount()
                                        : branchA.damageAssessment().estimatedDamage())
                                + "; request more photos, send to investigation, or approve at the recommended "
                                + "reserve. Not approved automatically.";
                case GateConsistencyChecker.RESERVE_TABLE_ENTRY_MISSING ->
                        "the initial-reserve table has no row for " + branchA.reserve().breakdown().baseRow()
                                + "; the AI's figure is shown only for reference. Set the reserve yourself.";
                case GateConsistencyChecker.DAMAGE_TYPE_LINE_MISMATCH ->
                        "the damage type does not fit the policy's line of business; confirm the right policy and photos.";
                case GateConsistencyChecker.PHOTO_SUBJECT_MISMATCH -> photoSubjectNote(
                        branchA.damageAssessment().photoSubject(), policyRecord.policyType());
                case GateConsistencyChecker.MODEL_DISAGREEMENT -> modelDisagreementNote(branchA);
                case GateConsistencyChecker.ON_PREMISE_VISION -> onPremiseNote(branchA);
                case GateConsistencyChecker.ESTIMATE_OUTSIDE_PUBLISHED_RANGE -> costRangeNote(branchA);
                case GateConsistencyChecker.POSSIBLE_FLOOD_EXCLUSION ->
                        "the photos show flood (rising surface water), which standard homeowners policies exclude; "
                                + "confirm the cause of loss and check for a separate flood policy before any payment.";
                case GateConsistencyChecker.AMBIGUOUS_DAMAGE_TYPE ->
                        "the AI could not settle on one damage type (its top answers were close); check the photos "
                                + "yourself before relying on the damage type or estimate.";
                case GateConsistencyChecker.CONFIDENCE_PHOTO_QUALITY_MISMATCH ->
                        "high AI confidence was reported for a poor-quality photo; do not rely on the estimate alone.";
                case FraudAnomalyScorer.FRAUD_ANOMALY -> fraudNote(fraud);
                default -> "review before any decision.";
            });
        }
        ReserveResult r = branchA.reserve();
        BigDecimal supportedByReserve = r.breakdown() != null ? r.breakdown().supportedAmount()
                : branchA.damageAssessment().estimatedDamage();
        if (r.claimedAmount() != null && r.claimedAmount().compareTo(supportedByReserve) <= 0) {
            notes.add("RESERVE: the claimant asks $" + r.claimedAmount() + ", within the $" + supportedByReserve
                    + " the reserve supports; the claimed amount is used, less the $"
                    + r.deductible() + " deductible = reserve $" + r.recommendedReserve() + ".");
        }
        notes.add(deadlineNote(branchB.deadline()));
        if (inputFlags.contains(IntakeReviewService.ATTORNEY_REPRESENTED)) {
            addFlagNote(notes, flagReasons, IntakeReviewService.ATTORNEY_REPRESENTED, "ATTORNEY REPRESENTED: the "
                    + "claimant mentions legal counsel. Communicate through the attorney, not the claimant directly, and "
                    + "assign the claim to an adjuster for represented claims. Not eligible for straight-through processing.");
        }
        boolean recovery = recoveryLikely(subrogation);
        if (recovery && branchB.deadline().recoverBy() != null) {
            notes.add("RECOVERY: another party may be liable. Start any recovery action before "
                    + branchB.deadline().recoverBy() + " (" + branchB.deadline().recoveryCitation() + ")"
                    + (subrogation.thirdParty() == null || subrogation.thirdParty().isEmpty() ? "."
                            : "; details from the description: " + subrogation.thirdParty().keySet() + "."));
        }
        String ccSyncStatus = populateClaimCenter(claimId, status, explanation, payload, policyRecord, branchA,
                branchB.deadline(), reviewFlags, flagReasons, notes, subrogation, recovery,
                IntakeReviewService.missingPhotoAngles(payload, policyRecord));

        // Gate Step 6: Audit trail — one SHA-256 hashed record per agent plus the gate decision.
        // Inputs are hashed from the sanitized intake only, never the raw payload (SSN, DOB, name).
        SanitizedIntake intake = SanitizedIntake.of(payload, branchB.pii().redactedDescription());
        List<AuditRecord> records = new ArrayList<>(List.of(
                audit.record(claimId, "VisionDamageAgent",      intake,                     branchA.damageAssessment(), "SUCCESS"),
                audit.record(claimId, "PolicyValidatorAgent",   payload.policyNumber(),     branchA.coverage(),         "SUCCESS"),
                audit.record(claimId, "ReserveCalculatorAgent", branchA.damageAssessment(), branchA.reserve(),          "SUCCESS"),
                audit.record(claimId, "PiiRedactionAgent",      intake,                     branchB.pii(),              "SUCCESS"),
                audit.record(claimId, "StatutoryDeadlineAgent", intake,                     branchB.deadline(),         "SUCCESS"),
                audit.record(claimId, "SubrogationScorerAgent", intake,                     subrogation,                "SUCCESS"),
                audit.record(claimId, "ReconciliationGate",     explanation,
                        "status=" + status + ";requiresHumanValidation=" + requiresHumanValidation
                                + ";inputFlags=" + inputFlags + ";gateFlags=" + gateFlags, status)
        ));
        if (fraud != null) {
            records.add(audit.record(claimId, "FraudAnomalyScorer", intake, fraud, fraud.anomalous() ? "FLAGGED" : "SUCCESS"));
        }

        BranchBResult branchBWithAudit = new BranchBResult(
                branchB.pii(), branchB.deadline(), subrogation, records
        );

        if ("FAILED".equals(ccSyncStatus)) {
            notes.add("ClaimCenter could not be fully populated (exposure, activities, note or documents); it is marked for retry.");
        }
        notes.add(adjusterAdvisory);
        notes.add("AI-assisted ADVISORY copilot only; human claim handlers retain final authority.");
        notes.add("Pre-population recommended for LOW-severity eligible claim types only.");
        notes.add("Coverage verified against synthetic line-of-business coverage rules.");
        notes.add("Subrogation score is deterministic and explained by contributing factors.");
        if (liteMode) {
            notes.add("Second opinion: not available in lite mode, so the model-disagreement check did not run.");
        }
        notes.add("Processing time: " + elapsedMs + "ms in total. Operational branch (photos, coverage, reserve) "
                + operationalMs + "ms and legal branch (PII, deadlines, subrogation) " + legalMs
                + "ms ran in parallel, so the legal checks added no waiting.");

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
                gateFlags,
                fraud
        );
    }

    /** Explains FRAUD_ANOMALY with the features that contributed most. */
    static String fraudNote(FraudAnomaly fraud) {
        StringBuilder top = new StringBuilder();
        for (FraudContribution c : fraud.topContributors()) {
            if (!top.isEmpty()) top.append("; ");
            top.append(c.label()).append(" ").append(c.value()).append(" (+").append(c.points()).append(")");
        }
        return "the claim's intake details are unusual together (anomaly score " + fraud.score() + ", threshold "
                + fraud.threshold() + (fraud.percentile() == null ? "" : "; more unusual than " + fraud.percentile()
                + "% of reference claims") + "): " + top + ". Consider a referral to the special investigations unit. "
                + "This is a statistical signal, not a finding of fraud, and it never denies the claim.";
    }

    /** Adds a note for a review flag and remembers it as the reason for that flag's ClaimCenter activity. */
    private static void addFlagNote(List<String> notes, Map<String, String> flagReasons, String flag, String note) {
        notes.add(note);
        flagReasons.put(flag, note);
    }

    /** Queue the FNOL engine's ClaimCenter activities are assigned to (assignment rules route them on from there). */
    static final String FNOL_QUEUE = "FNOL adjuster queue";
    /** Represented claims go to adjusters used to working with claimants' attorneys. */
    static final String LITIGATION_QUEUE = "Litigation and represented claims";
    static final String SUBROGATION_QUEUE = "Subrogation unit";
    public static final String ACKNOWLEDGEMENT_LETTER = "acknowledgement-letter.txt";

    /** One line stating the regulatory deadlines and the rules behind them. */
    static String deadlineNote(DeadlineResult d) {
        if (d.acknowledgeBy() == null) {
            return "DEADLINES: " + d.disclaimer();
        }
        if (d.rulesVersion() == null) {   // a placeholder rule provider without citations
            return "DEADLINES (" + d.state() + "): acknowledge by " + d.acknowledgeBy()
                    + (d.decisionBy() != null ? ", decide by " + d.decisionBy() : "") + " (placeholder rule: "
                    + d.disclaimer() + ")";
        }
        String decision = d.decisionBy() != null
                ? "accept or deny by " + d.decisionBy() + " (" + d.decisionCitation() + ")"
                : d.decisionNote();
        return "DEADLINES (" + d.state() + ", rules " + d.rulesVersion() + "): acknowledge by " + d.acknowledgeBy()
                + " (" + d.acknowledgeCitation() + "); " + decision
                + ("OK".equals(d.urgencyFlag()) ? "" : " [" + d.urgencyFlag() + "]")
                + ". Prototype rule set: confirm with Compliance.";
    }

    /** Another party may be liable: the subrogation score says so, or the description names them. */
    public static boolean recoveryLikely(SubrogationResult s) {
        return "INVESTIGATE".equals(s.recommendedAction())
                || s.factors().getOrDefault("thirdPartyMention", 0) >= 35
                || (s.thirdParty() != null && !s.thirdParty().isEmpty());
    }

    /**
     * Fills the pre-filed ClaimCenter claim the way an adjuster works with it: an exposure carrying the reserve line
     * under the matched coverage, a note with the AI advisory, one activity per review flag (or one to confirm an
     * STP-eligible advisory) due by the statutory acknowledgement date, and the WHY panel and evidence list as
     * documents. Best effort: the claim already exists, so a failure here is logged and reported as FAILED for a
     * retry rather than failing the intake.
     */
    private String populateClaimCenter(String claimId, String status, ExplanationObject explanation, FNOLPayload payload,
                                       Policy policy, BranchAResult branchA, DeadlineResult deadline,
                                       List<String> reviewFlags, Map<String, String> flagReasons, List<String> notes,
                                       SubrogationResult subrogation, boolean recovery, List<String> missingAngles) {
        try {
            String coverage = branchA.coverage().coverageType();
            boolean unreviewedStp = "STRAIGHT_THROUGH_PROCESSING".equals(status);
            String exposureType = exposureType(policy.policyType(), coverage);
            cc.addExposure(claimId, new CcExposure(null, exposureType, coverage,
                    unreviewedStp ? branchA.reserve().recommendedReserve() : null, "claimcost", null,
                    "Dwelling".equals(exposureType) || "PersonalProperty".equals(exposureType)
                            ? "FixedPropertyIncident" : "VehicleIncident",
                    payload.claimantName() != null ? payload.claimantName() : policy.insuredName()));

            cc.addNote(claimId, new CcNote(null, "AI advisory",
                    "AI advisory: " + explanation.recommendedAction().replace('_', ' ').toLowerCase(),
                    explanation.summary() + "\n\n" + String.join("\n", notes)
                            + "\n\nAdvisory only: the adjuster decides.", null));

            String priority = "HIGH".equals(explanation.severityGate()) ? "urgent" : "high";
            LocalDate due = deadline.acknowledgeBy();
            if (!unreviewedStp) {
                // The recommendation reaches the money only through this person: approving posts it, or their figure.
                cc.addActivity(claimId, new CcActivity(null, "Review recommended reserve",
                        "AI recommends a reserve of $" + branchA.reserve().recommendedReserve() + " on " + coverage
                                + ", from a rough AI figure of $" + branchA.damageAssessment().estimatedDamage()
                                + (branchA.repairCost() == null ? " with no published repair-cost range to compare it with"
                                        : "; published range " + costBasis(branchA.repairCost()))
                                + ". Nothing is posted until an adjuster decides.",
                        priority, due, FNOL_QUEUE, null, null));
            }
            for (String flag : reviewFlags) {
                cc.addActivity(claimId, new CcActivity(null, "Review AI flag: " + flag.replace('_', ' ').toLowerCase(),
                        flagReasons.getOrDefault(flag, "Raised at intake review; check before any decision."),
                        priority, due, IntakeReviewService.ATTORNEY_REPRESENTED.equals(flag) ? LITIGATION_QUEUE : FNOL_QUEUE,
                        null, null));
            }
            if (deadline.decisionBy() != null) {
                cc.addActivity(claimId, new CcActivity(null, "Accept or deny the claim (regulatory deadline)",
                        deadline.decisionNote() + " " + Objects.toString(deadline.decisionCitation(), ""), "high",
                        deadline.decisionBy(), FNOL_QUEUE, null, null));
            }
            if (recovery && deadline.recoverBy() != null) {
                cc.addActivity(claimId, new CcActivity(null, "Subrogation: start recovery from the at-fault party",
                        "Limitation deadline " + deadline.recoverBy() + " (" + deadline.recoveryCitation() + "). "
                                + String.join(" ", subrogation.complianceNotes()),
                        "normal", deadline.recoverBy(), SUBROGATION_QUEUE, null, null));
            }
            if (reviewFlags.isEmpty() && "PENDING_ADVISORY_REVIEW".equals(status)) {
                cc.addActivity(claimId, new CcActivity(null, "Confirm AI advisory (straight-through eligible)",
                        explanation.summary(), "normal", due, FNOL_QUEUE, null, null));
            } else if (reviewFlags.isEmpty() && "HUMAN_REVIEW".equals(status)) {
                cc.addActivity(claimId, new CcActivity(null, "Review claim escalated by the AI",
                        explanation.summary(), priority, due, FNOL_QUEUE, null, null));
            }

            cc.attachDocument(claimId, new ClaimAttachment("ai-advisory-why-panel.json", "application/json", explanation));
            // The written acknowledgement the regulations require, generated from a template (not by AI).
            cc.attachDocument(claimId, new ClaimAttachment(ACKNOWLEDGEMENT_LETTER, "text/plain",
                    AcknowledgementLetter.write(claimId, payload, deadline, missingAngles,
                            reviewFlags.contains(IntakeReviewService.ATTORNEY_REPRESENTED), LocalDate.now())));
            cc.attachDocument(claimId, new ClaimAttachment("evidence-uris.json", "application/json",
                    java.util.Map.of("evidenceUris", summarizeEvidence(payload.evidenceUris()))));
            return "SYNCED";
        } catch (RuntimeException e) {
            log.warn("[FNOL] ClaimCenter population failed for claimId={} — marked FAILED for retry: {}",
                    claimId, e.getMessage());
            return "FAILED";
        }
    }

    /** ClaimCenter exposure type for the matched coverage. */
    static String exposureType(String lineOfBusiness, String coverageType) {
        if ("HOME".equals(lineOfBusiness)) {
            return "PERSONAL_PROPERTY".equals(coverageType) ? "PersonalProperty" : "Dwelling";
        }
        return "VehicleDamage";
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
