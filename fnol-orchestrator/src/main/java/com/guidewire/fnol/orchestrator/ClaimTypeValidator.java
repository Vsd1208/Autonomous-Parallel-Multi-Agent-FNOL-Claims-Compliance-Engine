package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.common.Models.*;
import org.springframework.stereotype.Component;

import java.util.Map;
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

    // D17: the vision model reports the damaged area, not a claim type. Map areas to claim types.
    private static final Map<String, String> VISION_DAMAGE_TO_CLAIM_TYPE = Map.of(
            "WINDSHIELD", "GLASS_ONLY",
            "FRONT_BUMPER", "MINOR_COLLISION",
            "REAR_BUMPER", "MINOR_COLLISION",
            "SIDE_PANEL", "MINOR_COLLISION"
    );

    private final StpProperties stp;

    public ClaimTypeValidator(StpProperties stp) {
        this.stp = stp;
    }

    /**
     * Requirements:
     * 1. Damage type (mapped to a claim type) must be in the safe list
     * 2. Estimated damage must be LOW severity (at most fnol.stp.low-severity-max)
     * 3. Claim history frequency risk must NOT be HIGH
     */
    public boolean isEligibleForAdvisoryPrefill(DamageAssessment damage, PolicyHistoryContext history) {
        if (!ELIGIBLE_CLAIM_TYPES.contains(claimTypeFor(damage.damageType()))) {
            return false;
        }
        if (damage.estimatedDamage().compareTo(stp.getLowSeverityMax()) > 0) {
            return false;
        }
        return !"HIGH".equals(history.claimFrequencyRisk());
    }

    /** Claim type for a vision damage type; claim types pass through unchanged. */
    static String claimTypeFor(String damageType) {
        if (damageType == null) return "UNKNOWN";
        return VISION_DAMAGE_TO_CLAIM_TYPE.getOrDefault(damageType, damageType);
    }
}
