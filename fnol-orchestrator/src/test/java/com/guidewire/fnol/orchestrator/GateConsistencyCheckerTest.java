package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.common.Models.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.guidewire.fnol.orchestrator.GateConsistencyChecker.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Diagram: reconciliation gate "validate consistency". */
class GateConsistencyCheckerTest {

    private final GateConsistencyChecker checker = new GateConsistencyChecker(new StpProperties());

    private static Policy policy(String type) {
        return new Policy("POL-" + type + "-112233", type, "ACTIVE", LocalDate.now().minusMonths(6),
                LocalDate.now().plusMonths(6), "Avery Johnson", "CA", "risk", List.of());
    }

    private static BranchAResult branchA(String type, String severity, String damage, String confidence,
                                         String quality, String limit) {
        DamageAssessment d = new DamageAssessment(type, severity, new BigDecimal(damage), new BigDecimal(confidence),
                quality, Map.of());
        BigDecimal reserve = new BigDecimal(damage).multiply(new BigDecimal("1.15"));
        return new BranchAResult(d,
                new PolicyValidationResult("POL", true, true, true, true, "COLLISION", new BigDecimal(limit),
                        BigDecimal.ZERO, List.of()),
                new ReserveResult(new BigDecimal(damage), new BigDecimal("1.15"), reserve));
    }

    @Test
    @DisplayName("A consistent claim raises no flags")
    void consistentClaimHasNoFlags() {
        assertThat(checker.check(policy("AUTO"), branchA("FRONT_BUMPER", "MODERATE", "4200", "0.91", "CLEAR", "50000")))
                .isEmpty();
    }

    private static BranchAResult withProbabilities(Map<String, BigDecimal> probabilities) {
        DamageAssessment d = new DamageAssessment("MULTIPLE", "MODERATE", new BigDecimal("4200"), new BigDecimal("0.90"),
                "CLEAR", probabilities);
        BranchAResult base = branchA("MULTIPLE", "MODERATE", "4200", "0.90", "CLEAR", "50000");
        return new BranchAResult(d, base.coverage(), base.reserve());
    }

    @Test
    @DisplayName("AMBIGUOUS_DAMAGE_TYPE when the two highest damage-type probabilities are close, despite high confidence")
    void ambiguousDamageType() {
        assertThat(checker.check(policy("AUTO"), withProbabilities(Map.of(
                "MULTIPLE", new BigDecimal("0.45"), "FRONT_BUMPER", new BigDecimal("0.35"), "SIDE_PANEL", new BigDecimal("0.20")))))
                .containsExactly(AMBIGUOUS_DAMAGE_TYPE);
        // A clear answer (gap 0.85) and an answer without probabilities raise nothing.
        assertThat(checker.check(policy("AUTO"), withProbabilities(Map.of(
                "MULTIPLE", new BigDecimal("0.90"), "FRONT_BUMPER", new BigDecimal("0.05")))))
                .isEmpty();
        assertThat(checker.check(policy("AUTO"), withProbabilities(Map.of()))).isEmpty();
    }

    @Test
    @DisplayName("SEVERITY_ESTIMATE_MISMATCH when the severity label disagrees with the estimate band")
    void severityMismatch() {
        assertThat(checker.check(policy("AUTO"), branchA("FRONT_BUMPER", "MINOR", "4200", "0.91", "CLEAR", "50000")))
                .containsExactly(SEVERITY_ESTIMATE_MISMATCH);
        assertThat(checker.check(policy("AUTO"), branchA("FRONT_BUMPER", "MODERATE", "12000", "0.91", "CLEAR", "50000")))
                .containsExactly(SEVERITY_ESTIMATE_MISMATCH);
    }

    @Test
    @DisplayName("RESERVE_EXCEEDS_COVERAGE_LIMIT when the reserve is above the coverage limit")
    void reserveAboveLimit() {
        assertThat(checker.check(policy("AUTO"), branchA("FRONT_BUMPER", "MODERATE", "4200", "0.91", "CLEAR", "3000")))
                .containsExactly(RESERVE_EXCEEDS_COVERAGE_LIMIT);
    }

    @Test
    @DisplayName("DAMAGE_TYPE_LINE_MISMATCH for vehicle damage on a home policy or water damage on an auto policy")
    void damageTypeLineMismatch() {
        assertThat(checker.check(policy("HOME"), branchA("FRONT_BUMPER", "MODERATE", "4200", "0.91", "CLEAR", "350000")))
                .containsExactly(DAMAGE_TYPE_LINE_MISMATCH);
        assertThat(checker.check(policy("AUTO"), branchA("WATER_DAMAGE", "MODERATE", "4200", "0.91", "CLEAR", "50000")))
                .containsExactly(DAMAGE_TYPE_LINE_MISMATCH);
        assertThat(checker.check(policy("HOME"), branchA("WATER_DAMAGE", "MODERATE", "4200", "0.90", "CLEAR", "350000")))
                .isEmpty();
    }

    @Test
    @DisplayName("CONFIDENCE_PHOTO_QUALITY_MISMATCH for high confidence on a blurry or unusable photo")
    void confidencePhotoMismatch() {
        assertThat(checker.check(policy("AUTO"), branchA("FRONT_BUMPER", "MODERATE", "4200", "0.92", "BLURRY", "50000")))
                .containsExactly(CONFIDENCE_PHOTO_QUALITY_MISMATCH);
        assertThat(checker.check(policy("AUTO"), branchA("FRONT_BUMPER", "MODERATE", "4200", "0.61", "BLURRY", "50000")))
                .isEmpty();
    }
}
