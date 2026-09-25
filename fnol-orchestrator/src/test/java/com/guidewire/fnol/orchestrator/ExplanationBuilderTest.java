package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.common.Models.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(MockitoJUnitRunner.class)
public class ExplanationBuilderTest {

    private ExplanationBuilder builder;

    @Before
    public void setup() {
        builder = new ExplanationBuilder();
    }
    

    /**
     * Test 2: Low confidence → HUMAN_REVIEW
     */
    @Test
    public void testLowConfidenceResultsInHumanReview() {
        PolicyValidationResult coverage = new PolicyValidationResult(
                "POL-001", true, true, true, true, "COLLISION",
                new BigDecimal("50000"), new BigDecimal("1000"), List.of()
        );

        DamageAssessment damage = new DamageAssessment(
                "BUMPER", "MODERATE", new BigDecimal("4200"), new BigDecimal("0.72")
        );

        ReserveResult reserve = new ReserveResult(
                new BigDecimal("4200"), new BigDecimal("1.15"), new BigDecimal("4830")
        );

        PIIResult pii = new PIIResult("Redacted text", List.of(), false);
        SubrogationResult subrogation = new SubrogationResult(45, "ACCEPT", Map.of());
        PolicyHistoryContext history = new PolicyHistoryContext(
                0, 0, 0, null, null, null, 0, "LOW"
        );

        ExplanationObject result = builder.build(coverage, damage, reserve, pii, subrogation, history);

        assertEquals("ADVISORY_RECOMMENDED_ACTION", result.decision());

        ExplanationFactor confidenceFactor = result.factors().stream()
                .filter(f -> "AI Confidence".equals(f.factor()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("AI Confidence factor not found"));

        assertEquals("FAIL", confidenceFactor.status());
    }

    /**
     * Test 3: Coverage not covered → HUMAN_REVIEW
     */
    @Test
    public void testUncoveredClaimResultsInHumanReview() {
        PolicyValidationResult coverage = new PolicyValidationResult(
                "POL-001", true, true, true, false, "LIABILITY",
                new BigDecimal("50000"), new BigDecimal("1000"), List.of()
        );

        DamageAssessment damage = new DamageAssessment(
                "BUMPER", "MODERATE", new BigDecimal("4200"), new BigDecimal("0.91")
        );

        ReserveResult reserve = new ReserveResult(
                new BigDecimal("4200"), new BigDecimal("1.15"), new BigDecimal("4830")
        );

        PIIResult pii = new PIIResult("Redacted text", List.of(), false);
        SubrogationResult subrogation = new SubrogationResult(45, "ACCEPT", Map.of());
        PolicyHistoryContext history = new PolicyHistoryContext(
                0, 0, 0, null, null, null, 0, "LOW"
        );

        ExplanationObject result = builder.build(coverage, damage, reserve, pii, subrogation, history);

        assertEquals("ADVISORY_RECOMMENDED_ACTION", result.decision());

        ExplanationFactor coverageFactor = result.factors().stream()
                .filter(f -> "Coverage Status".equals(f.factor()))
                .findFirst()
                .orElseThrow();

        assertEquals("FAIL", coverageFactor.status());
    }

    /**
     * Test 4: PII detected → HUMAN_REVIEW
     */
    @Test
    public void testPIIDetectedResultsInHumanReview() {
        PolicyValidationResult coverage = new PolicyValidationResult(
                "POL-001", true, true, true, true, "COLLISION",
                new BigDecimal("50000"), new BigDecimal("1000"), List.of()
        );

        DamageAssessment damage = new DamageAssessment(
                "BUMPER", "MODERATE", new BigDecimal("4200"), new BigDecimal("0.91")
        );

        ReserveResult reserve = new ReserveResult(
                new BigDecimal("4200"), new BigDecimal("1.15"), new BigDecimal("4830")
        );

        PIIResult pii = new PIIResult("Description with PII", List.of("SSN", "DOB"), true);

        SubrogationResult subrogation = new SubrogationResult(45, "ACCEPT", Map.of());
        PolicyHistoryContext history = new PolicyHistoryContext(
                0, 0, 0, null, null, null, 0, "LOW"
        );

        ExplanationObject result = builder.build(coverage, damage, reserve, pii, subrogation, history);

        assertEquals("ADVISORY_RECOMMENDED_ACTION", result.decision());

        ExplanationFactor complianceFactor = result.factors().stream()
                .filter(f -> "Compliance (PII)".equals(f.factor()))
                .findFirst()
                .orElseThrow();

        assertEquals("FAIL", complianceFactor.status());
    }

    /**
     * Test 5: Reserve exceeds $25,000 → HUMAN_REVIEW
     */
    @Test
    public void testHighReserveResultsInHumanReview() {
        PolicyValidationResult coverage = new PolicyValidationResult(
                "POL-001", true, true, true, true, "COLLISION",
                new BigDecimal("50000"), new BigDecimal("1000"), List.of()
        );

        DamageAssessment damage = new DamageAssessment(
                "BUMPER", "MODERATE", new BigDecimal("30000"), new BigDecimal("0.91")
        );

        ReserveResult reserve = new ReserveResult(
                new BigDecimal("30000"), new BigDecimal("1.15"), new BigDecimal("34500")
        );

        PIIResult pii = new PIIResult("Redacted text", List.of(), false);
        SubrogationResult subrogation = new SubrogationResult(45, "ACCEPT", Map.of());
        PolicyHistoryContext history = new PolicyHistoryContext(
                0, 0, 0, null, null, null, 0, "LOW"
        );

        ExplanationObject result = builder.build(coverage, damage, reserve, pii, subrogation, history);

        assertEquals("ADVISORY_RECOMMENDED_ACTION", result.decision());
        // Damage is $30k, so HIGH severity → always escalate
        assertEquals("ESCALATE_TO_HUMAN_HIGH_RISK_SEVERITY", result.recommendedAction());
        assertEquals("HIGH", result.severityGate());

        ExplanationFactor reserveFactor = result.factors().stream()
                .filter(f -> "Reserve Amount".equals(f.factor()))
                .findFirst()
                .orElseThrow();

        assertEquals("FAIL", reserveFactor.status());
    }

    /**
     * Test 6: HIGH frequency risk → HUMAN_REVIEW
     */
    @Test
    public void testHighFrequencyRiskResultsInHumanReview() {
        PolicyValidationResult coverage = new PolicyValidationResult(
                "POL-001", true, true, true, true, "COLLISION",
                new BigDecimal("50000"), new BigDecimal("1000"), List.of()
        );

        DamageAssessment damage = new DamageAssessment(
                "BUMPER", "MODERATE", new BigDecimal("4200"), new BigDecimal("0.91")
        );

        ReserveResult reserve = new ReserveResult(
                new BigDecimal("4200"), new BigDecimal("1.15"), new BigDecimal("4830")
        );

        PIIResult pii = new PIIResult("Redacted text", List.of(), false);
        SubrogationResult subrogation = new SubrogationResult(45, "ACCEPT", Map.of());

        PolicyHistoryContext history = new PolicyHistoryContext(
                5, 2, 3, LocalDate.now().minusDays(45), "COLLISION",
                new BigDecimal("3500"), 0, "HIGH"
        );

        ExplanationObject result = builder.build(coverage, damage, reserve, pii, subrogation, history);

        assertEquals("ADVISORY_RECOMMENDED_ACTION", result.decision());

        ExplanationFactor historyFactor = result.factors().stream()
                .filter(f -> "Claim History".equals(f.factor()))
                .findFirst()
                .orElseThrow();

        assertEquals("FAIL", historyFactor.status());
    }

    /**
     * Test 7: High subrogation score → HUMAN_REVIEW
     */
    @Test
    public void testHighSubrogationScoreResultsInHumanReview() {
        PolicyValidationResult coverage = new PolicyValidationResult(
                "POL-001", true, true, true, true, "COLLISION",
                new BigDecimal("50000"), new BigDecimal("1000"), List.of()
        );

        DamageAssessment damage = new DamageAssessment(
                "BUMPER", "MODERATE", new BigDecimal("4200"), new BigDecimal("0.91")
        );

        ReserveResult reserve = new ReserveResult(
                new BigDecimal("4200"), new BigDecimal("1.15"), new BigDecimal("4830")
        );

        PIIResult pii = new PIIResult("Redacted text", List.of(), false);
        SubrogationResult subrogation = new SubrogationResult(85, "INVESTIGATE", Map.of("thirdPartyMention", 50));

        PolicyHistoryContext history = new PolicyHistoryContext(
                0, 0, 0, null, null, null, 0, "LOW"
        );

        ExplanationObject result = builder.build(coverage, damage, reserve, pii, subrogation, history);

        assertEquals("ADVISORY_RECOMMENDED_ACTION", result.decision());
        assertEquals("ESCALATE_TO_HUMAN", result.recommendedAction());
        assertEquals("LOW", result.severityGate());

        ExplanationFactor subrogationFactor = result.factors().stream()
                .filter(f -> "Subrogation Risk".equals(f.factor()))
                .findFirst()
                .orElseThrow();

        assertEquals("FAIL", subrogationFactor.status());
    }

    /**
     * Test 8: LOW severity with all factors PASS → PREFILL_LOW_SEVERITY_CLAIM
     */
    @Test
    public void testLowSeverityAllPassResultsInPrefill() {
        PolicyValidationResult coverage = new PolicyValidationResult(
                "POL-001", true, true, true, true, "COLLISION",
                new BigDecimal("50000"), new BigDecimal("1000"), List.of()
        );

        DamageAssessment damage = new DamageAssessment(
                "GLASS_ONLY", "MINOR", new BigDecimal("2500"), new BigDecimal("0.91")
        );

        ReserveResult reserve = new ReserveResult(
                new BigDecimal("2500"), new BigDecimal("1.15"), new BigDecimal("2875")
        );

        PIIResult pii = new PIIResult("Redacted text", List.of(), false);
        SubrogationResult subrogation = new SubrogationResult(45, "ACCEPT", Map.of());
        PolicyHistoryContext history = new PolicyHistoryContext(
                0, 0, 0, null, null, null, 0, "LOW"
        );

        ExplanationObject result = builder.build(coverage, damage, reserve, pii, subrogation, history);

        assertEquals("ADVISORY_RECOMMENDED_ACTION", result.decision());
        assertEquals("PREFILL_LOW_SEVERITY_CLAIM", result.recommendedAction());
        assertEquals("LOW", result.severityGate());
        assertTrue("All factors should PASS",
                result.factors().stream().allMatch(f -> "PASS".equals(f.status())));
    }

    /**
     * Test 9: LOW severity but low confidence → ESCALATE_TO_HUMAN
     */
    @Test
    public void testLowSeverityLowConfidenceEscalates() {
        PolicyValidationResult coverage = new PolicyValidationResult(
                "POL-001", true, true, true, true, "COLLISION",
                new BigDecimal("50000"), new BigDecimal("1000"), List.of()
        );

        DamageAssessment damage = new DamageAssessment(
                "GLASS_ONLY", "MINOR", new BigDecimal("2500"), new BigDecimal("0.72")  // LOW confidence
        );

        ReserveResult reserve = new ReserveResult(
                new BigDecimal("2500"), new BigDecimal("1.15"), new BigDecimal("2875")
        );

        PIIResult pii = new PIIResult("Redacted text", List.of(), false);
        SubrogationResult subrogation = new SubrogationResult(45, "ACCEPT", Map.of());
        PolicyHistoryContext history = new PolicyHistoryContext(
                0, 0, 0, null, null, null, 0, "LOW"
        );

        ExplanationObject result = builder.build(coverage, damage, reserve, pii, subrogation, history);

        assertEquals("ADVISORY_RECOMMENDED_ACTION", result.decision());
        assertEquals("ESCALATE_TO_HUMAN", result.recommendedAction());
        assertEquals("LOW", result.severityGate());

        ExplanationFactor confidenceFactor = result.factors().stream()
                .filter(f -> "AI Confidence".equals(f.factor()))
                .findFirst()
                .orElseThrow();

        assertEquals("FAIL", confidenceFactor.status());
    }

    /**
     * Test 10: MEDIUM severity always escalates regardless of factors
     */
    @Test
    public void testMediumSeverityAlwaysEscalates() {
        PolicyValidationResult coverage = new PolicyValidationResult(
                "POL-001", true, true, true, true, "COLLISION",
                new BigDecimal("50000"), new BigDecimal("1000"), List.of()
        );

        DamageAssessment damage = new DamageAssessment(
                "DOOR_PANEL", "MODERATE", new BigDecimal("12000"), new BigDecimal("0.91")  // MEDIUM severity
        );

        ReserveResult reserve = new ReserveResult(
                new BigDecimal("12000"), new BigDecimal("1.15"), new BigDecimal("13800")
        );

        PIIResult pii = new PIIResult("Redacted text", List.of(), false);
        SubrogationResult subrogation = new SubrogationResult(45, "ACCEPT", Map.of());
        PolicyHistoryContext history = new PolicyHistoryContext(
                0, 0, 0, null, null, null, 0, "LOW"
        );

        ExplanationObject result = builder.build(coverage, damage, reserve, pii, subrogation, history);

        assertEquals("ADVISORY_RECOMMENDED_ACTION", result.decision());
        assertEquals("ESCALATE_TO_HUMAN_HIGH_RISK_SEVERITY", result.recommendedAction());
        assertEquals("MEDIUM", result.severityGate());
    }

    /**
     * Test 11: HIGH severity always escalates
     */
    @Test
    public void testHighSeverityAlwaysEscalates() {
        PolicyValidationResult coverage = new PolicyValidationResult(
                "POL-001", true, true, true, true, "COLLISION",
                new BigDecimal("50000"), new BigDecimal("1000"), List.of()
        );

        DamageAssessment damage = new DamageAssessment(
                "STRUCTURAL_DAMAGE", "SEVERE", new BigDecimal("35000"), new BigDecimal("0.91")  // HIGH severity
        );

        ReserveResult reserve = new ReserveResult(
                new BigDecimal("35000"), new BigDecimal("1.15"), new BigDecimal("40250")
        );

        PIIResult pii = new PIIResult("Redacted text", List.of(), false);
        SubrogationResult subrogation = new SubrogationResult(45, "ACCEPT", Map.of());
        PolicyHistoryContext history = new PolicyHistoryContext(
                0, 0, 0, null, null, null, 0, "LOW"
        );

        ExplanationObject result = builder.build(coverage, damage, reserve, pii, subrogation, history);

        assertEquals("ADVISORY_RECOMMENDED_ACTION", result.decision());
        assertEquals("ESCALATE_TO_HUMAN_HIGH_RISK_SEVERITY", result.recommendedAction());
        assertEquals("HIGH", result.severityGate());
    }

    /**
     * Test 12: LOW severity + HIGH frequency risk → block prefill
     */
    @Test
    public void testHighFrequencyRiskBlocksPrefill() {
        PolicyValidationResult coverage = new PolicyValidationResult(
                "POL-001", true, true, true, true, "COLLISION",
                new BigDecimal("50000"), new BigDecimal("1000"), List.of()
        );

        DamageAssessment damage = new DamageAssessment(
                "GLASS_ONLY", "MINOR", new BigDecimal("2500"), new BigDecimal("0.91")
        );

        ReserveResult reserve = new ReserveResult(
                new BigDecimal("2500"), new BigDecimal("1.15"), new BigDecimal("2875")
        );

        PIIResult pii = new PIIResult("Redacted text", List.of(), false);
        SubrogationResult subrogation = new SubrogationResult(45, "ACCEPT", Map.of());
        PolicyHistoryContext history = new PolicyHistoryContext(
                5, 2, 3, LocalDate.now().minusDays(45), "COLLISION",
                new BigDecimal("3500"), 0, "HIGH"  // HIGH frequency risk
        );

        ExplanationObject result = builder.build(coverage, damage, reserve, pii, subrogation, history);

        assertEquals("ESCALATE_TO_HUMAN", result.recommendedAction());
        assertEquals("LOW", result.severityGate());

        ExplanationFactor historyFactor = result.factors().stream()
                .filter(f -> "Claim History".equals(f.factor()))
                .findFirst()
                .orElseThrow();

        assertEquals("FAIL", historyFactor.status());
    }
}