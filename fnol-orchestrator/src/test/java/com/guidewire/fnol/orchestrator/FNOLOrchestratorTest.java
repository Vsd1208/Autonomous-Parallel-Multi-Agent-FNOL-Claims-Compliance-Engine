package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.agents.*;
import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.common.ProcessingTimeoutException;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import com.guidewire.fnol.guardrails.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FNOLOrchestratorTest {

    private FNOLOrchestrator orchestrator;
    private MockClaimCenterClient mockCc;

    static class MockClaimCenterClient implements ClaimCenterClient {
        private ClaimCreateRequest lastRequest;

        public ClaimCreateResponse createClaim(ClaimCreateRequest request) {
            this.lastRequest = request;
            return new ClaimCreateResponse("CLM-2026-000001", "OPEN");
        }

        public Claim getClaim(String claimId) {
            return new Claim(claimId, "OPEN", "POL-AUTO-112233", LocalDate.now(), "CA", new BigDecimal("4200"), new BigDecimal("4830"));
        }
    }

    @BeforeEach
    void setUp() {
        InputGuardrail inputGuard = new InputGuardrail();
        OutputGuardrail outputGuard = new OutputGuardrail();
        PIIGuardrail piiGuard = new PIIGuardrail();
        ValidationGuardrail validationGuard = new ValidationGuardrail();

        VisionDamageAgent visionAgent = new VisionDamageAgent(new MockVisionProvider());

        PolicyCenterClient mockPc = new PolicyCenterClient() {
            public Policy getPolicy(String n) {
                return new Policy(n, "AUTO", "ACTIVE", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), "Avery Johnson", "CA", "2025 Camry", List.of());
            }
            public List<Coverage> getCoverages(String n) {
                return List.of(new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000")));
            }
            public List<ClaimHistory> getPolicyHistory(String n) {
                return List.of();
            }
        };
        PolicyValidatorAgent policyAgent = new PolicyValidatorAgent(mockPc);
        ReserveCalculatorAgent reserveAgent = new ReserveCalculatorAgent();
        PiiRedactionAgent piiAgent = new PiiRedactionAgent();
        StatutoryDeadlineAgent deadlineAgent = new StatutoryDeadlineAgent(new SyntheticDeadlineRuleProvider());
        SubrogationScorerAgent subroAgent = new SubrogationScorerAgent(new DeterministicSubrogationScorer());
        AuditTrailAgent auditAgent = new AuditTrailAgent();

        mockCc = new MockClaimCenterClient();

        orchestrator = new FNOLOrchestrator(
                inputGuard, outputGuard, piiGuard, validationGuard,
                visionAgent, policyAgent, reserveAgent,
                piiAgent, deadlineAgent, subroAgent, auditAgent,
                mockCc,
                10L  // timeout seconds
        );
    }

    @Test
    @DisplayName("FNOLOrchestrator executes end-to-end parallel workflow and produces explainable result")
    void process_executesEndToEndSuccessfully() {
        FNOLPayload payload = new FNOLPayload(
                "POL-AUTO-112233",
                LocalDate.now().minusDays(2),
                "CA",
                "Rear-ended by other driver at intersection. Police report filed. Avery Johnson SSN 123-45-6789 reported.",
                "Avery Johnson",
                "01/02/1980",
                "123-45-6789",
                List.of("mock://photo/front-bumper.jpg")
        );

        FNOLResponse response = orchestrator.process(payload);

        assertThat(response).isNotNull();
        assertThat(response.claimId()).isEqualTo("CLM-2026-000001");
        assertThat(response.status()).isEqualTo("OPEN");

        // Branch A verification
        BranchAResult branchA = response.branchA();
        assertThat(branchA.damageAssessment().damageType()).isEqualTo("FRONT_BUMPER");
        assertThat(branchA.damageAssessment().severity()).isEqualTo("MODERATE");
        assertThat(branchA.coverage().covered()).isTrue();
        assertThat(branchA.coverage().coverageType()).isEqualTo("COLLISION");
        assertThat(branchA.reserve().recommendedReserve()).isEqualByComparingTo("4830");

        // Branch B verification
        BranchBResult branchB = response.branchB();
        assertThat(branchB.pii().piiDetected()).isTrue();
        assertThat(branchB.pii().redactedDescription()).doesNotContain("123-45-6789");
        assertThat(branchB.deadline().state()).isEqualTo("CA");
        assertThat(branchB.subrogation().recommendedAction()).isEqualTo("INVESTIGATE");
        assertThat(branchB.audit()).hasSize(4);

        // Explanation list verification
        assertThat(response.explanation()).isNotEmpty();
        assertThat(response.explanation()).anyMatch(s -> s.contains("AI-assisted decision support"));
        assertThat(response.explanation()).anyMatch(s -> s.contains("parallel execution"));
    }

    @Test
    @DisplayName("FNOLOrchestrator halts when input fails guardrail validation")
    void process_failsOnInvalidInput() {
        FNOLPayload badPayload = new FNOLPayload(
                "BAD-POLICY-ID",
                LocalDate.now(),
                "CA",
                "Valid description",
                null, null, null, List.of()
        );

        assertThatThrownBy(() -> orchestrator.process(badPayload))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Parallel branches complete faster than sequential sum")
    void process_parallelExecutionIsFasterThanSequential() {
        // Create an orchestrator with slow mocks to measure parallelism
        final AtomicLong branchAStart = new AtomicLong();
        final AtomicLong branchAEnd = new AtomicLong();
        final AtomicLong branchBStart = new AtomicLong();
        final AtomicLong branchBEnd = new AtomicLong();

        // Slow vision agent that takes 200ms
        VisionProvider slowVision = payload -> {
            branchAStart.set(System.currentTimeMillis());
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            branchAEnd.set(System.currentTimeMillis());
            return new DamageAssessment("FRONT_BUMPER", "MODERATE", new BigDecimal("4200"), new BigDecimal("0.91"));
        };

        // Slow PII agent that records timing
        PiiRedactionAgent slowPii = new PiiRedactionAgent() {
            @Override
            public PIIResult execute(FNOLPayload payload) {
                branchBStart.set(System.currentTimeMillis());
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                PIIResult result = super.execute(payload);
                branchBEnd.set(System.currentTimeMillis());
                return result;
            }
        };

        PolicyCenterClient mockPc = new PolicyCenterClient() {
            public Policy getPolicy(String n) {
                return new Policy(n, "AUTO", "ACTIVE", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), "Avery Johnson", "CA", "2025 Camry", List.of());
            }
            public List<Coverage> getCoverages(String n) {
                return List.of(new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000")));
            }
            public List<ClaimHistory> getPolicyHistory(String n) {
                return List.of();
            }
        };

        FNOLOrchestrator parallelOrch = new FNOLOrchestrator(
                new InputGuardrail(), new OutputGuardrail(), new PIIGuardrail(), new ValidationGuardrail(),
                new VisionDamageAgent(slowVision),
                new PolicyValidatorAgent(mockPc),
                new ReserveCalculatorAgent(),
                slowPii,
                new StatutoryDeadlineAgent(new SyntheticDeadlineRuleProvider()),
                new SubrogationScorerAgent(new DeterministicSubrogationScorer()),
                new AuditTrailAgent(),
                new MockClaimCenterClient(),
                10L
        );

        FNOLPayload payload = new FNOLPayload(
                "POL-AUTO-112233",
                LocalDate.now().minusDays(2),
                "CA",
                "Rear-ended by other driver at intersection. Police report filed.",
                "Avery Johnson", "01/02/1980", "123-45-6789",
                List.of("mock://photo/front-bumper.jpg")
        );

        long wallStart = System.currentTimeMillis();
        FNOLResponse response = parallelOrch.process(payload);
        long wallTime = System.currentTimeMillis() - wallStart;

        assertThat(response).isNotNull();
        assertThat(response.claimId()).isEqualTo("CLM-2026-000001");

        // Both branches ran — verify overlapping execution windows
        // In sequential execution, wall time would be >= 400ms (200 + 200).
        // In parallel execution, wall time should be closer to ~200ms.
        long branchADuration = branchAEnd.get() - branchAStart.get();
        long branchBDuration = branchBEnd.get() - branchBStart.get();

        assertThat(branchADuration).as("Branch A should take ~200ms").isGreaterThanOrEqualTo(180);
        assertThat(branchBDuration).as("Branch B should take ~200ms").isGreaterThanOrEqualTo(180);

        // The wall clock time should be less than the sum of both branch durations
        // (proving they ran in parallel, not sequentially)
        assertThat(wallTime)
                .as("Parallel wall time (%dms) should be less than sequential sum (%dms + %dms = %dms)",
                        wallTime, branchADuration, branchBDuration, branchADuration + branchBDuration)
                .isLessThan(branchADuration + branchBDuration);
    }

    @Test
    @DisplayName("Processing timeout throws ProcessingTimeoutException")
    void process_throwsTimeoutWhenBranchExceedsLimit() {
        // Create an orchestrator with a 1-second timeout and a vision agent that sleeps for 3 seconds
        VisionProvider sleepyVision = payload -> {
            try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new DamageAssessment("FRONT_BUMPER", "MODERATE", new BigDecimal("4200"), new BigDecimal("0.91"));
        };

        PolicyCenterClient mockPc = new PolicyCenterClient() {
            public Policy getPolicy(String n) {
                return new Policy(n, "AUTO", "ACTIVE", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), "Avery Johnson", "CA", "2025 Camry", List.of());
            }
            public List<Coverage> getCoverages(String n) {
                return List.of(new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000")));
            }
            public List<ClaimHistory> getPolicyHistory(String n) {
                return List.of();
            }
        };

        FNOLOrchestrator timeoutOrch = new FNOLOrchestrator(
                new InputGuardrail(), new OutputGuardrail(), new PIIGuardrail(), new ValidationGuardrail(),
                new VisionDamageAgent(sleepyVision),
                new PolicyValidatorAgent(mockPc),
                new ReserveCalculatorAgent(),
                new PiiRedactionAgent(),
                new StatutoryDeadlineAgent(new SyntheticDeadlineRuleProvider()),
                new SubrogationScorerAgent(new DeterministicSubrogationScorer()),
                new AuditTrailAgent(),
                new MockClaimCenterClient(),
                1L  // 1-second timeout — will be exceeded by the 3-second vision agent
        );

        FNOLPayload payload = new FNOLPayload(
                "POL-AUTO-112233",
                LocalDate.now().minusDays(2),
                "CA",
                "Minor fender bender in parking lot",
                "Avery Johnson", "01/02/1980", "123-45-6789",
                List.of("mock://photo/front-bumper.jpg")
        );

        assertThatThrownBy(() -> timeoutOrch.process(payload))
                .isInstanceOf(ProcessingTimeoutException.class)
                .hasMessageContaining("timed out");
    }
}
