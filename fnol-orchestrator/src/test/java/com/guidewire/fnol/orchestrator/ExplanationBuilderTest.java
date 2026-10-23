package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.common.Models.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** P8: each of the six factors can flip an otherwise STP-eligible claim to a human. */
class ExplanationBuilderTest {

    private StpProperties stp;
    private ExplanationBuilder builder;

    // Baseline: every factor passes, LOW severity.
    private PolicyValidationResult coverage;
    private DamageAssessment damage;
    private ReserveResult reserve;
    private PIIResult pii;
    private SubrogationResult subrogation;
    private PolicyHistoryContext history;

    @BeforeEach
    void setup() {
        stp = new StpProperties();
        builder = new ExplanationBuilder(stp);
        coverage = new PolicyValidationResult("POL-001", true, true, true, true, "COLLISION",
                new BigDecimal("50000"), new BigDecimal("1000"), List.of());
        damage = DamageAssessment.of("FRONT_BUMPER", "MODERATE", new BigDecimal("4200"), new BigDecimal("0.91"));
        reserve = new ReserveResult(new BigDecimal("4200"), new BigDecimal("1.15"), new BigDecimal("4830"));
        pii = new PIIResult("Redacted text", List.of(), false, new BigDecimal("0.95"));
        subrogation = new SubrogationResult(30, "MONITOR", Map.of());
        history = new PolicyHistoryContext(0, 0, 0, null, null, null, 0, "LOW");
    }

    private ExplanationObject build() {
        return builder.build(coverage, damage, reserve, pii, subrogation, history);
    }

    private static ExplanationFactor factor(ExplanationObject e, String name) {
        return e.factors().stream().filter(f -> name.equals(f.factor())).findFirst().orElseThrow();
    }

    private static void assertEscalatedBy(ExplanationObject e, String failingFactor) {
        assertThat(e.decision()).isEqualTo("ADVISORY_RECOMMENDED_ACTION");
        assertThat(e.recommendedAction()).isEqualTo("ESCALATE_TO_HUMAN");
        assertThat(e.factors()).filteredOn(f -> "FAIL".equals(f.status()))
                .extracting(ExplanationFactor::factor).containsExactly(failingFactor);
    }

    @Test
    @DisplayName("Baseline: all six factors pass at LOW severity → STRAIGHT_THROUGH")
    void allFactorsPassResultsInStraightThrough() {
        ExplanationObject result = build();

        assertThat(result.decision()).isEqualTo("STRAIGHT_THROUGH");
        assertThat(result.recommendedAction()).isEqualTo("APPROVE_AND_STP");
        assertThat(result.severityGate()).isEqualTo("LOW");
        assertThat(result.factors()).hasSize(6).allMatch(f -> "PASS".equals(f.status()));
    }

    @Test
    @DisplayName("Factor 1: confidence below 0.85 flips the route")
    void lowConfidenceFlipsRoute() {
        damage = DamageAssessment.of("FRONT_BUMPER", "MODERATE", new BigDecimal("4200"), new BigDecimal("0.84"));
        assertEscalatedBy(build(), "AI Confidence");
    }

