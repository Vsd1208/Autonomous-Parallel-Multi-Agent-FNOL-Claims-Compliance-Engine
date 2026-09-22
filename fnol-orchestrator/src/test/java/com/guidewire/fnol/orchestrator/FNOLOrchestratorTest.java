package com.guidewire.fnol.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.agents.*;
import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.common.ProcessingTimeoutException;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.ClaimHistoryEnricher;
import com.guidewire.fnol.enrichment.MockPolicyCenterClient;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import com.guidewire.fnol.guardrails.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FNOLOrchestratorTest {

    private FNOLOrchestrator orchestrator;
    private MockClaimCenterClient mockCc;
    private MockRestServiceServer mockServer;
    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;

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
    void setUp() throws Exception {
        // Create RestTemplate with MockRestServiceServer
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        objectMapper = new ObjectMapper().findAndRegisterModules();

        String baseUrl = "http://localhost:9090";

        // Mock Policy endpoint
        Policy mockPolicy = new Policy(
                "POL-AUTO-112233", "AUTO", "ACTIVE",
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                "Avery Johnson", "CA", "2025 Camry", List.of()
        );
        mockServer.expect(requestTo(baseUrl + "/pc/policies/POL-AUTO-112233"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(mockPolicy), MediaType.APPLICATION_JSON));

        // Mock Coverages endpoint
        List<Coverage> mockCoverages = List.of(
                new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000")),
                new Coverage("LIABILITY", new BigDecimal("100000"), new BigDecimal("500")),
                new Coverage("COMPREHENSIVE", new BigDecimal("30000"), new BigDecimal("250"))
        );
        mockServer.expect(requestTo(baseUrl + "/pc/policies/POL-AUTO-112233/coverages"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(mockCoverages), MediaType.APPLICATION_JSON));

        // Mock History endpoint (returns empty for test)
        List<ClaimHistory> emptyHistory = List.of();
        mockServer.expect(requestTo(baseUrl + "/pc/policies/POL-AUTO-112233/history"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(emptyHistory), MediaType.APPLICATION_JSON));

        // Create PolicyCenterClient with RestTemplate (original implementation)
        PolicyCenterClient policyClient = new MockPolicyCenterClient(restTemplate, baseUrl);

        // Create other agents
        InputGuardrail inputGuard = new InputGuardrail();
        OutputGuardrail outputGuard = new OutputGuardrail();
        PIIGuardrail piiGuard = new PIIGuardrail();
        ValidationGuardrail validationGuard = new ValidationGuardrail();

        VisionDamageAgent visionAgent = new VisionDamageAgent(new MockVisionProvider());
        PolicyValidatorAgent policyAgent = new PolicyValidatorAgent(policyClient);
        ReserveCalculatorAgent reserveAgent = new ReserveCalculatorAgent();
        PiiRedactionAgent piiAgent = new PiiRedactionAgent();
        StatutoryDeadlineAgent deadlineAgent = new StatutoryDeadlineAgent(new SyntheticDeadlineRuleProvider());
        SubrogationScorerAgent subroAgent = new SubrogationScorerAgent(new DeterministicSubrogationScorer());
        AuditTrailAgent auditAgent = new AuditTrailAgent();

        mockCc = new MockClaimCenterClient();

        // NEW: Create ClaimHistoryEnricher and ExplanationBuilder
        ClaimHistoryEnricher historyEnricher = new ClaimHistoryEnricher(policyClient);
        ExplanationBuilder explanationBuilder = new ExplanationBuilder();

        orchestrator = new FNOLOrchestrator(
                inputGuard, outputGuard, piiGuard, validationGuard,
                visionAgent, policyAgent, reserveAgent,
                piiAgent, deadlineAgent, subroAgent, auditAgent,
                mockCc,
                10L,  // timeout seconds
                historyEnricher,
                explanationBuilder
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

        // Policy context verification (NEW - Part 2)
        PolicyHistoryContext policyContext = response.policyContext();
        assertThat(policyContext).isNotNull();
        assertThat(policyContext.claimFrequencyRisk()).isEqualTo("LOW");

        // Explanation verification (CHANGED - Part 2)
        ExplanationObject explanation = response.explanation();
        assertThat(explanation).isNotNull();
        assertThat(explanation.decision()).isNotNull();
        assertThat(explanation.factors()).isNotEmpty();
        assertThat(explanation.factors()).hasSize(6);

        // Notes list verification — includes adjuster advisory from Reconciliation Safety Gate
        assertThat(response.notes()).isNotEmpty();
        assertThat(response.notes()).anyMatch(s -> s.contains("ADJUSTER ADVISORY"));
        assertThat(response.notes()).anyMatch(s -> s.contains("AI-assisted decision support"));
        assertThat(response.notes()).anyMatch(s -> s.contains("deterministic rules engine"));
        assertThat(response.notes()).anyMatch(s -> s.contains("parallel execution"));
        // Verify advisory reflects the gate decision (INVESTIGATE → HUMAN_REVIEW)
        assertThat(response.notes()).anyMatch(s -> s.contains("ADJUSTER ADVISORY"));
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
    @DisplayName("Parallel branches execute successfully with both results available")
    void process_parallelExecutionCompletes() {
        FNOLPayload payload = new FNOLPayload(
                "POL-AUTO-112233",
                LocalDate.now().minusDays(2),
                "CA",
                "Minor fender bender in parking lot",
                "Avery Johnson", "01/02/1980", "123-45-6789",
                List.of("mock://photo/front-bumper.jpg")
        );

        FNOLResponse response = orchestrator.process(payload);

        // Verify both branches executed successfully
        assertThat(response).isNotNull();
        assertThat(response.claimId()).isEqualTo("CLM-2026-000001");

        // Branch A results
        assertThat(response.branchA()).isNotNull();
        assertThat(response.branchA().damageAssessment()).isNotNull();
        assertThat(response.branchA().coverage()).isNotNull();
        assertThat(response.branchA().reserve()).isNotNull();

        // Branch B results
        assertThat(response.branchB()).isNotNull();
        assertThat(response.branchB().pii()).isNotNull();
        assertThat(response.branchB().deadline()).isNotNull();
        assertThat(response.branchB().subrogation()).isNotNull();

        // Part 2 additions
        assertThat(response.policyContext()).isNotNull();
        assertThat(response.explanation()).isNotNull();
        assertThat(response.explanation().factors()).hasSize(6);
    }

    @Test
    @DisplayName("Processing timeout throws ProcessingTimeoutException")
    void process_throwsTimeoutWhenBranchExceedsLimit() throws Exception {
        // Create a new orchestrator with 1-second timeout
        RestTemplate slowRestTemplate = new RestTemplate();
        MockRestServiceServer slowMockServer = MockRestServiceServer.createServer(slowRestTemplate);

        String baseUrl = "http://localhost:9090";

        // Mock Policy endpoint with slow response
        Policy mockPolicy = new Policy(
                "POL-AUTO-112233", "AUTO", "ACTIVE",
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                "Avery Johnson", "CA", "2025 Camry", List.of()
        );
        slowMockServer.expect(requestTo(baseUrl + "/pc/policies/POL-AUTO-112233"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(mockPolicy), MediaType.APPLICATION_JSON));

        // Mock Coverages endpoint
        List<Coverage> mockCoverages = List.of(
                new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000"))
        );
        slowMockServer.expect(requestTo(baseUrl + "/pc/policies/POL-AUTO-112233/coverages"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(mockCoverages), MediaType.APPLICATION_JSON));

        // Mock History endpoint
        List<ClaimHistory> emptyHistory = List.of();
        slowMockServer.expect(requestTo(baseUrl + "/pc/policies/POL-AUTO-112233/history"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(emptyHistory), MediaType.APPLICATION_JSON));

        PolicyCenterClient slowPolicyClient = new MockPolicyCenterClient(slowRestTemplate, baseUrl);

        VisionProvider sleepyVision = payload -> {
            try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new DamageAssessment("FRONT_BUMPER", "MODERATE", new BigDecimal("4200"), new BigDecimal("0.91"));
        };

        ClaimHistoryEnricher historyEnricher = new ClaimHistoryEnricher(slowPolicyClient);
        ExplanationBuilder explanationBuilder = new ExplanationBuilder();

        FNOLOrchestrator timeoutOrch = new FNOLOrchestrator(
                new InputGuardrail(), new OutputGuardrail(), new PIIGuardrail(), new ValidationGuardrail(),
                new VisionDamageAgent(sleepyVision),
                new PolicyValidatorAgent(slowPolicyClient),
                new ReserveCalculatorAgent(),
                new PiiRedactionAgent(),
                new StatutoryDeadlineAgent(new SyntheticDeadlineRuleProvider()),
                new SubrogationScorerAgent(new DeterministicSubrogationScorer()),
                new AuditTrailAgent(),
                new MockClaimCenterClient(),
                1L,  // 1-second timeout
                historyEnricher,
                explanationBuilder
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