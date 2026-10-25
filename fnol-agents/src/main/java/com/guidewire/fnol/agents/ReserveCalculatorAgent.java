package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Recommends what the insurer should pay, never more than either side's figure:
 * <pre>
 *   basis   = min(claimed amount, AI estimate)      (the AI estimate when no amount was claimed)
 *   reserve = min(max(basis − deductible, 0), coverage limit)
 * </pre>
 * A claimed amount above the AI estimate is not paid automatically: the reconciliation gate flags it
 * (CLAIMED_EXCEEDS_AI_ESTIMATE) for an adjuster, and the reserve stays at the AI-supported figure.
 * The confidence score is the vision confidence weighted by how usable the photo was (prototype weights).
 */
@Component
public class ReserveCalculatorAgent implements FNOLAgent<DamageAssessment, ReserveResult> {

    /** No loading: the reserve is the amount owed under the policy. */
    static final BigDecimal FACTOR = BigDecimal.ONE;

    private static final Map<String, BigDecimal> PHOTO_QUALITY_WEIGHT = Map.of(
            "CLEAR", new BigDecimal("1.00"),
            "PARTIAL", new BigDecimal("0.85"),
            "BLURRY", new BigDecimal("0.70"),
            "UNUSABLE", new BigDecimal("0.50"));
    private static final BigDecimal UNKNOWN_QUALITY_WEIGHT = new BigDecimal("0.80");

    /** Reserve from the AI estimate alone (no claimed amount, no policy terms). */
    @Override
    public ReserveResult execute(DamageAssessment d) {
        return calculate(d, null, null);
    }

    public ReserveResult calculate(DamageAssessment d, PolicyValidationResult coverage, BigDecimal claimedAmount) {
        BigDecimal estimate = d.estimatedDamage();
        BigDecimal basis = claimedAmount != null && claimedAmount.compareTo(estimate) < 0 ? claimedAmount : estimate;

        BigDecimal deductible = coverage == null || coverage.deductible() == null ? BigDecimal.ZERO : coverage.deductible();
        BigDecimal limit = coverage == null ? null : coverage.coverageLimit();
        BigDecimal owed = basis.subtract(deductible).max(BigDecimal.ZERO);
        if (limit != null && limit.signum() > 0) {
            owed = owed.min(limit);
        }
        BigDecimal reserve = owed.multiply(FACTOR).setScale(0, RoundingMode.HALF_UP);

        String quality = d.photoQuality() == null ? "UNKNOWN" : d.photoQuality();
        BigDecimal weight = PHOTO_QUALITY_WEIGHT.getOrDefault(quality, UNKNOWN_QUALITY_WEIGHT);
        BigDecimal visionConfidence = d.confidence() == null ? BigDecimal.ZERO : d.confidence();
        BigDecimal confidence = visionConfidence.multiply(weight).min(BigDecimal.ONE).setScale(2, RoundingMode.HALF_UP);

        return new ReserveResult(estimate, FACTOR, reserve, confidence,
                "vision confidence " + visionConfidence + " × photo quality " + quality + " weight " + weight,
                claimedAmount, basis, deductible, limit);
    }
}