    @Test
    @DisplayName("Factor 2: uncovered claim flips the route")
    void uncoveredFlipsRoute() {
        coverage = new PolicyValidationResult("POL-001", true, true, false, false, null,
                BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        assertEscalatedBy(build(), "Coverage Status");
    }

    @Test
    @DisplayName("Factor 3: PII detected with redaction confidence below 0.90 flips the route")
    void lowConfidencePiiRedactionFlipsRoute() {
        pii = new PIIResult("Redacted", List.of("SSN"), true, new BigDecimal("0.80"));
        assertEscalatedBy(build(), "Compliance (PII)");
    }

    @Test
    @DisplayName("D11: PII detected and redacted with confidence >= 0.90 passes")
    void redactedPiiPasses() {
        pii = new PIIResult("***-**-**** reported", List.of("SSN", "DOB"), true, new BigDecimal("0.95"));
        ExplanationObject result = build();

        assertThat(factor(result, "Compliance (PII)").status()).isEqualTo("PASS");
        assertThat(factor(result, "Compliance (PII)").value()).contains("REDACTED");
        assertThat(result.decision()).isEqualTo("STRAIGHT_THROUGH");
    }

    @Test
    @DisplayName("Factor 4: reserve above $25,000 flips a MEDIUM claim when the medium ceiling allows it")
    void highReserveFlipsRoute() {
        stp.setMediumSeverityMax(new BigDecimal("30000"));   // lets the reserve ceiling bind
        damage = DamageAssessment.of("SIDE_PANEL", "SEVERE", new BigDecimal("22000"), new BigDecimal("0.91"));
        reserve = new ReserveResult(new BigDecimal("22000"), new BigDecimal("1.15"), new BigDecimal("25300"));

        ExplanationObject result = build();
        assertThat(result.severityGate()).isEqualTo("MEDIUM");
        assertEscalatedBy(result, "Reserve Amount");
    }

    @Test
    @DisplayName("Factor 5: HIGH claim frequency flips the route")
    void highFrequencyFlipsRoute() {
        history = new PolicyHistoryContext(2, 1, 2, LocalDate.now().minusDays(45), "CLOSED",
                new BigDecimal("3500"), 0, "HIGH");
        assertEscalatedBy(build(), "Claim History");
    }

    @Test
    @DisplayName("Factor 6: subrogation score of 70 flips the route")
    void subrogationAtThresholdFlipsRoute() {
        subrogation = new SubrogationResult(70, "INVESTIGATE", Map.of());
        assertEscalatedBy(build(), "Subrogation Risk");
    }

    @Test
    @DisplayName("D13: MEDIUM severity with all factors passing is STRAIGHT_THROUGH")
    void mediumSeverityAllPassIsStraightThrough() {
        damage = DamageAssessment.of("SIDE_PANEL", "MODERATE", new BigDecimal("12000"), new BigDecimal("0.91"));
        reserve = new ReserveResult(new BigDecimal("12000"), new BigDecimal("1.15"), new BigDecimal("13800"));

        ExplanationObject result = build();
        assertThat(result.severityGate()).isEqualTo("MEDIUM");
        assertThat(result.decision()).isEqualTo("STRAIGHT_THROUGH");
    }

    @Test
    @DisplayName("HIGH severity always goes to a human, even with all factors passing")
    void highSeverityAlwaysEscalates() {
        damage = DamageAssessment.of("MULTIPLE", "SEVERE", new BigDecimal("21000"), new BigDecimal("0.95"));
        reserve = new ReserveResult(new BigDecimal("21000"), new BigDecimal("1.15"), new BigDecimal("24150"));

        ExplanationObject result = build();
        assertThat(result.severityGate()).isEqualTo("HIGH");
        assertThat(result.factors()).allMatch(f -> "PASS".equals(f.status()));
        assertThat(result.decision()).isEqualTo("ADVISORY_RECOMMENDED_ACTION");
        assertThat(result.recommendedAction()).isEqualTo("ESCALATE_TO_HUMAN_HIGH_RISK_SEVERITY");
    }

    @Test
    @DisplayName("Severity boundaries: 5000 LOW, 5001 MEDIUM, 20000 MEDIUM, 20001 HIGH")
    void severityBoundaries() {
        assertThat(builder.calculateClaimSeverity(DamageAssessment.of("X", "M", new BigDecimal("5000"), BigDecimal.ONE))).isEqualTo("LOW");
        assertThat(builder.calculateClaimSeverity(DamageAssessment.of("X", "M", new BigDecimal("5001"), BigDecimal.ONE))).isEqualTo("MEDIUM");
        assertThat(builder.calculateClaimSeverity(DamageAssessment.of("X", "M", new BigDecimal("20000"), BigDecimal.ONE))).isEqualTo("MEDIUM");
        assertThat(builder.calculateClaimSeverity(DamageAssessment.of("X", "M", new BigDecimal("20001"), BigDecimal.ONE))).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("Thresholds come from fnol.stp properties")
    void thresholdsAreConfigurable() {
        stp.setConfidence(new BigDecimal("0.95"));
        assertEscalatedBy(build(), "AI Confidence");
    }
}
