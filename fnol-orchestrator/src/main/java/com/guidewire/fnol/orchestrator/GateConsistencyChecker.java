package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.common.Models.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reconciliation gate "validate consistency": cross-checks the two branches and the policy.
 * Any flag blocks STP and is explained to the adjuster; it never blocks the intake itself.
 */
@Component
public class GateConsistencyChecker {

    /** Vision's severity label disagrees with its own estimate (bands from the vision prompt). */
    public static final String SEVERITY_ESTIMATE_MISMATCH = "SEVERITY_ESTIMATE_MISMATCH";
    /** The loss after the deductible is above the matched coverage limit (the reserve is capped at the limit). */
    public static final String RESERVE_EXCEEDS_COVERAGE_LIMIT = "RESERVE_EXCEEDS_COVERAGE_LIMIT";
    /** The claimant asks for more than the photos support; never paid without an adjuster. */
    public static final String CLAIMED_EXCEEDS_AI_ESTIMATE = "CLAIMED_EXCEEDS_AI_ESTIMATE";
    /** The damage type does not fit the policy's line of business (e.g. a bumper on a home policy). */
    public static final String DAMAGE_TYPE_LINE_MISMATCH = "DAMAGE_TYPE_LINE_MISMATCH";
    /** High vision confidence reported for a photo it rated blurry or unusable. */
    public static final String CONFIDENCE_PHOTO_QUALITY_MISMATCH = "CONFIDENCE_PHOTO_QUALITY_MISMATCH";

    static final BigDecimal MINOR_MAX = new BigDecimal("2000");
    static final BigDecimal MODERATE_MAX = new BigDecimal("10000");

    private static final Set<String> VEHICLE_DAMAGE = Set.of("FRONT_BUMPER", "REAR_BUMPER", "WINDSHIELD", "SIDE_PANEL");
    private static final Set<String> PROPERTY_DAMAGE = Set.of("WATER_DAMAGE");
    private static final Set<String> POOR_PHOTOS = Set.of("BLURRY", "UNUSABLE");

    private final StpProperties stp;

    public GateConsistencyChecker(StpProperties stp) {
        this.stp = stp;
    }

    public List<String> check(Policy policy, BranchAResult branchA) {
        List<String> flags = new ArrayList<>();
        DamageAssessment damage = branchA.damageAssessment();

        String expectedSeverity = damage.estimatedDamage().compareTo(MINOR_MAX) < 0 ? "MINOR"
                : damage.estimatedDamage().compareTo(MODERATE_MAX) <= 0 ? "MODERATE" : "SEVERE";
        if (damage.severity() != null && !damage.severity().equals(expectedSeverity)) {
            flags.add(SEVERITY_ESTIMATE_MISMATCH);
        }

        ReserveResult reserve = branchA.reserve();
        BigDecimal limit = branchA.coverage().coverageLimit();
        BigDecimal deductible = reserve.deductible() == null ? BigDecimal.ZERO : reserve.deductible();
        BigDecimal basis = reserve.settlementBasis() == null ? reserve.recommendedReserve() : reserve.settlementBasis();
        if (branchA.coverage().covered() && limit != null && limit.signum() > 0
                && basis.subtract(deductible).compareTo(limit) > 0) {
            flags.add(RESERVE_EXCEEDS_COVERAGE_LIMIT);
        }

        // More than the AI estimate plus the tolerance: the claimant must support it (photos, investigation).
        BigDecimal allowed = damage.estimatedDamage().multiply(BigDecimal.ONE.add(stp.getClaimedAmountTolerance()));
        if (reserve.claimedAmount() != null && reserve.claimedAmount().compareTo(allowed) > 0) {
            flags.add(CLAIMED_EXCEEDS_AI_ESTIMATE);
        }

        String line = policy.policyType();
        if (("HOME".equals(line) && VEHICLE_DAMAGE.contains(damage.damageType()))
                || ("AUTO".equals(line) && PROPERTY_DAMAGE.contains(damage.damageType()))) {
            flags.add(DAMAGE_TYPE_LINE_MISMATCH);
        }

        if (POOR_PHOTOS.contains(damage.photoQuality()) && damage.confidence().compareTo(stp.getConfidence()) >= 0) {
            flags.add(CONFIDENCE_PHOTO_QUALITY_MISMATCH);
        }
        return List.copyOf(flags);
    }
}
