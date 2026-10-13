package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.common.Models.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(MockitoJUnitRunner.class)
public class ClaimTypeValidatorTest {

    private ClaimTypeValidator validator;

    @Before
    public void setup() {
        validator = new ClaimTypeValidator();
    }

    /**
     * Test 1: GLASS_ONLY + LOW severity + LOW risk → Eligible
     */
    @Test
    public void testGlassOnlyLowSeverityEligible() {
        DamageAssessment damage = new DamageAssessment(
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
        DamageAssessment damage = new DamageAssessment(
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
        DamageAssessment damage = new DamageAssessment(
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
        DamageAssessment damage = new DamageAssessment(
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
        DamageAssessment damage = new DamageAssessment(
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
        DamageAssessment damage = new DamageAssessment(
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
        DamageAssessment damage = new DamageAssessment(
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
        DamageAssessment damage = new DamageAssessment(
                "GLASS_ONLY", "MINOR", new BigDecimal("2500"), new BigDecimal("0.91")
        );

        PolicyHistoryContext history = new PolicyHistoryContext(
                1, 0, 1, LocalDate.now().minusDays(200), "THEFT",
                new BigDecimal("1800"), 0, "MEDIUM"
        );

        assertTrue(validator.isEligibleForAdvisoryPrefill(damage, history));
    }
}