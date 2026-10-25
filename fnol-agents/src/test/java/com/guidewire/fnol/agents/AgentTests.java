package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTests {

    private FNOLPayload createPayload(String state, String description, String name, String dob, String ssn) {
        return new FNOLPayload(
                "POL-AUTO-112233",
                LocalDate.of(2026, 8, 16),
                state,
                description,
                name,
                dob,
                ssn,
                List.of("mock://photo/evidence-1.jpg"),
                null   // channel not required in tests
        );
    }

    @Test
    @DisplayName("VisionDamageAgent returns deterministic assessment from mock provider")
    void visionDamageAgent_returnsAssessment() {
        VisionProvider provider = new MockVisionProvider();
        VisionDamageAgent agent = new VisionDamageAgent(provider);

        DamageAssessment assessment = agent.execute(createPayload("CA", "Front end hit a barrier", null, null, null));
        assertThat(assessment.damageType()).isEqualTo("FRONT_BUMPER");
        assertThat(assessment.severity()).isEqualTo("MODERATE");
        assertThat(assessment.estimatedDamage()).isEqualByComparingTo("4200");
        assertThat(assessment.confidence()).isEqualByComparingTo("0.91");
        assertThat(assessment.photoQuality()).isEqualTo("CLEAR");
        assertThat(assessment.damageTypeProbabilities()).containsKey("FRONT_BUMPER");
        assertThat(assessment.damageTypeProbabilities().get("FRONT_BUMPER")).isEqualByComparingTo("0.91");
    }

    @Test
    @DisplayName("MockVisionProvider keywords: blurry lowers confidence, severe is HIGH damage, water is a home loss")
    void mockVisionProvider_keywords() {
        MockVisionProvider provider = new MockVisionProvider();
        java.util.function.Function<String, DamageAssessment> assess = uri -> provider.assess(
                new FNOLPayload("POL-AUTO-112233", LocalDate.now(), "CA", "loss", null, null, null, List.of(uri), null));

        assertThat(assess.apply("mock://photo/normal.jpg").confidence()).isEqualByComparingTo("0.91");
        assertThat(assess.apply("mock://photo/blurry.jpg").confidence()).isEqualByComparingTo("0.61");
        assertThat(assess.apply("mock://photo/severe.jpg").estimatedDamage()).isEqualByComparingTo("24000");
        assertThat(assess.apply("mock://photo/water.jpg").damageType()).isEqualTo("WATER_DAMAGE");
        // An uploaded photo whose base64 happens to contain a keyword is not treated as one.
        assertThat(assess.apply("data:image/png;base64,AAslowAAwaterAAsevereAAblurryAA").damageType()).isEqualTo("FRONT_BUMPER");
        assertThat(assess.apply("data:image/png;base64,AAslowAAwaterAAsevereAAblurryAA").confidence()).isEqualByComparingTo("0.91");
        assertThat(provider.assess(new FNOLPayload("POL-AUTO-112233", LocalDate.now(), "CA", "loss",
                null, null, null, List.of(), null)).confidence()).isEqualByComparingTo("0.60");
    }

    @Test
    @DisplayName("ReserveCalculatorAgent never reserves more than the AI estimate (no loading)")
    void reserveCalculatorAgent_computesCorrectReserve() {
        ReserveCalculatorAgent agent = new ReserveCalculatorAgent();

        DamageAssessment assessment = DamageAssessment.of("FRONT_BUMPER", "MODERATE", new BigDecimal("4200"), new BigDecimal("0.91"));
        ReserveResult result = agent.execute(assessment);

        assertThat(result.estimatedDamage()).isEqualByComparingTo("4200");
        assertThat(result.factor()).isEqualByComparingTo("1");
        assertThat(result.recommendedReserve()).isEqualByComparingTo("4200");
    }

    @Test
    @DisplayName("PiiRedactionAgent detects and masks SSN, DOB, and Full Name")
    void piiRedactionAgent_redactsAllDetectedTypes() {
        PiiRedactionAgent agent = new PiiRedactionAgent();
        FNOLPayload payload = createPayload(
                "CA",
                "Accident occurred on highway. Driver John Doe, SSN: 123-45-6789, DOB: 05/15/1990 reported the loss.",
                "John Doe",
                "05/15/1990",
                "123-45-6789"
        );

        PIIResult result = agent.execute(payload);

        assertThat(result.piiDetected()).isTrue();
        assertThat(result.detectedTypes()).contains("SSN", "DOB", "FULL_NAME");
        assertThat(result.redactedDescription())
                .doesNotContain("123-45-6789", "05/15/1990", "John Doe")
                .contains("***-**-****", "**/**/****", "[REDACTED_NAME]");
    }

    @Test
    @DisplayName("PiiRedactionAgent handles text without any PII")
    void piiRedactionAgent_handlesCleanText() {
        PiiRedactionAgent agent = new PiiRedactionAgent();
        FNOLPayload payload = createPayload("CA", "Rear fender scratch in parking lot with no injury.", null, null, null);

        PIIResult result = agent.execute(payload);

        assertThat(result.piiDetected()).isFalse();
        assertThat(result.detectedTypes()).isEmpty();
        assertThat(result.redactedDescription()).isEqualTo("Rear fender scratch in parking lot with no injury.");
    }

    @Test
    @DisplayName("StatutoryDeadlineAgent returns state-specific deadline for supported states")
    void statutoryDeadlineAgent_calculatesStateDeadlines() {
        StatutoryDeadlineAgent agent = new StatutoryDeadlineAgent(new SyntheticDeadlineRuleProvider());
        LocalDate incident = LocalDate.of(2026, 8, 16);

        // CA = 30 days
        DeadlineResult ca = agent.execute(new FNOLPayload("POL-AUTO-112233", incident, "CA", "loss", null, null, null, List.of(), null));
        assertThat(ca.deadline()).isEqualTo(incident.plusDays(30));

        // TX = 15 days
        DeadlineResult tx = agent.execute(new FNOLPayload("POL-AUTO-112233", incident, "TX", "loss", null, null, null, List.of(), null));
        assertThat(tx.deadline()).isEqualTo(incident.plusDays(15));

        // NY = 35 days
        DeadlineResult ny = agent.execute(new FNOLPayload("POL-AUTO-112233", incident, "NY", "loss", null, null, null, List.of(), null));
        assertThat(ny.deadline()).isEqualTo(incident.plusDays(35));

        // FL = 20 days
        DeadlineResult fl = agent.execute(new FNOLPayload("POL-AUTO-112233", incident, "FL", "loss", null, null, null, List.of(), null));
        assertThat(fl.deadline()).isEqualTo(incident.plusDays(20));

        // IL = 30 days
        DeadlineResult il = agent.execute(new FNOLPayload("POL-AUTO-112233", incident, "IL", "loss", null, null, null, List.of(), null));
        assertThat(il.deadline()).isEqualTo(incident.plusDays(30));
    }

    @Test
    @DisplayName("SubrogationScorerAgent calculates deterministic score with factor breakdown")
    void subrogationScorerAgent_scoresWithFactorBreakdown() {
        SubrogationScorerAgent agent = new SubrogationScorerAgent(new DeterministicSubrogationScorer());
        DamageAssessment damage = DamageAssessment.of("FRONT_BUMPER", "MODERATE", new BigDecimal("4200"), new BigDecimal("0.91"));

        FNOLPayload highSubro = createPayload("CA", "Rear-ended by other driver at stoplight. Police report filed.", null, null, null);
        SubrogationResult highResult = agent.execute(highSubro, damage);

        // 35 (third party) + 20 (police) + 15 (MODERATE)
        assertThat(highResult.score()).isEqualTo(70);
        assertThat(highResult.recommendedAction()).isEqualTo("INVESTIGATE");
        assertThat(highResult.factors()).containsKeys("thirdPartyMention", "policeReportSignal", "damageSeverity");

        FNOLPayload lowSubro = createPayload("CA", "Hit stationary post in garage.", null, null, null);
        SubrogationResult lowResult = agent.execute(lowSubro, DamageAssessment.of("DOOR", "MINOR", new BigDecimal("800"), new BigDecimal("0.95")));

        // 10 + 5 + 5 (MINOR)
        assertThat(lowResult.score()).isEqualTo(20);
        assertThat(lowResult.recommendedAction()).isEqualTo("MONITOR");
    }

    @Test
    @DisplayName("D12: subrogation severity points follow Branch A's real severity: MINOR 5, MODERATE 15, SEVERE 25")
    void subrogationScorer_usesRealSeverity() {
        SubrogationScorerAgent agent = new SubrogationScorerAgent(new DeterministicSubrogationScorer());
        FNOLPayload payload = createPayload("CA", "Hit stationary post in garage.", null, null, null);

        assertThat(agent.execute(payload, DamageAssessment.of("X", "MINOR", BigDecimal.ONE, BigDecimal.ONE))
                .factors().get("damageSeverity")).isEqualTo(5);
        assertThat(agent.execute(payload, DamageAssessment.of("X", "MODERATE", BigDecimal.ONE, BigDecimal.ONE))
                .factors().get("damageSeverity")).isEqualTo(15);
        assertThat(agent.execute(payload, DamageAssessment.of("X", "SEVERE", BigDecimal.ONE, BigDecimal.ONE))
                .factors().get("damageSeverity")).isEqualTo(25);
    }

    @Test
    @DisplayName("PolicyValidatorAgent validates active policy with matching collision coverage")
    void policyValidatorAgent_validatesCoveredPolicy() {
        PolicyCenterClient mockPc = new PolicyCenterClient() {
            public Policy getPolicy(String n) {
                return new Policy(n, "AUTO", "ACTIVE", LocalDate.now().minusMonths(6), LocalDate.now().plusMonths(6), "Avery Johnson", "CA", "Car", List.of());
            }
            public List<Coverage> getCoverages(String n) {
                return List.of(new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000")));
            }
            @Override
            public List<ClaimHistory> getPolicyClaimHistory(String policyNumber) {
                return List.of();
            }
        };

        PolicyValidatorAgent agent = new PolicyValidatorAgent(mockPc, new SyntheticCoverageRuleProvider());
        PolicyValidationResult result = agent.execute("POL-AUTO-112233");

        assertThat(result.policyFound()).isTrue();
        assertThat(result.policyActive()).isTrue();
        assertThat(result.relevantCoverageFound()).isTrue();
        assertThat(result.covered()).isTrue();
        assertThat(result.coverageType()).isEqualTo("COLLISION");
        assertThat(result.coverageLimit()).isEqualByComparingTo("50000");
        assertThat(result.deductible()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("PolicyValidatorAgent rejects an auto policy with only liability coverage")
    void policyValidatorAgent_handlesMissingCoverage() {
        PolicyCenterClient mockPc = new PolicyCenterClient() {
            public Policy getPolicy(String n) {
                return new Policy(n, "AUTO", "ACTIVE", LocalDate.now().minusMonths(6), LocalDate.now().plusMonths(6), "Avery Johnson", "CA", "Car", List.of());
            }
            public List<Coverage> getCoverages(String n) {
                return List.of(new Coverage("LIABILITY", new BigDecimal("100000"), new BigDecimal("0")));
            }
            @Override
            public List<ClaimHistory> getPolicyClaimHistory(String policyNumber) {
                return List.of();
            }
        };

        PolicyValidatorAgent agent = new PolicyValidatorAgent(mockPc, new SyntheticCoverageRuleProvider());
        PolicyValidationResult result = agent.execute("POL-AUTO-112233");

        assertThat(result.policyFound()).isTrue();
        assertThat(result.policyActive()).isTrue();
        assertThat(result.relevantCoverageFound()).isFalse();
        assertThat(result.covered()).isFalse();
    }

    @Test
    @DisplayName("AuditTrailAgent produces SHA-256 hashed records with unique IDs")
    void auditTrailAgent_recordsEvent() {
        AuditTrailAgent agent = new AuditTrailAgent();
        AuditRecord record = agent.record("CLM-001", "TestAgent", "input-data", "output-data", "SUCCESS");

        assertThat(record.claimId()).isEqualTo("CLM-001");
        assertThat(record.agentName()).isEqualTo("TestAgent");
        assertThat(record.inputHash()).hasSize(64);
        assertThat(record.outputHash()).hasSize(64);
        assertThat(record.status()).isEqualTo("SUCCESS");
        assertThat(record.timestamp()).isNotNull();
    }
}
