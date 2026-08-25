package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.agents.*;
import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import com.guidewire.fnol.guardrails.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
                mockCc
        );
    }

    @Test
    @DisplayName("FNOLOrchestrator executes end-to-end sequential workflow and produces explainable result")
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
}
