package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.common.Models.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClaimTypeValidatorTest {

    private ClaimTypeValidator validator;

    @BeforeEach
    public void setup() {
        validator = new ClaimTypeValidator(new StpProperties());
    }

    /**
     * D17: vision damage types map to claim types, so a LOW bumper or windshield claim is eligible.
     */
    @Test
    public void testVisionDamageTypesMapToEligibleClaimTypes() {
        PolicyHistoryContext history = new PolicyHistoryContext(0, 0, 0, null, null, null, 0, "LOW");

        for (String visionType : new String[]{"FRONT_BUMPER", "REAR_BUMPER", "SIDE_PANEL", "WINDSHIELD"}) {
            DamageAssessment damage = DamageAssessment.of(visionType, "MINOR", new BigDecimal("2500"), new BigDecimal("0.91"));
            assertTrue(validator.isEligibleForAdvisoryPrefill(damage, history), visionType);
        }
        assertFalse(validator.isEligibleForAdvisoryPrefill(
                DamageAssessment.of("ROOF", "MINOR", new BigDecimal("2500"), new BigDecimal("0.91")), history));
        assertFalse(validator.isEligibleForAdvisoryPrefill(
                DamageAssessment.of("UNKNOWN", "MODERATE", new BigDecimal("3500"), new BigDecimal("0.60")), history));
    }

    /**
     * Test 1: GLASS_ONLY + LOW severity + LOW risk → Eligible
     */
    @Test
    public void testGlassOnlyLowSeverityEligible() {
        DamageAssessment damage = DamageAssessment.of(
                "GLASS_ONLY", "MINOR", new BigDecimal("2500"), new BigDecimal("0.91")
        );

        PolicyHistoryContext history = new PolicyHistoryContext(
                0, 0, 0, null, null, null, 0, "LOW"
        );

        assertTrue(validator.isEligibleForAdvisoryPrefill(damage, history));
    }

    /**
     * Test 2: MINOR_COLLISION + LOW severity + LOW risk → Eligible
     */
    @Test
    public void testMinorCollisionLowSeverityEligible() {
        DamageAssessment damage = DamageAssessment.of(
                "MINOR_COLLISION", "MINOR", new BigDecimal("4500"), new BigDecimal("0.88")
        );

        PolicyHistoryContext history = new PolicyHistoryContext(
                0, 0, 0, null, null, null, 0, "LOW"
        );

        assertTrue(validator.isEligibleForAdvisoryPrefill(damage, history));
    }

    /**
     * Test 3: THEFT_ACCESSORY + LOW severity + LOW risk → Eligible
     */
    @Test
    public void testTheftAccessoryLowSeverityEligible() {
        DamageAssessment damage = DamageAssessment.of(
                "THEFT_ACCESSORY", "MINOR", new BigDecimal("3000"), new BigDecimal("0.90")
        );

        PolicyHistoryContext history = new PolicyHistoryContext(
                0, 0, 0, null, null, null, 0, "LOW"
        );

        assertTrue(validator.isEligibleForAdvisoryPrefill(damage, history));
    }

    /**
     * Test 4: VANDALISM_COSMETIC + LOW severity + LOW risk → Eligible
     */
    @Test
    public void testVandalismCosmeticLowSeverityEligible() {
        DamageAssessment damage = DamageAssessment.of(
                "VANDALISM_COSMETIC", "MINOR", new BigDecimal("1800"), new BigDecimal("0.85")
        );

        PolicyHistoryContext history = new PolicyHistoryContext(
                0, 0, 0, null, null, null, 0, "LOW"
        );

        assertTrue(validator.isEligibleForAdvisoryPrefill(damage, history));
    }

    /**
     * Test 5: Ineligible claim type (STRUCTURAL_DAMAGE) → Not eligible
     */
    @Test
    public void testStructuralDamageNotEligible() {
        DamageAssessment damage = DamageAssessment.of(
                "STRUCTURAL_DAMAGE", "SEVERE", new BigDecimal("4500"), new BigDecimal("0.91")
        );

        PolicyHistoryContext history = new PolicyHistoryContext(
                0, 0, 0, null, null, null, 0, "LOW"
        );

        assertFalse(validator.isEligibleForAdvisoryPrefill(damage, history));
    }

    /**
     * Test 6: GLASS_ONLY but MEDIUM severity (>= $5000) → Not eligible
     */
    @Test
    public void testMediumSeverityNotEligible() {
        DamageAssessment damage = DamageAssessment.of(
                "GLASS_ONLY", "MODERATE", new BigDecimal("12000"), new BigDecimal("0.91")
        );

        PolicyHistoryContext history = new PolicyHistoryContext(
                0, 0, 0, null, null, null, 0, "LOW"
        );

        assertFalse(validator.isEligibleForAdvisoryPrefill(damage, history));
    }

    /**
     * Test 7: GLASS_ONLY + LOW severity but HIGH claim frequency risk → Not eligible
     */
    @Test
    public void testHighFrequencyRiskNotEligible() {
        DamageAssessment damage = DamageAssessment.of(
                "GLASS_ONLY", "MINOR", new BigDecimal("2500"), new BigDecimal("0.91")
        );

        PolicyHistoryContext history = new PolicyHistoryContext(
                5, 2, 3, LocalDate.now().minusDays(45), "COLLISION",
                new BigDecimal("3500"), 0, "HIGH"
        );

        assertFalse(validator.isEligibleForAdvisoryPrefill(damage, history));
    }

    /**
     * Test 8: GLASS_ONLY + LOW severity + MEDIUM risk → Eligible
     */
    @Test
    public void testMediumFrequencyRiskEligible() {
        DamageAssessment damage = DamageAssessment.of(
                "GLASS_ONLY", "MINOR", new BigDecimal("2500"), new BigDecimal("0.91")
        );

        PolicyHistoryContext history = new PolicyHistoryContext(
                1, 0, 1, LocalDate.now().minusDays(200), "THEFT",
                new BigDecimal("1800"), 0, "MEDIUM"
        );

        assertTrue(validator.isEligibleForAdvisoryPrefill(damage, history));
    }
}
