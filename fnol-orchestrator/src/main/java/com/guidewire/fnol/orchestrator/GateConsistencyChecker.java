package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.agents.VisionConfidenceCalibrator;
import com.guidewire.fnol.common.Models.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    /**
     * The AI hedged between damage types (gap between its two highest probabilities below
     * fnol.stp.min-probability-margin). On labelled photos these were the answers that were wrong despite
     * a high reported confidence.
     */
    public static final String AMBIGUOUS_DAMAGE_TYPE = "AMBIGUOUS_DAMAGE_TYPE";
    /** The claimant asks for more than the photos support; never paid without an adjuster. */
    public static final String CLAIMED_EXCEEDS_AI_ESTIMATE = "CLAIMED_EXCEEDS_AI_ESTIMATE";
    /** The damage type does not fit the policy's line of business (e.g. a bumper on a home policy). */
    public static final String DAMAGE_TYPE_LINE_MISMATCH = "DAMAGE_TYPE_LINE_MISMATCH";
    /**
     * Vision reports FLOOD on a HOME policy. Standard homeowners policies exclude flood (it needs a separate
     * flood policy), but the cause of loss must be confirmed by a person: the AI never denies a claim.
     */
    public static final String POSSIBLE_FLOOD_EXCLUSION = "POSSIBLE_FLOOD_EXCLUSION";
    /**
     * The photos do not show what the policy insures: vision reports what it sees (photoSubject) independently of
     * the prompt the policy selected, e.g. a house on an auto policy, a car on a home policy, or nothing damaged.
     */
    public static final String PHOTO_SUBJECT_MISMATCH = "PHOTO_SUBJECT_MISMATCH";
    /** The photo subject each line of business insures. */
    static final Map<String, String> EXPECTED_SUBJECT = Map.of("AUTO", "VEHICLE", "HOME", "BUILDING");
    /**
     * The second-opinion model (independently trained, fnol-vision-ml) gives Gemini's damage type a probability below
     * fnol.stp.second-opinion-min-probability. On labelled car photos this caught the confident wrong answer that no
     * other vision check stops (tools/second-opinion/results/disagreement.md).
     */
    public static final String MODEL_DISAGREEMENT = "MODEL_DISAGREEMENT";
    /** High vision confidence reported for a photo it rated blurry or unusable. */
    public static final String CONFIDENCE_PHOTO_QUALITY_MISMATCH = "CONFIDENCE_PHOTO_QUALITY_MISMATCH";

    static final BigDecimal MINOR_MAX = new BigDecimal("2000");
    static final BigDecimal MODERATE_MAX = new BigDecimal("10000");

    private static final Set<String> VEHICLE_DAMAGE = Set.of("FRONT_BUMPER", "REAR_BUMPER", "WINDSHIELD", "SIDE_PANEL");
    /** Causes of loss from the property prompt (GeminiVisionProvider.PROPERTY_ASSESSMENT_PROMPT). */
    private static final Set<String> PROPERTY_DAMAGE =
            Set.of("WATER_DAMAGE", "FLOOD", "FIRE_SMOKE", "WIND_STORM", "FALLEN_TREE");
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
        String expectedSubject = EXPECTED_SUBJECT.get(line);
        if (damage.photoSubject() != null && expectedSubject != null && !expectedSubject.equals(damage.photoSubject())) {
            flags.add(PHOTO_SUBJECT_MISMATCH);
        }
        SecondOpinion second = branchA.secondOpinion();
        BigDecimal agreement = second == null ? null : second.probabilityOf(damage.damageType());
        if (agreement != null && agreement.compareTo(stp.getSecondOpinionMinProbability()) < 0) {
            flags.add(MODEL_DISAGREEMENT);
        }
        if ("HOME".equals(line) && "FLOOD".equals(damage.damageType())) {
            flags.add(POSSIBLE_FLOOD_EXCLUSION);
        }

        Map<String, BigDecimal> probabilities = damage.damageTypeProbabilities();
        if (probabilities != null && !probabilities.isEmpty()
                && VisionConfidenceCalibrator.margin(probabilities).compareTo(stp.getMinProbabilityMargin()) < 0) {
            flags.add(AMBIGUOUS_DAMAGE_TYPE);
        }

        if (POOR_PHOTOS.contains(damage.photoQuality()) && damage.confidence().compareTo(stp.getConfidence()) >= 0) {
            flags.add(CONFIDENCE_PHOTO_QUALITY_MISMATCH);
        }
        return List.copyOf(flags);
    }
}
