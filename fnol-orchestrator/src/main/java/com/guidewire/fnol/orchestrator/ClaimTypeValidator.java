package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.common.Models.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Validates whether a claim is eligible for advisory-guided pre-population.
 * Only LOW-severity claims of specific types are eligible.
 */
@Component
public class ClaimTypeValidator {

    // Only these claim types eligible for advisory pre-population in LOW severity
    private static final Set<String> ELIGIBLE_CLAIM_TYPES = Set.of(
            "GLASS_ONLY",          // Windshield/window damage, no injury
            "MINOR_COLLISION",     // Bumper/door/mirror damage < $5k
            "THEFT_ACCESSORY",     // Stolen aftermarket parts only
            "VANDALISM_COSMETIC"   // Paint, scratches, no structural damage
    );

    /**
     * Determines if a claim is eligible for advisory pre-population prefill.
     * Requirements:
     * 1. Damage type must be in safe list
     * 2. Estimated damage must be LOW severity (< $5,000)
     * 3. Claim history frequency risk must NOT be HIGH
     */
    public boolean isEligibleForAdvisoryPrefill(
            DamageAssessment damage,
            PolicyHistoryContext history) {

        // Requirement 1: Claim type must be in safe list
        if (!ELIGIBLE_CLAIM_TYPES.contains(damage.damageType())) {
            return false;
        }

        // Requirement 2: Severity must be LOW
        if (!isLowSeverity(damage)) {
            return false;
        }

        // Requirement 3: Claimant history must not be high-risk
        if ("HIGH".equals(history.claimFrequencyRisk())) {
            return false;
        }

        return true;
    }

    /**
     * Checks if damage is low severity (< $5,000)
     */
    private boolean isLowSeverity(DamageAssessment damage) {
        BigDecimal lowThreshold = new BigDecimal("5000");
        return damage.estimatedDamage().compareTo(lowThreshold) <= 0;
    }
}