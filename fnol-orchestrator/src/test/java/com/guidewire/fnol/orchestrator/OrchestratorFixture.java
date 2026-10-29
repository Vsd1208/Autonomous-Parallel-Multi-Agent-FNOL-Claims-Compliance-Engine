package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.agents.*;
import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.ClaimHistoryEnricher;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import com.guidewire.fnol.guardrails.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Builds an FNOLOrchestrator wired to in-memory Guidewire stubs. */
final class OrchestratorFixture {

    static final LocalDate POLICY_START = LocalDate.now().minusMonths(6);

    final StubPolicyCenter policyCenter = new StubPolicyCenter();
    final StubClaimCenter claimCenter = new StubClaimCenter();
    final OrchestratorProperties properties = new OrchestratorProperties();
    final StpProperties stp = new StpProperties();
    VisionProvider vision = new MockVisionProvider();
    AuditTrailAgent audit = new AuditTrailAgent();

    OrchestratorFixture() {
        policyCenter.add(autoPolicy("POL-AUTO-112233", "Avery Johnson"), List.of());
        policyCenter.add(autoPolicy("POL-AUTO-445566", "Jordan Rivera"), List.of(
                new ClaimHistory("HIST-5566-01", LocalDate.now().minusDays(40), "CLOSED", new BigDecimal("2200"), "Rear bumper"),
                new ClaimHistory("HIST-5566-02", LocalDate.now().minusDays(130), "OPEN", new BigDecimal("3100"), "Side panel")));
    }

    FNOLOrchestrator build() {
        PolicyCenterClient pc = policyCenter;
        return new FNOLOrchestrator(
                new InputGuardrail(), new OutputGuardrail(), new PIIGuardrail(), new ValidationGuardrail(),
                new VisionDamageAgent(vision),
                new PolicyValidatorAgent(pc, new SyntheticCoverageRuleProvider()),
                new ReserveCalculatorAgent(),
                new PiiRedactionAgent(),
                new StatutoryDeadlineAgent(new CitedDeadlineRuleProvider()),
                new SubrogationScorerAgent(new DeterministicSubrogationScorer()),
                audit,
                claimCenter,
                pc,
                new ClaimHistoryEnricher(pc),
                new IntakeReviewService(),
                new ExplanationBuilder(stp),
                new ClaimTypeValidator(stp),
                new GateConsistencyChecker(stp),
                properties,
                stp);
    }

    static Policy autoPolicy(String number, String insured) {
        return new Policy(number, "AUTO", "ACTIVE", POLICY_START, POLICY_START.plusYears(1),
                insured, "CA", "Synthetic vehicle", List.of());
    }

    static List<Coverage> autoCoverages() {
        return List.of(
                new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000")),
                new Coverage("LIABILITY", new BigDecimal("100000"), new BigDecimal("500")),
                new Coverage("COMPREHENSIVE", new BigDecimal("30000"), new BigDecimal("250")));
    }

    /** A clean payload: no PII in the description, a mock photo, the insured as claimant. */
    static FNOLPayload payload(String policyNumber, String claimantName, String description) {
        return new FNOLPayload(policyNumber, LocalDate.now().minusDays(2), "CA", description,
                claimantName, "01/02/1980", "123-45-6789", FULL_SET, "WEB", null, null, null, FULL_SET_ANGLES);
    }

    /** The standard photo set (every side plus a close-up), as mock references. */
    static final List<String> FULL_SET_ANGLES = List.of("FRONT", "REAR", "LEFT", "RIGHT", "CLOSE_UP");
    static final List<String> FULL_SET = FULL_SET_ANGLES.stream()
            .map(a -> "mock://photo/front-bumper-" + a.toLowerCase() + ".jpg").toList();

    /** Same claim, with the amount the claimant asks for. */
    static FNOLPayload payload(String policyNumber, String claimantName, String description, String claimedAmount) {
        FNOLPayload p = payload(policyNumber, claimantName, description);
        return new FNOLPayload(p.policyNumber(), p.incidentDate(), p.state(), p.description(), p.claimantName(),
                p.claimantDob(), p.claimantSsn(), p.evidenceUris(), p.channel(), null, null,
                new java.math.BigDecimal(claimedAmount), p.evidenceAngles());
    }

    static final class StubPolicyCenter implements PolicyCenterClient {
        final Map<String, Policy> policies = new ConcurrentHashMap<>();
        final Map<String, List<Coverage>> coverages = new ConcurrentHashMap<>();
        final Map<String, List<ClaimHistory>> history = new ConcurrentHashMap<>();

        void add(Policy policy, List<ClaimHistory> claims) {
            add(policy, autoCoverages(), claims);
        }

        void add(Policy policy, List<Coverage> covs, List<ClaimHistory> claims) {
            policies.put(policy.policyNumber(), policy);
            coverages.put(policy.policyNumber(), covs);
            history.put(policy.policyNumber(), claims);
        }

        @Override public Policy getPolicy(String n) { return policies.get(n); }
        @Override public List<Coverage> getCoverages(String n) { return coverages.getOrDefault(n, List.of()); }
        @Override public List<ClaimHistory> getPolicyClaimHistory(String n) { return history.getOrDefault(n, List.of()); }
    }

    static final class StubClaimCenter implements ClaimCenterClient {
        final AtomicInteger sequence = new AtomicInteger(1);
        final List<ClaimCreateRequest> created = Collections.synchronizedList(new ArrayList<>());

        @Override
        public ClaimCreateResponse createClaim(ClaimCreateRequest request) {
            if (createFails) throw new IllegalStateException("ClaimCenter rejected the claim");
            created.add(request);
            return new ClaimCreateResponse("CLM-2026-%06d".formatted(sequence.getAndIncrement()), "OPEN");
        }

        @Override
        public Claim getClaim(String claimId) {
            return new Claim(claimId, "OPEN", "POL-AUTO-112233", LocalDate.now(), "CA",
                    new BigDecimal("4200"), new BigDecimal("4830"));
        }

        final List<String> updates = Collections.synchronizedList(new ArrayList<>());
        final Map<String, List<ClaimAttachment>> attachments = new ConcurrentHashMap<>();
        volatile boolean attachmentsFail;
        volatile boolean createFails;

        @Override
        public void updateClaim(String claimId, ClaimUpdateRequest request) {
            updates.add(claimId + ":" + request.status());
        }

        @Override
        public void attachDocument(String claimId, ClaimAttachment attachment) {
            if (attachmentsFail) throw new IllegalStateException("ClaimCenter unavailable");
            attachments.computeIfAbsent(claimId, id -> Collections.synchronizedList(new ArrayList<>())).add(attachment);
        }

        final Map<String, List<CcExposure>> exposures = new ConcurrentHashMap<>();
        final Map<String, List<CcActivity>> activities = new ConcurrentHashMap<>();
        final Map<String, List<CcNote>> notes = new ConcurrentHashMap<>();

        @Override
        public CcExposure addExposure(String claimId, CcExposure exposure) {
            exposures.computeIfAbsent(claimId, id -> Collections.synchronizedList(new ArrayList<>())).add(exposure);
            return exposure;
        }

        @Override
        public CcActivity addActivity(String claimId, CcActivity activity) {
            activities.computeIfAbsent(claimId, id -> Collections.synchronizedList(new ArrayList<>())).add(activity);
            return activity;
        }

        @Override
        public CcNote addNote(String claimId, CcNote note) {
            notes.computeIfAbsent(claimId, id -> Collections.synchronizedList(new ArrayList<>())).add(note);
            return note;
        }
    }
}
